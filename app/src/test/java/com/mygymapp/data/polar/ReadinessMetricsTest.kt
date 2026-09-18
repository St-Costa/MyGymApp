package com.mygymapp.data.polar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Formula coverage for [ReadinessMetrics] — the pure kernels behind the morning HRV
 * measurement (artifact filter, RMSSD, Uth VO2max). Extracted from `PolarManager` so
 * the numbers are pinned by tests instead of only by morning manual runs.
 */
class ReadinessMetricsTest {

    @Test
    fun `out-of-range intervals are dropped`() {
        assertEquals(
            listOf(800, 810, 820),
            ReadinessMetrics.filterArtifacts(listOf(800, 250, 2500, 810, 820)),
        )
    }

    @Test
    fun `median deviants are dropped`() {
        assertEquals(
            listOf(800, 810, 820),
            ReadinessMetrics.filterArtifacts(listOf(800, 810, 820, 1500)),
        )
    }

    @Test
    fun `fewer than three in-range samples pass through untouched`() {
        assertEquals(listOf(800, 810), ReadinessMetrics.filterArtifacts(listOf(800, 810)))
        assertEquals(emptyList<Int>(), ReadinessMetrics.filterArtifacts(listOf(100, 3000)))
    }

    @Test
    fun `rmssd golden value`() {
        // diffs 20, -30, 20 → squares 400, 900, 400 → mean 566.67 → sqrt ≈ 23.8048
        assertEquals(23.8048, ReadinessMetrics.calculateRMSSD(listOf(800, 820, 790, 810)), 1e-3)
    }

    @Test
    fun `rmssd needs two samples`() {
        assertEquals(0.0, ReadinessMetrics.calculateRMSSD(emptyList()), 0.0)
        assertEquals(0.0, ReadinessMetrics.calculateRMSSD(listOf(800)), 0.0)
    }

    @Test
    fun `vo2max golden value and null case`() {
        // 15.3 * (190/50) = 58.14
        assertEquals(58.14, ReadinessMetrics.uthVo2max(190, 50)!!, 1e-9)
        assertNull(ReadinessMetrics.uthVo2max(190, 0))
        assertNull(ReadinessMetrics.uthVo2max(190, -5))
    }
}
