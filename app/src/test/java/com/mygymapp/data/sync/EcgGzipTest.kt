package com.mygymapp.data.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Round-trip coverage for [gzipEcg] — the `POST /v1/ecg` payload compressor. Extracted
 * top-level (not a worker method) precisely so it can be tested without a WorkManager
 * harness; the worker contract is "server gunzips exactly these bytes".
 */
class EcgGzipTest {

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    @Test
    fun `round-trips arbitrary bytes`() {
        val raw = ByteArray(4096) { (it * 31 % 251).toByte() }
        assertArrayEquals(raw, gunzip(gzipEcg(raw)))
    }

    @Test
    fun `round-trips empty input`() {
        assertArrayEquals(ByteArray(0), gunzip(gzipEcg(ByteArray(0))))
    }

    @Test
    fun `output is a real gzip stream`() {
        val out = gzipEcg("MYGMECG1-payload".toByteArray())
        assertTrue(out.size >= 2)
        assertEquals(0x1f.toByte(), out[0])
        assertEquals(0x8b.toByte(), out[1])
    }

    @Test
    fun `highly repetitive ECG-like data compresses smaller`() {
        val raw = ByteArray(10_000) { 0x07 }
        assertTrue(gzipEcg(raw).size < raw.size)
    }
}
