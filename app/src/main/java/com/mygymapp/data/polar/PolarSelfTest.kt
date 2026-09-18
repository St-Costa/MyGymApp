package com.mygymapp.data.polar

/**
 * On-device self-test for the session-metrics pipeline (Options → Debug).
 *
 * Runs the [SessionMetrics] kernels against fixed synthetic fixtures with known-good
 * outputs (the same golden values pinned by `SessionMetricsTest` on JVM) and reports
 * one PASS/FAIL line per check. Pure — no BLE, no clock, no disk — so it runs anywhere,
 * including on a release/minified build where it doubles as a smoke test that R8 kept
 * the math intact.
 *
 * The live half of the button (hunting the strap if needed, then collecting real HR
 * samples) lives in `OptionsViewModel.runPolarSelfTest` and only *reads*
 * `PolarManager` state; it never starts a session, writes history, or enqueues sync — pressing it any number of
 * times leaves the workout list untouched.
 */
object PolarSelfTest {

    data class Check(
        val name: String,
        val passed: Boolean,
        val detail: String,
    )

    private fun check(name: String, detail: String, passed: Boolean) = Check(name, passed, detail)

    /** All offline checks. Every [Check.passed] must be true on correct code. */
    fun runOfflineChecks(): List<Check> {
        val out = mutableListOf<Check>()

        // Drift: +1 BPM every 30 s = exactly +2.0 BPM/min.
        val rising = List(120) { i -> (i * 30_000L) to 100 + i }
        val drift = SessionMetrics.driftSlopeBpmPerMinute(rising)
        out += check("drift +2.0 BPM/min", "misurato=%.4f".format(drift), kotlin.math.abs(drift - 2.0) < 1e-9)

        val flat = List(60) { i -> (i * 6000L) to 130 }
        val flatDrift = SessionMetrics.driftSlopeBpmPerMinute(flat)
        out += check("drift piatto = 0", "misurato=%.4f".format(flatDrift), flatDrift == 0.0)

        // Keytel goldens: (-55.0969 + 0.6309*150 + 0.1988*80 + 0.2017*30)/4.184 ≈ 14.697 (M),
        // (-20.4022 + 0.4472*140 - 0.1263*65 + 0.074*30)/4.184 ≈ 8.656 (F).
        val kcalM = SessionMetrics.keytelKcalPerMinute(150, 80.0, 30, true)
        out += check("Keytel uomo ≈ 14.70", "misurato=%.3f".format(kcalM), kotlin.math.abs(kcalM - 14.697) < 1e-3)
        val kcalF = SessionMetrics.keytelKcalPerMinute(140, 65.0, 30, false)
        out += check("Keytel donna ≈ 8.66", "misurato=%.3f".format(kcalF), kotlin.math.abs(kcalF - 8.656) < 1e-3)

        // TRIMP golden: hrr = 0.5 → 0.5 * 0.64 * exp(1.92*0.5) ≈ 0.8357.
        val trimp = SessionMetrics.banisterTrimpPerMinute(125, 60, 190, true)
        out += check("TRIMP 50% HRR ≈ 0.8357", "misurato=%.4f".format(trimp), kotlin.math.abs(trimp - 0.8357) < 1e-4)
        val trimpRest = SessionMetrics.banisterTrimpPerMinute(60, 60, 190, true)
        out += check("TRIMP a riposo = 0", "misurato=%.4f".format(trimpRest), trimpRest == 0.0)

        // Peak-detection rules.
        val risingOk = SessionMetrics.isRisingHalf(listOf(100, 101, 102, 103, 110, 111, 112, 113)) &&
            !SessionMetrics.isRisingHalf(listOf(113, 112, 111, 110, 103, 102, 101, 100))
        out += check("finestra rising/falling", if (risingOk) "ok" else "KO", risingOk)
        val hrrOk = SessionMetrics.peakMeetsHrrThresholds(150, 60, 190) &&
            !SessionMetrics.peakMeetsHrrThresholds(100, 60, 190)
        out += check("soglie HRR ammetti/rifiuta", if (hrrOk) "ok" else "KO", hrrOk)

        return out
    }

    /** One-line-per-check report: `OK <name> (<detail>)` or `FAIL <name> (<detail>)`. */
    fun formatReport(liveSummary: String?, checks: List<Check>): String = buildString {
        if (liveSummary != null) {
            appendLine(liveSummary)
        }
        val failed = checks.count { !it.passed }
        appendLine("Check formule: ${checks.size - failed}/${checks.size} OK")
        for (c in checks) {
            appendLine("${if (c.passed) "OK" else "FAIL"} ${c.name} (${c.detail})")
        }
    }.trimEnd()
}
