package com.example.navis_test

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Locale

class DebugActivity : ImmersiveActivity() {

    private lateinit var etCanId: EditText
    private lateinit var etCanData: EditText
    private lateinit var cbExtended: CheckBox
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnToggleReading: Button

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val canListener: (Int, ByteArray) -> Unit = { canId, payload ->
        runOnUiThread {
            appendLog("RX", canId, payload)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        etCanId = findViewById(R.id.etCanId)
        etCanData = findViewById(R.id.etCanData)
        cbExtended = findViewById(R.id.cbExtended)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        btnToggleReading = findViewById(R.id.btnToggleReading)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { tvLog.text = "" }

        updateToggleReadingLabel()
        btnToggleReading.setOnClickListener {
            if (CanConnector.CanServiceConnector.isReadingActive()) {
                CanConnector.CanServiceConnector.stopReadingLoop()
            } else {
                CanConnector.CanServiceConnector.startReadingLoop()
            }
            updateToggleReadingLabel()
        }

        findViewById<Button>(R.id.btnSend).setOnClickListener { sendPacket() }
    }

    private fun updateToggleReadingLabel() {
        btnToggleReading.text = if (CanConnector.CanServiceConnector.isReadingActive()) {
            getString(R.string.btn_stop_reading)
        } else {
            getString(R.string.btn_start_reading)
        }
    }

    private fun sendPacket() {
        val canId = parseCanId(etCanId.text.toString())
        if (canId == null) {
            Toast.makeText(this, R.string.error_can_id_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val data = parseCanData(etCanData.text.toString())
        if (data == null) {
            Toast.makeText(this, R.string.error_can_data_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        val extended = cbExtended.isChecked
        CanConnector.CanServiceConnector.writeAsync(canId, data, extended) { success ->
            runOnUiThread {
                if (success) {
                    appendLog("TX", canId, data)
                } else {
                    Toast.makeText(this, R.string.msg_can_send_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Nhận "3A6" hoặc "0x3A6" dạng hex
    private fun parseCanId(input: String): Int? {
        val trimmed = input.trim().removePrefix("0x").removePrefix("0X")
        if (trimmed.isEmpty()) return null
        return trimmed.toIntOrNull(16)
    }

    // Nhận danh sách byte hex cách nhau bởi khoảng trắng, VD: "01 02 0A"
    private fun parseCanData(input: String): ByteArray? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ByteArray(0)
        val tokens = trimmed.split(Regex("\\s+"))
        val bytes = ByteArray(tokens.size)
        for (i in tokens.indices) {
            val value = tokens[i].toIntOrNull(16) ?: return null
            if (value !in 0..0xFF) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    private fun appendLog(direction: String, canId: Int, payload: ByteArray) {
        val time = timeFormat.format(System.currentTimeMillis())
        val idHex = String.format("%X", canId)
        val dataHex = payload.joinToString(" ") { String.format("%02X", it) }
        tvLog.append("[$time] $direction  ID=0x$idHex  DATA=$dataHex\n")
        scrollLog.post { scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    override fun onResume() {
        super.onResume()
        CanConnector.CanServiceConnector.addListener(canListener)
        updateToggleReadingLabel()
    }

    override fun onPause() {
        CanConnector.CanServiceConnector.removeListener(canListener)
        super.onPause()
    }
}
