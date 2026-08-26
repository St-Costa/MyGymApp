package com.mygymapp.data.scale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [VtrumpSenheProtocol.parse]: the raw BLE notify-frame decoder for the VitaFit
 * VT701 scale. See the class kdoc for the reverse-engineered frame shape this mirrors.
 */
class VtrumpSenheProtocolTest {

    private fun byte(v: Int): Byte = v.toByte()

    @Test
    fun `weight frame decodes big-endian weight scaled by decimal accuracy`() {
        // [7] = 0x11 -> high nibble accuracy=1 (one decimal), low nibble unit=1 (KG, since
        // DisplayUnit.fromCode only special-cases 0/2/3 and defaults everything else to KG).
        // weightRaw = 0x02 0xEE = 750 -> /10^1 = 75.0 kg. weightType=3 -> stable.
        val payload = byteArrayOf(
            byte(0x5A), byte(0), byte(0), byte(0x10), byte(3),
            byte(0), byte(0), byte(0x11), byte(0x02), byte(0xEE),
        )

        val reading = VtrumpSenheProtocol.parse(payload)

        requireNotNull(reading)
        assertEquals(75.0, reading.weightKg!!, 0.001)
        assertTrue(reading.weightStable)
        assertNull(reading.impedanceOhm)
        assertEquals(DisplayUnit.KG, reading.displayUnit)
    }

    @Test
    fun `weight frame with weightType other than 3 is not stable`() {
        val payload = byteArrayOf(
            byte(0x5A), byte(0), byte(0), byte(0x10), byte(1),
            byte(0), byte(0), byte(0x00), byte(0x00), byte(0x64),
        )

        val reading = VtrumpSenheProtocol.parse(payload)

        requireNotNull(reading)
        assertEquals(100.0, reading.weightKg!!, 0.001) // accuracy=0 -> no scaling
        assertEquals(false, reading.weightStable)
    }

    @Test
    fun `body composition frame decodes impedance`() {
        // impedanceRaw = 0x01 0x2C = 300 ohm
        val payload = byteArrayOf(
            byte(0x5A), byte(0), byte(0), byte(0x11), byte(0),
            byte(0), byte(0), byte(0), byte(0), byte(0x01), byte(0x2C),
        )

        val reading = VtrumpSenheProtocol.parse(payload)

        requireNotNull(reading)
        assertNull(reading.weightKg)
        assertEquals(300, reading.impedanceOhm)
    }

    @Test
    fun `body composition frame treats 0xFFFF and 0x55AA impedance as no reading yet`() {
        val sentinelFfff = byteArrayOf(
            byte(0x5A), byte(0), byte(0), byte(0x11), byte(0),
            byte(0), byte(0), byte(0), byte(0), byte(0xFF), byte(0xFF),
        )
        val sentinel55aa = byteArrayOf(
            byte(0x5A), byte(0), byte(0), byte(0x11), byte(0),
            byte(0), byte(0), byte(0), byte(0), byte(0x55), byte(0xAA),
        )

        assertNull(VtrumpSenheProtocol.parse(sentinelFfff)!!.impedanceOhm)
        assertNull(VtrumpSenheProtocol.parse(sentinel55aa)!!.impedanceOhm)
    }

    @Test
    fun `unknown command returns null`() {
        val payload = byteArrayOf(byte(0x5A), byte(0), byte(0), byte(0x99), byte(0))

        assertNull(VtrumpSenheProtocol.parse(payload))
    }

    @Test
    fun `bad header byte returns null`() {
        val payload = byteArrayOf(byte(0x00), byte(0), byte(0), byte(0x10), byte(3), byte(0), byte(0), byte(0), byte(0), byte(0))

        assertNull(VtrumpSenheProtocol.parse(payload))
    }

    @Test
    fun `empty payload returns null without throwing`() {
        assertNull(VtrumpSenheProtocol.parse(byteArrayOf()))
    }

    @Test
    fun `truncated payload shorter than minimum length returns null without throwing`() {
        val payload = byteArrayOf(byte(0x5A), byte(0), byte(0), byte(0x10))

        assertNull(VtrumpSenheProtocol.parse(payload))
    }

    @Test
    fun `weight frame truncated before the weight bytes returns null without throwing`() {
        val payload = byteArrayOf(byte(0x5A), byte(0), byte(0), byte(0x10), byte(3), byte(0), byte(0), byte(0))

        assertNull(VtrumpSenheProtocol.parse(payload))
    }

    @Test
    fun `DisplayUnit fromCode maps known codes and defaults unknown ones to KG`() {
        assertEquals(DisplayUnit.JIN, DisplayUnit.fromCode(0))
        assertEquals(DisplayUnit.LB, DisplayUnit.fromCode(2))
        assertEquals(DisplayUnit.STONE, DisplayUnit.fromCode(3))
        assertEquals(DisplayUnit.KG, DisplayUnit.fromCode(1))
        assertEquals(DisplayUnit.KG, DisplayUnit.fromCode(99))
    }
}
