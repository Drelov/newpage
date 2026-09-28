package app.qiaolian.phone

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Same frame layout as the PC package qiaolian/internal/qlp:
 * 'Q''L', version 1, type, uint32 json length, uint32 binary length, json, binary.
 */
object Qlp {
    const val TCP_PORT = 37622
    const val UDP_PORT = 37621
    const val MAX_JSON = 1 shl 20
    const val MAX_BIN = 4 shl 20
    const val CHUNK = 256 * 1024

    const val HELLO: Byte = 1
    const val PAIR: Byte = 2
    const val PAIR_RESULT: Byte = 3
    const val PING: Byte = 4
    const val PONG: Byte = 5
    const val LIST: Byte = 10
    const val LIST_RESULT: Byte = 11
    const val PULL: Byte = 12
    const val PUSH_BEGIN: Byte = 13
    const val CHUNK_FRAME: Byte = 14
    const val TRANSFER: Byte = 15
    const val MKDIR: Byte = 16
    const val DELETE: Byte = 17
    const val RENAME: Byte = 18
    const val OP_RESULT: Byte = 19
    const val SCREEN_START: Byte = 20
    const val SCREEN_STOP: Byte = 21
    const val FRAME: Byte = 22
    const val SCREEN_STATUS: Byte = 23
    const val INPUT: Byte = 30
    const val INPUT_RESULT: Byte = 31
    const val CLIP_SET: Byte = 32
    const val CLIP_GET: Byte = 33
    const val CLIP_DATA: Byte = 34
    const val ERROR: Byte = 40

    data class Frame(val type: Byte, val json: ByteArray, val bin: ByteArray)

    fun encode(type: Byte, json: String?, bin: ByteArray?): ByteArray {
        val js = json?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val body = bin ?: ByteArray(0)
        if (js.size > MAX_JSON || body.size > MAX_BIN) {
            throw IOException("帧过大")
        }
        val out = ByteArray(12 + js.size + body.size)
        out[0] = 'Q'.code.toByte()
        out[1] = 'L'.code.toByte()
        out[2] = 1
        out[3] = type
        putInt(out, 4, js.size)
        putInt(out, 8, body.size)
        js.copyInto(out, 12)
        body.copyInto(out, 12 + js.size)
        return out
    }

    private fun putInt(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v ushr 24).toByte()
        dst[off + 1] = (v ushr 16).toByte()
        dst[off + 2] = (v ushr 8).toByte()
        dst[off + 3] = v.toByte()
    }

    fun intAt(src: ByteArray, off: Int): Int {
        return ((src[off].toInt() and 0xff) shl 24) or
            ((src[off + 1].toInt() and 0xff) shl 16) or
            ((src[off + 2].toInt() and 0xff) shl 8) or
            (src[off + 3].toInt() and 0xff)
    }
}

class FrameReader(private val input: InputStream) {
    private var buf = ByteArray(0)
    private var start = 0
    private var end = 0

    fun next(): Qlp.Frame {
        while (true) {
            val available = end - start
            if (available >= 12) {
                if (buf[start] != 'Q'.code.toByte() || buf[start + 1] != 'L'.code.toByte()) {
                    throw IOException("协议头不匹配")
                }
                if (buf[start + 2] != 1.toByte()) {
                    throw IOException("协议版本不受支持")
                }
                val jsonLen = Qlp.intAt(buf, start + 4)
                val binLen = Qlp.intAt(buf, start + 8)
                if (jsonLen < 0 || binLen < 0 || jsonLen > Qlp.MAX_JSON || binLen > Qlp.MAX_BIN) {
                    throw IOException("帧过大")
                }
                val total = 12 + jsonLen + binLen
                if (available >= total) {
                    val type = buf[start + 3]
                    val json = buf.copyOfRange(start + 12, start + 12 + jsonLen)
                    val bin = buf.copyOfRange(start + 12 + jsonLen, start + total)
                    start += total
                    if (start > 1 shl 20) compact()
                    return Qlp.Frame(type, json, bin)
                }
            }
            ensure(8192)
            val n = input.read(buf, end, buf.size - end)
            if (n < 0) throw EOFException()
            end += n
        }
    }

    private fun ensure(extra: Int) {
        if (buf.size - end >= extra) return
        compact()
        if (buf.size - end < extra) {
            buf = buf.copyOf(maxOf(buf.size * 2, end - start + extra))
        }
    }

    private fun compact() {
        if (start == 0) return
        buf.copyInto(buf, 0, start, end)
        end -= start
        start = 0
    }
}

fun OutputStream.writeFrame(type: Byte, json: String?, bin: ByteArray?) {
    write(Qlp.encode(type, json, bin))
    flush()
}

fun jsonText(bytes: ByteArray): String {
    if (bytes.isEmpty()) return "{}"
    return bytes.toString(Charsets.UTF_8)
}
