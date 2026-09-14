package com.fairylive.app.core.compositor.rasterizer

/**
 * Minimal QR matrix decoder for validation tests.
 * Decodes Version 1 (21x21), byte-mode only, back to a payload string.
 * Verifies Reed-Solomon EC correctness (no silent error).
 */
internal object QrMatrixDecoder {

    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            GF_EXP[i] = x
            GF_LOG[x] = i
            x = (x shl 1) xor (if (x and 0x80 != 0) 0x11D else 0)
        }
        for (i in 255 until 512) GF_EXP[i] = GF_EXP[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else GF_EXP[GF_LOG[a] + GF_LOG[b]]

    /** Verify Reed-Solomon codeword syndrome is zero. */
    private fun verifyRs(dataAndEc: IntArray, ecLen: Int): Boolean {
        // Divide message+EC by generator; remainder must be 0
        val gen = QrMatrixEncoder.generatorPolyForTest()
        val msg = dataAndEc.copyOf()
        val n = dataAndEc.size - ecLen
        for (i in 0 until n) {
            val lead = msg[i]
            if (lead != 0) {
                for (j in gen.indices) {
                    msg[i + j] = msg[i + j] xor QrMatrixEncoder.gfMulForTest(gen[gen.size - 1 - j], lead)
                }
            }
        }
        return msg.copyOfRange(n, msg.size).all { it == 0 }
    }

    private fun functionMask(matrix: Array<BooleanArray>): Array<BooleanArray> {
        val size = matrix.size
        val mask = Array(size) { BooleanArray(size) }

        fun markFinder(r0: Int, c0: Int) {
            for (r in 0 until 8) {
                for (c in 0 until 8) {
                    val rr = r0 + r
                    val cc = c0 + c
                    if (rr in 0 until size && cc in 0 until size) mask[rr][cc] = true
                }
            }
        }
        markFinder(0, 0)
        markFinder(0, size - 8)
        markFinder(size - 8, 0)

        // Timing
        for (i in 8 until size - 8) {
            mask[6][i] = true
            mask[i][6] = true
        }

        // Dark module
        mask[13][8] = true

        // Format info areas (both copies) + (8,8)
        for (i in 0 until 6) mask[i][8] = true
        mask[7][8] = true
        mask[8][7] = true
        for (i in 0 until 6) mask[8][i] = true
        mask[8][8] = true
        for (i in size - 8 until size) {
            mask[8][i] = true
            mask[i][8] = true
        }
        return mask
    }

    /** Read format info from the 15 bits placed around the top-left finder. */
    private fun readFormatInfo(matrix: Array<BooleanArray>): Int {
        val size = matrix.size
        val bits = ArrayList<Int>(15)
        // Copy 1: rows 0..5 at col 8, row 7 col 8, row 8 col 7, row 8 cols 5..0, and (8,8)
        for (i in 0..5) bits.add(if (matrix[8][i]) 1 else 0)
        bits.add(if (matrix[8][7]) 1 else 0)
        bits.add(if (matrix[7][8]) 1 else 0)
        for (i in 5 downTo 0) bits.add(if (matrix[i][8]) 1 else 0)
        bits.add(if (matrix[8][8]) 1 else 0)
        var fmt = 0
        for (b in bits) fmt = (fmt shl 1) or b
        return fmt
    }

    /**
     * Decode a 21x21 boolean QR matrix. Returns the decoded payload string.
     * Throws if Reed-Solomon EC fails (proving the matrix is NOT valid).
     */
    fun decode(matrix: Array<BooleanArray>): String {
        require(matrix.size == 21 && matrix.all { it.size == 21 }) { "Must be 21x21 matrix" }

        // 1. Read format info; the stored value is (data<<10 | BCH) XOR 0x5412, so
        //    recover the data bits first, then extract EC level and mask pattern.
        val fmtInfo = readFormatInfo(matrix)
        val unmaskedFmt = fmtInfo xor 0x5412
        val maskBits = (unmaskedFmt shr 10) and 0b111 // bits 12..10 after XOR-back
        val ecLevel = (unmaskedFmt shr 13) and 0b11 // EC level 00 = M

        // 2. Build function mask and un-mask data modules (apply mask to revert)
        val fMask = functionMask(matrix)
        val data = Array(21) { row ->
            BooleanArray(21) { col ->
                val v = matrix[row][col]
                if (fMask[row][col]) v
                else {
                    when (maskBits) {
                        0 -> if ((row + col) % 2 == 0) !v else v
                        1 -> if (row % 2 == 0) !v else v
                        2 -> if (col % 3 == 0) !v else v
                        3 -> if ((row + col) % 3 == 0) !v else v
                        4 -> if ((row / 2 + col / 3) % 2 == 0) !v else v
                        5 -> if (row * col % 2 + row * col % 3 == 0) !v else v
                        6 -> if ((row * col % 2 + row * col % 3) % 2 == 0) !v else v
                        7 -> if ((row * col % 3 + (row + col) % 2) % 2 == 0) !v else v
                        else -> v
                    }
                }
            }
        }

        // 3. Extract data bits in zigzag order (skip function columns), max 26 codewords (208 bits)
        val bits = ArrayList<Int>(208)
        var col = 20
        var dirUp = true
        val maxBits = 26 * 8
        while (col > 0 && bits.size < maxBits) {
            if (col == 6) col--
            var row = if (dirUp) 20 else 0
            while (row in 0..20 && bits.size < maxBits) {
                for (d in 0..1) {
                    val c = col - d
                    if (c < 0) continue
                    if (!fMask[row][c]) {
                        if (bits.size < maxBits) {
                            bits.add(if (data[row][c]) 1 else 0)
                        }
                    }
                }
                row += if (dirUp) -1 else 1
            }
            dirUp = !dirUp
            col -= 2
        }

        // 4. Reconstruct codewords from bits
        val codewords = IntArray(26) // 16 data + 10 EC
        for (i in 0 until 26) {
            var v = 0
            for (j in 0 until 8) {
                val idx = i * 8 + j
                v = (v shl 1) or (if (idx < bits.size) bits[idx] else 0)
            }
            codewords[i] = v
        }

        // 5. Verify Reed-Solomon EC
        val ecValid = verifyRs(codewords, 10)
        require(ecValid) { "QR Reed-Solomon EC verification failed — matrix is corrupted" }

        // 6. Decode payload: mode (4 bits) + count (8 bits for byte mode v1) + bytes
        val dataWords = codewords.copyOfRange(0, 16)
        val payloadBits = ArrayList<Int>()
        for (word in dataWords) {
            for (j in 7 downTo 0) payloadBits.add((word shr j) and 1)
        }

        // Mode indicator (bits 0-3)
        val mode = (0..3).fold(0) { acc, i -> (acc shl 1) or payloadBits[i] }
        require(mode == 4) { "Expected byte mode (0100), got $mode" }

        // Character count (bits 4-11)
        val count = (0..7).fold(0) { acc, i -> (acc shl 1) or payloadBits[4 + i] }
        require(count in 0..14) { "Invalid payload length for v1: $count" }

        // Payload bytes (bits 12..)
        val payload = ByteArray(count)
        for (i in 0 until count) {
            var v = 0
            for (j in 0 until 8) v = (v shl 1) or payloadBits[12 + i * 8 + j]
            payload[i] = v.toByte()
        }

        return String(payload, Charsets.UTF_8)
    }
}
