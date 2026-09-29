package app.qiaolian.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class LinkService : Service(), PhoneServer.Host {
    private lateinit var files: FileStore
    private var filesRoot: File? = null

    @Synchronized
    private fun currentFiles(): FileStore {
        val root = chooseRoot()
        if (filesRoot != root || !::files.isInitialized) {
            root.mkdirs()
            filesRoot = root
            files = FileStore(root)
        }
        return files
    }
    private lateinit var server: PhoneServer
    private lateinit var discovery: Discovery
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var storageLabel = "应用目录"

    override fun onCreate() {
        super.onCreate()
        Bridge.init(this)
        createChannel()
        startAsDataSync()
        acquireLocks()
        currentFiles()
        server = PhoneServer(::currentFiles, this)
        FrameHub.attach(server)
        FrameHub.listen()
        server.start()
        discovery = Discovery { Discovery.beacon(helloName(), deviceId()) }
        discovery.start()
        Bridge.setStatus("等待电脑连接\n共享：$storageLabel")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        if (::server.isInitialized) server.shutdown()
        if (::discovery.isInitialized) discovery.shutdown()
        wakeLock?.release()
        wifiLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsDataSync() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qiaolian:link").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "qiaolian").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    private fun chooseRoot(): File {
        val shared = Environment.getExternalStorageDirectory()
        val allowed = if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        if (allowed && shared != null && (shared.canRead() || shared.mkdirs())) {
            storageLabel = "内部存储"
            return shared
        }
        storageLabel = "应用目录（尚未获得全部文件权限）"
        return getExternalFilesDir(null) ?: filesDir
    }

    override fun helloName(): String = Build.MODEL ?: "Android"

    override fun osVersion(): String = Build.VERSION.RELEASE ?: ""

    override fun deviceId(): String = Bridge.deviceId()

    override fun width(): Int = resources.displayMetrics.widthPixels

    override fun height(): Int = resources.displayMetrics.heightPixels

    override fun pairCode(): String = Bridge.code()

    override fun token(): String = Bridge.token()

    override fun saveToken(token: String) {
        Bridge.saveToken(token)
        Bridge.setStatus("电脑已配对")
    }

    override fun startScreen(maxWidth: Int, quality: Int, fps: Int): String? {
        if (!FrameHub.ready) {
            return FrameHub.lastError ?: "请打开手机上的桥联，在系统录屏提示里点「立即开始」"
        }
        FrameHub.configure(maxWidth, quality, fps, true)
        return null
    }

    override fun stopScreen() {
        FrameHub.configure(720, 40, 24, false)
    }

    override fun onInput(body: JSONObject): String? {
        if (body.optString("action") == "text" && body.optString("text").toByteArray().size > 4000) {
            return "文字过长"
        }
        if (body.optString("action") == "toast") {
            val text = body.optString("text")
            android.os.Handler(mainLooper).post {
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            }
            return null
        }
        val control = RemoteAccessibilityService.instance ?: return "请在手机的无障碍设置里打开桥联"
        return control.perform(body)
    }

    override fun clipboard(): String {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this)?.toString() ?: ""
    }

    override fun setClipboard(text: String): String? {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("qiaolian", text))
        return null
    }

    override fun clientChanged(connected: Boolean) {
        Bridge.setStatus(if (connected) "电脑已连上\n共享：$storageLabel" else "等待电脑连接\n共享：$storageLabel")
    }

    companion object {
        const val ACTION_PROJECTION = "app.qiaolian.phone.PROJECTION"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val NOTIF_ID = 42
        private const val CHANNEL = "qiaolian"

        fun localIp(): String {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "未连接 Wi-Fi"
                while (interfaces.hasMoreElements()) {
                    val net = interfaces.nextElement()
                    val addrs = net.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress ?: continue
                        }
                    }
                }
                "未连接 Wi-Fi"
            } catch (_: Exception) {
                "未连接 Wi-Fi"
            }
        }
    }
}
