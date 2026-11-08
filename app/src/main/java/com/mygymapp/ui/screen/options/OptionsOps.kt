package com.mygymapp.ui.screen.options

/**
 * Pure Options-screen decisions (ISM-26).
 *
 * The six async ops in `OptionsViewModel` (connection test, resync, restore,
 * backup-verify, ECG debug, step debug, Polar self-test) share the same
 * running/result shape but computed their transitions inline. The rules below
 * are the single definition of each decision — the ViewModel only executes
 * them (workers, countdown tickers and Health Connect reads stay there).
 */

/**
 * Fraction of the resync batch drained, 0..1. The denominator is the
 * just-enqueued total snapshotted once (never re-read mid-run); the numerator
 * clamps so a concurrently queued session can't push the bar backwards or
 * past full. An empty batch is trivially complete.
 */
internal fun resyncProgress(totalQueued: Int, stillPending: Int): Float {
    if (totalQueued <= 0) return 1f
    val drained = (totalQueued - stillPending).coerceIn(0, totalQueued)
    return drained.toFloat() / totalQueued
}

/** ECG-debug preconditions, checked in order. */
internal enum class EcgDebugGate { READY, NO_POLAR, NO_SERVER }

/**
 * Mirrors `sendDebugEcg`'s entry guards: a debug recording needs a live strap
 * first, then a configured server — same requirements as "Invia", since a
 * recording with nowhere to send it would just accumulate on disk.
 */
internal fun ecgDebugGate(polarConnected: Boolean, serverConfigured: Boolean): EcgDebugGate =
    when {
        !polarConnected -> EcgDebugGate.NO_POLAR
        !serverConfigured -> EcgDebugGate.NO_SERVER
        else -> EcgDebugGate.READY
    }

/**
 * Self-test live summary: a connected-but-silent strap is reported as silent
 * (not as zero), otherwise sample count + avg/min/max. Mirrors the
 * end-of-sampling branch of `runPolarSelfTest`.
 */
internal fun polarSelfTestLiveSummary(samples: List<Int>, totalSeconds: Int): String =
    if (samples.isEmpty()) {
        "Live: no HR samples in ${totalSeconds}s (strap connected but silent)"
    } else {
        "Live: ${samples.size} samples, avg ${samples.average().toInt()}, " +
            "min ${samples.min()}, max ${samples.max()}"
    }

/** Self-test button label: searching (strap hunt) vs sampling vs idle. */
internal fun polarSelfTestLabel(running: Boolean, searching: Boolean, secondsLeft: Int): String =
    when {
        running && searching -> "SEARCHING… ${secondsLeft}S"
        running -> "TESTING… ${secondsLeft}S"
        else -> "POLAR SELF-TEST"
    }
