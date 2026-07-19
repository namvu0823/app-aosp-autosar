package com.example.navis_test.data.uds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Kiểm chứng logic ghép ISO-TP (ISO 15765-2) cho VIN: 62 F1 90 + 17 ký tự.
// VIN "LDC613P23A1300001" → tổng độ dài 20 byte → FF chứa 3 ký tự đầu,
// 2 Consecutive Frame mỗi khung 7 ký tự.
class IsoTpReassemblerTest {

    private val header = byteArrayOf(0x62, 0xF1.toByte(), 0x90.toByte())

    private var flowControlCount = 0
    private var completed: ByteArray? = null
    private var sequenceError: Pair<Int, Int>? = null

    private fun newReassembler() = IsoTpReassembler(
        expectedHeader = header,
        onSendFlowControl = { flowControlCount++ },
        onComplete = { completed = it },
        onSequenceError = { received, expected -> sequenceError = Pair(received, expected) }
    )

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun chars(s: String) = s.map { it.code }.toIntArray()

    // First Frame của VIN: 10 14 62 F1 90 'L' 'D' 'C'
    private fun vinFirstFrame() = bytes(0x10, 0x14, 0x62, 0xF1, 0x90, *chars("LDC"))
    private fun vinCf1() = bytes(0x21, *chars("613P23A"))
    private fun vinCf2() = bytes(0x22, *chars("1300001"))

    @Test
    fun `ghep du 3 khung ra dung VIN`() {
        val r = newReassembler()

        assertTrue(r.onFirstFrame(vinFirstFrame()))
        assertEquals(1, flowControlCount) // FC gửi đúng 1 lần, sau FF
        assertTrue(r.isActive)
        assertNull(completed)

        r.onConsecutiveFrame(vinCf1())
        assertNull(completed) // mới 10/17 ký tự

        r.onConsecutiveFrame(vinCf2())
        val vin = completed!!.toString(Charsets.US_ASCII)
        assertEquals("LDC613P23A1300001", vin)
        assertFalse(r.isActive) // xong lượt thì tự reset
        assertNull(sequenceError)
    }

    @Test
    fun `CF sai thu tu bao sequence error va khong ghep tiep`() {
        val r = newReassembler()
        r.onFirstFrame(vinFirstFrame())

        // Rớt CF1 (SN=1), CF2 (SN=2) tới trước
        r.onConsecutiveFrame(vinCf2())

        assertEquals(Pair(2, 1), sequenceError)
        assertNull(completed)
        assertFalse(r.isActive) // lượt hỏng phải bị huỷ để caller retry
    }

    @Test
    fun `CF toi khi chua co FF thi bo qua`() {
        val r = newReassembler()
        r.onConsecutiveFrame(vinCf1())
        assertNull(completed)
        assertNull(sequenceError)
        assertFalse(r.isActive)
    }

    @Test
    fun `FF sai header bi tu choi`() {
        val r = newReassembler()
        // Header F1 91 thay vì F1 90
        val wrong = bytes(0x10, 0x14, 0x62, 0xF1, 0x91, *chars("LDC"))
        assertFalse(r.onFirstFrame(wrong))
        assertEquals(0, flowControlCount)
        assertFalse(r.isActive)
    }

    @Test
    fun `reset giua chung thi CF sau do bi bo qua`() {
        val r = newReassembler()
        r.onFirstFrame(vinFirstFrame())
        r.reset()
        r.onConsecutiveFrame(vinCf1())
        assertNull(completed)
        assertNull(sequenceError)
    }

    @Test
    fun `SN quay vong 15 ve 0`() {
        // Payload dài để cần hơn 16 CF: header + 120 ký tự 'A'
        val totalLen = 3 + 120
        val r = newReassembler()
        val ff = bytes(0x10 or (totalLen shr 8), totalLen and 0xFF, 0x62, 0xF1, 0x90, *chars("AAA"))
        assertTrue(r.onFirstFrame(ff))

        var sn = 1
        var sent = 3
        while (sent < 120 && completed == null) {
            r.onConsecutiveFrame(bytes(0x20 or sn, *chars("AAAAAAA")))
            sent += 7
            sn = (sn + 1) and 0x0F
        }
        assertEquals(120, completed!!.size)
        assertNull(sequenceError)
    }
}
