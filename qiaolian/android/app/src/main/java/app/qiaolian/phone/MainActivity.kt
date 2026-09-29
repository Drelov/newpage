package app.qiaolian.phone

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.qiaolian.phone.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var waitingFiles = false
    private var waitingAccessibility = false
    private var projectionAsked = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        continueSetup()
    }

    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            Bridge.setStatus("还没有允许录屏。再点一次「一键连接」，在系统提示里点「立即开始」。")
            refresh()
            return@registerForActivityResult
        }
        val intent = Intent(this, CaptureService::class.java).apply {
            putExtra(LinkService.EXTRA_CODE, result.resultCode)
            putExtra(LinkService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
        Bridge.setStatus("正在准备画面，准备好后电脑就可以连接")
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Bridge.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ContextCompat.startForegroundService(this, Intent(this, LinkService::class.java))
        binding.readyButton.setOnClickListener { beginSetup() }
        binding.newCodeButton.setOnClickListener {
            Bridge.newCode()
            refresh()
        }
        binding.clearPairButton.setOnClickListener {
            Bridge.clearPairing()
            refresh()
        }
    }

    override fun onStart() {
        super.onStart()
        Bridge.onStatus = { refresh() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (waitingFiles || waitingAccessibility || accessibilityOn()) {
            continueSetup()
        }
    }

    override fun onStop() {
        Bridge.onStatus = null
        super.onStop()
    }

    private fun beginSetup() {
        projectionAsked = false
        waitingFiles = false
        waitingAccessibility = false
        continueSetup()
    }

    private fun continueSetup() {
        if (FrameHub.ready) {
            Bridge.setStatus("屏幕已就绪，电脑上直接连接即可")
            refresh()
            return
        }
        val missing = missingRuntimePermissions()
        if (missing.isNotEmpty()) {
            permissions.launch(missing.toTypedArray())
            return
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            if (!waitingFiles) {
                waitingFiles = true
                Bridge.setStatus("请允许访问全部文件，返回后会自动继续")
                openAllFilesSettings()
            }
            refresh()
            return
        }
        waitingFiles = false
        if (!accessibilityOn()) {
            if (!waitingAccessibility) {
                waitingAccessibility = true
                Bridge.setStatus("请在无障碍列表里打开桥联，返回后会自动弹出录屏确认")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            refresh()
            return
        }
        waitingAccessibility = false
        if (!projectionAsked) {
            projectionAsked = true
            val mgr = getSystemService(MediaProjectionManager::class.java)
            projection.launch(mgr.createScreenCaptureIntent())
        }
        refresh()
    }

    private fun refresh() {
        binding.codeText.text = Bridge.code()
        binding.ipText.text = LinkService.localIp() + "  ·  端口 " + Qlp.TCP_PORT
        binding.statusText.text = Bridge.status
        binding.readyButton.text = if (FrameHub.ready) "已就绪，电脑直接连接" else "一键连接"
    }

    private fun missingRuntimePermissions(): List<String> {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            needed += Manifest.permission.POST_NOTIFICATIONS
            needed += Manifest.permission.READ_MEDIA_IMAGES
            needed += Manifest.permission.READ_MEDIA_VIDEO
            needed += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun accessibilityOn(): Boolean {
        val expected = ComponentName(this, RemoteAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun openAllFilesSettings() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}
