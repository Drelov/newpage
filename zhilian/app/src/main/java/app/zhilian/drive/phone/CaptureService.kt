package app.zhilian.drive.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import app.zhilian.drive.R
import app.zhilian.drive.wire.Packet
import app.zhilian.drive.wire.PacketWriter
import app.zhilian.drive.wire.VideoFrame
import org.json.JSONObject
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var surface: Surface? = null
    private var writer: PacketWriter? = null
    private var socket: Socket? = null
    private var usb: ParcelFileDescriptor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = projectionData(intent)
        val host = intent?.getStringExtra(EXTRA_HOST)
        val token = intent?.getStringExtra(EXTRA_TOKEN)
        val port = intent?.getIntExtra(EXTRA_PORT, Packet.VIDEO_PORT) ?: Packet.VIDEO_PORT
        usb = if (intent != null && Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_USB, ParcelFileDescriptor::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_USB)
        }
        if (code != 0 && data != null && token != null && (host != null || usb != null)) {
            thread(name = "zl-encode") {
                try {
                    attach(code, data, host, port, token)
                } catch (e: Exception) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        display?.release()
        surface?.release()
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        encoder?.release()
        projection?.stop()
        try {
            writer?.send(Packet.BYE, ByteArray(0))
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        try {
            usb?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun attach(code: Int, data: Intent, host: String?, port: Int, token: String) {
        val out = if (usb != null) {
            PacketWriter(FileOutputStream(usb!!.fileDescriptor))
        } else {
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host, port), 4000)
            socket = sock
            val writer = PacketWriter(sock.getOutputStream())
            val hello = JSONObject().put("token", token).toString().toByteArray()
            writer.send(Packet.HELLO, hello)
            writer
        }
        writer = out
        val mgr = getSystemService(MediaProjectionManager::class.java)
        val mp = mgr.getMediaProjection(code, data)
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(mainLooper))
        val (width, height, dpi) = captureSize()
        val codec = createEncoder(width, height)
        val input = codec.createInputSurface()
        surface = input
        codec.start()
        encoder = codec
        display = mp.createVirtualDisplay(
            "zhilian",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            input,
            null,
            null,
        )
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 10_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) continue
            val buf = codec.getOutputBuffer(index)
            if (buf != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                buf.get(bytes)
                val flags = when {
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> Packet.FLAG_CONFIG
                    info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> Packet.FLAG_KEY
                    else -> 0
                }
                out.send(Packet.VIDEO, VideoFrame.encode(flags, width, height, bytes))
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun createEncoder(width: Int, height: Int): MediaCodec {
        fun format(profile: Boolean): MediaFormat {
            val created = MediaFormat.createVideoFormat(MIME, width, height)
            created.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            created.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            created.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            created.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            created.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            created.setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 30) {
                created.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            if (profile) {
                created.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
            return created
        }
        val first = MediaCodec.createEncoderByType(MIME)
        try {
            first.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return first
        } catch (_: Exception) {
            try {
                first.release()
            } catch (_: Exception) {
            }
        }
        val second = MediaCodec.createEncoderByType(MIME)
        second.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return second
    }

    private fun captureSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val long = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(2)
        val scale = minOf(1f, 1280f / long)
        var w = (metrics.widthPixels * scale).toInt()
        var h = (metrics.heightPixels * scale).toInt()
        if (w % 2 != 0) w -= 1
        if (h % 2 != 0) h -= 1
        return Triple(w.coerceAtLeast(2), h.coerceAtLeast(2), metrics.densityDpi.coerceAtLeast(160))
    }

    private fun startInForeground() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "直联投屏", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在把画面送到车机")
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, notification)
        }
    }

    private fun projectionData(intent: Intent?): Intent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_USB = "usb"
        private const val CHANNEL = "zhilian-capture"
        private const val MIME = "video/avc"

        fun start(context: Context, code: Int, data: Intent, host: String?, port: Int, token: String, usb: ParcelFileDescriptor?) {
            val intent = Intent(context, CaptureService::class.java)
            intent.putExtra(EXTRA_CODE, code)
            intent.putExtra(EXTRA_DATA, data)
            intent.putExtra(EXTRA_HOST, host)
            intent.putExtra(EXTRA_PORT, port)
            intent.putExtra(EXTRA_TOKEN, token)
            if (usb != null) intent.putExtra(EXTRA_USB, usb)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
