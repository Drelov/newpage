package app.zhilian.drive.phone

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.EditText
import android.widget.TextView
import app.zhilian.drive.R
import app.zhilian.drive.RoleActivity
import app.zhilian.drive.store.Vault
import app.zhilian.drive.wire.Packet
import java.util.UUID

class PhoneActivity : Activity() {
    private lateinit var vault: Vault
    private lateinit var status: TextView
    private lateinit var pin: EditText
    private lateinit var hostField: EditText
    private lateinit var foundView: TextView
    private val link = PhoneLink()
    private val cars = HashMap<String, PhoneLink.FoundCar>()
    private var linking = false
    private var projecting = false
    private var pendingHost: String? = null
    private var pendingPort = Packet.VIDEO_PORT
    private var pendingToken: String? = null
    private var pendingUsb: ParcelFileDescriptor? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var scanner: BluetoothLeScanner? = null
    private var direct: WifiP2pManager.Channel? = null
    private var lastBle = 0L
    private val noopDirect = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {}
        override fun onFailure(reason: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone)
        vault = Vault(this)
        status = findViewById(R.id.status)
        pin = findViewById(R.id.pin)
        hostField = findViewById(R.id.host)
        foundView = findViewById(R.id.found)
        findViewById<View>(R.id.connect).setOnClickListener { manualConnect() }
        findViewById<View>(R.id.switchRole).setOnClickListener {
            vault.role = ""
            startActivity(Intent(this, RoleActivity::class.java))
            finish()
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        multicast = wifi?.createMulticastLock("zhilian")?.also {
            it.setReferenceCounted(false)
            it.acquire()
        }
        link.startSession(
            vault.deviceId,
            android.os.Build.MODEL ?: "手机",
            onReady = { issued, carId, pipe ->
                vault.saveCar(carId, issued, cars.values.firstOrNull()?.host ?: "usb")
                runOnUiThread {
                    if (projecting) return@runOnUiThread
                    projecting = true
                    status.text = getString(R.string.projecting)
                    beginProjection(null, Packet.VIDEO_PORT, issued, pipe)
                }
            },
            onTouch = { action, x, y -> RemoteAccessibilityService.instance?.touch(action, x, y) },
            onClosed = { reason ->
                linking = false
                runOnUiThread {
                    if (!projecting) status.text = friendly(reason)
                }
            },
            onCar = { car -> onFound(car) },
        )
        startDirect()
        startBle()
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            openAccessory(intent)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) openAccessory(intent)
    }

    override fun onDestroy() {
        link.stop()
        try {
            multicast?.release()
        } catch (_: Exception) {
        }
        try {
            scanner?.stopScan(bleCallback)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun onFound(car: PhoneLink.FoundCar) {
        runOnUiThread {
            cars[car.id] = car
            foundView.text = "已发现 ${car.name}  ${car.host}"
            val saved = vault.savedCar()
            if (!linking && saved != null && saved.carId == car.id) {
                linking = true
                status.text = "正在自动连接"
                connect(car, "", saved.token)
            }
        }
    }

    private fun manualConnect() {
        val typed = hostField.text?.toString()?.trim().orEmpty()
        val known = cars.values.firstOrNull()
        val target = when {
            typed.isNotEmpty() -> PhoneLink.FoundCar(vault.savedCar()?.carId ?: "manual", "车机", typed, Packet.CONTROL_PORT)
            known != null -> known
            vault.savedCar()?.host?.isNotEmpty() == true -> {
                val saved = vault.savedCar()!!
                PhoneLink.FoundCar(saved.carId, "车机", saved.host, Packet.CONTROL_PORT)
            }
            else -> null
        }
        if (target == null) {
            status.text = "还没有发现车机。请把两端连到同一 Wi-Fi，或插上数据线。"
            return
        }
        val saved = vault.savedCar()
        val token = if (saved != null && (saved.carId == target.id || typed.isNotEmpty())) saved.token else ""
        linking = true
        projecting = false
        status.text = "正在连接。模拟器会改由车机来连手机。"
        link.connectWifi(target, pin.text?.toString()?.trim().orEmpty(), token)
    }

    private fun connect(car: PhoneLink.FoundCar, code: String, token: String) {
        link.connectWifi(car, code, token)
    }

    private fun friendly(reason: String): String {
        if (reason.contains("ECONNREFUSED") || reason.contains("Connection refused")) {
            return "手机连不进模拟器。请把模拟器里的直联也换成新版本，再点一次连接。"
        }
        return reason
    }

    private fun openAccessory(intent: Intent) {
        val accessory = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        } ?: return
        val usb = getSystemService(UsbManager::class.java)
        val fd = usb.openAccessory(accessory) ?: return
        val saved = vault.savedCar()
        linking = true
        status.text = "USB 已连接"
        link.connectUsb(
            fd,
            pin.text?.toString()?.trim().orEmpty(),
            saved?.token ?: "",
        )
    }

    private fun beginProjection(host: String?, port: Int, token: String, usb: ParcelFileDescriptor?) {
        pendingHost = host
        pendingPort = port
        pendingToken = token
        pendingUsb = usb
        val mgr = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("projection consent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        val token = pendingToken
        if (resultCode != RESULT_OK || data == null || token == null) {
            status.text = "没有允许录屏"
            linking = false
            projecting = false
            return
        }
        CaptureService.start(this, resultCode, data, pendingHost, pendingPort, token, pendingUsb)
        status.text = getString(R.string.projecting)
    }

    private fun startDirect() {
        val mgr = getSystemService(WifiP2pManager::class.java) ?: return
        val channel = mgr.initialize(this, mainLooper, null) ?: return
        direct = channel
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) return
                mgr.requestPeers(channel) { peers ->
                    val match = peers.deviceList.firstOrNull { it.deviceName?.startsWith("直联") == true }
                    if (match != null) mgr.connect(channel, CarHostBridge.config(match), noopDirect)
                }
            }
        }
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        try {
            mgr.discoverPeers(channel, noopDirect)
        } catch (_: SecurityException) {
        }
    }

    private fun startBle() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            val perms = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            requestPermissions(perms, 2)
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        scanner = adapter.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner?.startScan(filters, settings, bleCallback)
        } catch (_: SecurityException) {
        }
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val saved = vault.savedCar() ?: return
            val host = saved.host
            val now = android.os.SystemClock.elapsedRealtime()
            if (host.isBlank() || host == "usb" || linking || now - lastBle < 8000) return
            lastBle = now
            linking = true
            runOnUiThread { status.text = "蓝牙发现已配对车机，正在连接" }
            connect(PhoneLink.FoundCar(saved.carId, "直联", host, Packet.CONTROL_PORT), "", saved.token)
        }
    }

    companion object {
        private const val REQUEST_CAPTURE = 11
        val SERVICE: UUID = UUID.fromString("7d1f3a20-6b1e-4b1a-9c1a-7e0a6d5c4b31")
    }
}

internal object CarHostBridge {
    fun config(device: WifiP2pDevice) = app.zhilian.drive.car.CarHost.connectConfig(device)
}
