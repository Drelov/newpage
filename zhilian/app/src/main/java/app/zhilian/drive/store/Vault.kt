package app.zhilian.drive.store

import android.content.Context
import org.json.JSONObject
import java.util.UUID

class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("zhilian", Context.MODE_PRIVATE)

    var role: String
        get() = prefs.getString("role", "") ?: ""
        set(value) = prefs.edit().putString("role", value).apply()

    val deviceId: String
        get() {
            val saved = prefs.getString("deviceId", null)
            if (saved != null) return saved
            val created = UUID.randomUUID().toString()
            prefs.edit().putString("deviceId", created).apply()
            return created
        }

    fun rememberPhone(phoneId: String, token: String) {
        prefs.edit().putString("phone.$phoneId", token).apply()
    }

    fun tokenFor(phoneId: String): String? = prefs.getString("phone.$phoneId", null)

    fun saveCar(carId: String, token: String, host: String) {
        val json = JSONObject()
        json.put("carId", carId)
        json.put("token", token)
        json.put("host", host)
        prefs.edit().putString("car", json.toString()).apply()
    }

    fun savedCar(): SavedCar? {
        val raw = prefs.getString("car", null) ?: return null
        val json = JSONObject(raw)
        return SavedCar(
            json.getString("carId"),
            json.getString("token"),
            json.optString("host"),
        )
    }

    fun forgetCar() {
        prefs.edit().remove("car").apply()
    }

    data class SavedCar(val carId: String, val token: String, val host: String)
}
