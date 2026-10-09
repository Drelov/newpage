package app.zhilian.drive.phone

import android.os.ParcelFileDescriptor
import app.zhilian.drive.wire.Packet
import app.zhilian.drive.wire.PacketReader
import app.zhilian.drive.wire.PacketWriter
import app.zhilian.drive.wire.Touch
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class PhoneLink {
    data class FoundCar(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val hosts: List<String> = listOf(host),
    )

    private val running = AtomicBoolean(false)
    private var beacon: DatagramSocket? = null
    private var control: Socket? = null
    private var server: ServerSocket? = null
    @Volatile private var deviceId: String = ""
    @Volatile private var phoneName: String = ""
    @Volatile private var armedPin: String = ""
    @Volatile private var armedToken: String = ""
    @Volatile private var armed: Boolean = false
    private var onReady: ((String, String, ParcelFileDescriptor) -> Unit)? = null
    private var onTouch: ((Int, Int, Int) -> Unit)? = null
    private var onClosed: ((String) -> Unit)? = null
    private var usbWriter: PacketWriter? = null
    private var usbReader: PacketReader? = null
    private var usbPfd: ParcelFileDescriptor? = null

    fun startSession(
        deviceId: String,
        phoneName: String,
        onReady: (token: String, carId: String, pipe: ParcelFileDescriptor) -> Unit,
        onTouch: (Int, Int, Int) -> Unit,
        onClosed: (String) -> Unit,
        onCar: (FoundCar) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return
        this.deviceId = deviceId
        this.phoneName = phoneName
        this.onReady = onReady
        this.onTouch = onTouch
        this.onClosed = onClosed
        Thread { receiveCars(onCar) }.start()
        Thread { announcePhone() }.start()
        Thread { acceptCars() }.start()
    }

    fun prepare(pin: String, token: String) {
        armedPin = pin
        armedToken = token
        armed = pin.isNotEmpty() || token.isNotEmpty()
    }

    fun stop() {
        running.set(false)
        try {
            beacon?.close()
        } catch (_: Exception) {
        }
        try {
            server?.close()
        } catch (_: Exception) {
        }
        try {
            control?.close()
        } catch (_: Exception) {
        }
    }

    fun hasUsb(): Boolean = usbWriter != null

    fun connectWifi(car: FoundCar, pin: String, token: String) {
        prepare(pin, token)
        if (usbWriter != null) return
        Thread {
            var last = "连接失败"
            for (host in car.hosts.ifEmpty { listOf(car.host) }) {
                val socket = Socket()
                try {
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, car.port), 2500)
                    speak(socket, pin, token)
                    return@Thread
                } catch (e: Exception) {
                    last = e.message ?: last
                    try {
                        socket.close()
                    } catch (_: Exception) {
                    }
                }
            }
            onClosed?.invoke(last)
        }.start()
    }

    fun attachUsb(pfd: ParcelFileDescriptor) {
        if (usbWriter != null) return
        usbPfd = pfd
        usbWriter = PacketWriter(FileOutputStream(pfd.fileDescriptor))
        usbReader = PacketReader(FileInputStream(pfd.fileDescriptor))
        Thread {
            val writer = usbWriter ?: return@Thread
            val reader = usbReader ?: return@Thread
            try {
                while (true) {
                    val msg = reader.recv()
                    if (msg.type == Packet.PING) {
                        writer.send(Packet.HELLO, helloBytes("", "", true))
                        continue
                    }
                    if (msg.type == Packet.REJECT) {
                        onClosed?.invoke(JSONObject(String(msg.payload, Charsets.UTF_8)).optString("reason", "连接被拒绝"))
                        return@Thread
                    }
                    if (msg.type != Packet.WELCOME) continue
                    val welcome = JSONObject(String(msg.payload, Charsets.UTF_8))
                    onReady?.invoke(welcome.getString("token"), welcome.optString("carId"), pfd.dup())
                    readTouches(reader)
                    return@Thread
                }
            } catch (e: Exception) {
                onClosed?.invoke(e.message ?: "USB 断开")
            }
        }.start()
    }

    private fun acceptCars() {
        val listen = ServerSocket()
        listen.reuseAddress = true
        try {
            listen.bind(InetSocketAddress(Packet.CONTROL_PORT))
        } catch (_: Exception) {
            return
        }
        server = listen
        while (running.get()) {
            val socket = try {
                listen.accept()
            } catch (_: Exception) {
                break
            }
            if (!armed) {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                continue
            }
            Thread {
                try {
                    socket.tcpNoDelay = true
                    speak(socket, armedPin, armedToken)
                } catch (e: Exception) {
                    onClosed?.invoke(e.message ?: "连接断开")
                    try {
                        socket.close()
                    } catch (_: Exception) {
                    }
                }
            }.start()
        }
    }

    private fun speak(socket: Socket, pin: String, token: String) {
        control = socket
        val writer = PacketWriter(socket.getOutputStream())
        val reader = PacketReader(socket.getInputStream())
        writer.send(Packet.HELLO, helloBytes(pin, token))
        val first = reader.recv()
        if (first.type == Packet.REJECT) {
            onClosed?.invoke(JSONObject(String(first.payload, Charsets.UTF_8)).optString("reason", "被拒绝"))
            socket.close()
            return
        }
        if (first.type != Packet.WELCOME) {
            onClosed?.invoke("车机没有应答")
            socket.close()
            return
        }
        val welcome = JSONObject(String(first.payload, Charsets.UTF_8))
        onReady?.invoke(welcome.getString("token"), welcome.optString("carId"), ParcelFileDescriptor.fromSocket(socket))
        readTouches(reader)
    }

    private fun readTouches(reader: PacketReader) {
        try {
            while (true) {
                val msg = reader.recv()
                if (msg.type == Packet.TOUCH) {
                    val (action, x, y) = Touch.decode(msg.payload)
                    onTouch?.invoke(action, x, y)
                }
                if (msg.type == Packet.BYE) break
            }
            onClosed?.invoke("已断开")
        } catch (e: Exception) {
            onClosed?.invoke(e.message ?: "已断开")
        }
    }

    private fun helloBytes(pin: String, token: String, usb: Boolean = false): ByteArray {
        return JSONObject()
            .put("deviceId", deviceId)
            .put("name", phoneName)
            .put("pin", pin)
            .put("token", token)
            .put("usb", usb)
            .toString()
            .toByteArray()
    }

    private fun receiveCars(onCar: (FoundCar) -> Unit) {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.broadcast = true
        socket.bind(InetSocketAddress(Packet.BEACON_PORT))
        socket.soTimeout = 1000
        beacon = socket
        val buf = ByteArray(1024)
        while (running.get()) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                continue
            }
            if (packet.length < 5) continue
            if (buf[0] != 'Z'.code.toByte() || buf[1] != 'L'.code.toByte() ||
                buf[2] != 'B'.code.toByte() || buf[3] != '1'.code.toByte()
            ) {
                continue
            }
            try {
                val json = JSONObject(String(buf, 4, packet.length - 4, Charsets.UTF_8))
                if (json.optString("role") == "phone") continue
                val source = packet.address?.hostAddress ?: continue
                val listed = ArrayList<String>()
                val arr = json.optJSONArray("ips")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val ip = arr.optString(i)
                        if (ip.isNotBlank()) listed.add(ip)
                    }
                }
                if (source.isNotBlank() && !listed.contains(source)) listed.add(0, source)
                val host = listed.firstOrNull() ?: continue
                onCar(
                    FoundCar(
                        json.optString("id"),
                        json.optString("name", "直联"),
                        host,
                        json.optInt("port", Packet.CONTROL_PORT),
                        listed,
                    ),
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun announcePhone() {
        val socket = DatagramSocket()
        socket.broadcast = true
        val body = JSONObject()
            .put("role", "phone")
            .put("id", deviceId)
            .put("name", phoneName)
            .put("port", Packet.CONTROL_PORT)
        while (running.get()) {
            val bytes = body.toString().toByteArray()
            val packet = ByteArray(4 + bytes.size)
            packet[0] = 'Z'.code.toByte()
            packet[1] = 'L'.code.toByte()
            packet[2] = 'B'.code.toByte()
            packet[3] = '1'.code.toByte()
            bytes.copyInto(packet, 4)
            sendAll(socket, packet)
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun sendAll(socket: DatagramSocket, packet: ByteArray) {
        val targets = HashSet<InetAddress>()
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
            val interfaces = NetworkInterface.getNetworkInterfaces()
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
}
