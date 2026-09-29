package app.qiaolian.phone

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Phone side of the protocol. The sequence matches qiaolian/internal/agent.
 */
class PhoneServer(
    private val filesOf: () -> FileStore,
    private val host: Host,
) {
    interface Host {
        fun helloName(): String
        fun osVersion(): String
        fun deviceId(): String
        fun width(): Int
        fun height(): Int
        fun pairCode(): String
        fun token(): String
        fun saveToken(token: String)
        fun startScreen(maxWidth: Int, quality: Int, fps: Int): String?
        fun stopScreen()
        fun onInput(body: JSONObject): String?
        fun clipboard(): String
        fun setClipboard(text: String): String?
        fun clientChanged(connected: Boolean)
    }

    private val stop = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val random = SecureRandom()
    private var failCount = 0
    private var lockUntil = 0L
    @Volatile private var active: Socket? = null
    @Volatile private var screenOn = false

    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(Qlp.TCP_PORT))
        server = socket
        thread(name = "qiaolian-accept", isDaemon = true) {
            while (!stop.get()) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                client.tcpNoDelay = true
                active?.close()
                active = client
                thread(name = "qiaolian-client", isDaemon = true) {
                    try {
                        handle(client)
                    } catch (e: Exception) {
                        Log.i(TAG, "client ended: ${e.message}")
                    } finally {
                        screenOn = false
                        host.stopScreen()
                        if (active == client) {
                            active = null
                            host.clientChanged(false)
                        }
                        client.close()
                    }
                }
            }
        }
    }

    fun shutdown() {
        stop.set(true)
        active?.close()
        server?.close()
    }

    fun sendFrame(jpeg: ByteArray, w: Int, h: Int) {
        if (!screenOn) return
        val socket = active ?: return
        val meta = JSONObject().put("w", w).put("h", h).put("ts", System.currentTimeMillis())
        try {
            synchronized(socket) {
                socket.getOutputStream().writeFrame(Qlp.FRAME, meta.toString(), jpeg)
            }
        } catch (_: Exception) {
            screenOn = false
        }
    }

    private fun handle(socket: Socket) {
        val input = FrameReader(socket.getInputStream())
        val output = socket.getOutputStream()
        fun send(type: Byte, json: String?, bin: ByteArray? = null) {
            synchronized(socket) {
                output.writeFrame(type, json, bin)
            }
        }
        val sender: (Byte, String?, ByteArray?) -> Unit = { type, json, bin -> send(type, json, bin) }
        send(Qlp.HELLO, helloJson())
        host.clientChanged(true)
        var authed = false
        var push: Push? = null
        while (!stop.get()) {
            val frame = try {
                input.next()
            } catch (_: EOFException) {
                push?.abort()
                return
            }
            if (!authed && frame.type != Qlp.HELLO && frame.type != Qlp.PAIR && frame.type != Qlp.PING) {
                send(Qlp.ERROR, JSONObject().put("error", "未配对").toString())
                continue
            }
            val body = jsonObject(frame.json)
            when (frame.type) {
                Qlp.PING -> send(Qlp.PONG, null)
                Qlp.HELLO -> {
                    val token = body.optString("token")
                    val saved = host.token()
                    if (token.isNotEmpty() && saved.isNotEmpty() && token == saved) {
                        authed = true
                        send(Qlp.PAIR_RESULT, JSONObject().put("ok", true).put("token", saved).toString())
                    } else {
                        send(Qlp.PAIR_RESULT, JSONObject().put("ok", false).put("error", "需要配对码").toString())
                    }
                }
                Qlp.PAIR -> {
                    val result = tryPair(body.optString("code"))
                    if (result.getBoolean("ok")) authed = true
                    send(Qlp.PAIR_RESULT, result.toString())
                }
                Qlp.LIST -> send(Qlp.LIST_RESULT, listJson(body.optString("path")))
                Qlp.PULL -> sendFile(sender, body.optString("path"), body.optString("id"))
                Qlp.PUSH_BEGIN -> {
                    push?.abort()
                    push = beginPush(sender, body)
                }
                Qlp.CHUNK_FRAME -> {
                    val current = push
                    if (current == null) {
                        send(Qlp.TRANSFER, JSONObject().put("ok", false).put("error", "没有正在接收的文件").toString())
                        continue
                    }
                    try {
                        val done = current.write(body, frame.bin)
                        if (done) {
                            val size = filesOf().finishPart(current.rel, current.part)
                            push = null
                            send(Qlp.TRANSFER, JSONObject().put("id", current.id).put("ok", true).put("size", size).toString())
                        }
                    } catch (e: Exception) {
                        val id = current.id
                        current.abort()
                        push = null
                        send(Qlp.TRANSFER, JSONObject().put("id", id).put("ok", false).put("error", errText(e)).toString())
                    }
                }
                Qlp.MKDIR -> simpleOp(sender, "mkdir", body.optString("path")) { filesOf().mkdir(it) }
                Qlp.DELETE -> simpleOp(sender, "delete", body.optString("path")) { filesOf().delete(it) }
                Qlp.RENAME -> {
                    val from = body.optString("path")
                    val to = body.optString("to")
                    val res = JSONObject().put("op", "rename").put("path", to)
                    try {
                        filesOf().rename(from, to)
                        res.put("ok", true)
                    } catch (e: Exception) {
                        res.put("ok", false).put("error", errText(e))
                    }
                    send(Qlp.OP_RESULT, res.toString())
                }
                Qlp.SCREEN_START -> {
                    screenOn = false
                    host.stopScreen()
                    val maxWidth = body.optInt("maxWidth", 1280)
                    val quality = body.optInt("quality", 70)
                    val fps = body.optInt("fps", 8)
                    val err = host.startScreen(maxWidth, quality, fps)
                    if (err != null) {
                        send(Qlp.SCREEN_STATUS, JSONObject().put("ok", false).put("active", false).put("error", err).toString())
                    } else {
                        screenOn = true
                        send(
                            Qlp.SCREEN_STATUS,
                            JSONObject().put("ok", true).put("active", true)
                                .put("width", host.width()).put("height", host.height()).toString(),
                        )
                    }
                }
                Qlp.SCREEN_STOP -> {
                    screenOn = false
                    host.stopScreen()
                    send(Qlp.SCREEN_STATUS, JSONObject().put("ok", true).put("active", false).toString())
                }
                Qlp.INPUT -> {
                    val err = host.onInput(body)
                    val res = JSONObject().put("ok", err == null)
                    if (err != null) res.put("error", err)
                    send(Qlp.INPUT_RESULT, res.toString())
                }
                Qlp.CLIP_GET -> send(Qlp.CLIP_DATA, JSONObject().put("text", host.clipboard()).toString())
                Qlp.CLIP_SET -> {
                    val text = body.optString("text")
                    if (text.toByteArray().size > 1 shl 20) {
                        send(Qlp.ERROR, JSONObject().put("error", "剪贴板内容过长").toString())
                    } else {
                        val err = host.setClipboard(text)
                        if (err != null) {
                            send(Qlp.ERROR, JSONObject().put("error", err).toString())
                        } else {
                            send(Qlp.CLIP_DATA, JSONObject().put("text", text).toString())
                        }
                    }
                }
                else -> send(Qlp.ERROR, JSONObject().put("error", "未知消息").toString())
            }
        }
        push?.abort()
    }

    private fun helloJson(): String {
        return JSONObject()
            .put("role", "phone")
            .put("name", host.helloName())
            .put("os", "android")
            .put("osVersion", host.osVersion())
            .put("proto", 1)
            .put("id", host.deviceId())
            .put("w", host.width())
            .put("h", host.height())
            .put("needPair", true)
            .toString()
    }

    private fun tryPair(code: String): JSONObject {
        val now = System.currentTimeMillis()
        if (now < lockUntil) {
            return JSONObject().put("ok", false).put("error", "尝试过多，请稍后再试")
        }
        if (code.isEmpty() || code != host.pairCode()) {
            failCount++
            if (failCount >= 5) {
                lockUntil = now + 30_000
                failCount = 0
            }
            return JSONObject().put("ok", false).put("error", "配对码错误")
        }
        failCount = 0
        var token = host.token()
        if (token.isEmpty()) {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            token = bytes.joinToString("") { "%02x".format(it) }
            host.saveToken(token)
        }
        return JSONObject().put("ok", true).put("token", token)
    }

    private fun listJson(path: String): String {
        return try {
            filesOf().list(path).toString()
        } catch (e: Exception) {
            JSONObject().put("path", path).put("error", errText(e)).put("entries", JSONArray()).toString()
        }
    }

    private fun sendFile(send: (Byte, String?, ByteArray?) -> Unit, path: String, id: String) {
        val opened = try {
            filesOf().openRead(path)
        } catch (e: Exception) {
            send(Qlp.TRANSFER, JSONObject().put("id", id).put("ok", false).put("error", errText(e)).toString(), null)
            return
        }
        val input = opened.first
        val size = opened.second
        input.use { stream ->
            val buf = ByteArray(Qlp.CHUNK)
            var seq = 0
            var sent = 0L
            while (true) {
                val n = stream.read(buf)
                if (n < 0) {
                    if (sent == 0L) {
                        send(Qlp.CHUNK_FRAME, JSONObject().put("id", id).put("seq", seq).put("eof", true).toString(), null)
                    }
                    break
                }
                sent += n
                val eof = sent == size
                val chunk = if (n == buf.size) buf else buf.copyOf(n)
                send(Qlp.CHUNK_FRAME, JSONObject().put("id", id).put("seq", seq).put("eof", eof).toString(), chunk)
                seq++
                if (eof) break
            }
        }
        send(Qlp.TRANSFER, JSONObject().put("id", id).put("ok", true).put("size", size).toString(), null)
    }

    private fun beginPush(send: (Byte, String?, ByteArray?) -> Unit, body: JSONObject): Push? {
        val id = body.optString("id")
        val path = body.optString("path")
        return try {
            val (clean, part, out) = filesOf().createPart(path)
            send(Qlp.TRANSFER, JSONObject().put("id", id).put("ok", true).put("ready", true).toString(), null)
            Push(id, clean, part, out)
        } catch (e: Exception) {
            send(Qlp.TRANSFER, JSONObject().put("id", id).put("ok", false).put("error", errText(e)).toString(), null)
            null
        }
    }

    private fun simpleOp(send: (Byte, String?, ByteArray?) -> Unit, op: String, path: String, fn: (String) -> Unit) {
        val res = JSONObject().put("op", op).put("path", path)
        try {
            fn(path)
            res.put("ok", true)
        } catch (e: Exception) {
            res.put("ok", false).put("error", errText(e))
        }
        send(Qlp.OP_RESULT, res.toString(), null)
    }

    private class Push(
        val id: String,
        val rel: String,
        val part: File,
        val out: FileOutputStream,
    ) {
        fun write(meta: JSONObject, bin: ByteArray): Boolean {
            if (meta.optString("id") != id) throw IllegalStateException("文件传输编号不一致")
            if (bin.isNotEmpty()) out.write(bin)
            if (meta.optBoolean("eof")) {
                out.close()
                return true
            }
            return false
        }

        fun abort() {
            try {
                out.close()
            } catch (_: Exception) {
            }
            part.delete()
        }
    }

    private fun jsonObject(bytes: ByteArray): JSONObject {
        val text = jsonText(bytes)
        if (text.isBlank()) return JSONObject()
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun errText(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            msg.contains("Permission") || e is SecurityException -> "没有权限"
            msg == "文件不存在" || e is java.io.FileNotFoundException -> "文件不存在"
            msg.isNotEmpty() -> msg
            else -> "失败"
        }
    }

    companion object {
        private const val TAG = "QiaoLian"
    }
}
