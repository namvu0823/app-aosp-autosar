package com.example.navis_test.model

// Một dòng log RX/TX dùng chung cho dashboard và màn debug.
class CanLogEntry(
    val direction: Direction,
    val ok: Boolean, // với TX: gửi lên bus thành công hay không; RX luôn true
    val canId: Int,
    val data: ByteArray,
    val atMillis: Long
) {
    enum class Direction { RX, TX }

    fun formatPacket(): String {
        val idHex = String.format("%X", canId)
        val dataHex = data.joinToString(" ") { String.format("%02X", it) }
        return "ID=0x$idHex  DATA=$dataHex"
    }
}
