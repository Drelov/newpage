package app.zhilian.drive.wire

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PacketTest {
    @Test
    fun roundTrip() {
        val raw = Packet.encode(Packet.TOUCH, byteArrayOf(1, 2, 3, 4, 5))
        val reader = PacketReader(ByteArrayInputStream(raw))
        val msg = reader.recv()
        assertEquals(Packet.TOUCH, msg.type)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), msg.payload)
    }

    @Test
    fun writerReader() {
        val buf = ByteArrayOutputStream()
        val writer = PacketWriter(buf)
        writer.send(Packet.PING, ByteArray(0))
        writer.send(Packet.VIDEO, byteArrayOf(2, 0, 0, 0, 10))
        val reader = PacketReader(ByteArrayInputStream(buf.toByteArray()))
        val ping = reader.recv()
        val video = reader.recv()
        assertEquals(Packet.PING, ping.type)
        assertEquals(0, ping.payload.size)
        assertEquals(Packet.VIDEO, video.type)
        assertEquals(5, video.payload.size)
    }

    @Test
    fun avccSplit() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x0a)
        val pps = byteArrayOf(0x68, 0xce.toByte(), 0x38, 0x80.toByte())
        val avcc = byteArrayOf(
            0x01, 0x42, 0x00, 0x0a, 0xff.toByte(), 0xe1.toByte(),
            0x00, 0x04, 0x67, 0x42, 0x00, 0x0a,
            0x01, 0x00, 0x04, 0x68, 0xce.toByte(), 0x38, 0x80.toByte(),
        )
        val pair = AvcConfig.split(avcc)
        assertArrayEquals(sps, pair.first)
        assertArrayEquals(pps, pair.second)
    }

    @Test
    fun annexBSplit() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x0a)
        val pps = byteArrayOf(0x68, 0xce.toByte(), 0x38, 0x80.toByte())
        val annex = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps
        val pair = AvcConfig.split(annex)
        assertArrayEquals(sps, pair.first)
        assertArrayEquals(pps, pair.second)
    }
}
