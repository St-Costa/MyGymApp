package com.mygymapp.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AtomicFilesTest {

    @Test
    fun `writeTextAtomically creates file with exact content`() {
        val dir = Files.createTempDirectory("atomic-test").toFile()
        val target = File(dir, "sub/state.yml")

        AtomicFiles.writeTextAtomically(target, "sessions:\n  \"abc\":\n")

        assertEquals("sessions:\n  \"abc\":\n", target.readText())
    }

    @Test
    fun `writeTextAtomically overwrites existing file atomically`() {
        val dir = Files.createTempDirectory("atomic-overwrite").toFile()
        val target = File(dir, "state.yml")
        target.writeText("old-content")

        AtomicFiles.writeTextAtomically(target, "new-content")

        assertEquals("new-content", target.readText())
    }

    @Test
    fun `writeTextAtomically leaves no tmp files behind`() {
        val dir = Files.createTempDirectory("atomic-cleanup").toFile()
        val target = File(dir, "state.yml")

        repeat(5) { AtomicFiles.writeTextAtomically(target, "v$it") }

        assertEquals("v4", target.readText())
        assertTrue(dir.listFiles()!!.none { it.name.contains(".tmp-") })
    }

    @Test
    fun `writeBytesAtomically round-trips binary content`() {
        val dir = Files.createTempDirectory("atomic-bytes").toFile()
        val target = File(dir, "blob.bin")
        val bytes = ByteArray(4096) { it.toByte() }

        AtomicFiles.writeBytesAtomically(target, bytes)

        assertTrue(target.readBytes().contentEquals(bytes))
    }

    @Test
    fun `writeTextAtomically replaces corrupt content with valid content`() {
        // Regression proxy for the truncated-YAML scenario: whatever bytes were there
        // before, after one atomic write the file parses as exactly what was written.
        val dir = Files.createTempDirectory("atomic-corrupt").toFile()
        val target = File(dir, "state.yml")
        target.writeText("sessions:\n  \"abc\":\n    status: PEND")

        AtomicFiles.writeTextAtomically(target, "sessions:\n")

        assertEquals("sessions:\n", target.readText())
        assertFalse(target.readText().contains("PEND"))
    }
}
