package com.mygymapp.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [isCleartextToPublicHost] — the sync preflight must refuse to send the
 * bearer token over plain HTTP to a public host, while still allowing it on the private
 * networks the self-hosted setup actually runs on (LAN, Tailscale CGNAT/tailnet DNS,
 * loopback). Pure function, no Android needed.
 */
class SyncPreflightCleartextTest {

    @Test
    fun `https is never cleartext`() {
        assertFalse(isCleartextToPublicHost("https://gym-server.tailnet.ts.net"))
        assertFalse(isCleartextToPublicHost("https://example.com/sync"))
    }

    @Test
    fun `http on private networks is allowed`() {
        assertFalse(isCleartextToPublicHost("http://192.168.1.10:8080"))
        assertFalse(isCleartextToPublicHost("http://10.0.0.5/health"))
        assertFalse(isCleartextToPublicHost("http://172.16.4.2:9000"))
        assertFalse(isCleartextToPublicHost("http://100.64.0.1:8080"))
        assertFalse(isCleartextToPublicHost("http://100.100.200.30:8080"))
        assertFalse(isCleartextToPublicHost("http://localhost:8080/health"))
        assertFalse(isCleartextToPublicHost("http://127.0.0.1:8080"))
        assertFalse(isCleartextToPublicHost("http://gym-server.tailnet.ts.net"))
        assertFalse(isCleartextToPublicHost("http://nas.local:5000"))
    }

    @Test
    fun `http to public hosts is cleartext`() {
        assertTrue(isCleartextToPublicHost("http://example.com/sync"))
        assertTrue(isCleartextToPublicHost("http://203.0.113.7:8080/health"))
        assertTrue(isCleartextToPublicHost("http://8.8.8.8"))
    }

    @Test
    fun `172 dot-32 is public, 100 dot-128 is public`() {
        // Boundaries of 172.16/12 and 100.64/10 — just outside must be refused.
        assertTrue(isCleartextToPublicHost("http://172.32.0.1:8080"))
        assertTrue(isCleartextToPublicHost("http://100.128.0.1:8080"))
    }

    @Test
    fun `malformed url is not cleartext (own error path handles it)`() {
        assertFalse(isCleartextToPublicHost("not a url"))
        assertFalse(isCleartextToPublicHost(""))
    }
}
