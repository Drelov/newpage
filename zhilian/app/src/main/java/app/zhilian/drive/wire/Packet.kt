package app.zhilian.drive.wire

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object Packet {
    const val HELLO = 1
    const val WELCOME = 2
    const val REJECT = 3
    const val VIDEO = 4
    const val TOUCH = 5
    const val PING = 6
    const val PONG = 7
    const val BYE = 8
    const val AUDIO = 9

    const val FLAG_CONFIG = 1
    const val FLAG_KEY = 2

    const val CONTROL_PORT = 39220
    const val VIDEO_PORT = 39221
    const val BEACON_PORT = 39223

    fun encode(type: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(5 + payload.size)
        out[0] = type.toByte()
        val n = payload.size
        out[1] = (n ushr 24).toByte()
        out[2] = (n ushr 16).toByte()
        out[3] = (n ushr 8).toByte()
        out[4] = n.toByte()
        if (n > 0) payload.copyInto(out, 5)
        return out
    }
}

class PacketWriter(private val output: OutputStream) {
    private val lock = Any()

    fun send(type: Int, payload: ByteArray) {
        val packet = Packet.encode(type, payload)
        synchronized(lock) {
            output.write(packet)
            output.flush()
        }
    }
}

class PacketReader(private val input: InputStream) {
    fun recv(): Message {
        val head = ByteArray(5)
        readFully(head)
        val type = head[0].toInt() and 0xff
        val n = ((head[1].toInt() and 0xff) shl 24) or
            ((head[2].toInt() and 0xff) shl 16) or
            ((head[3].toInt() and 0xff) shl 8) or
            (head[4].toInt() and 0xff)
        if (n < 0 || n > 4_000_000) throw IOException("bad packet $n")
        val body = ByteArray(n)
        if (n > 0) readFully(body)
        return Message(type, body)
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }
}

data class Message(val type: Int, val payload: ByteArray)

object Touch {
    const val DOWN = 0
    const val MOVE = 1
    const val UP = 2

    fun encode(action: Int, x: Int, y: Int): ByteArray {
        val xx = x.coerceIn(0, 10000)
        val yy = y.coerceIn(0, 10000)
        return byteArrayOf(
            action.toByte(),
            (xx ushr 8).toByte(),
            xx.toByte(),
            (yy ushr 8).toByte(),
            yy.toByte(),
        )
    }

    fun decode(payload: ByteArray): Triple<Int, Int, Int> {
        if (payload.size < 5) return Triple(UP, 0, 0)
        val action = payload[0].toInt() and 0xff
        val x = ((payload[1].toInt() and 0xff) shl 8) or (payload[2].toInt() and 0xff)
        val y = ((payload[3].toInt() and 0xff) shl 8) or (payload[4].toInt() and 0xff)
        return Triple(action, x, y)
    }
}

object VideoFrame {
    fun encode(flags: Int, width: Int, height: Int, data: ByteArray): ByteArray {
        val out = ByteArray(5 + data.size)
        out[0] = flags.toByte()
        out[1] = (width ushr 8).toByte()
        out[2] = width.toByte()
        out[3] = (height ushr 8).toByte()
        out[4] = height.toByte()
        data.copyInto(out, 5)
        return out
    }

    fun decode(payload: ByteArray): Frame {
        if (payload.size < 5) return Frame(0, 0, 0, ByteArray(0))
        val flags = payload[0].toInt() and 0xff
        val width = ((payload[1].toInt() and 0xff) shl 8) or (payload[2].toInt() and 0xff)
        val height = ((payload[3].toInt() and 0xff) shl 8) or (payload[4].toInt() and 0xff)
        return Frame(flags, width, height, payload.copyOfRange(5, payload.size))
    }

    data class Frame(val flags: Int, val width: Int, val height: Int, val data: ByteArray)
}
