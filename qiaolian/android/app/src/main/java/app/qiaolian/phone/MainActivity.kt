package app.qiaolian.phone

import android.Manifest
import android.content.Intent
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

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
    }

    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        val intent = Intent(this, LinkService::class.java).apply {
            action = LinkService.ACTION_PROJECTION
            putExtra(LinkService.EXTRA_CODE, result.resultCode)
            putExtra(LinkService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Bridge.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ContextCompat.startForegroundService(this, Intent(this, LinkService::class.java))
        binding.newCodeButton.setOnClickListener {
            Bridge.newCode()
            refresh()
        }
        binding.clearPairButton.setOnClickListener {
            Bridge.clearPairing()
            refresh()
        }
        binding.storageButton.setOnClickListener { requestStorage() }
        binding.projectionButton.setOnClickListener {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            projection.launch(mgr.createScreenCaptureIntent())
        }
        binding.accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        requestBasics()
    }

    override fun onStart() {
        super.onStart()
        Bridge.onStatus = { refresh() }
        refresh()
    }

    override fun onStop() {
        if (Bridge.onStatus != null) Bridge.onStatus = null
        super.onStop()
    }

    private fun refresh() {
        binding.codeText.text = Bridge.code()
        binding.ipText.text = LinkService.localIp() + "  ·  端口 " + Qlp.TCP_PORT
        binding.statusText.text = Bridge.status
    }

    private fun requestBasics() {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            needed += Manifest.permission.POST_NOTIFICATIONS
            needed += Manifest.permission.READ_MEDIA_IMAGES
            needed += Manifest.permission.READ_MEDIA_VIDEO
            needed += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            return
        }
        requestBasics()
    }
}
