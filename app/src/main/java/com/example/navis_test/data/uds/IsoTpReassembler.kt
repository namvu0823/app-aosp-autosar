package com.example.navis_test.data.uds

/**
 * Ghép các khung ISO-TP (ISO 15765-2) multi-frame thành payload hoàn chỉnh.
 * Dùng cho phản hồi dài như VIN: First Frame → (gửi Flow Control) → Consecutive Frame.
 *
 * Thuần Kotlin, không phụ thuộc Android — unit test được từng ca rớt khung/sai SN.
 *
 * @param expectedHeader các byte service+DID mong đợi ngay sau PCI của First Frame
 *        (ví dụ VIN: 62 F1 90). Khung có header khác bị bỏ qua. Header được trừ
 *        khỏi tổng độ dài và KHÔNG nằm trong kết quả trả về.
 * @param onSendFlowControl gọi SAU KHI nhận First Frame hợp lệ — theo ISO 15765-2,
 *        Flow Control phải gửi sau FF; gửi sớm hơn sẽ bị CanTp phía ECU vứt bỏ
 *        → ECU timeout N_Bs → không phát Consecutive Frame.
 * @param onComplete nhận payload hoàn chỉnh (đã bỏ header) khi ghép đủ độ dài.
 * @param onSequenceError SN của Consecutive Frame không khớp giá trị đang chờ —
 *        có khung bị rớt/đảo thứ tự; caller nên bỏ nguyên lượt và thử lại thay vì
 *        ghép nhầm byte.
 */
class IsoTpReassembler(
    private val expectedHeader: ByteArray,
    private val onSendFlowControl: () -> Unit,
    private val onComplete: (ByteArray) -> Unit,
    private val onSequenceError: (received: Int, expected: Int) -> Unit
) {
    private var buffer = ByteArray(0)
    private var expectedLength = -1
    // Sequence number của Consecutive Frame kế tiếp đang chờ (1, 2, ...). -1 = chưa có FF.
    private var nextSn = -1

    // Đang giữa chừng một lượt ghép (đã có First Frame, chưa đủ dữ liệu).
    val isActive: Boolean get() = expectedLength >= 0

    fun reset() {
        buffer = ByteArray(0)
        expectedLength = -1
        nextSn = -1
    }

    // First Frame: 1L LL <header> <dữ liệu đầu> — 12 bit độ dài nằm ở nibble thấp
    // byte0 + byte1. Trả về true nếu khung được chấp nhận.
    fun onFirstFrame(payload: ByteArray): Boolean {
        if (payload.size < 2 + expectedHeader.size) return false
        for (i in expectedHeader.indices) {
            if (payload[2 + i] != expectedHeader[i]) return false
        }

        val totalLen = ((payload[0].toInt() and 0x0F) shl 8) or (payload[1].toInt() and 0xFF)
        buffer = payload.copyOfRange(2 + expectedHeader.size, payload.size)
        expectedLength = totalLen - expectedHeader.size
        nextSn = 1 // Consecutive Frame đầu tiên phải mang SN = 1

        onSendFlowControl()
        tryFinish()
        return true
    }

    // Consecutive Frame: 2N <dữ liệu tiếp theo>, N = sequence number 0..15 quay vòng.
    fun onConsecutiveFrame(payload: ByteArray) {
        if (!isActive || payload.isEmpty()) return

        val sn = payload[0].toInt() and 0x0F
        if (sn != nextSn) {
            val expected = nextSn
            reset()
            onSequenceError(sn, expected)
            return
        }
        nextSn = (nextSn + 1) and 0x0F

        val remaining = expectedLength - buffer.size
        val take = minOf(remaining, payload.size - 1)
        if (take > 0) {
            buffer += payload.copyOfRange(1, 1 + take)
        }
        tryFinish()
    }

    private fun tryFinish() {
        if (expectedLength in 0..buffer.size) {
            val result = buffer.copyOfRange(0, expectedLength)
            reset()
            onComplete(result)
        }
    }
}
