package app.tsunagi.e46m3.launcher.vehicle.ds2

/**
 * Turns a byte stream off the K-line back into DS2 frames.
 *
 * Kept apart from the USB layer and free of Android types so it can be tested,
 * because the two things it has to survive are exactly the two things that are
 * awkward to reproduce with a car attached:
 *
 * ## The echo
 *
 * DS2 runs on a single-wire K-line, and a K+DCAN cable's transmitter is wired to
 * its own receiver. Everything sent comes straight back before the ECU answers.
 * That echo is a *valid* DS2 frame — same address, correct checksum — so it
 * cannot be rejected by validation; it has to be recognised. [feed] is told what
 * was transmitted and drops a byte-for-byte copy of it once.
 *
 * ## Resynchronisation
 *
 * Noise, a half-finished exchange from a previous session, or an ECU that was
 * mid-sentence when we opened the port all leave junk in front of the real
 * frame. Rather than give up, the parser walks forward one byte at a time until
 * a frame validates. `length` counting the whole frame and an XOR over every
 * preceding byte make a false positive unlikely: a random 4-byte run has about a
 * 1-in-65000 chance of passing both.
 */
class Ds2Framer {

    private var buffer = ByteArray(0)

    /** Bytes still unconsumed. Exposed for diagnostics, not for parsing. */
    val pending: Int get() = buffer.size

    fun reset() {
        buffer = ByteArray(0)
        echoToDrop = null
    }

    private var echoToDrop: ByteArray? = null

    /** Arms echo suppression for the next matching bytes seen. */
    fun expectEcho(transmitted: ByteArray) {
        echoToDrop = transmitted.copyOf()
    }

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        if (length <= 0) return
        buffer += bytes.copyOfRange(0, length)
    }

    /**
     * The next complete frame, or null if more bytes are needed.
     *
     * Call repeatedly until it returns null — one read can carry several frames.
     *
     * ## Why this scans every offset instead of sliding one byte at a time
     *
     * The obvious loop — look at the head, wait if `length` says the frame is
     * not all here yet, otherwise validate and slide on failure — deadlocks, and
     * a unit test caught it doing so. A corrupted length byte of 0xFF makes the
     * parser wait for 255 bytes that will never arrive, and it waits forever
     * while the real frame sits three bytes behind it.
     *
     * So an incomplete candidate is not allowed to block: the scan carries on
     * past it, and only if *no* offset yields a complete valid frame does the
     * buffer get trimmed — back to the earliest offset that could still complete
     * once more bytes arrive, so a genuinely split frame is never thrown away.
     */
    fun next(): Ds2.Frame? {
        dropEchoIfPresent()

        var earliestPending = -1
        var start = 0
        while (start + Ds2.MIN_FRAME_LENGTH <= buffer.size) {
            val declared = buffer[start + 1].toInt() and 0xff
            if (declared >= Ds2.MIN_FRAME_LENGTH) {
                if (start + declared > buffer.size) {
                    // Could still complete. Note it, but keep looking behind it.
                    if (earliestPending < 0) earliestPending = start
                } else {
                    val frame = Ds2.parseFrame(buffer.copyOfRange(start, start + declared))
                    if (frame != null) {
                        buffer = buffer.copyOfRange(start + declared, buffer.size)
                        return frame
                    }
                }
            }
            start++
        }

        // Nothing complete. Keep only what might still become a frame.
        val keepFrom = when {
            earliestPending >= 0 -> earliestPending
            // No candidate, but a header can straddle two reads.
            buffer.size >= Ds2.MIN_FRAME_LENGTH -> buffer.size - (Ds2.MIN_FRAME_LENGTH - 1)
            else -> 0
        }
        if (keepFrom > 0) buffer = buffer.copyOfRange(keepFrom, buffer.size)

        // A stuck length byte must not pin bytes here indefinitely.
        if (buffer.size > MAX_PENDING) {
            buffer = buffer.copyOfRange(buffer.size - MAX_PENDING, buffer.size)
        }
        return null
    }

    private fun dropEchoIfPresent() {
        val echo = echoToDrop ?: return
        if (buffer.size < echo.size) return
        for (i in echo.indices) if (buffer[i] != echo[i]) { echoToDrop = null; return }
        buffer = buffer.copyOfRange(echo.size, buffer.size)
        echoToDrop = null
    }

    companion object {
        /**
         * Ceiling on unconsumed bytes. A DS2 frame is at most 255, so twice that
         * leaves room for one whole frame behind one whole corrupted one while
         * still bounding a K-line that has gone to noise.
         */
        const val MAX_PENDING = 512
    }
}
