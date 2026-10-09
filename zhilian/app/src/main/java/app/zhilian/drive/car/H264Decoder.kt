package app.zhilian.drive.car

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import app.zhilian.drive.wire.AvcConfig
import java.nio.ByteBuffer

class H264Decoder {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null

    fun setSurface(next: Surface?) {
        surface = next
        if (next == null) release()
    }

    fun configured(): Boolean = codec != null

    fun configure(width: Int, height: Int, csd: ByteArray) {
        val target = surface ?: return
        val (sps, pps) = AvcConfig.split(csd)
        if (sps.isEmpty() || pps.isEmpty() || width <= 0 || height <= 0) return
        release()
        val format = MediaFormat.createVideoFormat(MIME, width, height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1_500_000)
        if (Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val decoder = MediaCodec.createDecoderByType(MIME)
        decoder.configure(format, target, null, 0)
        decoder.start()
        codec = decoder
    }

    fun frame(data: ByteArray, key: Boolean) {
        val decoder = codec ?: return
        val wait = if (key) 30_000L else 0L
        val index = decoder.dequeueInputBuffer(wait)
        if (index < 0) return
        val buf = decoder.getInputBuffer(index) ?: return
        buf.clear()
        if (data.size > buf.capacity()) return
        buf.put(data)
        decoder.queueInputBuffer(index, 0, data.size, System.nanoTime() / 1000, 0)
        drain(decoder)
    }

    fun release() {
        val decoder = codec
        codec = null
        try {
            decoder?.stop()
        } catch (_: Exception) {
        }
        try {
            decoder?.release()
        } catch (_: Exception) {
        }
    }

    private fun drain(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val out = decoder.dequeueOutputBuffer(info, 0)
            if (out >= 0) {
                decoder.releaseOutputBuffer(out, true)
            } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue
            } else {
                break
            }
        }
    }

    companion object {
        private const val MIME = "video/avc"
    }
}
