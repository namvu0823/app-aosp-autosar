package com.example.navis_test.ui.main

import com.example.navis_test.model.ConnectionState
import com.example.navis_test.model.VehicleStatus
import com.example.navis_test.model.VinState

// Toàn bộ trạng thái màn dashboard mà MainActivity cần để vẽ.
// Các field null = chưa có dữ liệu — giữ nguyên giá trị mặc định của layout.
data class DashboardUiState(
    val connection: ConnectionState = ConnectionState.CONNECTING,
    val vehicle: VehicleStatus? = null,
    val fuelPercent: Int? = null,
    val fuelNormal: Boolean? = null,
    val vin: VinState = VinState.Idle,
    val threshold: ThresholdUi = ThresholdUi.Idle,
    val thresholdSaving: Boolean = false, // đang chờ ECU xác nhận → khoá nút GHI
    val rxLog: List<String> = emptyList(),
    val txLog: List<String> = emptyList()
)

// Trạng thái vùng hiển thị kết quả ghi ngưỡng.
sealed class ThresholdUi {
    object Idle : ThresholdUi()
    data class Result(val success: Boolean, val errorCode: String? = null) : ThresholdUi()
    data class Readback(val value: Int) : ThresholdUi()
}

// Sự kiện một lần (không phải trạng thái) — hiện Toast.
sealed class MainEvent {
    object ShowWriteSuccessToast : MainEvent()
}
