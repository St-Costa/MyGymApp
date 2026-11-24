package com.mygymapp.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Round-trip + tolerance coverage for [DeviceBackupParser] (`exercises/_devices.md`). */
class DeviceBackupParserTest {

    @Test
    fun `both devices round-trip`() {
        val ids = DeviceBackupIds(polarDeviceId = "A1B2C3D4", scaleAddress = "AA:BB:CC:DD:EE:FF")

        val result = DeviceBackupParser.fromMarkdown(DeviceBackupParser.toMarkdown(ids))

        assertEquals(ids, result)
    }

    @Test
    fun `single device stays present, other stays absent`() {
        val polarOnly = DeviceBackupIds(polarDeviceId = "A1B2C3D4")
        val scaleOnly = DeviceBackupIds(scaleAddress = "AA:BB:CC:DD:EE:FF")

        assertEquals(polarOnly, DeviceBackupParser.fromMarkdown(DeviceBackupParser.toMarkdown(polarOnly)))
        assertEquals(scaleOnly, DeviceBackupParser.fromMarkdown(DeviceBackupParser.toMarkdown(scaleOnly)))
    }

    @Test
    fun `empty file yields null for caller fallback`() {
        assertNull(DeviceBackupParser.fromMarkdown(""))
        assertNull(DeviceBackupParser.fromMarkdown("---\n---\n"))
    }

    @Test
    fun `file with neither device yields null, never an empty overwrite`() {
        val markdown = """
            ---
            type: "device-ids"
            version: 1
            ---
        """.trimIndent()

        assertNull(DeviceBackupParser.fromMarkdown(markdown))
    }

    @Test
    fun `blank value reads as unset, other device survives`() {
        val markdown = """
            ---
            type: "device-ids"
            version: 1
            polarDeviceId: "   "
            scaleAddress: "AA:BB:CC:DD:EE:FF"
            ---
        """.trimIndent()

        assertEquals(
            DeviceBackupIds(scaleAddress = "AA:BB:CC:DD:EE:FF"),
            DeviceBackupParser.fromMarkdown(markdown),
        )
    }
}
