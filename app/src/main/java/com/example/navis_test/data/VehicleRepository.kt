package com.example.navis_test.data

import android.os.Handler
import android.os.Looper
import com.example.navis_test.data.can.CanConnector
import com.example.navis_test.data.uds.IsoTpReassembler
import com.example.navis_test.data.uds.UdsClient
import com.example.navis_test.data.uds.UdsRequest
import com.example.navis_test.model.CanLogEntry
import com.example.navis_test.model.ConnectionState
import com.example.navis_test.model.FuelState
import com.example.navis_test.model.ThresholdEvent
import com.example.navis_test.model.VehicleStatus
import com.example.navis_test.model.VinState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Nguồn sự thật duy nhất về trạng thái xe, sống theo vòng đời app (không theo Activity)
 * — chuyển màn hình không làm mất kết nối CAN hay trạng thái.
 *
 * Đây là mặt tiếp giáp giữa tầng giao tiếp (CanConnector/UdsClient — Phần 1)
 * và tầng UI (ViewModel/Activity — Phần 2): UI chỉ thấy StateFlow/SharedFlow
 * và các hàm nghiệp vụ, không thấy byte hay binder.
 */
object VehicleRepository {

    // ---- Bản tin CAN của hệ thống ----
    private const val STATUS_BROADCAST_ID = 0x3C6 // ECU → App: trạng thái đèn/cửa/điều hoà
    private const val LIGHT_CONTROL_ID = 0x3A6    // App → ECU: điều khiển đèn
    private const val UDS_REQUEST_ID = 0x768      // App → ECU: UDS request
    private const val UDS_RESPONSE_ID = 0x769     // ECU → App: UDS response

    private const val LABEL_FUEL_VALUE = "fuelValue"
    private const val LABEL_FUEL_STATUS = "fuelStatus"
    private const val LABEL_THRESHOLD = "threshold"
    private const val LABEL_THRESHOLD_READ = "thresholdRead"
    private const val LABEL_VIN = "vin"

    private const val CAN_INTERFACE = "can0"
    private const val CAN_BITRATE = 500000

