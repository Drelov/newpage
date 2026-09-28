package app.qiaolian.phone

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ScreenGrabber(
    private val service: LinkService,
    private val onFrame: (ByteArray, Int, Int) -> Unit,
) {
    private var projection: MediaProjection? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var quality = 70
    private var intervalMs = 125L
    private var lastSent = 0L
    private val busy = AtomicBoolean(false)

    fun attach(resultCode: Int, data: Intent): String? {
        return try {
            service.promoteProjectionForeground()
            val mgr = service.getSystemService(MediaProjectionManager::class.java)
            projection?.stop()
            val created = mgr.getMediaProjection(resultCode, data) ?: return "系统没有返回投屏许可"
            created.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    projection = null
                    stopMirror()
                }
            }, handler())
            projection = created
            null
        } catch (e: Exception) {
            Log.e(TAG, "projection", e)
            "投屏许可失败：" + (e.message ?: "未知错误")
        }
    }

    fun start(maxWidth: Int, quality: Int, fps: Int): String? {
        val proj = projection ?: return "请先在手机上点「允许投屏」"
        stopMirror()
        this.quality = quality.coerceIn(30, 95)
        intervalMs = 1000L / fps.coerceIn(1, 20)
        val (realW, realH) = realSize()
        var w = realW
        var h = realH
        val cap = maxWidth.coerceIn(320, 1920)
        if (w > cap) {
            h = (h.toLong() * cap / w).toInt()
            w = cap
        }
        if (w % 2 != 0) w -= 1
        if (h % 2 != 0) h -= 1
        if (w < 2 || h < 2) return "屏幕尺寸无效"
        val images = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = images
        images.setOnImageAvailableListener({ onImage(it) }, handler())
        display = proj.createVirtualDisplay(
            "qiaolian",
            w,
            h,
            dpi(w, realW),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            images.surface,
            null,
            handler(),
        )
        return null
    }

    fun stopMirror() {
        display?.release()
        display = null
        reader?.close()
        reader = null
        busy.set(false)
    }

    fun release() {
        stopMirror()
        projection?.stop()
        projection = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    fun hasProjection(): Boolean = projection != null

    private fun onImage(images: ImageReader) {
        val image = try {
            images.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSent < intervalMs) return
            if (!busy.compareAndSet(false, true)) return
            lastSent = now
            val jpeg = toJpeg(image)
            if (jpeg != null) onFrame(jpeg, image.width, image.height)
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
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        bitmap.copyPixelsFromBuffer(plane.buffer)
        val cropped = if (paddedWidth == width) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)
        val out = ByteArrayOutputStream()
        val ok = cropped.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (cropped !== bitmap) cropped.recycle()
        bitmap.recycle()
        if (!ok) return null
        return out.toByteArray()
    }

    private fun realSize(): Pair<Int, Int> {
        val wm = service.getSystemService(WindowManager::class.java)
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
        val base = service.resources.displayMetrics.densityDpi
        if (realWidth <= 0) return base
        return (base.toLong() * width / realWidth).toInt().coerceAtLeast(120)
    }

    private fun handler(): Handler {
        val existing = handler
        if (existing != null) return existing
        val worker = HandlerThread("qiaolian-screen")
        worker.start()
        thread = worker
        val created = Handler(worker.looper)
        handler = created
        return created
    }

    companion object {
        private const val TAG = "QiaoLian"
    }
}
