package com.fairylive.app.core.stream.rtmp

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * Handles standard RTMP protocol handshaking (C0+C1, S0+S1, C2, S2).
 */
object RtmpHandshake {

    const val RTMP_VERSION = 3
    const val HANDSHAKE_SIZE = 1536

    fun execute(input: InputStream, output: OutputStream) {
        val random = SecureRandom()
        val c1 = ByteArray(HANDSHAKE_SIZE)
        random.nextBytes(c1)
        // Set timestamp (4 bytes) to 0
        c1[0] = 0
        c1[1] = 0
        c1[2] = 0
        c1[3] = 0
        // Set zero field (4 bytes)
        c1[4] = 0
        c1[5] = 0
        c1[6] = 0
        c1[7] = 0

        // 1. Send C0 (1 byte) + C1 (1536 bytes)
        output.write(RTMP_VERSION)
        output.write(c1)
        output.flush()

        // 2. Read S0 (1 byte)
        val s0 = input.read()
        if (s0 < 0) throw IllegalStateException("Unexpected EOF reading S0")
        if (s0 != RTMP_VERSION) {
            // Non-fatal if server version matches 3, warn otherwise
        }

        // 3. Read S1 (1536 bytes)
        val s1 = ByteArray(HANDSHAKE_SIZE)
        readFully(input, s1)

        // 4. Send C2 (1536 bytes, echo of S1)
        output.write(s1)
        output.flush()

        // 5. Read S2 (1536 bytes, echo of C1)
        val s2 = ByteArray(HANDSHAKE_SIZE)
        readFully(input, s2)
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var total = 0
        while (total < buffer.size) {
            val count = input.read(buffer, total, buffer.size - total)
            if (count < 0) {
                throw IllegalStateException("Premature EOF during RTMP handshake ($total/${buffer.size} bytes read)")
            }
            total += count
        }
    }
}
