package com.example.navis_test.model

// Tiến trình đọc VIN qua ISO-TP (DID F1 90).
sealed class VinState {
    object Idle : VinState()
    object Reading : VinState()
    data class Success(val vin: String) : VinState()
    object Failed : VinState()
}
