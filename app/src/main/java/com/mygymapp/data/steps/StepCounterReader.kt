package com.mygymapp.data.steps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One-shot reader for the device's hardware step counter (`TYPE_STEP_COUNTER`) — the same
 * always-on sensor backing Google Fit/Health Connect, not a service the app starts or stops.
 * It reports a value cumulative since the last device boot, so callers must diff two readings
 * themselves (see [com.mygymapp.data.steps.StepLedgerRepository]); this class only knows how
 * to fetch "the counter's value right now."
 *
 * No `ACTIVITY_RECOGNITION` permission (or the sensor not existing on this device, e.g. some
 * tablets/emulators) both surface as [readOnce] returning `null` rather than throwing — steps
 * are a nice-to-have alongside readiness, never something that should block or crash it.
 */
class StepCounterReader(private val context: Context) {

    /**
     * Reads the current cumulative step count, or `null` if the sensor is unavailable, the
     * permission hasn't been granted, or no reading arrives within [timeoutMs] (some OEMs
     * deliver the first event with a delay after registration).
     */
    suspend fun readOnce(timeoutMs: Long = 5_000L): Long? {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return null
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null

        return try {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sensorManager.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values[0].toLong())
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }

                cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }

                val registered = sensorManager.registerListener(
                    listener,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                )
                if (!registered) {
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }

                // Timeout guard: on some devices no event ever fires (e.g. permission
                // silently denied by a MIUI-style privacy toggle) — never hang the caller.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (cont.isActive) {
                        sensorManager.unregisterListener(listener)
                        cont.resume(null)
                    }
                }, timeoutMs)
            }
        } catch (e: SecurityException) {
            // Permission not granted — treat as "unavailable", not a crash.
            null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** Whether this device exposes the sensor at all, independent of permission state. */
        fun isAvailable(context: Context): Boolean {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                ?: return false
            return sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
        }
    }
}
