package app.qiaolian.phone

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class Discovery(private val beacon: () -> String) {
    private val stop = AtomicBoolean(false)
    private var recv: DatagramSocket? = null
    private var send: DatagramSocket? = null

    fun start() {
        val outgoing = DatagramSocket(null)
        outgoing.reuseAddress = true
        outgoing.broadcast = true
        outgoing.bind(InetSocketAddress(0))
        send = outgoing
        thread(name = "qiaolian-beacon", isDaemon = true) {
            val target = InetAddress.getByName("255.255.255.255")
            while (!stop.get()) {
                try {
                    val bytes = beacon().toByteArray(Charsets.UTF_8)
                    outgoing.send(DatagramPacket(bytes, bytes.size, target, Qlp.UDP_PORT))
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
        thread(name = "qiaolian-probe", isDaemon = true) {
            val socket = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(Qlp.UDP_PORT))
                }
            } catch (_: Exception) {
                return@thread
            }
            recv = socket
            val buf = ByteArray(2048)
            while (!stop.get()) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.soTimeout = 2000
                    socket.receive(packet)
                } catch (_: Exception) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (!text.contains("\"op\":\"discover\"")) continue
                try {
                    val bytes = beacon().toByteArray(Charsets.UTF_8)
                    outgoing.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
                } catch (_: Exception) {
                }
            }
        }
    }

    fun shutdown() {
        stop.set(true)
        recv?.close()
        send?.close()
    }

    companion object {
        fun beacon(name: String, id: String): String {
            return JSONObject()
                .put("magic", "QLP1")
                .put("name", name)
                .put("os", "android")
                .put("id", id)
                .put("port", Qlp.TCP_PORT)
                .put("proto", 1)
                .toString()
        }
    }
}