    private const val THRESHOLD_TIMEOUT_MS = 2000L
    // Timeout chờ phản hồi cho một request UDS single-frame (đọc nhiên liệu/trạng thái).
    private const val UDS_TIMEOUT_MS = 500L
    // Cycle time tối thiểu giữa hai bản tin 0x768 phát xuống S32K144 (giãn tải cho ECU).
    private const val UDS_TX_MIN_GAP_MS = 1000L
    // Timeout mỗi LƯỢT đọc VIN (ngắn để retry nhanh khi rớt khung), và số lượt tối đa.
    private const val VIN_ATTEMPT_TIMEOUT_MS = 700L
    private const val VIN_MAX_ATTEMPTS = 3

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- Trạng thái công khai cho tầng UI ----
    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTING)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // null = chưa nhận broadcast 0x3C6 nào — UI giữ giá trị mặc định của layout.
    private val _vehicleStatus = MutableStateFlow<VehicleStatus?>(null)
    val vehicleStatus: StateFlow<VehicleStatus?> = _vehicleStatus

    private val _fuel = MutableStateFlow(FuelState())
    val fuel: StateFlow<FuelState> = _fuel

    private val _vin = MutableStateFlow<VinState>(VinState.Idle)
    val vin: StateFlow<VinState> = _vin

    private val _thresholdEvents = MutableSharedFlow<ThresholdEvent>(
        extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val thresholdEvents: SharedFlow<ThresholdEvent> = _thresholdEvents

    private val _canLog = MutableSharedFlow<CanLogEntry>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val canLog: SharedFlow<CanLogEntry> = _canLog

    // ---- Ống UDS + ghép ISO-TP cho VIN ----
    private val udsClient = UdsClient(UDS_TX_MIN_GAP_MS) { canId, data, onResult ->
        write(canId, data, onResult = onResult)
    }

    // Số lần đã thử đọc VIN trong chu trình hiện tại (tự retry khi rớt khung —
    // gói ISO-TP thỉnh thoảng rớt khi bus bận broadcast 0x3C6, một lượt hỏng
    // không có nghĩa là ECU không đọc được).
    private var vinAttempt = 0

    private val vinReassembler = IsoTpReassembler(
        expectedHeader = byteArrayOf(0x62, 0xF1.toByte(), 0x90.toByte()),
        onSendFlowControl = {
            // 0x30 = CTS, BlockSize=0 (gửi hết), STmin=0x0A = giãn 10ms mỗi khung
            // để vòng đọc (đang cạnh tranh với broadcast 0x3C6) kịp gom, giảm rớt.
            // Gửi thẳng KHÔNG qua hàng đợi UDS để giữ đúng thời gian ISO-TP.
            write(UDS_REQUEST_ID, byteArrayOf(0x30, 0x00, 0x0A, 0, 0, 0, 0, 0))
        },
        onComplete = { bytes ->
            val vin = String(CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() })
            _vin.value = VinState.Success(vin)
            udsClient.complete(LABEL_VIN)
        },
        onSequenceError = { received, expected ->
            android.util.Log.w("VehicleRepository", "VIN CF sai thứ tự: nhận SN=$received, chờ SN=$expected")
            retryOrFailVin()
        }
    )

    private var listenerRegistered = false

    // Nhận mọi gói CAN từ luồng đọc dùng chung; đưa về main thread để mọi mutation
    // trạng thái + hàng đợi UDS đều main-thread-confined (không cần khoá).
    private val canListener: (Int, ByteArray) -> Unit = { canId, payload ->
        mainHandler.post {
            _canLog.tryEmit(
                CanLogEntry(CanLogEntry.Direction.RX, true, canId, payload, System.currentTimeMillis())
            )
            when (canId) {
                STATUS_BROADCAST_ID -> handleStatusBroadcast(payload)
                UDS_RESPONSE_ID -> handleUdsResponse(payload)
            }
        }
    }

    // ---- Khởi động chuỗi kết nối ----

    // Dựng cả chuỗi: kết nối CanService → mở can0 → chạy vòng đọc. Idempotent —
    // gọi lại khi đã chạy thì không làm gì.
    fun ensureStarted() {
        if (!listenerRegistered) {
            CanConnector.addListener(canListener)
            listenerRegistered = true
        }
        if (CanConnector.isReadingActive()) return

        if (!CanConnector.isConnected() && !CanConnector.connect()) {
            _connectionState.value = ConnectionState.SERVICE_OFFLINE
            return
        }

        CanConnector.openAsync(CAN_INTERFACE, CAN_BITRATE) { opened ->
            mainHandler.post {
                if (opened) {
                    _connectionState.value = ConnectionState.ONLINE
                    CanConnector.startReadingLoop()
                } else {
                    _connectionState.value = ConnectionState.OPEN_FAILED
                }
            }
        }
    }

    fun isConnected(): Boolean = CanConnector.isConnected()

    // ---- Lệnh nghiệp vụ từ UI ----

    // Điều khiển đèn qua 0x3A6: byte0 = bật(01)/tắt(02)
    fun setLight(on: Boolean) {
        val data = ByteArray(8)
        data[0] = if (on) 0x01 else 0x02
        write(LIGHT_CONTROL_ID, data)
    }

    // Chế độ đèn qua 0x3A6: byte1 = normal(01)/blink(02)
    fun setLightMode(blink: Boolean) {
        val data = ByteArray(8)
        data[1] = if (blink) 0x02 else 0x01
        write(LIGHT_CONTROL_ID, data)
    }

    fun requestFuelValue() {
        udsClient.enqueue(
            UdsRequest(LABEL_FUEL_VALUE, UDS_REQUEST_ID,
                byteArrayOf(0x03, 0x22, 0xF1.toByte(), 0x01, 0, 0, 0, 0), UDS_TIMEOUT_MS),
            unique = true
        )
    }

    fun requestFuelStatus() {
        udsClient.enqueue(
            UdsRequest(LABEL_FUEL_STATUS, UDS_REQUEST_ID,
                byteArrayOf(0x03, 0x22, 0xF1.toByte(), 0x02, 0, 0, 0, 0), UDS_TIMEOUT_MS),
            unique = true
        )
    }

    // Ghi ngưỡng cảnh báo qua UDS WriteDataByIdentifier (DID F1 03).
    // front=true: chen lên đầu hàng đợi vì là tác vụ người dùng bấm.
    fun writeThreshold(value: Int) {
        val data = byteArrayOf(0x04, 0x2E, 0xF1.toByte(), 0x03, value.toByte(), 0, 0, 0)
        udsClient.enqueue(
            UdsRequest(
                LABEL_THRESHOLD, UDS_REQUEST_ID, data, THRESHOLD_TIMEOUT_MS,
                onTimeout = { _thresholdEvents.tryEmit(ThresholdEvent.WriteFailed("04")) },
                onSendFailed = { _thresholdEvents.tryEmit(ThresholdEvent.WriteFailed("02")) }
            ),
            front = true
        )
    }

    // Đọc VIN thật qua ISO-TP (DID F1 90), có retry khi rớt khung.
    fun readVin() {
        vinAttempt = 0
        _vin.value = VinState.Reading
        enqueueVinAttempt()
    }

    // Dừng mọi request UDS còn treo (gọi khi dashboard pause).
    fun cancelPendingUds() = udsClient.clear()

    // ---- Nội bộ ----

    // Mọi TX đi qua đây: gửi xuống connector, ghi log TX (kể cả thất bại) và
    // đưa callback về main thread.
    private fun write(
        canId: Int,
        data: ByteArray,
        extended: Boolean = false,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        CanConnector.writeAsync(canId, data, extended) { success ->
            mainHandler.post {
                _canLog.tryEmit(
                    CanLogEntry(CanLogEntry.Direction.TX, success, canId, data, System.currentTimeMillis())
                )
                if (!success) {
                    android.util.Log.e("VehicleRepository", "Write FAILED for ID=0x${Integer.toHexString(canId)}")
                }
                onResult?.invoke(success)
            }
        }
    }

    private fun handleStatusBroadcast(payload: ByteArray) {
        if (payload.size < 4) return
        _vehicleStatus.value = VehicleStatus(
            lightOn = (payload[0].toInt() and 0xFF) == 1,
            lightBlinking = (payload[1].toInt() and 0xFF) == 1,
            doorUnlocked = (payload[2].toInt() and 0xFF) == 1,
            climateOn = (payload[3].toInt() and 0xFF) == 1
        )
    }

    // Phân loại phản hồi 0x769: dữ liệu single-frame (nhiên liệu/ngưỡng), xác nhận
    // ghi ngưỡng, hoặc các khung ISO-TP (First/Consecutive Frame) của VIN.
    private fun handleUdsResponse(payload: ByteArray) {
        if (payload.isEmpty()) return
        val pci = payload[0].toInt() and 0xFF

        when {
            pci == 0x04 && payload.size >= 5 &&
                    (payload[1].toInt() and 0xFF) == 0x62 && (payload[2].toInt() and 0xFF) == 0xF1 -> {
                val value = payload[4].toInt() and 0xFF
                when (payload[3].toInt() and 0xFF) {
                    0x01 -> { _fuel.value = _fuel.value.copy(percent = value); udsClient.complete(LABEL_FUEL_VALUE) }
                    0x02 -> { _fuel.value = _fuel.value.copy(normal = value == 1); udsClient.complete(LABEL_FUEL_STATUS) }
                    0x03 -> { _thresholdEvents.tryEmit(ThresholdEvent.Readback(value)); udsClient.complete(LABEL_THRESHOLD_READ) }
                }
            }
            pci == 0x03 && payload.size >= 4 &&
                    (payload[1].toInt() and 0xFF) == 0x6E && (payload[2].toInt() and 0xFF) == 0xF1 &&
                    (payload[3].toInt() and 0xFF) == 0x03 -> {
                udsClient.complete(LABEL_THRESHOLD)
                _thresholdEvents.tryEmit(ThresholdEvent.WriteOk)
                // Ghi xong thì đọc lại DID F1 03 để xác nhận ECU thực sự đã lưu.
                // Nếu đọc về khác số vừa ghi, nghĩa là lệnh ghi không "xuống" tới
                // biến g_Threshold trên ECU.
                requestThresholdReadback()
            }
            // Chỉ nhận First Frame khi VIN đang là transaction hoạt động — tránh
            // nhận nhầm khung lạ khi không hề yêu cầu VIN.
            (pci and 0xF0) == 0x10 -> {
                if (udsClient.activeLabel == LABEL_VIN) vinReassembler.onFirstFrame(payload)
            }
            (pci and 0xF0) == 0x20 -> vinReassembler.onConsecutiveFrame(payload)
        }
    }

    // Đọc lại ngưỡng đang lưu trên ECU chỉ để HIỂN THỊ đối chiếu. Nếu ECU không
    // hỗ trợ đọc DID này thì bỏ qua im lặng, giữ nguyên thông báo ghi thành công.
    private fun requestThresholdReadback() {
        udsClient.enqueue(
            UdsRequest(LABEL_THRESHOLD_READ, UDS_REQUEST_ID,
                byteArrayOf(0x03, 0x22, 0xF1.toByte(), 0x03, 0, 0, 0, 0), UDS_TIMEOUT_MS,
                onTimeout = {
                    android.util.Log.d("VehicleRepository", "Threshold readback (22 F1 03) không có phản hồi — bỏ qua, ghi vẫn OK")
                })
        )
    }

    private fun enqueueVinAttempt() {
        vinReassembler.reset()
        // CHỈ gửi request — Flow Control do IsoTpReassembler kích sau khi nhận
        // First Frame (đúng thứ tự ISO 15765-2).
        udsClient.enqueue(
            UdsRequest(
                LABEL_VIN, UDS_REQUEST_ID,
                byteArrayOf(0x03, 0x22, 0xF1.toByte(), 0x90.toByte(), 0, 0, 0, 0),
                VIN_ATTEMPT_TIMEOUT_MS,
                onTimeout = { retryOrFailVin() }
            )
        )
    }

    // Một lượt đọc VIN hỏng (timeout hoặc khung sai thứ tự): huỷ transaction đang
    // chạy rồi thử lại nếu còn lượt, hết lượt thì báo Failed ra UI.
    private fun retryOrFailVin() {
        udsClient.abort(LABEL_VIN)
        vinReassembler.reset()
        if (vinAttempt < VIN_MAX_ATTEMPTS - 1) {
            vinAttempt++
            enqueueVinAttempt()
        } else {
            _vin.value = VinState.Failed
        }
    }
}
