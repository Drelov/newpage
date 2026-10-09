package app.zhilian.drive.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.ArrayDeque

class RemoteAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var drag: Drag? = null

    private class Step(val x: Float, val y: Float, val up: Boolean)

    private class Drag {
        var lastX = 0f
        var lastY = 0f
        var stroke: GestureDescription.StrokeDescription? = null
        var busy = false
        val queue = ArrayDeque<Step>()
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun touch(action: Int, xNorm: Int, yNorm: Int) {
        val (width, height) = screenSize()
        val x = xNorm / 10000f * width
        val y = yNorm / 10000f * height
        when (action) {
            0 -> beginDrag(x, y, width, height)
            1 -> moveDrag(x, y, width, height)
            else -> endDrag(x, y, width, height)
        }
    }

    private fun screenSize(): Pair<Float, Float> {
        val wm = getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION")
        val point = android.graphics.Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(point)
        return point.x.toFloat().coerceAtLeast(1f) to point.y.toFloat().coerceAtLeast(1f)
    }

    private fun inside(x: Float, y: Float, width: Float, height: Float): Pair<Float, Float> {
        val inset = 20f
        return x.coerceIn(inset, width - inset) to y.coerceIn(inset, height - inset)
    }

    private fun beginDrag(x: Float, y: Float, width: Float, height: Float) {
        val (px, py) = inside(x, y, width, height)
        val session = Drag()
        session.lastX = px
        session.lastY = py
        drag = session
        val path = Path().apply {
            moveTo(px, py)
            lineTo(px + 1f, py)
        }
        session.lastX = px + 1f
        val stroke = GestureDescription.StrokeDescription(path, 0, 20, true)
        session.stroke = stroke
        dispatchStroke(session, stroke, false)
    }

    private fun moveDrag(x: Float, y: Float, width: Float, height: Float) {
        val session = drag ?: return
        val (px, py) = inside(x, y, width, height)
        enqueue(session, Step(px, py, false))
    }

    private fun endDrag(x: Float, y: Float, width: Float, height: Float) {
        val session = drag ?: return
        val (px, py) = inside(x, y, width, height)
        enqueue(session, Step(px, py, true))
    }

    private fun enqueue(session: Drag, step: Step) {
        synchronized(session.queue) {
            if (!step.up && session.queue.isNotEmpty() && !session.queue.last().up) {
                session.queue.removeLast()
            }
            session.queue.addLast(step)
        }
        mainHandler.post { pump(session) }
    }

    private fun pump(session: Drag) {
        if (drag !== session || session.busy || session.stroke == null) return
        val step = synchronized(session.queue) {
            if (session.queue.isEmpty()) null else session.queue.removeFirst()
        } ?: return
        val path = Path().apply {
            moveTo(session.lastX, session.lastY)
            lineTo(step.x, step.y)
        }
        val next = session.stroke!!.continueStroke(path, 0, 24, !step.up)
        session.stroke = next
        session.lastX = step.x
        session.lastY = step.y
        dispatchStroke(session, next, step.up)
    }

    private fun dispatchStroke(session: Drag, stroke: GestureDescription.StrokeDescription, finished: Boolean) {
        session.busy = true
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                session.busy = false
                if (finished) {
                    if (drag === session) drag = null
                } else {
                    pump(session)
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                session.busy = false
                if (drag === session) drag = null
            }
        }, mainHandler)
        if (!ok) {
            session.busy = false
            if (drag === session) drag = null
        }
    }

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
    }
}
