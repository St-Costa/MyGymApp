package com.mygymapp.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Duration of the UI tick shared by the home menu tiles and the +/− steppers.
 *
 * Deliberately a direct [Vibrator] one-shot rather than `LocalHapticFeedback`:
 * the Compose `LongPress` pulse is a ~20 ms tick that reads as "nothing" on
 * several devices, and `performHapticFeedback` is silently dropped when the
 * system touch-feedback setting is off. A 60 ms one-shot at default amplitude
 * is felt reliably on real hardware while still reading as a "tick".
 * `VIBRATE` is already declared in the manifest.
 */
const val HAPTIC_TICK_MS = 60L

fun Context.performHapticTick(durationMs: Long = HAPTIC_TICK_MS) {
    try {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: Throwable) {
        // No-op: haptics must never break interaction.
    }
}

/**
 * Stable `() -> Unit` tick for click handlers. In `pointerInput` blocks keyed
 * on `Unit` (never restarted) read it through `rememberUpdatedState`.
 */
@Composable
fun rememberHapticTick(): () -> Unit {
    val context = LocalContext.current
    return remember(context) { { context.performHapticTick() } }
}
