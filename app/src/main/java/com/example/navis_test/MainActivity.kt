package com.example.navis_test

import android.os.Bundle
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var isReading = false
    private var currentRgbControl = 0x02
    private var currentRgbModeControl = 0x01
    private var advancedSettingsExpanded = false

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
    private lateinit var tvLightError: TextView
    private lateinit var rowAdvancedToggle: LinearLayout
    private lateinit var advancedSettingsContent: LinearLayout
    private lateinit var ivAdvancedChevron: ImageView
    private lateinit var lightsListener: CompoundButton.OnCheckedChangeListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        tvLightError = findViewById(R.id.tvLightError)
        rowAdvancedToggle = findViewById(R.id.rowAdvancedToggle)
        advancedSettingsContent = findViewById(R.id.advancedSettingsContent)
        ivAdvancedChevron = findViewById(R.id.ivAdvancedChevron)

        val btnSave: Button = findViewById(R.id.btnSave)

        // Mở/đóng khối cài đặt nâng cao
        rowAdvancedToggle.setOnClickListener {
            advancedSettingsExpanded = !advancedSettingsExpanded
            advancedSettingsContent.visibility = if (advancedSettingsExpanded) View.VISIBLE else View.GONE
            ivAdvancedChevron.animate()
                .rotation(if (advancedSettingsExpanded) 180f else 0f)
                .setDuration(200)
                .start()
        }

        // Khởi tạo giá trị điều khiển từ trạng thái ban đầu của Switch
        currentRgbControl = if (switchLights.isChecked) 0x01 else 0x02

        // Cập nhật trạng thái UI ban đầu cho các nút mode
        updateModeButtonsUI()

        // Hiệu ứng xoay cho quạt
        startFanAnimation()

        // Tự động kết nối và mở CAN ngay khi vào màn hình
        connectCan()

        // Điều khiển đèn qua Switch (Signal 1: rgb_control)
        lightsListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            currentRgbControl = if (isChecked) 0x01 else 0x02
            if (sendRgbCommand()) {
                tvLightError.visibility = View.GONE
                updateModeButtonsUI()
            } else {
                tvLightError.visibility = View.VISIBLE
            }
        }
        switchLights.setOnCheckedChangeListener(lightsListener)

        // Lưu ngưỡng cảnh báo
        btnSave.setOnClickListener {
            val thresholdText = etThreshold.text.toString()
            if (thresholdText.isNotEmpty()) {
                try {
                    val threshold = thresholdText.toInt()
                    val data = byteArrayOf(threshold.toByte())
                    val success = CanConnector.CanServiceConnector.write(0x124, data, false)
                    if (success) {
                        Toast.makeText(this, "Đã lưu ngưỡng: $threshold", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Gửi ngưỡng thất bại", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: NumberFormatException) {
                    Toast.makeText(this, "Ngưỡng không hợp lệ", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Điều khiển Mode (Signal 2: rgb_mode_control)
        btnNormal.setOnClickListener {
            currentRgbModeControl = 0x01
            if (sendRgbCommand()) {
                tvLightError.visibility = View.GONE
                updateModeButtonsUI()
            } else {
                tvLightError.visibility = View.VISIBLE
            }
        }

        btnBlink.setOnClickListener {
            currentRgbModeControl = 0x02
            if (sendRgbCommand()) {
                tvLightError.visibility = View.GONE
                updateModeButtonsUI()
            } else {
                tvLightError.visibility = View.VISIBLE
            }
        }
    }

    private fun connectCan() {
        val connected = CanConnector.CanServiceConnector.connect()
        if (connected) {
            val opened = CanConnector.CanServiceConnector.open("can0", 500000)
            if (opened) {
                Toast.makeText(this, "Đã kết nối CAN: can0 (500k)", Toast.LENGTH_SHORT).show()
                startReadingLoop()
            } else {
                Toast.makeText(this, "Không thể mở cổng CAN", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Không thể kết nối Service", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateModeButtonsUI() {
        val isLightOn = currentRgbControl == 0x01

        // Cập nhật nút Normal
        btnNormal.setBackgroundResource(R.drawable.glass_panel)
        if (isLightOn && currentRgbModeControl == 0x01) {
            btnNormal.backgroundTintList = getColorStateList(R.color.purple_500)
            btnNormal.setTextColor(getColor(R.color.white))
        } else {
            btnNormal.backgroundTintList = getColorStateList(R.color.dashboard_surface_container)
            btnNormal.setTextColor(getColor(R.color.dashboard_on_surface_variant))
        }

        // Cập nhật nút Blink
        btnBlink.setBackgroundResource(R.drawable.glass_panel)
        if (isLightOn && currentRgbModeControl == 0x02) {
            btnBlink.backgroundTintList = getColorStateList(R.color.purple_500)
            btnBlink.setTextColor(getColor(R.color.white))
        } else {
            btnBlink.backgroundTintList = getColorStateList(R.color.dashboard_surface_container)
            btnBlink.setTextColor(getColor(R.color.dashboard_on_surface_variant))
        }
    }

    private fun sendRgbCommand(): Boolean {
        val data = ByteArray(8)
        data[0] = currentRgbControl.toByte()     // Signal 1: rgb_control (startbit 0)
        data[1] = currentRgbModeControl.toByte() // Signal 2: rgb_mode_control (startbit 8)
        return CanConnector.CanServiceConnector.write(0x3A6, data, false)
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

    private fun startReadingLoop() {
        if (isReading) return
        isReading = true

        Thread {
            while (isReading) {
                val result = CanConnector.CanServiceConnector.read()
                if (result != null) {
                    val (canId, payload) = result
                    runOnUiThread {
                        handleCanMessage(canId, payload)
                    }
                }
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    private fun handleCanMessage(canId: Int, payload: ByteArray) {
        when (canId) {
            0x301 -> { // Fuel percent
                if (payload.isNotEmpty()) {
                    val percent = payload[0].toInt() and 0xFF
                    tvFuelPercent.text = percent.toString()
                    pbFuel.progress = percent

                    if (percent < 20) {
                        tvFuelStatus.text = getString(R.string.fuel_status_warning)
                        tvFuelStatus.setTextColor(getColor(R.color.warning_red))
                    } else {
                        tvFuelStatus.text = getString(R.string.fuel_status_normal)
                        tvFuelStatus.setTextColor(getColor(R.color.neon_green))
                    }
                }
            }
            0x302 -> { // VIN ID (String)
                tvVinId.text = String(payload)
            }
            0x303 -> { // Door status
                if (payload.isNotEmpty()) {
                    val isLocked = payload[0].toInt() == 0x01
                    tvDoorStatus.text = if (isLocked) getString(R.string.door_locked) else getString(R.string.door_unlocked)
                    ivDoorLock.setImageResource(if (isLocked) android.R.drawable.ic_lock_lock else android.R.drawable.ic_lock_power_off)
                    val color = if (isLocked) getColor(R.color.neon_green) else getColor(R.color.warning_red)
                    tvDoorStatus.setTextColor(color)
                    ivDoorLock.setColorFilter(color)
                }
            }
            0x304 -> { // Climate status
                if (payload.isNotEmpty()) {
                    val isOn = payload[0].toInt() == 0x01
                    tvClimateStatus.text = if (isOn) getString(R.string.climate_on) else getString(R.string.climate_off)
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
            }
            0x3A6 -> { // RGB Control Status Sync
                if (payload.size >= 2) {
                    val rgbControl = payload[0].toInt() and 0x03
                    val rgbMode = payload[1].toInt() and 0x03

                    if (rgbControl != 0x00) {
                        currentRgbControl = rgbControl
                        // Sync switch state without triggering listener
                        switchLights.setOnCheckedChangeListener(null)
                        switchLights.isChecked = (currentRgbControl == 0x01)
                        switchLights.setOnCheckedChangeListener(lightsListener)
                    }
                    if (rgbMode != 0x00) {
                        currentRgbModeControl = rgbMode
                    }
                    updateModeButtonsUI()
                }
            }
        }
    }

    private fun stopReading() {
        isReading = false
    }

    override fun onDestroy() {
        stopReading()
        CanConnector.CanServiceConnector.close()
        super.onDestroy()
    }
}
