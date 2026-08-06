package app.tsunagi.e46m3.launcher.vehicle.ds2

/**
 * BMW DS2 frame codec, ported from the verified TypeScript implementation in
 * `C:\Users\kazuh\E46M3-Diagnosis\packages\ds2-core\src\frame.ts`.
 *
 * Frame layout:  [address][length][control|status][payload...][xor checksum]
 *
 * `length` counts the WHOLE frame — address, length, control and checksum
 * included — which is the detail most reimplementations get wrong. The checksum
 * is a plain XOR over every preceding byte.
 *
 * ## This module is READ-ONLY by construction
 *
 * DS2 also carries WRITE_MEMORY (0x07), CLEAR_ADAPTATIONS (0x43) and a flash
 * path. None of them are here, and none of them belong here: this runs
 * unattended on a car's home screen. The only control byte it can emit is
 * READ_IO_STATUS, plus a keep-alive. If a future change needs to write to an
 * ECU, that belongs in a tool a human is deliberately operating — not in a
 * launcher that starts itself every time the ignition turns on.
 *
 * Note also `0x43` with payload `00 01` is NOT a clear on MSS54 — it starts
 * VANOS diagnostic idle mode. Another reason this stays read-only.
 */
object Ds2 {

    /** Address, length, control and checksum: the fixed cost of any frame. */
    const val MIN_FRAME_LENGTH = 4
    const val MAX_FRAME_LENGTH = 255

    /** MSS54 / MSS54HP engine DME. */
    const val ADDR_DME = 0x12

    /** The only control byte this module will emit for data. */
    const val CTRL_READ_IO_STATUS = 0x0b
    const val CTRL_KEEP_ALIVE = 0x9e

    const val STATUS_ACK = 0xa0
    const val STATUS_BUSY = 0xa1

    fun checksum(bytes: ByteArray, from: Int = 0, until: Int = bytes.size): Int {
        var x = 0
        for (i in from until until) x = x xor (bytes[i].toInt() and 0xff)
        return x and 0xff
    }

    fun buildFrame(address: Int, control: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val length = MIN_FRAME_LENGTH + payload.size
        require(length <= MAX_FRAME_LENGTH) { "DS2 frame too long: $length" }
        val frame = ByteArray(length)
        frame[0] = address.toByte()
        frame[1] = length.toByte()
        frame[2] = control.toByte()
        payload.copyInto(frame, 3)
        frame[length - 1] = checksum(frame, 0, length - 1).toByte()
        return frame
    }

    /** Read one live-measurement block. One exchange returns the whole block. */
    fun readBlock(selection: Int): ByteArray =
        buildFrame(ADDR_DME, CTRL_READ_IO_STATUS, byteArrayOf(selection.toByte()))

    fun keepAlive(): ByteArray = buildFrame(ADDR_DME, CTRL_KEEP_ALIVE)

    data class Frame(val address: Int, val status: Int, val payload: ByteArray) {
        // ByteArray in a data class needs these; the generated ones compare identity.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Frame && address == other.address &&
                status == other.status && payload.contentEquals(other.payload))

        override fun hashCode(): Int =
            (address * 31 + status) * 31 + payload.contentHashCode()
    }

    /**
     * Validates and unpacks a complete frame. Returns null rather than throwing
     * — a bad frame on a noisy K-line is an ordinary event, not an exception.
     */
    fun parseFrame(bytes: ByteArray): Frame? {
        if (bytes.size < MIN_FRAME_LENGTH) return null
        val declared = bytes[1].toInt() and 0xff
        if (declared != bytes.size) return null
        val expected = checksum(bytes, 0, bytes.size - 1)
        if (expected != (bytes[bytes.size - 1].toInt() and 0xff)) return null
        return Frame(
            address = bytes[0].toInt() and 0xff,
            status = bytes[2].toInt() and 0xff,
            payload = bytes.copyOfRange(3, bytes.size - 1),
        )
    }
}

/**
 * The MSS54 live-measurement channels this launcher reads.
 *
 * Offsets, formats and scales are transcribed from
 * `E46M3-Diagnosis/packages/ds2-mss54/src/liveValueBlocks.generated.ts`, which
 * was generated from a decompilation of MSS54DS0.prg. The block-3 layout is 35
 * bytes and every field below is an offset into it.
 *
 * **All five are now confirmed against the running car**, which they were not
 * when this was written. At a warm idle over the K+DCAN cable the DME returned:
 *
 *     rpm=888  coolant=75C  oil=75C  intake=50C  batt=13.5V
 *
 * — engine speed drifting 885..888 as an idle does, oil tracking coolant, an
 * intake heat-soaked well above ambient at a standstill, and a charging system
 * voltage. Every one of those is the right magnitude and the right sign, which
 * is what a decompiled offset table cannot tell you on its own.
 *
 * One block is one round trip, so reading four channels that all live in block 3
 * costs exactly as much as reading one.
 */
object Mss54Block3 {
    const val SELECTION = 3
    const val PAYLOAD_LENGTH = 35

    /** Engine speed, rpm. uint16 at offset 0, scale 1. */
    fun rpm(payload: ByteArray): Int? = u16(payload, 0)

    /** Engine (coolant) temperature, °C. uint8 at offset 11, offset -48. */
    fun coolantC(payload: ByteArray): Int? = u8(payload, 11)?.minus(48)

    /** Oil temperature, °C. uint8 at offset 12, offset -48. */
    fun oilC(payload: ByteArray): Int? = u8(payload, 12)?.minus(48)

    /** Intake air temperature, °C. uint8 at offset 10, offset -48. */
    fun intakeC(payload: ByteArray): Int? = u8(payload, 10)?.minus(48)

    /**
     * **Outside air temperature, °C.** uint8 at offset 15, offset -48.
     *
     * `tumg` / `Umgebungstemperatur`, and the generated table names it
     * "Ambient temperature **CAN**" — the DME does not measure this, it receives
     * it over the bus from the cluster. So this *is* the I-Bus outside
     * temperature; it simply arrives by asking the DME rather than by tapping
     * the bus.
     *
     * It was missed for an embarrassing length of time. This block was already
     * being read eleven times a second for the gauge, and offsets 10, 11, 12 and
     * 16 were taken out of it while 15 sat one byte away, which sent the search
     * off to `ITsCommon`, then to `ICarInfoService`, then to the idea of a
     * second physical tap on the I-Bus — three investigations that were all
     * looking for something already arriving in a buffer this code owned.
     */
    fun ambientC(payload: ByteArray): Int? = u8(payload, 15)?.minus(48)

    /**
     * Battery / main relay voltage, V. uint8 at offset 16, scale 0.1.
     *
     * Divided by ten rather than multiplied by 0.1f. The datum is an integer
     * count of tenths, and 0.1f is not exactly a tenth, so `134 * 0.1f` prints
     * as 13.400001 — which was on the car's own log before this line changed.
     */
    fun batteryV(payload: ByteArray): Float? = u8(payload, 16)?.div(10f)

    private fun u8(p: ByteArray, at: Int): Int? =
        if (at < p.size) p[at].toInt() and 0xff else null

    private fun u16(p: ByteArray, at: Int): Int? =
        if (at + 1 < p.size) ((p[at].toInt() and 0xff) shl 8) or (p[at + 1].toInt() and 0xff) else null
}
