package app.qiaolian.phone

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.UUID

object Bridge {
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    var onStatus: ((String) -> Unit)? = null

    @Volatile
    var status: String = "正在启动"
        private set

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("qiaolian", Context.MODE_PRIVATE)
        if (code().isEmpty()) {
            prefs.edit().putString("code", randomCode()).apply()
        }
        if (deviceId().isEmpty()) {
            prefs.edit().putString("id", UUID.randomUUID().toString().replace("-", "")).apply()
        }
    }

    fun code(): String = prefs.getString("code", "") ?: ""

    fun deviceId(): String = prefs.getString("id", "") ?: ""

    fun token(): String = prefs.getString("token", "") ?: ""

    fun newCode() {
        prefs.edit().putString("code", randomCode()).apply()
        setStatus("已更换配对码")
    }

    fun clearPairing() {
        prefs.edit().remove("token").apply()
        setStatus("已取消配对，电脑需要重新输入配对码")
    }

    fun saveToken(token: String) {
        prefs.edit().putString("token", token).apply()
    }

    fun setStatus(text: String) {
        status = text
        val callback = onStatus ?: return
        main.post { callback(text) }
    }

    private fun randomCode(): String = (100000 + (Math.random() * 900000).toInt()).toString()
}
