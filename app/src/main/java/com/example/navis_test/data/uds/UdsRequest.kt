package com.example.navis_test.data.uds

// Một request UDS chờ trong hàng đợi của UdsClient.
class UdsRequest(
    val label: String,
    val canId: Int,
    val data: ByteArray,
    val timeoutMs: Long,
    val onTimeout: (() -> Unit)? = null,
    val onSendFailed: (() -> Unit)? = null
)
