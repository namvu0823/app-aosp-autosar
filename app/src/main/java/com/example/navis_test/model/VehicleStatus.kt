package com.example.navis_test.model

// Trạng thái xe parse từ broadcast 0x3C6 của ECU:
// byte0 = đèn bật/tắt, byte1 = chế độ đèn, byte2 = khoá cửa, byte3 = điều hoà
data class VehicleStatus(
    val lightOn: Boolean,
    val lightBlinking: Boolean,
    val doorUnlocked: Boolean,
    val climateOn: Boolean
)
