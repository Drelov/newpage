package app.qiaolian.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteAccessibilityService : AccessibilityService() {
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
        val width = resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
        val height = resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
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
                body.optLong("duration", 280).coerceIn(40, 2000),
            )
            "scroll" -> {
                val x = (body.optDouble("x", 0.5) * width).toFloat()
                val y = (body.optDouble("y", 0.5) * height).toFloat()
                val dy = (body.optDouble("dy", 0.2) * height).toFloat()
                gesture(x, y, x, (y + dy).coerceIn(0f, height - 1f), 220)
            }
            "key" -> key(body.optString("key"))
            "text" -> setText(body.optString("text"))
            else -> "未知操作"
        }
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): String? {
        val maxX = resources.displayMetrics.widthPixels - 1f
        val maxY = resources.displayMetrics.heightPixels - 1f
        val path = Path()
        path.moveTo(x1.coerceIn(0f, maxX), y1.coerceIn(0f, maxY))
        path.lineTo(x2.coerceIn(0f, maxX), y2.coerceIn(0f, maxY))
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var ok = false
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, null)
        if (!dispatched) return "手势被系统拒绝"
        latch.await(2, TimeUnit.SECONDS)
        return if (ok) null else "手势未完成"
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
