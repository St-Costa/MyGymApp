package com.mygymapp.data.polar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [PolarSelfTest] — the Options → Debug on-device self-test. The checks
 * mirror `SessionMetricsTest` goldens by construction; what is pinned here is the
 * harness itself: every check passes on correct code, and the report renders one
 * OK line per check plus the live summary.
 */
class PolarSelfTestTest {

    @Test
    fun `all offline checks pass on correct code`() {
        val checks = PolarSelfTest.runOfflineChecks()
        assertEquals(10, checks.size)
        assertTrue(checks.all { it.passed })
    }

    @Test
    fun `report renders live summary plus one OK line per check`() {
        val checks = PolarSelfTest.runOfflineChecks()
        val report = PolarSelfTest.formatReport("Live: 58 campioni, media 112, max 148", checks)
        assertTrue(report.startsWith("Live: 58 campioni"))
        assertTrue(report.contains("10/10 OK"))
        assertEquals(10, report.lines().count { it.startsWith("OK ") })
    }

    @Test
    fun `report without live data still lists the formula checks`() {
        val report = PolarSelfTest.formatReport(null, PolarSelfTest.runOfflineChecks())
        assertTrue(report.startsWith("Check formule: 10/10 OK"))
    }
}
