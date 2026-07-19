package com.example.navis_test.model

// Nhiên liệu đọc qua UDS: DID F1 01 (mức %) và F1 02 (trạng thái).
// null = chưa nhận được phản hồi nào — UI giữ nguyên giá trị mặc định của layout.
data class FuelState(
    val percent: Int? = null,
    val normal: Boolean? = null
)
