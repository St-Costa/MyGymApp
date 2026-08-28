package com.mygymapp.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Records a Baseline Profile from the critical user journey — see
 * docs/CHANGELOG.md § Baseline Profile ("How", step 2).
 *
 * Generate (this module is `useConnectedDevices = true`):
 *   ./gradlew :app:generateBaselineProfile
 * or directly:
 *   ./gradlew :baseline-profile:connectedNonMinifiedReleaseAndroidTest
 *
 * Output (committed): app/src/nonMinifiedRelease/generated/baselineProfiles/baseline-prof.txt
 * plus a startup-prof.txt (includeInStartupProfile = true).
 *
 * The journey covers the shared cold code — WorkoutParser / MarkdownParser / snakeyaml,
 * repositories, base ViewModels, the Compose+Hilt+Navigation runtime — by touching the
 * home, a routine, an exercise screen, the session-progress screen, and both list screens
 * once each. The routine/exercise/session hops are wrapped defensively: on a device whose
 * data doesn't happen to surface a tappable target, the profile still generates (it just
 * covers slightly less) rather than failing the whole run.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        // The freshly (re)installed nonMinifiedRelease app has no runtime permissions, so
        // Home's LaunchedEffect fires system permission dialogs that would otherwise sit on
        // top of the app for the whole journey. Pre-grant them once.
        grantRuntimePermissions()

        rule.collect(
            packageName = PACKAGE_NAME,
            includeInStartupProfile = true,
            maxIterations = 12,
            stableIterations = 3,
        ) {
            pressHome()
            startActivityAndWait()
            dismissAnyPermissionDialog()

            // 1. Home: wait for the gitgraph to actually compose its rows.
            device.wait(Until.hasObject(By.res(GITGRAPH_RES_ID)), UI_TIMEOUT)
            device.waitForIdle()

            // 2. Open a routine + one exercise from within it (ActiveRoutineViewModel /
            //    RoutineParser / getExerciseStats / the exercise screens).
            runCatching { openRoutineAndOneExercise() }
            backToHome()

            // 3. Open a history square → SessionProgressScreen (getSessionsInRange over
            //    months, tonnage math) → back.
            runCatching { openHistorySquare() }
            backToHome()

            // 4. Routine list, then exercise list — first open of each pays the cold tax.
            openFromHomeByText("Routines")
            device.pressBack()
            device.wait(Until.hasObject(By.res(GITGRAPH_RES_ID)), UI_TIMEOUT)

            openFromHomeByText("Exercises")
            device.pressBack()
            device.wait(Until.hasObject(By.res(GITGRAPH_RES_ID)), UI_TIMEOUT)

            // 5. Options once (OptionsViewModel, sync-config read).
            openFromHomeByText("Opzioni")
            device.pressBack()
            device.wait(Until.hasObject(By.res(GITGRAPH_RES_ID)), UI_TIMEOUT)
        }
    }

    // ── journey helpers ──────────────────────────────────────────────────────

    private fun MacrobenchmarkScope.openFromHomeByText(label: String) {
        val tile = device.wait(Until.findObject(By.text(label)), UI_TIMEOUT) ?: return
        tile.click()
        device.waitForIdle()
        device.wait(Until.gone(By.res(GITGRAPH_RES_ID)), UI_TIMEOUT)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.openRoutineAndOneExercise() {
        openFromHomeByText("Routines")
        val row = device.wait(
            Until.findObject(By.textContains("exercises").clickable(true)),
            UI_TIMEOUT,
        ) ?: device.wait(Until.findObject(By.clazz("android.widget.Button").clickable(true)), UI_TIMEOUT)
        row?.click()
        device.waitForIdle()
        // RoutineEditScreen renders (RoutineParser + exercise resolution).
        device.wait(Until.hasObject(By.textContains("exercise")), UI_TIMEOUT)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.openHistorySquare() {
        device.wait(Until.hasObject(By.res(GITGRAPH_RES_ID)), UI_TIMEOUT)
        val graph = device.findObject(By.res(GITGRAPH_RES_ID)) ?: return
        val square = graph.findObjects(By.clickable(true)).firstOrNull() ?: return
        square.click()
        device.waitForIdle()
        device.wait(Until.gone(By.res(GITGRAPH_RES_ID)), UI_TIMEOUT)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.backToHome() {
        repeat(4) {
            if (device.hasObject(By.res(GITGRAPH_RES_ID))) return
            device.pressBack()
            device.waitForIdle()
        }
        device.wait(Until.hasObject(By.res(GITGRAPH_RES_ID)), UI_TIMEOUT)
    }

    private fun MacrobenchmarkScope.dismissAnyPermissionDialog() {
        // Belt-and-braces: click through any stacked system permission dialogs that still
        // appear despite the pre-grant. Matches the common allow/deny button ids and the
        // English + Italian button captions (this device is set to Italian).
        val allowIds = By.res(Pattern.compile("com\\.android\\.permissioncontroller:id/permission_(allow|deny).*"))
        val captions = Pattern.compile("(?i)(allow|consenti|deny|nega|non consentire|don.t allow).*")
        repeat(6) {
            val btn = device.wait(Until.findObject(allowIds), 1_200L)
                ?: device.wait(Until.findObject(By.text(captions).clickable(true)), 400L)
                ?: return
            btn.click()
            device.waitForIdle()
        }
    }

    private fun grantRuntimePermissions() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val ui = instr.uiAutomation
        RUNTIME_PERMISSIONS.forEach { perm ->
            runCatching {
                ui.executeShellCommand("pm grant $PACKAGE_NAME $perm").close()
            }
        }
        Thread.sleep(500)
    }

    private companion object {
        const val PACKAGE_NAME = "com.mygymapp"
        // testTag "gitgraph" surfaced as a resource-id by testTagsAsResourceId on the
        // NavHost — Compose exposes it as the bare tag string, no "pkg:id/" prefix.
        const val GITGRAPH_RES_ID = "gitgraph"
        const val UI_TIMEOUT = 10_000L
        val RUNTIME_PERMISSIONS = listOf(
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.ACCESS_FINE_LOCATION",
            // Home also launches Health Connect's step-read request; grantable via adb on
            // API 34+, which stops that Activity from covering the app during the journey.
            "android.permission.health.READ_STEPS",
        )
    }
}
