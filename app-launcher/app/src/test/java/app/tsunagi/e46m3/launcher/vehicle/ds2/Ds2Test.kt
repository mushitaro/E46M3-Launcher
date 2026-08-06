package app.tsunagi.e46m3.launcher.vehicle.ds2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Proves the DS2 port against telegrams that were established independently of
 * this code, so a mistake in the port cannot quietly agree with itself.
 *
 * Two of the fixtures come from `E46M3-Diagnosis`, where they were obtained by
 * statically extracting SGBD bytecode rather than by running this encoder:
 *
 *  - `12 05 0B 23 3F` — read live block 35 (VANOS/CSL)
 *  - `32 08 90 42 4D 57 05 F7` — SMG II seed/key request, ASCII "BMW" + level 5
 *
 * The second is deliberately included even though this launcher will never send
 * it: it exercises a different address, control byte and payload length, so it
 * catches errors the single-byte-payload case would hide.
 */
class Ds2Test {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `read block 35 matches the extracted telegram`() {
        assertArrayEquals(bytes(0x12, 0x05, 0x0B, 0x23, 0x3F), Ds2.readBlock(35))
    }

    @Test
    fun `seed key request matches the extracted telegram`() {
        // Different address, control and payload length than any request we send.
        val frame = Ds2.buildFrame(0x32, 0x90, bytes(0x42, 0x4D, 0x57, 0x05))
        assertArrayEquals(bytes(0x32, 0x08, 0x90, 0x42, 0x4D, 0x57, 0x05, 0xF7), frame)
    }

    @Test
    fun `read block 3 is the request this launcher actually sends`() {
        assertArrayEquals(bytes(0x12, 0x05, 0x0B, 0x03, 0x1F), Ds2.readBlock(3))
    }

    @Test
    fun `length counts the whole frame, not just the payload`() {
        // The detail most reimplementations get wrong.
        assertEquals(4, Ds2.buildFrame(0x12, 0x00).size)
        assertEquals(4, Ds2.buildFrame(0x12, 0x00)[1].toInt())
        assertEquals(9, Ds2.buildFrame(0x12, 0x0B, ByteArray(5))[1].toInt())
    }

    @Test
    fun `parse accepts a well formed response`() {
        val payload = bytes(0x0B, 0xB8, 0x11, 0x22)
        val raw = Ds2.buildFrame(Ds2.ADDR_DME, Ds2.STATUS_ACK, payload)
        val parsed = Ds2.parseFrame(raw)!!
        assertEquals(Ds2.ADDR_DME, parsed.address)
        assertEquals(Ds2.STATUS_ACK, parsed.status)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `parse rejects a corrupted checksum`() {
        val raw = Ds2.buildFrame(Ds2.ADDR_DME, Ds2.STATUS_ACK, bytes(0x01))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0xFF).toByte()
        assertNull(Ds2.parseFrame(raw))
    }

    @Test
    fun `parse rejects a length that disagrees with the buffer`() {
        val raw = Ds2.buildFrame(Ds2.ADDR_DME, Ds2.STATUS_ACK, bytes(0x01))
        raw[1] = 0x40
        assertNull(Ds2.parseFrame(raw))
    }

    @Test
    fun `block 3 decodes rpm as a big endian uint16 at offset 0`() {
        val payload = ByteArray(Mss54Block3.PAYLOAD_LENGTH)
        payload[0] = 0x0B; payload[1] = 0xB8.toByte()   // 3000
        assertEquals(3000, Mss54Block3.rpm(payload))
    }

    @Test
    fun `block 3 temperatures carry the minus 48 offset`() {
        val payload = ByteArray(Mss54Block3.PAYLOAD_LENGTH)
        payload[10] = 68     // intake  -> 20
        payload[11] = 138.toByte()  // coolant -> 90
        payload[12] = 158.toByte()  // oil     -> 110
        payload[16] = 138.toByte()  // battery -> 13.8V
        assertEquals(20, Mss54Block3.intakeC(payload))
        assertEquals(90, Mss54Block3.coolantC(payload))
        assertEquals(110, Mss54Block3.oilC(payload))
        assertEquals(13.8f, Mss54Block3.batteryV(payload)!!, 0.001f)
    }

    @Test
    fun `a short payload yields null rather than a wrong number`() {
        val truncated = ByteArray(4)
        assertNull(Mss54Block3.coolantC(truncated))
        assertNull(Mss54Block3.batteryV(truncated))
    }
}
