package app.zhilian.drive.car

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import app.zhilian.drive.R
import app.zhilian.drive.RoleActivity
import app.zhilian.drive.store.Vault

class CarActivity : Activity(), CarHost.CarUi {
    private lateinit var host: CarHost
    private lateinit var decoder: H264Decoder
    private lateinit var panel: View
    private lateinit var pinRow: View
    private lateinit var actions: View
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private val digits = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_car)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideBars()
        panel = findViewById(R.id.panel)
        pinRow = findViewById(R.id.pinRow)
        actions = findViewById(R.id.actions)
        title = findViewById(R.id.title)
        hint = findViewById(R.id.hint)
        digits.add(findViewById(R.id.d0))
        digits.add(findViewById(R.id.d1))
        digits.add(findViewById(R.id.d2))
        digits.add(findViewById(R.id.d3))
        digits.add(findViewById(R.id.d4))
        digits.add(findViewById(R.id.d5))
        val video = findViewById<SurfaceView>(R.id.video)
        decoder = H264Decoder()
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                decoder.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                decoder.setSurface(null)
            }
        })
        video.setOnTouchListener { _, event ->
            val width = video.width.coerceAtLeast(1)
            val height = video.height.coerceAtLeast(1)
            val action = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> 0
                MotionEvent.ACTION_MOVE -> 1
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 2
                else -> return@setOnTouchListener false
            }
            val x = (event.x / width * 10000).toInt()
            val y = (event.y / height * 10000).toInt()
            host.sendTouch(action, x, y)
            true
        }
        findViewById<View>(R.id.retry).setOnClickListener {
            host.refreshPin()
        }
        findViewById<View>(R.id.cancel).setOnClickListener {
            host.refreshPin()
        }
        findViewById<View>(R.id.switchRole).setOnClickListener {
            Vault(this).role = ""
            startActivity(Intent(this, RoleActivity::class.java))
            finish()
        }
        host = CarHost(this, Vault(this), this, decoder)
        host.start()
        askUsb()
    }

    override fun onDestroy() {
        host.stop()
        super.onDestroy()
    }

    override fun showPin(pin: String) = runOnUi { paintPin(pin, false) }

    override fun showConnecting(phoneName: String) = runOnUi {
        panel.visibility = View.VISIBLE
        pinRow.visibility = View.GONE
        actions.visibility = View.GONE
        title.text = getString(R.string.connecting, phoneName)
        hint.text = ""
    }

    override fun showVideo() = runOnUi {
        panel.visibility = View.GONE
    }

    override fun showFail(reason: String) = runOnUi {
        panel.visibility = View.VISIBLE
        pinRow.visibility = View.GONE
        actions.visibility = View.VISIBLE
        title.text = getString(R.string.fail_title)
        hint.text = reason
    }

    override fun showStatus(text: String) = runOnUi {
        if (panel.visibility == View.VISIBLE && pinRow.visibility == View.VISIBLE) {
            hint.text = text + "\n" + getString(R.string.car_hint)
        }
    }

    private fun paintPin(pin: String, failed: Boolean) {
        panel.visibility = View.VISIBLE
        pinRow.visibility = View.VISIBLE
        actions.visibility = if (failed) View.VISIBLE else View.GONE
        title.text = if (failed) getString(R.string.fail_title) else getString(R.string.app_name)
        hint.text = getString(R.string.car_hint)
        pin.forEachIndexed { index, ch ->
            if (index < digits.size) digits[index].text = ch.toString()
        }
    }

    private fun runOnUi(block: () -> Unit) {
        runOnUiThread(block)
    }

    private fun askUsb() {
        val usb = getSystemService(UsbManager::class.java) ?: return
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 1, Intent(ACTION_USB), flags)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {}
            }, IntentFilter(ACTION_USB), RECEIVER_NOT_EXPORTED)
        }
        for (device in usb.deviceList.values) {
            if (!usb.hasPermission(device)) usb.requestPermission(device, pi)
        }
    }

    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    companion object {
        private const val ACTION_USB = "app.zhilian.drive.USB"
    }
}
