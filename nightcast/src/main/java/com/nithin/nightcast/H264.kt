package com.nithin.nightcast

/** Just enough H.264 bitstream parsing to set up a decoder: finding the SPS/PPS and the picture size. */
internal object H264 {

    private const val NAL_SPS = 7
    private const val NAL_PPS = 8

    /** Parameter sets found at the head of an Annex-B buffer, each with its start code. */
    class Config(val sps: ByteArray, val pps: ByteArray) {
        fun sameAs(other: Config?) =
            other != null && sps.contentEquals(other.sps) && pps.contentEquals(other.pps)
    }

    /**
     * Returns the SPS and PPS if [data] begins with them, which is how the stream announces a new
     * picture size (connecting, or the phone rotating); null for an ordinary frame.
     */
    fun leadingConfig(data: ByteArray, length: Int): Config? {
        if (length < 5 || nalType(data, 0, length) != NAL_SPS) return null
        val ppsStart = nextStartCode(data, 4, length)
        if (ppsStart < 0 || nalType(data, ppsStart, length) != NAL_PPS) return null
        var end = nextStartCode(data, ppsStart + 4, length)
        if (end < 0) end = length
        return Config(data.copyOfRange(0, ppsStart), data.copyOfRange(ppsStart, end))
    }

    private fun nalType(data: ByteArray, at: Int, length: Int): Int {
        if (at + 4 >= length) return -1
        if (data[at].toInt() != 0 || data[at + 1].toInt() != 0 || data[at + 2].toInt() != 0 || data[at + 3].toInt() != 1) return -1
        return data[at + 4].toInt() and 0x1f
    }

    private fun nextStartCode(data: ByteArray, from: Int, length: Int): Int {
        var i = from
        while (i + 3 < length) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) return i
            i++
        }
        return -1
    }

    /** Picture width and height from an SPS (with its 4-byte start code), or null if it cannot be read. */
    fun pictureSize(sps: ByteArray): Pair<Int, Int>? = try {
        parseSps(BitReader(unescape(sps, 5)), sps[5].toInt() and 0xff)
    } catch (e: RuntimeException) {
        null
    }

    private fun parseSps(r: BitReader, profile: Int): Pair<Int, Int>? {
        r.skip(16) // constraint flags, level
        r.ue() // seq_parameter_set_id
        var chroma = 1
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = r.ue()
            if (chroma == 3) r.skip(1)
            r.ue() // bit_depth_luma_minus8
            r.ue() // bit_depth_chroma_minus8
            r.skip(1)
            if (r.bit() == 1) {
                for (i in 0 until if (chroma != 3) 8 else 12) {
                    if (r.bit() == 1) skipScalingList(r, if (i < 6) 16 else 64)
                }
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> {
                r.skip(1)
                r.se()
                r.se()
                repeat(r.ue()) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.skip(1)
        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.bit()
        if (frameMbsOnly == 0) r.skip(1)
        r.skip(1) // direct_8x8_inference_flag
        var cropX = 0
        var cropY = 0
        if (r.bit() == 1) {
            val left = r.ue()
            val right = r.ue()
            val top = r.ue()
            val bottom = r.ue()
            val unitX = if (chroma == 1 || chroma == 2) 2 else 1
            val unitY = (if (chroma == 1) 2 else 1) * (2 - frameMbsOnly)
            cropX = (left + right) * unitX
            cropY = (top + bottom) * unitY
        }
        val width = widthMbs * 16 - cropX
        val height = (2 - frameMbsOnly) * heightMapUnits * 16 - cropY
        return if (width in 16..8192 && height in 16..8192) width to height else null
    }

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        repeat(size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            if (next != 0) last = next
        }
    }

    /** Strips the emulation-prevention bytes (00 00 03 -> 00 00) from a NAL payload. */
    private fun unescape(nal: ByteArray, from: Int): ByteArray {
        val out = ByteArray(nal.size - from)
        var n = 0
        var zeros = 0
        for (i in from until nal.size) {
            val b = nal[i].toInt() and 0xff
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            zeros = if (b == 0) zeros + 1 else 0
            out[n++] = b.toByte()
        }
        return out.copyOf(n)
    }

    private class BitReader(private val data: ByteArray) {
        private var pos = 8 // past profile_idc

        fun bit(): Int {
            val value = (data[pos shr 3].toInt() shr (7 - (pos and 7))) and 1
            pos++
            return value
        }

        fun skip(bits: Int) {
            pos += bits
        }

        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) {
                if (++zeros > 31) throw IllegalStateException("bad exp-golomb code")
            }
            var value = 0
            repeat(zeros) { value = (value shl 1) or bit() }
            return (1 shl zeros) - 1 + value
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }
}
