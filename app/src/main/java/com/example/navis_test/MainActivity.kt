package com.example.navis_test

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : ImmersiveActivity() {

    private lateinit var ivFan: ImageView
    private lateinit var switchLights: Switch
    private lateinit var etThreshold: EditText
    private lateinit var tvFuelPercent: TextView
    private lateinit var pbFuel: ProgressBar
    private lateinit var tvFuelStatus: TextView
    private lateinit var tvVinId: TextView
    private lateinit var tvDoorStatus: TextView
    private lateinit var ivDoorLock: ImageView
    private lateinit var tvClimateStatus: TextView
    private lateinit var btnNormal: Button
    private lateinit var btnBlink: Button
    private lateinit var tvLightState: TextView
    private lateinit var tvLightMode: TextView
    private lateinit var tvLightError: TextView
    private lateinit var tvThresholdResult: TextView
    private lateinit var btnReadVin: Button
    private lateinit var tvCanRx: TextView
    private lateinit var tvCanTx: TextView
    private lateinit var tvCanStatus: TextView
    private lateinit var lightsListener: CompoundButton.OnCheckedChangeListener

    // Gộp dữ liệu VIN nhận về từ 3 khung ISO-TP (First Frame + 2 Consecutive Frame) trên ID 0x769
    private val vinBuffer = StringBuilder()
    private var vinExpectedLength = -1

    private val fuelPollHandler = Handler(Looper.getMainLooper())
    private val fuelPollRunnable = object : Runnable {
        override fun run() {
            requestFuelValue()
            requestFuelStatus()
            fuelPollHandler.postDelayed(this, FUEL_POLL_INTERVAL_MS)
        }
    }

    private val thresholdTimeoutHandler = Handler(Looper.getMainLooper())
    private val thresholdTimeoutRunnable = Runnable {
        showThresholdResult(success = false, errorCode = "04")
    }

    private val vinTimeoutHandler = Handler(Looper.getMainLooper())
    private val vinTimeoutRunnable = Runnable {
        vinExpectedLength = -1
        tvVinId.text = getString(R.string.vin_read_failed)
        tvVinId.setTextColor(getColor(R.color.warning_red))
    }

    private val rxLog = mutableListOf<String>()
    private val txLog = mutableListOf<String>()
    private val MAX_LOG = 5

    // Nhận mọi gói CAN đọc được từ luồng đọc dùng chung (CanConnector.CanServiceConnector)
    private val canListener: (Int, ByteArray) -> Unit = { canId, payload ->
        runOnUiThread {
            val logEntry = "RX  " + formatCanPacket(canId, payload)
            rxLog.add(0, logEntry)
            if (rxLog.size > MAX_LOG) rxLog.removeAt(rxLog.size - 1)
            tvCanRx.text = rxLog.joinToString("\n")

            handleCanMessage(canId, payload)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("MainActivity", "onCreate started")
        setContentView(R.layout.activity_main)

        // Khởi tạo các view
        ivFan = findViewById(R.id.ivFan)
        switchLights = findViewById(R.id.switchLights)
        etThreshold = findViewById(R.id.etThreshold)
        tvFuelPercent = findViewById(R.id.tvFuelPercent)
        pbFuel = findViewById(R.id.pbFuel)
        tvFuelStatus = findViewById(R.id.tvFuelStatus)
        tvVinId = findViewById(R.id.tvVinId)
        tvDoorStatus = findViewById(R.id.tvDoorStatus)
        ivDoorLock = findViewById(R.id.ivDoorLock)
        tvClimateStatus = findViewById(R.id.tvClimateStatus)
        btnNormal = findViewById(R.id.btnNormal)
        btnBlink = findViewById(R.id.btnBlink)
        tvLightState = findViewById(R.id.tvLightState)
        tvLightMode = findViewById(R.id.tvLightMode)
        tvLightError = findViewById(R.id.tvLightError)
        tvThresholdResult = findViewById(R.id.tvThresholdResult)
        btnReadVin = findViewById(R.id.btnReadVin)
        tvCanRx = findViewById(R.id.tvCanRx)
        tvCanTx = findViewById(R.id.tvCanTx)
        tvCanStatus = findViewById(R.id.tvCanStatus)

        tvCanRx.text = "RX: Idle"
        tvCanTx.text = "TX: Idle"

        val btnSave: Button = findViewById(R.id.btnSave)
        val btnOpenCanDebug: ImageButton = findViewById(R.id.btnOpenCanDebug)
        btnOpenCanDebug.setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }

        // Đọc VIN ID: chỉ gửi yêu cầu khi bấm ĐỌC
        btnReadVin.setOnClickListener {
            requestVin()
        }

        // Hiệu ứng xoay cho quạt
        startFanAnimation()

        // Tự động kết nối và mở CAN sau khi giao diện đã load xong
        Handler(Looper.getMainLooper()).postDelayed({
            connectCan()
        }, 500)

        // Điều khiển đèn qua Switch: bật -> 0x3A6 byte0=01, tắt -> byte0=02
        lightsListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            tvLightError.visibility = if (writeLightOnOff(isChecked)) View.GONE else View.VISIBLE
        }
        switchLights.setOnCheckedChangeListener(lightsListener)

        // Ghi ngưỡng cảnh báo qua UDS WriteDataByIdentifier (0x768 -> chờ xác nhận 0x769)
        btnSave.setOnClickListener {
            writeThreshold()
        }

        // Đổi mode đèn: Normal -> 0x3A6 byte1=01, Blink -> byte1=02
        btnNormal.setOnClickListener {
            tvLightError.visibility = if (writeLightMode(blink = false)) View.GONE else View.VISIBLE
        }

        btnBlink.setOnClickListener {
            tvLightError.visibility = if (writeLightMode(blink = true)) View.GONE else View.VISIBLE
        }
    }

    private fun connectCan() {
        android.util.Log.i("MainActivity", "connectCan() called")
        val connected = CanConnector.CanServiceConnector.connect()
        if (connected) {
            android.util.Log.i("MainActivity", "Service connected, opening can0...")
            CanConnector.CanServiceConnector.openAsync("can0", 500000) { opened ->
                runOnUiThread {
                    if (opened) {
                        android.util.Log.i("MainActivity", "can0 opened successfully")
                        tvCanStatus.text = getString(R.string.status_online)
                        tvCanStatus.setTextColor(getColor(R.color.neon_green))

                        CanConnector.CanServiceConnector.startReadingLoop()
                    } else {
                        android.util.Log.e("MainActivity", "Failed to open can0")
                        tvCanStatus.text = getString(R.string.status_error)
                        tvCanStatus.setTextColor(getColor(R.color.warning_red))
                    }
                }
            }
        } else {
            android.util.Log.e("MainActivity", "Failed to connect to CAN service")
            tvCanStatus.text = getString(R.string.status_offline)
            tvCanStatus.setTextColor(getColor(R.color.warning_red))
        }
    }

    // Điều khiển đèn (ghi qua 0x3A6) ----

    private fun writeLightOnOff(turnOn: Boolean): Boolean {
        val data = ByteArray(8)
        data[0] = if (turnOn) 0x01 else 0x02
        writeAndLog(0x3A6, data)
        return true // Trả về true tạm thời vì việc ghi là async
    }

    private fun writeLightMode(blink: Boolean): Boolean {
        val data = ByteArray(8)
        data[1] = if (blink) 0x02 else 0x01
        writeAndLog(0x3A6, data)
        return true // Trả về true tạm thời vì việc ghi là async
    }

    private fun writeAndLog(canId: Int, data: ByteArray, callback: ((Boolean) -> Unit)? = null) {
        android.util.Log.d("MainActivity", "Sending TX: ID=0x${Integer.toHexString(canId)}")
        CanConnector.CanServiceConnector.writeAsync(canId, data, false) { success ->
            runOnUiThread {
                val prefix = if (success) "TX  " else "TX (ERR) "
                val logEntry = prefix + formatCanPacket(canId, data)
                txLog.add(0, logEntry)
                if (txLog.size > MAX_LOG) txLog.removeAt(txLog.size - 1)
                tvCanTx.text = txLog.joinToString("\n")
                
                if (!success) {
                    android.util.Log.e("MainActivity", "Write FAILED for ID=0x${Integer.toHexString(canId)}")
                }
                callback?.invoke(success)
            }
        }
    }

    private fun formatCanPacket(canId: Int, payload: ByteArray): String {
        val idHex = String.format("%X", canId)
        val dataHex = payload.joinToString(" ") { String.format("%02X", it) }
        return "ID=0x$idHex  DATA=$dataHex"
    }

    private fun startFanAnimation() {
        val rotate = RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 2000
            repeatCount = Animation.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }
        ivFan.startAnimation(rotate)
    }

    // ---- Xử lý gói CAN nhận về ----

    private fun handleCanMessage(canId: Int, payload: ByteArray) {
        when (canId) {
            0x3C6 -> handleStatusBroadcast(payload)
            0x769 -> handleUdsResponse(payload)
        }
    }

    // Trạng thái cửa / điều hoà / đèn được S32 gửi liên tục trên 0x3C6:
    // byte0 = đèn bật/tắt, byte1 = chế độ đèn, byte2 = khoá cửa, byte3 = điều hoà
    private fun handleStatusBroadcast(payload: ByteArray) {
        if (payload.size < 4) return

        val lightOn = (payload[0].toInt() and 0xFF) == 1
        val lightBlinking = (payload[1].toInt() and 0xFF) == 1
        val doorUnlocked = (payload[2].toInt() and 0xFF) == 1
        val climateOn = (payload[3].toInt() and 0xFF) == 1

        updateLightStatusUI(lightOn, lightBlinking)
        updateDoorStatusUI(doorUnlocked)
        updateClimateStatusUI(climateOn)
    }

    private fun updateLightStatusUI(isOn: Boolean, isBlinking: Boolean) {
        // Đồng bộ Switch theo trạng thái thực tế, không kích hoạt lại listener
        switchLights.setOnCheckedChangeListener(null)
        switchLights.isChecked = isOn
        switchLights.setOnCheckedChangeListener(lightsListener)

        tvLightState.text = getString(if (isOn) R.string.light_state_on else R.string.light_state_off)
        tvLightState.setTextColor(getColor(if (isOn) R.color.electric_blue else R.color.dashboard_on_surface_variant))

        tvLightMode.text = getString(if (isBlinking) R.string.light_mode_blink else R.string.light_mode_solid)
        tvLightMode.setTextColor(getColor(if (isBlinking) R.color.purple_500 else R.color.dashboard_on_surface_variant))

        btnNormal.setBackgroundResource(R.drawable.glass_panel)
        if (isOn && !isBlinking) {
            btnNormal.backgroundTintList = getColorStateList(R.color.purple_500)
            btnNormal.setTextColor(getColor(R.color.white))
        } else {
            btnNormal.backgroundTintList = getColorStateList(R.color.dashboard_surface_container)
            btnNormal.setTextColor(getColor(R.color.dashboard_on_surface_variant))
        }

        btnBlink.setBackgroundResource(R.drawable.glass_panel)
        if (isOn && isBlinking) {
            btnBlink.backgroundTintList = getColorStateList(R.color.purple_500)
            btnBlink.setTextColor(getColor(R.color.white))
        } else {
            btnBlink.backgroundTintList = getColorStateList(R.color.dashboard_surface_container)
            btnBlink.setTextColor(getColor(R.color.dashboard_on_surface_variant))
        }
    }

    private fun updateDoorStatusUI(unlocked: Boolean) {
        tvDoorStatus.text = getString(if (unlocked) R.string.door_unlocked else R.string.door_locked)
        ivDoorLock.setImageResource(if (unlocked) android.R.drawable.ic_lock_power_off else android.R.drawable.ic_lock_lock)
        val color = getColor(if (unlocked) R.color.warning_red else R.color.neon_green)
        tvDoorStatus.setTextColor(color)
        ivDoorLock.setColorFilter(color)
    }

    private fun updateClimateStatusUI(isOn: Boolean) {
        tvClimateStatus.text = getString(if (isOn) R.string.climate_on else R.string.climate_off)
        if (isOn) {
            startFanAnimation()
            ivFan.setColorFilter(getColor(R.color.electric_blue))
            tvClimateStatus.setTextColor(getColor(R.color.electric_blue))
        } else {
            ivFan.clearAnimation()
            ivFan.setColorFilter(getColor(R.color.dashboard_on_surface_variant))
            tvClimateStatus.setTextColor(getColor(R.color.dashboard_on_surface_variant))
        }
    }

    // ---- UDS qua 0x768 (yêu cầu) / 0x769 (phản hồi) ----

    private fun requestFuelValue() {
        writeAndLog(0x768, byteArrayOf(0x03, 0x22, 0xF1.toByte(), 0x01, 0, 0, 0, 0))
    }

    private fun requestFuelStatus() {
        writeAndLog(0x768, byteArrayOf(0x03, 0x22, 0xF1.toByte(), 0x02, 0, 0, 0, 0))
    }

    private fun requestVin() {
        vinBuffer.clear()
        vinExpectedLength = -1
        tvVinId.text = getString(R.string.vin_reading)
        tvVinId.setTextColor(getColor(R.color.dashboard_on_surface_variant))
        vinTimeoutHandler.removeCallbacks(vinTimeoutRunnable)
        vinTimeoutHandler.postDelayed(vinTimeoutRunnable, VIN_TIMEOUT_MS)

        writeAndLog(0x768, byteArrayOf(0x03, 0x22, 0xF1.toByte(), 0x90.toByte(), 0, 0, 0, 0))
        writeAndLog(0x768, byteArrayOf(0x30, 0, 0, 0, 0, 0, 0, 0))
    }

    private fun writeThreshold() {
        val thresholdText = etThreshold.text.toString()
        if (thresholdText.isEmpty()) {
            showThresholdResult(success = false, errorCode = "03")
            return
        }
        val threshold: Int
        try {
            threshold = thresholdText.toInt()
        } catch (e: NumberFormatException) {
            showThresholdResult(success = false, errorCode = "03")
            return
        }

        val data = byteArrayOf(0x04, 0x2E, 0xF1.toByte(), 0x03, threshold.toByte(), 0, 0, 0)
        writeAndLog(0x768, data) { success ->
            if (success) {
                thresholdTimeoutHandler.removeCallbacks(thresholdTimeoutRunnable)
                thresholdTimeoutHandler.postDelayed(thresholdTimeoutRunnable, THRESHOLD_TIMEOUT_MS)
            } else {
                val errorCode = if (!CanConnector.CanServiceConnector.isConnected()) "01" else "02"
                showThresholdResult(success = false, errorCode = errorCode)
            }
        }
    }

    private fun showThresholdResult(success: Boolean, errorCode: String? = null) {
        if (success) {
            tvThresholdResult.text = getString(R.string.msg_write_success)
            tvThresholdResult.setTextColor(getColor(R.color.neon_green))
        } else {
            tvThresholdResult.text = getString(R.string.msg_write_failed, errorCode)
            tvThresholdResult.setTextColor(getColor(R.color.warning_red))
        }
        tvThresholdResult.visibility = View.VISIBLE
    }

    // Phân loại phản hồi 0x769: dữ liệu nhiên liệu (single frame), xác nhận ghi ngưỡng,
    // hoặc các khung ISO-TP (First Frame / Consecutive Frame) của VIN
    private fun handleUdsResponse(payload: ByteArray) {
        if (payload.isEmpty()) return
        val pci = payload[0].toInt() and 0xFF

        when {
            pci == 0x04 && payload.size >= 5 &&
                    (payload[1].toInt() and 0xFF) == 0x62 && (payload[2].toInt() and 0xFF) == 0xF1 -> {
                val data = payload[4].toInt() and 0xFF
                when (payload[3].toInt() and 0xFF) {
                    0x01 -> updateFuelPercent(data)
                    0x02 -> updateFuelStatus(data)
                }
            }
            pci == 0x03 && payload.size >= 4 &&
                    (payload[1].toInt() and 0xFF) == 0x6E && (payload[2].toInt() and 0xFF) == 0xF1 &&
                    (payload[3].toInt() and 0xFF) == 0x03 -> {
                thresholdTimeoutHandler.removeCallbacks(thresholdTimeoutRunnable)
                showThresholdResult(success = true)
                Toast.makeText(this, R.string.msg_write_success, Toast.LENGTH_SHORT).show()
            }
            (pci and 0xF0) == 0x10 -> handleVinFirstFrame(payload)
            (pci and 0xF0) == 0x20 -> handleVinConsecutiveFrame(payload)
        }
    }

    private fun updateFuelPercent(percent: Int) {
        tvFuelPercent.text = percent.toString()
        pbFuel.progress = percent
    }

    private fun updateFuelStatus(status: Int) {
        if (status == 1) {
            tvFuelStatus.text = getString(R.string.fuel_status_normal)
            tvFuelStatus.setTextColor(getColor(R.color.neon_green))
        } else {
            tvFuelStatus.text = getString(R.string.fuel_status_warning)
            tvFuelStatus.setTextColor(getColor(R.color.warning_red))
        }
    }

    // First Frame: 10 <len> 62 F1 90 <3 ký tự đầu VIN>
    private fun handleVinFirstFrame(payload: ByteArray) {
        if (payload.size < 5) return
        if ((payload[2].toInt() and 0xFF) != 0x62 ||
            (payload[3].toInt() and 0xFF) != 0xF1 ||
            (payload[4].toInt() and 0xFF) != 0x90
        ) return

        val totalLen = ((payload[0].toInt() and 0x0F) shl 8) or (payload[1].toInt() and 0xFF)
        vinBuffer.clear()
        vinExpectedLength = totalLen - 3 // trừ 3 byte service (62) + DID (F1 90)
        for (i in 5 until payload.size) {
            vinBuffer.append((payload[i].toInt() and 0xFF).toChar())
        }
        tryFinishVin()
    }

    // Consecutive Frame: 21/22 <7 ký tự tiếp theo>
    private fun handleVinConsecutiveFrame(payload: ByteArray) {
        if (vinExpectedLength < 0) return
        for (i in 1 until payload.size) {
            if (vinBuffer.length >= vinExpectedLength) break
            vinBuffer.append((payload[i].toInt() and 0xFF).toChar())
        }
        tryFinishVin()
    }

    private fun tryFinishVin() {
        if (vinExpectedLength in 0..vinBuffer.length) {
            vinTimeoutHandler.removeCallbacks(vinTimeoutRunnable)
            tvVinId.text = vinBuffer.substring(0, vinExpectedLength)
            tvVinId.setTextColor(getColor(R.color.dashboard_on_surface))
            vinExpectedLength = -1
        }
    }

    override fun onResume() {
        super.onResume()
        CanConnector.CanServiceConnector.addListener(canListener)
        fuelPollHandler.post(fuelPollRunnable)
    }

    override fun onPause() {
        CanConnector.CanServiceConnector.removeListener(canListener)
        fuelPollHandler.removeCallbacks(fuelPollRunnable)
        thresholdTimeoutHandler.removeCallbacks(thresholdTimeoutRunnable)
        vinTimeoutHandler.removeCallbacks(vinTimeoutRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        CanConnector.CanServiceConnector.close()
        super.onDestroy()
    }

    companion object {
        private const val FUEL_POLL_INTERVAL_MS = 1000L
        private const val THRESHOLD_TIMEOUT_MS = 2000L
        private const val VIN_TIMEOUT_MS = 2000L
    }
}
