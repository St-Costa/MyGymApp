package com.mygymapp.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Duration of the UI tick shared by the home menu tiles and the +/− steppers.
 * Only used as the fallback one-shot length when the device has no predefined
 * `EFFECT_HEAVY_CLICK` (see [performHapticTick]).
 *
 * Deliberately a direct [Vibrator] call rather than `LocalHapticFeedback`:
 * the Compose `LongPress` pulse is a ~20 ms tick that reads as "nothing" on
 * several devices, and `performHapticFeedback` is silently dropped when the
 * system touch-feedback setting is off. `VIBRATE` is already declared in the
 * manifest.
 *
 * Uses `USAGE_HARDWARE_FEEDBACK` rather than the default `TOUCH` usage: the
 * system silently mutes `TOUCH` vibrations when the user's "vibrate on touch"
 * setting is off, which would mute this tick for a reason unrelated to it.
 */
const val HAPTIC_TICK_MS = 60L

private val hapticTickAttributes: VibrationAttributes? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
    } else {
        null
    }

fun Context.performHapticTick(durationMs: Long = HAPTIC_TICK_MS) {
    try {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        if (vibrator == null) return
        // A predefined HEAVY_CLICK is tuned per-device and reads much more
        // distinctly on real hardware than a generic amplitude one-shot,
        // which several linear motors (e.g. Samsung) render as a faint buzz.
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            vibrator.areEffectsSupported(VibrationEffect.EFFECT_HEAVY_CLICK)[0] ==
            Vibrator.VIBRATION_EFFECT_SUPPORT_YES
        ) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        } else {
            VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        val attrs = hapticTickAttributes
        if (attrs != null) {
            vibrator.vibrate(effect, attrs)
        } else {
            vibrator.vibrate(effect)
        }
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
