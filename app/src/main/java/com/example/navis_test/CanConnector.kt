package com.example.navis_test
import android.os.IBinder
import hust.can.ICan

class CanConnector {
    object CanServiceConnector {
        private var canService: ICan? = null

        fun connect(): Boolean {
            try {
                val serviceManager = Class.forName("android.os.ServiceManager")
                val getService = serviceManager.getMethod("getService", String::class.java)
                val binder = getService.invoke(null, "navis.can.ICan/default") as? IBinder
                canService = ICan.Stub.asInterface(binder)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return canService != null
        }

        fun open(ifName: String, bitrate: Int): Boolean {
            return canService?.canOpen(ifName, bitrate) ?: false
        }

        fun write(canId: Int, data: ByteArray, extended: Boolean): Boolean {
            return canService?.canWrite(canId, data, extended) ?: false
        }

        // Trả về Pair<canId thật, payload thật theo đúng dlc>, hoặc null nếu lỗi
        fun read(): Pair<Int, ByteArray>? {
            // Cấp phát sẵn buffer cố định: 4 byte canId + 8 byte data tối đa = 12 byte
            val rawBuffer = ByteArray(12)
            val dlc = canService?.canRead(rawBuffer) ?: -1

            if (dlc < 0) return null

            val canId = (rawBuffer[0].toInt() and 0xFF) or
                    ((rawBuffer[1].toInt() and 0xFF) shl 8) or
                    ((rawBuffer[2].toInt() and 0xFF) shl 16) or
                    ((rawBuffer[3].toInt() and 0xFF) shl 24)

            val payload = rawBuffer.copyOfRange(4, 4 + dlc)
            return Pair(canId, payload)
        }

        fun close() {
            canService?.canClose()
        }
    }
}