package app.tsunagi.e46m3.launcher.vehicle.ds2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The K-line reader, exercised against the two things that actually happen on a
 * single-wire bus: our own transmission coming back, and junk in front of the
 * answer.
 */
class Ds2FramerTest {

    /** What we send to ask the DME for live block 3: 12 05 0B 03 1F. */
    private val request = Ds2.readBlock(Mss54Block3.SELECTION)

    /** A plausible reply: address 0x12, ACK, 35-byte payload. */
    private fun reply(rpm: Int): ByteArray {
        val payload = ByteArray(Mss54Block3.PAYLOAD_LENGTH)
        payload[0] = (rpm shr 8).toByte()
        payload[1] = (rpm and 0xff).toByte()
        return Ds2.buildFrame(Ds2.ADDR_DME, Ds2.STATUS_ACK, payload)
    }

    @Test
    fun `reads a clean reply`() {
        val f = Ds2Framer()
        f.feed(reply(3500))
        val frame = f.next()!!
        assertEquals(Ds2.ADDR_DME, frame.address)
        assertEquals(Ds2.STATUS_ACK, frame.status)
        assertEquals(3500, Mss54Block3.rpm(frame.payload))
        assertNull(f.next())
    }

    @Test
    fun `drops the K-line echo of our own request`() {
        val f = Ds2Framer()
        f.expectEcho(request)
        f.feed(request + reply(1200))
        val frame = f.next()!!
        assertEquals(Ds2.STATUS_ACK, frame.status)
        assertEquals(1200, Mss54Block3.rpm(frame.payload))
    }

    @Test
    fun `without echo suppression the request would itself parse as a frame`() {
        // This is why expectEcho exists: the echo is a structurally valid frame.
        val f = Ds2Framer()
        f.feed(request)
        val frame = f.next()!!
        assertEquals(Ds2.ADDR_DME, frame.address)
        assertEquals(Ds2.CTRL_READ_IO_STATUS, frame.status)
    }

    @Test
    fun `resynchronises past leading junk`() {
        val f = Ds2Framer()
        f.feed(byteArrayOf(0x00, 0xFF.toByte(), 0x7E, 0x01) + reply(6800))
        assertEquals(6800, Mss54Block3.rpm(f.next()!!.payload))
    }

    @Test
    fun `waits for a frame split across reads`() {
        val f = Ds2Framer()
        val whole = reply(900)
        f.feed(whole.copyOfRange(0, 10))
        assertNull(f.next())
        f.feed(whole.copyOfRange(10, whole.size))
        assertEquals(900, Mss54Block3.rpm(f.next()!!.payload))
    }

    @Test
    fun `returns several frames from one read`() {
        val f = Ds2Framer()
        f.feed(reply(1000) + reply(2000))
        assertEquals(1000, Mss54Block3.rpm(f.next()!!.payload))
        assertEquals(2000, Mss54Block3.rpm(f.next()!!.payload))
        assertNull(f.next())
    }

    @Test
    fun `a corrupted checksum is skipped, not returned`() {
        val f = Ds2Framer()
        val bad = reply(4000).also { it[5] = (it[5] + 1).toByte() }
        f.feed(bad)
        // Every alignment fails, so nothing is emitted and the buffer drains.
        assertNull(f.next())
    }

    @Test
    fun `a bad frame does not swallow the good one behind it`() {
        val f = Ds2Framer()
        val bad = reply(4000).also { it[5] = (it[5] + 1).toByte() }
        f.feed(bad + reply(5500))
        assertEquals(5500, Mss54Block3.rpm(f.next()!!.payload))
    }

    @Test
    fun `reset clears both the buffer and a pending echo`() {
        val f = Ds2Framer()
        f.expectEcho(request)
        f.feed(byteArrayOf(0x12, 0x05))
        f.reset()
        assertEquals(0, f.pending)
        f.feed(reply(2500))
        assertEquals(2500, Mss54Block3.rpm(f.next()!!.payload))
    }

    @Test
    fun `the request we transmit is the telegram the catalogue specifies`() {
        assertArrayEquals(
            byteArrayOf(0x12, 0x05, 0x0B, 0x03, 0x1F),
            request,
        )
    }
}
