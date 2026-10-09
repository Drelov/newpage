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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class PhoneLink {
    data class FoundCar(val id: String, val name: String, val host: String, val port: Int)

    private val running = AtomicBoolean(false)
    private var beacon: DatagramSocket? = null
    private var control: Socket? = null

    fun startBeacon(onCar: (FoundCar) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        Thread {
            val socket = DatagramSocket(null)
            socket.reuseAddress = true
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
                    val host = packet.address?.hostAddress ?: continue
                    onCar(FoundCar(json.optString("id"), json.optString("name", "直联"), host, json.optInt("port", Packet.CONTROL_PORT)))
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    fun stop() {
        running.set(false)
        try {
            beacon?.close()
        } catch (_: Exception) {
        }
        try {
            control?.close()
        } catch (_: Exception) {
        }
    }

    fun connectWifi(
        car: FoundCar,
        deviceId: String,
        phoneName: String,
        pin: String,
        token: String,
        onReady: (token: String, carId: String) -> Unit,
        onTouch: (Int, Int, Int) -> Unit,
        onClosed: (String) -> Unit,
    ) {
        Thread {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(car.host, car.port), 4000)
                control = socket
                val writer = PacketWriter(socket.getOutputStream())
                val reader = PacketReader(socket.getInputStream())
                val hello = JSONObject()
                    .put("deviceId", deviceId)
                    .put("name", phoneName)
                    .put("pin", pin)
                    .put("token", token)
                    .toString()
                    .toByteArray()
                writer.send(Packet.HELLO, hello)
                readWelcome(reader, writer, onReady, onTouch, onClosed)
            } catch (e: Exception) {
                onClosed(e.message ?: "连接失败")
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    fun connectUsb(
        pfd: ParcelFileDescriptor,
        deviceId: String,
        phoneName: String,
        pin: String,
        token: String,
        onReady: (token: String, carId: String, ParcelFileDescriptor) -> Unit,
        onTouch: (Int, Int, Int) -> Unit,
        onClosed: (String) -> Unit,
    ) {
        Thread {
            try {
                val input = FileInputStream(pfd.fileDescriptor)
                val output = FileOutputStream(pfd.fileDescriptor)
                val writer = PacketWriter(output)
                val reader = PacketReader(input)
                val hello = JSONObject()
                    .put("deviceId", deviceId)
                    .put("name", phoneName)
                    .put("pin", pin)
                    .put("token", token)
                    .toString()
                    .toByteArray()
                writer.send(Packet.HELLO, hello)
                val first = reader.recv()
                if (first.type == Packet.REJECT) {
                    onClosed(JSONObject(String(first.payload, Charsets.UTF_8)).optString("reason", "被拒绝"))
                    return@Thread
                }
                if (first.type != Packet.WELCOME) {
                    onClosed("车机没有应答")
                    return@Thread
                }
                val welcome = JSONObject(String(first.payload, Charsets.UTF_8))
                onReady(welcome.getString("token"), welcome.optString("carId"), pfd.dup())
                while (true) {
                    val msg = reader.recv()
                    if (msg.type == Packet.TOUCH) {
                        val (action, x, y) = Touch.decode(msg.payload)
                        onTouch(action, x, y)
                    }
                    if (msg.type == Packet.BYE) break
                }
                onClosed("已断开")
            } catch (e: Exception) {
                onClosed(e.message ?: "USB 断开")
            }
        }.start()
    }

    private fun readWelcome(
        reader: PacketReader,
        writer: PacketWriter,
        onReady: (String, String) -> Unit,
        onTouch: (Int, Int, Int) -> Unit,
        onClosed: (String) -> Unit,
    ) {
        val first = reader.recv()
        if (first.type == Packet.REJECT) {
            onClosed(JSONObject(String(first.payload, Charsets.UTF_8)).optString("reason", "被拒绝"))
            return
        }
        if (first.type != Packet.WELCOME) {
            onClosed("车机没有应答")
            return
        }
        val welcome = JSONObject(String(first.payload, Charsets.UTF_8))
        onReady(welcome.getString("token"), welcome.optString("carId"))
        Thread {
            while (true) {
                try {
                    writer.send(Packet.PING, ByteArray(0))
                    Thread.sleep(2000)
                } catch (_: Exception) {
                    break
                }
            }
        }.start()
        try {
            while (true) {
                val msg = reader.recv()
                if (msg.type == Packet.TOUCH) {
                    val (action, x, y) = Touch.decode(msg.payload)
                    onTouch(action, x, y)
                }
                if (msg.type == Packet.BYE) break
            }
            onClosed("已断开")
        } catch (e: Exception) {
            onClosed(e.message ?: "已断开")
        }
    }
}
