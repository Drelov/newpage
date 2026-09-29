package app.qiaolian.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs in its own process. Creating the virtual display here still works after
 * the accessibility service in the main process is already on.
 */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var socketOut: DataOutputStream? = null
    private val writeLock = Any()
    private var quality = 40
    private var intervalMs = 42L
    private var maxWidth = 720
    private var captureW = 0
    private var captureH = 0
    private var lastSent = 0L
    private val busy = AtomicBoolean(false)
    private val streaming = AtomicBoolean(false)
    private var blackFrames = 0
    private var bitmap: Bitmap? = null
    private var queuedStatus: Pair<Boolean, String>? = null
    private val jpegBuffer = ByteArrayOutputStream(256 * 1024)

    override fun onCreate() {
        super.onCreate()
        connectHub()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val code = intent?.getIntExtra(LinkService.EXTRA_CODE, 0) ?: 0
        val data = if (intent != null && Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(LinkService.EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(LinkService.EXTRA_DATA)
        }
        if (code != 0 && data != null) {
            val err = attach(code, data)
            sendStatus(err == null, err ?: "")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        streaming.set(false)
        display?.release()
        reader?.close()
        projection?.stop()
        bitmap?.recycle()
        thread?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "桥联投屏", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在准备屏幕画面")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun attach(resultCode: Int, data: Intent): String? {
        return try {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            projection?.stop()
            val created = mgr.getMediaProjection(resultCode, data) ?: return "系统没有返回投屏许可"
            created.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    projection = null
                    display?.release()
                    display = null
                    sendStatus(false, "系统停止了投屏，请重新打开桥联")
                }
            }, handler())
            projection = created
            openDisplay(maxWidth)
            null
        } catch (e: Exception) {
            Log.e(TAG, "projection", e)
            "投屏许可失败：" + (e.message ?: "未知错误")
        }
    }

    private fun openDisplay(widthCap: Int) {
        val proj = projection ?: return
        display?.release()
        reader?.close()
        val (realW, realH) = realSize()
        var w = realW
        var h = realH
        val cap = widthCap.coerceIn(320, 1280)
        if (w > cap) {
            h = (h.toLong() * cap / w).toInt()
            w = cap
        }
        if (w % 2 != 0) w -= 1
        if (h % 2 != 0) h -= 1
        if (w < 2 || h < 2) return
        captureW = w
        captureH = h
        val images = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader = images
        images.setOnImageAvailableListener({ onImage(it) }, handler())
        val dpi = dpi(w, realW)
        display = proj.createVirtualDisplay(
            "qiaolian",
            w,
            h,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            images.surface,
            null,
            handler(),
        )
        handler().postDelayed({
            try {
                display?.resize(w, h, dpi)
            } catch (_: Exception) {
            }
        }, 250)
    }

    private fun onImage(images: ImageReader) {
        val image = try {
            images.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return
        try {
            if (!streaming.get()) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastSent < intervalMs) return
            if (!busy.compareAndSet(false, true)) return
            val jpeg = toJpeg(image) ?: return
            lastSent = now
            sendFrame(jpeg, image.width, image.height)
        } catch (e: Exception) {
            Log.w(TAG, "frame", e)
        } finally {
            image.close()
            busy.set(false)
        }
    }

    private fun toJpeg(image: Image): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        if (pixelStride <= 0) return null
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        var full = bitmap
        if (full == null || full.width != paddedWidth || full.height != height) {
            full?.recycle()
            full = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            bitmap = full
        }
        plane.buffer.rewind()
        full.copyPixelsFromBuffer(plane.buffer)
        if (isMostlyBlack(full, width, height)) {
            blackFrames++
            if (blackFrames == 12) {
                try {
                    display?.resize(captureW, captureH, dpi(captureW, realSize().first))
                } catch (_: Exception) {
                }
            }
            if (blackFrames < 45) return null
        }
        blackFrames = 0
        val cropped = if (paddedWidth == width) full else Bitmap.createBitmap(full, 0, 0, width, height)
        jpegBuffer.reset()
        val ok = cropped.compress(Bitmap.CompressFormat.JPEG, quality, jpegBuffer)
        if (cropped !== full) cropped.recycle()
        if (!ok) return null
        return jpegBuffer.toByteArray()
    }

    private fun isMostlyBlack(bitmap: Bitmap, width: Int, height: Int): Boolean {
        val points = intArrayOf(
            width / 2, 8,
            8, height / 2,
            width - 8, height / 2,
            width / 2, height / 2,
            width / 2, height - 8,
        )
        var dark = 0
        var i = 0
        while (i < points.size) {
            val x = points[i].coerceIn(0, width - 1)
            val y = points[i + 1].coerceIn(0, height - 1)
            val color = bitmap.getPixel(x, y)
            val r = (color shr 16) and 0xff
            val g = (color shr 8) and 0xff
            val b = color and 0xff
            if (r + g + b < 24) dark++
            i += 2
        }
        return dark == points.size / 2
    }

    private fun connectHub() {
        thread(name = "qiaolian-capture-link", isDaemon = true) {
            var delay = 150L
            val sock = LocalSocket()
            while (true) {
                try {
                    sock.connect(LocalSocketAddress(FrameHub.NAME))
                    break
                } catch (_: Exception) {
                    try {
                        Thread.sleep(delay)
                    } catch (_: InterruptedException) {
                        return@thread
                    }
                    delay = (delay * 2).coerceAtMost(1000)
                }
            }
            synchronized(writeLock) {
                socketOut = DataOutputStream(sock.outputStream)
                queuedStatus?.let { (ok, message) ->
                    writeStatus(socketOut!!, ok, message)
                }
            }
            val input = DataInputStream(sock.inputStream)
            try {
                while (true) {
                    if (input.readByte().toInt().toChar() != 'C') continue
                    val width = input.readInt()
                    val q = input.readInt()
                    val fps = input.readInt()
                    val on = input.readByte().toInt() == 1
                    quality = q.coerceIn(25, 80)
                    intervalMs = 1000L / fps.coerceIn(5, 30)
                    streaming.set(on)
                    if (width != maxWidth && projection != null) {
                        maxWidth = width
                        handler().post { openDisplay(width) }
                    } else {
                        maxWidth = width
                    }
                }
            } catch (_: Exception) {
                streaming.set(false)
            }
        }
    }

    private fun sendStatus(ok: Boolean, message: String) {
        synchronized(writeLock) {
            val out = socketOut
            if (out == null) {
                queuedStatus = ok to message
                return
            }
            writeStatus(out, ok, message)
            queuedStatus = null
        }
    }

    private fun writeStatus(out: DataOutputStream, ok: Boolean, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        out.writeByte('S'.code)
        out.writeByte(if (ok) 1 else 0)
        out.writeInt(bytes.size)
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }

    private fun sendFrame(jpeg: ByteArray, w: Int, h: Int) {
        synchronized(writeLock) {
            val out = socketOut ?: return
            out.writeByte('F'.code)
            out.writeInt(w)
            out.writeInt(h)
            out.writeLong(System.currentTimeMillis())
            out.writeInt(jpeg.size)
            out.write(jpeg)
            out.flush()
        }
    }

    private fun realSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            point.x to point.y
        }
    }

    private fun dpi(width: Int, realWidth: Int): Int {
        val base = resources.displayMetrics.densityDpi
        if (realWidth <= 0) return base
        return (base.toLong() * width / realWidth).toInt().coerceAtLeast(120)
    }

    private fun handler(): Handler {
        val existing = handler
        if (existing != null) return existing
        val worker = HandlerThread("qiaolian-capture")
        worker.start()
        thread = worker
        val created = Handler(worker.looper)
        handler = created
        return created
    }

    companion object {
        private const val TAG = "QiaoLianCapture"
        private const val CHANNEL = "qiaolian.capture"
        private const val NOTIF_ID = 43
    }
}
