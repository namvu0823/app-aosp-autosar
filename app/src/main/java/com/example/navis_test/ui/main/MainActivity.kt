package com.example.navis_test.ui.main

import android.os.Bundle
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
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.navis_test.R
import com.example.navis_test.model.ConnectionState
import com.example.navis_test.model.VehicleStatus
import com.example.navis_test.model.VinState
import com.example.navis_test.ui.ImmersiveActivity
import com.example.navis_test.ui.ThemeManager
import kotlinx.coroutines.launch

// Dashboard chính — chỉ còn việc của View: bind view, đẩy thao tác người dùng
// sang MainViewModel và vẽ DashboardUiState. Toàn bộ logic CAN/UDS nằm ở tầng data.
class MainActivity : ImmersiveActivity() {

    private val viewModel: MainViewModel by viewModels()

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
    private lateinit var btnSave: Button
    private lateinit var tvCanRx: TextView
    private lateinit var tvCanTx: TextView
    private lateinit var tvCanStatus: TextView
    private lateinit var lightsListener: CompoundButton.OnCheckedChangeListener

    override fun onCreate(savedInstanceState: Bundle?) {
        // Áp chế độ sáng/tối đã lưu TRƯỚC super.onCreate để không bị recreate/nháy màn.
        ThemeManager.applySaved(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()

        tvCanRx.text = "RX: Idle"
        tvCanTx.text = "TX: Idle"

        findViewById<ImageButton>(R.id.btnToggleTheme).setOnClickListener {
            ThemeManager.toggle(this)
        }

        btnReadVin.setOnClickListener { viewModel.onReadVinClicked() }

        lightsListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            tvLightError.visibility = View.GONE
            viewModel.onLightToggled(isChecked)
        }
        switchLights.setOnCheckedChangeListener(lightsListener)

        btnSave.setOnClickListener { viewModel.onSaveThresholdClicked(etThreshold.text.toString()) }
        btnNormal.setOnClickListener { viewModel.onLightModeSelected(blink = false) }
        btnBlink.setOnClickListener { viewModel.onLightModeSelected(blink = true) }

        startFanAnimation()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is MainEvent.ShowWriteSuccessToast ->
                                Toast.makeText(this@MainActivity, R.string.msg_write_success, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResumed()
    }

    override fun onPause() {
        viewModel.onPaused()
        super.onPause()
    }

    private fun bindViews() {
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
        btnSave = findViewById(R.id.btnSave)
        tvCanRx = findViewById(R.id.tvCanRx)
        tvCanTx = findViewById(R.id.tvCanTx)
        tvCanStatus = findViewById(R.id.tvCanStatus)
    }

    // ---- Vẽ UiState ra màn hình ----

    private fun render(state: DashboardUiState) {
        renderConnection(state.connection)
        state.vehicle?.let { renderVehicle(it) }
        renderFuel(state.fuelPercent, state.fuelNormal)
        renderVin(state.vin)
        renderThreshold(state)
        if (state.rxLog.isNotEmpty()) tvCanRx.text = state.rxLog.joinToString("\n")
        if (state.txLog.isNotEmpty()) tvCanTx.text = state.txLog.joinToString("\n")
    }

    private fun renderConnection(connection: ConnectionState) {
        when (connection) {
            ConnectionState.CONNECTING -> Unit // giữ chữ mặc định của layout
            ConnectionState.ONLINE -> {
                tvCanStatus.text = getString(R.string.status_online)
                tvCanStatus.setTextColor(getColor(R.color.neon_green))
            }
            ConnectionState.SERVICE_OFFLINE -> {
                tvCanStatus.text = getString(R.string.status_offline)
                tvCanStatus.setTextColor(getColor(R.color.warning_red))
            }
            ConnectionState.OPEN_FAILED -> {
                tvCanStatus.text = getString(R.string.status_error)
                tvCanStatus.setTextColor(getColor(R.color.warning_red))
            }
        }
    }

    private fun renderVehicle(vehicle: VehicleStatus) {
        renderLightStatus(vehicle.lightOn, vehicle.lightBlinking)
        renderDoorStatus(vehicle.doorUnlocked)
        renderClimateStatus(vehicle.climateOn)
    }

    private fun renderLightStatus(isOn: Boolean, isBlinking: Boolean) {
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

    private fun renderDoorStatus(unlocked: Boolean) {
        tvDoorStatus.text = getString(if (unlocked) R.string.door_unlocked else R.string.door_locked)
        ivDoorLock.setImageResource(if (unlocked) android.R.drawable.ic_lock_power_off else android.R.drawable.ic_lock_lock)
        val color = getColor(if (unlocked) R.color.warning_red else R.color.neon_green)
        tvDoorStatus.setTextColor(color)
        ivDoorLock.setColorFilter(color)
    }

    private fun renderClimateStatus(isOn: Boolean) {
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

    private fun renderFuel(percent: Int?, normal: Boolean?) {
        if (percent != null) {
            tvFuelPercent.text = percent.toString()
            pbFuel.progress = percent
        }
        if (normal != null) {
            if (normal) {
                tvFuelStatus.text = getString(R.string.fuel_status_normal)
                tvFuelStatus.setTextColor(getColor(R.color.neon_green))
            } else {
                tvFuelStatus.text = getString(R.string.fuel_status_warning)
                tvFuelStatus.setTextColor(getColor(R.color.warning_red))
            }
        }
    }

    private fun renderVin(vin: VinState) {
        when (vin) {
            is VinState.Idle -> Unit // giữ chữ mặc định của layout
            is VinState.Reading -> {
                tvVinId.text = getString(R.string.vin_reading)
                tvVinId.setTextColor(getColor(R.color.dashboard_on_surface_variant))
            }
            is VinState.Success -> {
                tvVinId.text = vin.vin
                tvVinId.setTextColor(getColor(R.color.dashboard_on_surface))
            }
            is VinState.Failed -> {
                tvVinId.text = getString(R.string.vin_read_failed)
                tvVinId.setTextColor(getColor(R.color.warning_red))
            }
        }
    }

    private fun renderThreshold(state: DashboardUiState) {
        btnSave.isEnabled = !state.thresholdSaving
        when (val threshold = state.threshold) {
            is ThresholdUi.Idle -> Unit
            is ThresholdUi.Result -> {
                if (threshold.success) {
                    tvThresholdResult.text = getString(R.string.msg_write_success)
                    tvThresholdResult.setTextColor(getColor(R.color.neon_green))
                } else {
                    tvThresholdResult.text = getString(R.string.msg_write_failed, threshold.errorCode)
                    tvThresholdResult.setTextColor(getColor(R.color.warning_red))
                }
                tvThresholdResult.visibility = View.VISIBLE
            }
            is ThresholdUi.Readback -> {
                tvThresholdResult.text = getString(R.string.msg_threshold_readback, threshold.value)
                tvThresholdResult.setTextColor(getColor(R.color.neon_green))
                tvThresholdResult.visibility = View.VISIBLE
            }
        }
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
}
