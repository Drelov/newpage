package app.qiaolian.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
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

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun perform(body: JSONObject): String? {
        val action = body.optString("action")
        val (width, height) = screenSize()
        if (action == "down" || action == "move" || action == "up") {
            val x = (body.optDouble("x") * width).toFloat()
            val y = (body.optDouble("y") * height).toFloat()
            when (action) {
                "down" -> beginDrag(x, y, width, height)
                "move" -> moveDrag(x, y, width, height)
                "up" -> endDrag(x, y, width, height)
            }
            return null
        }
        return when (action) {
            "tap" -> gesture(
                (body.optDouble("x") * width).toFloat(),
                (body.optDouble("y") * height).toFloat(),
                (body.optDouble("x") * width).toFloat(),
                (body.optDouble("y") * height).toFloat(),
                50,
            )
            "swipe" -> gesture(
                (body.optDouble("x") * width).toFloat(),
                (body.optDouble("y") * height).toFloat(),
                (body.optDouble("x2", body.optDouble("x")) * width).toFloat(),
                (body.optDouble("y2", body.optDouble("y")) * height).toFloat(),
                body.optLong("duration", 280).coerceIn(160, 500),
            )
            "scroll" -> {
                val x = (body.optDouble("x", 0.5) * width).toFloat()
                val y = (body.optDouble("y", 0.5) * height).toFloat()
                val dy = (body.optDouble("dy", 0.2) * height).toFloat()
                gesture(x, y, x, (y + dy).coerceIn(0f, height - 1f), 90)
            }
            "key" -> key(body.optString("key"))
            "text" -> setText(body.optString("text"))
            else -> "未知操作"
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
        val path = Path()
        path.moveTo(px, py)
        path.lineTo(px + 1f, py)
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
            if (!step.up && session.queue.isNotEmpty() && !session.queue.last.up) {
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
        val path = Path()
        path.moveTo(session.lastX, session.lastY)
        path.lineTo(step.x, step.y)
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

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): String? {
        val (width, height) = screenSize()
        val maxX = width - 1f
        val maxY = height - 1f
        val path = Path()
        path.moveTo(x1.coerceIn(0f, maxX), y1.coerceIn(0f, maxY))
        path.lineTo(x2.coerceIn(0f, maxX), y2.coerceIn(0f, maxY))
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, null, null)
        return if (dispatched) null else "手势被系统拒绝"
    }

    private fun key(name: String): String? {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "enter" -> return clickFocused()
            "del" -> return deleteChar()
            else -> return "不支持的按键"
        }
        return if (performGlobalAction(action)) null else "按键被系统拒绝"
    }

    private fun setText(text: String): String? {
        val node = focusedEditable() ?: return "没有输入框"
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) null else "无法输入文字"
    }

    private fun deleteChar(): String? {
        val node = focusedEditable() ?: return "没有输入框"
        val current = node.text?.toString() ?: ""
        if (current.isEmpty()) return null
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current.dropLast(1))
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) null else "无法删除"
    }

    private fun clickFocused(): String? {
        val root = rootInActiveWindow ?: return "没有活动窗口"
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (node == null) return "没有可确认的控件"
        return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) null else "无法确认"
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        return findEditable(root, 0)
    }

    private fun findEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 12) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
    }
}
