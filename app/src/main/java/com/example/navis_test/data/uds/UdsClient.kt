package com.example.navis_test.data.uds

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Hàng đợi UDS nối tiếp.
 *
 * Mọi yêu cầu UDS (đọc nhiên liệu, trạng thái, ghi ngưỡng, VIN) đi qua hàng đợi
 * này để KHÔNG BAO GIỜ có 2 request cùng chờ phản hồi một lúc: tester phải chờ
 * phản hồi (hoặc timeout) của request trước rồi mới gửi request kế tiếp — đúng
 * kỷ luật UDS. Nếu pipeline 2 lệnh 0x22 sát nhau, DCM chỉ trả lời lệnh đầu và
 * bỏ lệnh sau. Giữa hai bản tin giữ cycle time tối thiểu [minTxGapMs].
 *
 * Toàn bộ truy cập hàng đợi đều trên main thread nên không cần khoá.
 * Việc gửi thực tế được uỷ quyền qua [send] — callback kết quả phải được gọi
 * lại trên main thread (VehicleRepository đảm bảo điều này).
 */
class UdsClient(
    private val minTxGapMs: Long,
    private val send: (canId: Int, data: ByteArray, onResult: (Boolean) -> Unit) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<UdsRequest>()
    private var active: UdsRequest? = null
    private val timeoutRunnable = Runnable { onTimeout() }
    private val pumpRunnable = Runnable { pump() }

    // Mốc uptime (ms) lần phát bản tin gần nhất qua hàng đợi.
    private var lastTxAt = 0L

    // Label của transaction đang chờ phản hồi (null nếu rảnh) — tầng trên dùng
    // để gate việc nhận khung (ví dụ chỉ nhận First Frame VIN khi đang hỏi VIN).
    val activeLabel: String? get() = active?.label

    // unique=true: bỏ qua nếu đã có request cùng label đang chạy/đang chờ —
    // tránh dồn ứ khi fuel poll bắn liên tục lúc bus bận.
    // front=true: chen lên ĐẦU hàng đợi. Dùng cho tác vụ do người dùng bấm
    // (ghi ngưỡng) để không bị kẹt sau vòng poll nhiên liệu chạy mỗi giây.
    fun enqueue(request: UdsRequest, unique: Boolean = false, front: Boolean = false) {
        if (unique && (active?.label == request.label || queue.any { it.label == request.label })) {
            return
        }
        if (front) queue.addFirst(request) else queue.addLast(request)
        pump()
    }

    // Nếu đang rảnh, lấy request kế tiếp ra gửi và đặt timeout cho nó.
    // Nếu chưa đủ minTxGapMs kể từ lần phát trước thì hoãn, phát khi đủ giờ.
    // Lưu ý: khung Flow Control của VIN không đi qua hàng đợi nên không bị giãn
    // — giữ đúng thời gian ISO-TP.
    private fun pump() {
        if (active != null) return
        if (queue.isEmpty()) return
        val sinceLastTx = SystemClock.uptimeMillis() - lastTxAt
        if (sinceLastTx < minTxGapMs) {
            handler.removeCallbacks(pumpRunnable)
            handler.postDelayed(pumpRunnable, minTxGapMs - sinceLastTx)
            return
        }
        val tx = queue.removeFirstOrNull() ?: return
        active = tx
        lastTxAt = SystemClock.uptimeMillis()
        handler.postDelayed(timeoutRunnable, tx.timeoutMs)
        send(tx.canId, tx.data) { success ->
            // Gửi lên bus thất bại → huỷ luôn transaction này, chuyển tiếp.
            if (!success && active === tx) {
                handler.removeCallbacks(timeoutRunnable)
                active = null
                tx.onSendFailed?.invoke()
                pump()
            }
        }
    }

    // Gọi khi transaction đang chạy đã nhận đủ phản hồi (thành công).
    fun complete(label: String) {
        if (active?.label != label) return
        handler.removeCallbacks(timeoutRunnable)
        active = null
        pump()
    }

    // Huỷ transaction đang chạy đúng label (dùng khi tự phát hiện lỗi giữa chừng,
    // ví dụ VIN nhận Consecutive Frame sai thứ tự) rồi chuyển sang request kế tiếp.
    fun abort(label: String) {
        if (active?.label != label) return
        handler.removeCallbacks(timeoutRunnable)
        active = null
        pump()
    }

    // Dừng hàng đợi: xoá timeout đang chờ và mọi request còn treo (gọi khi màn
    // dashboard pause để không tiếp tục bắn UDS xuống bus).
    fun clear() {
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(pumpRunnable)
        queue.clear()
        active = null
    }

    private fun onTimeout() {
        val tx = active ?: return
        active = null
        tx.onTimeout?.invoke()
        pump()
    }
}
