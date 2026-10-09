package app.zhilian.drive.wire

object AvcConfig {
    fun split(csd: ByteArray): Pair<ByteArray, ByteArray> {
        if (csd.isNotEmpty() && csd[0] == 1.toByte()) {
            return splitAvcc(csd)
        }
        val nals = splitAnnexB(csd)
        val sps = nals.firstOrNull { nalType(it) == 7 } ?: nals.getOrNull(0) ?: ByteArray(0)
        val pps = nals.firstOrNull { nalType(it) == 8 } ?: nals.getOrNull(1) ?: ByteArray(0)
        return sps to pps
    }

    private fun splitAvcc(csd: ByteArray): Pair<ByteArray, ByteArray> {
        var i = 5
        if (csd.size < 7) return ByteArray(0) to ByteArray(0)
        val spsCount = csd[i].toInt() and 0x1f
        i += 1
        var sps = ByteArray(0)
        repeat(spsCount) {
            if (i + 2 > csd.size) return sps to ByteArray(0)
            val len = ((csd[i].toInt() and 0xff) shl 8) or (csd[i + 1].toInt() and 0xff)
            i += 2
            if (i + len > csd.size) return sps to ByteArray(0)
            if (sps.isEmpty()) sps = csd.copyOfRange(i, i + len)
            i += len
        }
        if (i >= csd.size) return sps to ByteArray(0)
        val ppsCount = csd[i].toInt() and 0xff
        i += 1
        var pps = ByteArray(0)
        repeat(ppsCount) {
            if (i + 2 > csd.size) return sps to pps
            val len = ((csd[i].toInt() and 0xff) shl 8) or (csd[i + 1].toInt() and 0xff)
            i += 2
            if (i + len > csd.size) return sps to pps
            if (pps.isEmpty()) pps = csd.copyOfRange(i, i + len)
            i += len
        }
        return sps to pps
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val marks = ArrayList<Int>()
        var i = 0
        while (i + 3 < data.size) {
            val four = i + 4 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val three = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            if (four || three) {
                marks.add(i)
                i += if (four) 4 else 3
            } else {
                i += 1
            }
        }
        if (marks.isEmpty()) return listOf(data)
        val out = ArrayList<ByteArray>()
        for (index in marks.indices) {
            val mark = marks[index]
            val header = if (mark + 3 < data.size && data[mark + 2] == 1.toByte()) 3 else 4
            val from = mark + header
            val to = if (index + 1 < marks.size) marks[index + 1] else data.size
            if (to > from) out.add(data.copyOfRange(from, to))
        }
        return out
    }

    private fun nalType(nal: ByteArray): Int {
        if (nal.isEmpty()) return -1
        return nal[0].toInt() and 0x1f
    }
}
