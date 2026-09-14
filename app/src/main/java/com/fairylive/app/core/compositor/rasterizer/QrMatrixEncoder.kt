package com.fairylive.app.core.compositor.rasterizer

/**
 * Pure Kotlin, dependency-free QR Code encoder implementing ISO/IEC 18004.
 *
 * Scope: Version 1 (21x21), Error Correction Level M, Byte mode.
 * Capacity: 14 bytes payload (v1-M byte mode: 16 data codewords).
 *
 * Pipeline implemented:
 * 1. Payload encoding (byte mode indicator 0100 + 8-bit length + UTF-8 payload)
 * 2. Terminator / padding to 16 data codewords
 * 3. Reed-Solomon error correction over GF(256) with generator
 *    g(x) = (x - a^0)(x - a^1)...(x - a^9) → 10 EC codewords (v1-M table)
 * 4. Interleave: 16 data + 10 EC = 26 codewords
 * 5. Module placement: finder patterns, separators, timing, dark module,
 *    format info (BCH(15,5), EC=M + Mask 0 → 0x5412), data zigzag placement
 *    skipping function patterns, Mask 0 applied outside function modules.
 *
 * Mask 0 (row+col even) is used deterministically; the stored format value
 * 0x5412 is the standard M+mask0 code, so any ISO 18004 scanner can decode.
 */
object QrMatrixEncoder {

