package app.qiaolian.phone

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Main-process side of the capture socket. Screen capture runs in another
 * process so an enabled accessibility service does not blank the mirror.
 */
object FrameHub {
    const val NAME = "qiaolian.frames"

    @Volatile
    var ready: Boolean = false

    @Volatile
    var lastError: String? = null

    @Volatile
    var streaming: Boolean = false

    private var phone: PhoneServer? = null
    private val writeLock = Any()
    private var client: LocalSocket? = null
    private var pending: ByteArray? = null

    fun attach(server: PhoneServer) {
        phone = server
    }

    fun listen() {
        thread(name = "qiaolian-hub", isDaemon = true) {
            val server = try {
                LocalServerSocket(NAME)
            } catch (_: IOException) {
                return@thread
            }
            while (true) {
                val sock = try {
                    server.accept()
                } catch (_: IOException) {
                    return@thread
                }
                synchronized(writeLock) {
                    client?.close()
                    client = sock
                    pending?.let { packet ->
                        try {
                            sock.outputStream.write(packet)
                            sock.outputStream.flush()
                        } catch (_: IOException) {
                        }
                    }
                }
                thread(name = "qiaolian-frames", isDaemon = true) { read(sock) }
            }
        }
    }

    fun configure(width: Int, quality: Int, fps: Int, on: Boolean) {
        streaming = on
        val packet = java.io.ByteArrayOutputStream()
        val out = DataOutputStream(packet)
        out.writeByte('C'.code)
        out.writeInt(width)
        out.writeInt(quality)
        out.writeInt(fps)
        out.writeByte(if (on) 1 else 0)
        out.flush()
        val bytes = packet.toByteArray()
        synchronized(writeLock) {
            val sock = client
            if (sock == null) {
                pending = bytes
                return
            }
            try {
                sock.outputStream.write(bytes)
                sock.outputStream.flush()
                pending = null
            } catch (_: IOException) {
                pending = bytes
                ready = false
            }
        }
    }

    private fun read(sock: LocalSocket) {
        val input = DataInputStream(sock.inputStream)
        try {
            while (true) {
                when (input.readByte().toInt().toChar()) {
                    'S' -> {
                        val ok = input.readByte().toInt() == 1
                        val msg = readText(input)
                        ready = ok
                        lastError = if (ok) null else msg
                        Bridge.setStatus(if (ok) "屏幕已就绪，电脑上直接连接即可" else msg)
                    }
                    'F' -> {
                        val w = input.readInt()
                        val h = input.readInt()
                        input.readLong()
                        val n = input.readInt()
                        if (n <= 0 || n > Qlp.MAX_BIN) {
                            throw IOException("画面数据异常")
                        }
                        val jpeg = ByteArray(n)
                        input.readFully(jpeg)
                        if (streaming) {
                            phone?.sendFrame(jpeg, w, h)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            ready = false
            synchronized(writeLock) {
                if (client == sock) client = null
            }
        }
    }

    private fun readText(input: DataInputStream): String {
        val n = input.readInt()
        if (n <= 0) return ""
        if (n > 4096) throw IOException("状态过长")
        val buf = ByteArray(n)
        input.readFully(buf)
        return String(buf, Charsets.UTF_8)
    }
}
