package com.mygymapp.data.sync

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SyncUrlValidatorTest {

    @Test
    fun `blank and malformed urls are rejected`() {
        assertNotNull(SyncUrlValidator.errorFor(""))
        assertNotNull(SyncUrlValidator.errorFor("   "))
        assertNotNull(SyncUrlValidator.errorFor("not a url"))
        assertNotNull(SyncUrlValidator.errorFor("ftp://example.com/x"))
    }

    @Test
    fun `https urls are accepted`() {
        assertNull(SyncUrlValidator.errorFor("https://gym-server.tailnet.ts.net"))
        assertNull(SyncUrlValidator.errorFor("https://gym-server.tailnet.ts.net/"))
    }

    @Test
    fun `http to private hosts is accepted, to public hosts rejected`() {
        assertNull(SyncUrlValidator.errorFor("http://192.168.1.10:8080"))
        assertNull(SyncUrlValidator.errorFor("http://100.64.0.1:8080"))
        assertNotNull(SyncUrlValidator.errorFor("http://example.com/sync"))
        assertNotNull(SyncUrlValidator.errorFor("http://203.0.113.7:8080"))
    }

    @Test
    fun `requestUrlOrNull never throws and returns null for bad input`() {
        assertNotNull(SyncUrlValidator.requestUrlOrNull("https://example.com", "v1", "sessions"))
        assertNull(SyncUrlValidator.requestUrlOrNull("not a url", "v1", "sessions"))
        assertNull(SyncUrlValidator.requestUrlOrNull("", "v1", "sessions"))
    }

    @Test
    fun `normalize trims whitespace and trailing slashes`() {
        assertNull(SyncUrlValidator.errorFor("  https://example.com/  "))
    }
}