    const val VERSION = 1
    const val SIZE = 21
    const val EC_CODEWORDS = 10
    const val DATA_CODEWORDS = 16

    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            GF_EXP[i] = x
            GF_LOG[x] = i
            x = (x shl 1) xor (if (x and 0x80 != 0) 0x11D else 0)
        }
        for (i in 255 until 512) {
            GF_EXP[i] = GF_EXP[i - 255]
        }
    }

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return GF_EXP[GF_LOG[a] + GF_LOG[b]]
    }

    private val generatorPoly: IntArray by lazy {
        // Product over i in 0..9 of (x - a^i)
        var gen = intArrayOf(1)
        for (i in 0 until EC_CODEWORDS) {
            val root = GF_EXP[i]
            val newGen = IntArray(gen.size + 1)
            for (j in gen.indices) {
                newGen[j] = newGen[j] xor gfMul(gen[j], root)
                newGen[j + 1] = newGen[j + 1] xor gen[j]
            }
            gen = newGen
        }
        gen
    }

    /** Reed-Solomon remainder: message is 16 data codewords + 10 zero EC slots. */
    private fun reedSolomonRemainder(data: IntArray): IntArray {
        val gen = generatorPoly
        val msg = IntArray(data.size + EC_CODEWORDS)
        data.copyInto(msg)
        for (i in data.indices) {
            val lead = msg[i]
            if (lead != 0) {
                for (j in gen.indices) {
                    msg[i + j] = msg[i + j] xor gfMul(gen[gen.size - 1 - j], lead)
                }
            }
        }
        return msg.copyOfRange(data.size, msg.size)
    }

    private fun formatInfoBits(): Int {
        // EC level M = 00, mask 0 = 000. Data bits: 00 000 -> BCH(15,5) -> xor 0x5412
        val data = 0b00000
        var bch = data shl 10
        val genPoly = 0b10100110111
        for (i in 14 downTo 10) {
            if ((bch shr i) and 1 != 0) {
                bch = bch xor (genPoly shl (i - 10))
            }
        }
        return ((data shl 10) or (bch and 0x3FF)) xor 0x5412
    }

    private fun makeFunctionPatterns(): Array<BooleanArray> {
        val func = Array(SIZE) { BooleanArray(SIZE) }

        // Finder patterns (7x7 dark+border) + 1-module separator ring (8x8 total).
        // Both finder and separator are skipped by data placement.
        fun drawFinder(r0: Int, c0: Int) {
            for (r in 0 until 8) {
                for (c in 0 until 8) {
                    val rr = r0 + r
                    val cc = c0 + c
                    if (rr !in 0 until SIZE || cc !in 0 until SIZE) continue
                    func[rr][cc] = true
                }
            }
        }
        drawFinder(0, 0)
        drawFinder(0, SIZE - 8) // 0, 13
        drawFinder(SIZE - 8, 0) // 13, 0

        // Timing patterns: row 6 cols 8..12 and col 6 rows 8..12 (between finders)
        for (i in 8 until SIZE - 8) {
            func[6][i] = true
            func[i][6] = true
        }

        // Dark module (v1): (4*1+9, 8) = (13, 8)
        func[13][8] = true

        // Format info area 1 (around top-left finder):
        //   rows 0..5 at col 8, row 7 at col 8, row 8 at col 7, row 8 cols 0..5, and (8,8) holds bit 14
        for (i in 0 until 6) func[i][8] = true
        func[7][8] = true
        func[8][7] = true
        for (i in 0 until 6) func[8][i] = true
        func[8][8] = true

        // Format info area 2 (split top-right / bottom-left):
        //   row 8, cols 13..20 and col 8, rows 13..20
        for (i in SIZE - 8 until SIZE) {
            func[8][i] = true
            func[i][8] = true
        }

        return func
    }

    /** Test hook: expose format info bits (internal, used by unit tests). */
    internal fun formatInfoForTest(): Int = formatInfoBits()

    /** Test hook: expose Reed-Solomon remainder (internal, used by unit tests). */
    internal fun reedSolomonRemainderForTest(data: IntArray): IntArray = reedSolomonRemainder(data)

    /** Test hook: expose generator polynomial (internal, used by decoder tests). */
    internal fun generatorPolyForTest(): IntArray = generatorPoly

    /** Test hook: expose GF(256) multiply (internal, used by decoder tests). */
    internal fun gfMulForTest(a: Int, b: Int): Int = gfMul(a, b)

    /** Builds the module matrix (true = dark) for [payload] with deterministic Mask 0. */
    fun encode(payload: String): Array<BooleanArray> {
        require(payload.toByteArray(Charsets.UTF_8).size <= DATA_CODEWORDS) {
            "Version 1-M QR capacity exceeded: ${payload.toByteArray(Charsets.UTF_8).size} > $DATA_CODEWORDS bytes"
        }

        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= DATA_CODEWORDS) {
            "Version 1-M QR capacity exceeded: ${bytes.size} > $DATA_CODEWORDS bytes"
        }

        val bits = ArrayList<Int>(64)
        // Mode indicator: byte mode 0100
        bits.addAll(listOf(0, 1, 0, 0))
        // Character count: 8 bits for v1 byte mode
        for (i in 7 downTo 0) bits.add((bytes.size shr i) and 1)
        // Payload
        for (byte in bytes) {
            for (i in 7 downTo 0) bits.add((byte.toInt() shr i) and 1)
        }
        // Terminator: up to 4 zero bits (may be truncated if near capacity)
        repeat(4) {
            if (bits.size < DATA_CODEWORDS * 8) bits.add(0)
        }
        // Pad to byte boundary
        while (bits.size % 8 != 0) bits.add(0)

        // Build data codewords: real message bits first, then 0xEC/0x11 alternating pad
        val messageWords = IntArray(DATA_CODEWORDS)
        var bitPos = 0
        var padStart = -1
        for (i in 0 until DATA_CODEWORDS) {
            if (bitPos + 8 <= bits.size) {
                var v = 0
                for (j in 0 until 8) v = (v shl 1) or bits[bitPos + j]
                messageWords[i] = v
            } else {
                var v = 0
                while (bitPos < bits.size) {
                    v = (v shl 1) or bits[bitPos]
                    bitPos++
                }
                v = v shl (8 - (bits.size % 8)) // (bits.size % 8 == 0 here)
                messageWords[i] = v
                padStart = i + 1
                break
            }
            bitPos += 8
        }
        // 0xEC, 0x11 alternating from first pad codeword
        if (padStart >= 0) {
            for (k in padStart until DATA_CODEWORDS) {
                messageWords[k] = if ((k - padStart) % 2 == 0) 0xEC else 0x11
            }
        }

        val ec = reedSolomonRemainder(messageWords)

        // Interleave: 26 codewords
        val all = IntArray(DATA_CODEWORDS + EC_CODEWORDS)
        messageWords.copyInto(all, 0)
        ec.copyInto(all, DATA_CODEWORDS)

        val func = makeFunctionPatterns()
        val matrix = Array(SIZE) { BooleanArray(SIZE) }

        // Draw function patterns INTO the matrix:
        // Finder rings (7x7: border + center dark), within the 8x8 reserved blocks
        fun drawFinderPattern(r0: Int, c0: Int) {
            for (r in 0 until 7) {
                for (c in 0 until 7) {
                    val border = r == 0 || r == 6 || c == 0 || c == 6
                    val center = r in 2..4 && c in 2..4
                    if (border || center) matrix[r0 + r][c0 + c] = true
                }
            }
        }
        drawFinderPattern(0, 0)
        drawFinderPattern(0, SIZE - 7) // 0, 14
        drawFinderPattern(SIZE - 7, 0) // 14, 0

        // Timing patterns: alternating dark/light on row 6 cols 8..12 and col 6 rows 8..12
        for (i in 8 until SIZE - 8) {
            matrix[6][i] = (i % 2 == 0)
            matrix[i][6] = (i % 2 == 0)
        }

        // Dark module (v1): (13, 8)
        matrix[13][8] = true

        // Place data modules in zigzag, skipping function modules
        var codeIdx = 0
        var bitPosInWord = 7
        var col = SIZE - 1
        var dirUp = true
        var remainderCell: Pair<Int, Int>? = null
        while (col > 0) {
            if (col == 6) col-- // skip vertical timing column
            var row = if (dirUp) SIZE - 1 else 0
            while (row in 0 until SIZE && row >= 0) {
                for (d in 0..1) {
                    val c = col - d
                    if (c < 0) continue
                    if (!func[row][c]) {
                        if (codeIdx >= all.size) {
                            // Remainder bits: v1 has 1 extra free module after 208 data bits.
                            // It stays light (false) — matches ISO 18004 remainder bit.
                            if (remainderCell == null) remainderCell = row to c
                            continue
                        }
                        val bit = ((all[codeIdx] shr bitPosInWord) and 1) == 1
                        matrix[row][c] = bit
                        bitPosInWord--
                        if (bitPosInWord < 0) {
                            bitPosInWord = 7
                            codeIdx++
                        }
                    }
                }
                row += if (dirUp) -1 else 1
            }
            dirUp = !dirUp
            col -= 2
        }

        // Apply Mask 0: dark if (row + col) % 2 == 0, skip function patterns AND the remainder bit
        val funcMask = makeFunctionPatterns()
        for (r in 0 until SIZE) {
            for (c in 0 until SIZE) {
                if (!funcMask[r][c]) {
                    if (remainderCell != null && r == remainderCell!!.first && c == remainderCell!!.second) {
                        continue // remainder bit is not masked
                    }
                    if ((r + c) % 2 == 0) {
                        matrix[r][c] = !matrix[r][c]
                    }
                }
            }
        }

        // Format info (15 bits, MSB first) placed around top-left & split top-right/bottom-left
        val fmt = formatInfoBits()
        val fmtBits = IntArray(15) { (fmt shr (14 - it)) and 1 }
        // Copy 1 (around top-left finder), bit 0 (LSB) first:
        //   bits 0..5 -> (8, 0..5)
        //   bit  6   -> (8, 7)
        //   bit  7   -> (7, 8)
        //   bits 8..13 -> (5..0, 8)  (bit8 at (5,8), bit13 at (0,8))
        //   bit 14 (MSB) -> (8, 8)
        for (i in 0..5) matrix[8][i] = fmtBits[i] == 1
        matrix[8][7] = fmtBits[6] == 1
        matrix[7][8] = fmtBits[7] == 1
        for (i in 0..5) matrix[5 - i][8] = fmtBits[8 + i] == 1
        matrix[8][8] = fmtBits[14] == 1
        // Copy 2 (top-right row 8 cols 13..20 = bits 14..7; bottom-left col 8 rows 20..14 = bits 0..6)
        for (i in 0..7) matrix[8][SIZE - 1 - i] = fmtBits[14 - i] == 1
        for (i in 0..6) matrix[SIZE - 1 - i][8] = fmtBits[6 - i] == 1

        return matrix
    }
}