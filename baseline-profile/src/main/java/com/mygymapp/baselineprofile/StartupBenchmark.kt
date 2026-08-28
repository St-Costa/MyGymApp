package com.mygymapp.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start delta measurement — see docs/CHANGELOG.md § Baseline Profile ("How", step 5).
 *
 * Run on the same device, back to back:
 *   ./gradlew :baseline-profile:connectedBenchmarkReleaseAndroidTest
 *
 * `startupNoCompilation` = profile OFF (CompilationMode.None), `startupBaselineProfile` =
 * profile REQUIRED. Compare `timeToInitialDisplay` between the two and record it in the
 * changelog. Target: ~670 ms → ~450–500 ms (home rows visible).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupNoCompilation() = measure(CompilationMode.None())

    @Test
    fun startupBaselineProfile() =
        measure(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun measure(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        // Hold the run open until the home's gitgraph has composed, so
        // timeToInitialDisplay reflects "history rows visible", not just the first frame.
        device.wait(Until.hasObject(By.res(GITGRAPH_RES_ID)), 10_000L)
    }

    private companion object {
        const val PACKAGE_NAME = "com.mygymapp"
        const val GITGRAPH_RES_ID = "gitgraph"
    }
}
