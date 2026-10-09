package app.zhilian.drive.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.hardware.usb.UsbConstants
import android.os.ParcelUuid
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import app.zhilian.drive.store.Vault
import app.zhilian.drive.wire.Packet
import app.zhilian.drive.wire.PacketReader
import app.zhilian.drive.wire.PacketWriter
import app.zhilian.drive.wire.VideoFrame
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class CarHost(
    private val context: Context,
    private val vault: Vault,
    private val ui: CarUi,
    private val decoder: H264Decoder,
    private val requestUsb: (android.hardware.usb.UsbDevice) -> Unit,
) {
    interface CarUi {
        fun showPin(pin: String)
        fun showConnecting(phoneName: String)
        fun showVideo()
        fun showFail(reason: String)
        fun showStatus(text: String)
    }

    val carId: String = vault.deviceId
    @Volatile var pin: String = newPin()
        private set
    @Volatile private var activeToken: String = ""
    @Volatile private var writer: PacketWriter? = null
    private val running = AtomicBoolean(false)
    private val speaker = CarSpeaker()
    private val usbAsked = HashSet<String>()
    private var control: ServerSocket? = null
    private var video: ServerSocket? = null
    private var beacon: DatagramSocket? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var direct: WifiP2pManager.Channel? = null
    private val directManager = context.getSystemService(WifiP2pManager::class.java)
    private var directReceiver: BroadcastReceiver? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        pin = newPin()
        ui.showPin(pin)
        thread(name = "zl-control") { controlLoop() }
        thread(name = "zl-video") { videoLoop() }
        thread(name = "zl-beacon") { beaconLoop() }
        thread(name = "zl-phone-beacon") { phoneBeaconLoop() }
        thread(name = "zl-usb") { usbLoop() }
        startDirect()
        startBle()
    }

    fun stop() {
        running.set(false)
        writer = null
        decoder.release()
        speaker.stop()
        closeQuiet(control)
        closeQuiet(video)
        closeQuiet(beacon)
        try {
            usbConnection?.close()
        } catch (_: Exception) {
        }
        directReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
    }

    fun refreshPin() {
        pin = newPin()
        ui.showPin(pin)
    }

    fun sendTouch(action: Int, x: Int, y: Int) {
        val out = writer ?: return
        try {
            out.send(Packet.TOUCH, app.zhilian.drive.wire.Touch.encode(action, x, y))
        } catch (_: Exception) {
        }
    }

    private fun controlLoop() {
        val server = ServerSocket()
        server.reuseAddress = true
        try {
            server.bind(InetSocketAddress(Packet.CONTROL_PORT))
        } catch (e: Exception) {
            Log.e(TAG, "control bind", e)
            ui.showFail("车机端口被占用")
            return
        }
        control = server
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            thread(name = "zl-phone") { serveControl(socket) }
        }
    }

    private fun serveControl(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        var out: PacketWriter? = null
        try {
            val reader = PacketReader(socket.getInputStream())
            out = PacketWriter(socket.getOutputStream())
            val hello = reader.recv()
            if (hello.type != Packet.HELLO) {
                socket.close()
                return
            }
            if (!acceptHello(JSONObject(String(hello.payload, Charsets.UTF_8)), out!!)) {
                socket.close()
                return
            }
            writer = out
            while (running.get()) {
                val msg = reader.recv()
                when (msg.type) {
                    Packet.PING -> out?.send(Packet.PONG, ByteArray(0))
                    Packet.VIDEO -> feed(msg.payload)
                    Packet.AUDIO -> speaker.write(msg.payload)
                    Packet.BYE -> break
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "control ended ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            if (writer === out) {
                writer = null
                decoder.release()
                speaker.stop()
                ui.showPin(pin)
            }
        }
    }

    private fun videoLoop() {
        val server = ServerSocket()
        server.reuseAddress = true
        try {
            server.bind(InetSocketAddress(Packet.VIDEO_PORT))
        } catch (e: Exception) {
            Log.e(TAG, "video bind", e)
            return
        }
        video = server
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            thread(name = "zl-video-in") { serveVideo(socket) }
        }
    }

    private fun serveVideo(socket: Socket) {
        socket.tcpNoDelay = true
        try {
            val reader = PacketReader(socket.getInputStream())
            val hello = reader.recv()
            val json = JSONObject(String(hello.payload, Charsets.UTF_8))
            if (hello.type != Packet.HELLO || json.optString("token") != activeToken || activeToken.isEmpty()) {
                socket.close()
                return
            }
            while (running.get()) {
                val msg = reader.recv()
                if (msg.type == Packet.VIDEO) feed(msg.payload)
            }
        } catch (e: Exception) {
            Log.i(TAG, "video ended ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun acceptHello(json: JSONObject, out: PacketWriter): Boolean {
        val phoneId = json.optString("deviceId")
        val phoneName = json.optString("name", "手机")
        val givenPin = json.optString("pin")
        val givenToken = json.optString("token")
        val known = vault.tokenFor(phoneId)
        val trusted = known != null && known == givenToken && givenToken.isNotEmpty()
        if (json.optBoolean("usb")) {
            ui.showStatus("数据线已连接，等待手机允许录屏")
        } else if (!trusted && givenPin != pin) {
            val reason = JSONObject().put("reason", "连接码不一致").toString().toByteArray()
            out.send(Packet.REJECT, reason)
            ui.showStatus("连接码不一致，请看屏幕上的 6 位数再输入")
            return false
        }
        val token = if (trusted) known!! else UUID.randomUUID().toString()
        vault.rememberPhone(phoneId, token)
        activeToken = token
        val welcome = JSONObject()
            .put("token", token)
            .put("carId", carId)
            .put("name", "直联")
            .toString()
            .toByteArray()
        out.send(Packet.WELCOME, welcome)
        ui.showConnecting(phoneName)
        return true
    }

    private fun feed(payload: ByteArray) {
        val frame = VideoFrame.decode(payload)
        if (frame.flags and Packet.FLAG_CONFIG != 0) {
            decoder.configure(frame.width, frame.height, frame.data)
            ui.showVideo()
            return
        }
        if (!decoder.configured()) return
        decoder.frame(frame.data, frame.flags and Packet.FLAG_KEY != 0)
    }

    private fun beaconLoop() {
        val socket = DatagramSocket()
        socket.broadcast = true
        beacon = socket
        val body = JSONObject().put("role", "car").put("id", carId).put("name", "直联").put("port", Packet.CONTROL_PORT)
        while (running.get()) {
            val ips = org.json.JSONArray()
            for (ip in wifiIps()) ips.put(ip)
            body.put("ips", ips)
            body.put("pinReady", true)
            val bytes = body.toString().toByteArray()
            val packet = ByteArray(4 + bytes.size)
            packet[0] = 'Z'.code.toByte()
            packet[1] = 'L'.code.toByte()
            packet[2] = 'B'.code.toByte()
            packet[3] = '1'.code.toByte()
            bytes.copyInto(packet, 4)
            sendBeacon(socket, packet)
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun phoneBeaconLoop() {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.soTimeout = 1000
        try {
            socket.bind(InetSocketAddress(Packet.BEACON_PORT))
        } catch (e: Exception) {
            Log.e(TAG, "phone beacon bind", e)
            return
        }
        val buf = ByteArray(1024)
        while (running.get()) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                continue
            }
            if (packet.length < 5) continue
            if (buf[0] != 'Z'.code.toByte() || buf[3] != '1'.code.toByte()) continue
            try {
                val json = JSONObject(String(buf, 4, packet.length - 4, Charsets.UTF_8))
                if (json.optString("role") != "phone") continue
                val host = packet.address?.hostAddress ?: continue
                dial(host, json.optInt("port", Packet.CONTROL_PORT))
            } catch (_: Exception) {
            }
        }
    }

    private var lastDial = 0L

    private fun dial(host: String, port: Int) {
        if (writer != null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDial < 3000) return
        lastDial = now
        thread(name = "zl-dial") {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 4000)
                ui.showStatus("正在连接手机")
                serveControl(socket)
            } catch (e: Exception) {
                Log.i(TAG, "dial $host ${e.message}")
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun wifiIps(): List<String> {
        val preferred = ArrayList<String>()
        val backup = ArrayList<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (name.startsWith("dummy") || name.contains("rmnet") || name.startsWith("tun") || name.contains("p2p")) {
                    continue
                }
                val bucket = if (
                    name.startsWith("wlan") || name.startsWith("eth") ||
                    name.contains("ap") || name.contains("soft")
                ) {
                    preferred
                } else {
                    backup
                }
                val addresses = nif.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address) bucket.add(addr.hostAddress ?: continue)
                }
            }
        } catch (_: Exception) {
        }
        return if (preferred.isNotEmpty()) preferred else backup
    }

    private fun sendBeacon(socket: DatagramSocket, packet: ByteArray) {
        val targets = HashSet<InetAddress>()
        targets.add(InetAddress.getByName("255.255.255.255"))
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    ia.broadcast?.let { targets.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        for (address in targets) {
            try {
                socket.send(DatagramPacket(packet, packet.size, address, Packet.BEACON_PORT))
            } catch (_: Exception) {
            }
        }
    }

    private fun usbLoop() {
        val usb = context.getSystemService(UsbManager::class.java) ?: return
        while (running.get()) {
            val all = usb.deviceList.values.toList()
            if (all.isNotEmpty()) {
                ui.showStatus("已插入 USB：" + all.joinToString { it.productName ?: it.deviceName })
            }
            for (device in all) {
                if (!candidate(device)) continue
                if (!usb.hasPermission(device)) {
                    if (usbAsked.add(device.deviceName)) requestUsb(device)
                    ui.showStatus("请在车机上允许使用 USB")
                    continue
                }
                try {
                    ui.showStatus(if (isAccessory(device)) "USB 已进入直联模式" else "正在让手机打开直联")
                    if (isAccessory(device)) openAccessory(usb, device) else switchToAccessory(usb, device)
                } catch (e: Exception) {
                    Log.i(TAG, "usb ${e.message}")
                    ui.showStatus("USB 失败：" + (e.message ?: "未知"))
                }
            }
            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun candidate(device: UsbDevice): Boolean {
        if (isAccessory(device)) return true
        if (device.deviceClass == UsbConstants.USB_CLASS_HUB) return false
        if (device.deviceClass == UsbConstants.USB_CLASS_HID) return false
        return true
    }

    private fun isAccessory(device: UsbDevice): Boolean {
        return device.vendorId == GOOGLE_VID && (device.productId == 0x2d00 || device.productId == 0x2d01)
    }

    private fun switchToAccessory(usb: UsbManager, device: UsbDevice) {
        val conn = usb.openDevice(device) ?: return
        try {
            val proto = ByteArray(2)
            val got = conn.controlTransfer(0xC0, 51, 0, 0, proto, 2, 1000)
            if (got < 2) return
            val version = (proto[1].toInt() and 0xff shl 8) or (proto[0].toInt() and 0xff)
            if (version < 1) return
            listOf("ZhiLian", "CarDisplay", "ZhiLian", "1.0", "https://zhilian.local", "1").forEachIndexed { index, text ->
                val bytes = (text + "\u0000").toByteArray()
                conn.controlTransfer(0x40, 52, 0, index, bytes, bytes.size, 1000)
            }
            conn.controlTransfer(0x40, 53, 0, 0, ByteArray(0), 0, 1000)
        } finally {
            conn.close()
        }
    }

    private fun openAccessory(usb: UsbManager, device: UsbDevice) {
        val intf = device.getInterface(0)
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
        }
        val inputEp = inEp ?: return
        val outputEp = outEp ?: return
        val conn = usb.openDevice(device) ?: return
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            return
        }
        usbConnection = conn
        ui.showStatus("数据线已连接，等待手机允许录屏")
        val reader = PacketReader(BulkInput(conn, inputEp))
        val out = PacketWriter(BulkOutput(conn, outputEp))
        try {
            out.send(Packet.PING, ByteArray(0))
            val hello = reader.recv()
            if (hello.type != Packet.HELLO) return
            if (!acceptHello(JSONObject(String(hello.payload, Charsets.UTF_8)), out)) return
            writer = out
            while (running.get()) {
                val msg = reader.recv()
                when (msg.type) {
                    Packet.VIDEO -> feed(msg.payload)
                    Packet.AUDIO -> speaker.write(msg.payload)
                    Packet.PING -> out.send(Packet.PONG, ByteArray(0))
                    Packet.BYE -> break
                }
            }
        } finally {
            writer = null
            decoder.release()
            speaker.stop()
            try {
                conn.releaseInterface(intf)
                conn.close()
            } catch (_: Exception) {
            }
            ui.showPin(pin)
        }
    }

    private fun startBle() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            val advertiser = adapter.bluetoothLeAdvertiser ?: return
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build()
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(java.util.UUID.fromString("7d1f3a20-6b1e-4b1a-9c1a-7e0a6d5c4b31")))
                .setIncludeDeviceName(false)
                .build()
            advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {})
        } catch (_: Exception) {
        }
    }

    private fun startDirect() {
        val mgr = directManager ?: return
        val channel = mgr.initialize(context, context.mainLooper, null) ?: return
        direct = channel
        try {
            val method = mgr.javaClass.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java,
            )
            method.invoke(mgr, channel, "直联", null)
        } catch (_: Exception) {
        }
        mgr.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                ui.showStatus("无线直连已打开")
            }

            override fun onFailure(reason: Int) {
                Log.i(TAG, "wifi direct $reason")
            }
        })
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                val info = parcelInfo(intent) ?: return
                if (info.groupFormed) ui.showStatus("无线直连已建立")
            }
        }
        directReceiver = receiver
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun parcelInfo(intent: Intent): WifiP2pInfo? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
        }
    }

    private fun closeQuiet(closeable: java.io.Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    private fun newPin(): String = (100000 + java.util.Random().nextInt(900000)).toString()

    private class BulkInput(
        private val conn: UsbDeviceConnection,
        private val ep: UsbEndpoint,
    ) : InputStream() {
        private val buf = ByteArray(16 * 1024)
        private var pos = 0
        private var len = 0

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, length: Int): Int {
            while (pos >= len) {
                val n = conn.bulkTransfer(ep, buf, buf.size, 2000)
                if (n > 0) {
                    pos = 0
                    len = n
                    break
                }
            }
            val n = minOf(length, len - pos)
            buf.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }
    }

    private class BulkOutput(
        private val conn: UsbDeviceConnection,
        private val ep: UsbEndpoint,
    ) : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()))
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var sent = 0
            while (sent < len) {
                val n = minOf(16 * 1024, len - sent)
                val w = conn.bulkTransfer(ep, b, off + sent, n, 2000)
                if (w <= 0) throw java.io.IOException("usb write")
                sent += w
            }
        }
    }

    companion object {
        private const val TAG = "ZhiLianCar"
        private const val GOOGLE_VID = 0x18d1
        fun connectConfig(device: WifiP2pDevice): WifiP2pConfig {
            val config = WifiP2pConfig()
            config.deviceAddress = device.deviceAddress
            return config
        }
    }
}
