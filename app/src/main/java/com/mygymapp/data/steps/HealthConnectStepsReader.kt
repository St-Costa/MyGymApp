package com.mygymapp.data.steps

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.mygymapp.data.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads daily step totals via Health Connect rather than the raw `TYPE_STEP_COUNTER`
 * sensor. The sensor approach (this project's original implementation — see CHANGELOG) was
 * abandoned after real-device testing on Samsung/One UI: `dumpsys sensorservice` showed the
 * hardware sensor reporting `has sensor access: false` for this app even with
 * `ACTIVITY_RECOGNITION` granted and a listener held open for the app's whole process
 * lifetime. The actual gate turned out to be a separate OS-level "Health, fitness and
 * wellness" permission with no manual toggle reachable from Settings — Health Connect is the
 * only way to *request* that permission (there's no plain `ActivityResultContracts` for it).
 * This is very likely not Samsung-specific: modern Android increasingly routes health-ish
 * sensor data through Health Connect regardless of OEM, so this is the more future-proof
 * path even where the raw sensor would have worked.
 *
 * Bonus: Health Connect aggregates over an explicit time range natively
 * ([AggregateRequest]/[TimeRangeFilter]), so unlike the raw cumulative-since-boot sensor
 * value, there's no manual "diff two checkpoints, handle counter-went-backwards-on-reboot"
 * logic needed — see how much simpler [StepLedgerRepository] became.
 */
@Singleton
class HealthConnectStepsReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger,
) {
    companion object {
        private const val TAG = "HealthConnectStepsReader"
        val READ_STEPS_PERMISSION: String = HealthPermission.getReadPermission(StepsRecord::class)
    }

    /** Contract for requesting Health Connect permissions — Health Connect's own launcher, not [android.Manifest.permission]. */
    val permissionRequestContract = PermissionController.createRequestPermissionResultContract()

    /**
     * Whether Health Connect is installed and usable on this device. `false` on devices
     * without Play Services / without the Health Connect provider installed — callers must
     * treat that as "steps unavailable", not retry.
     */
    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client: HealthConnectClient? by lazy {
        if (!isAvailable()) null else HealthConnectClient.getOrCreate(context)
    }

    suspend fun hasReadPermission(): Boolean {
        val c = client ?: return false
        return try {
            READ_STEPS_PERMISSION in c.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            appLogger.i(TAG, "getGrantedPermissions failed: ${e.message}")
            false
        }
    }

    /**
     * Steps over the whole of yesterday in the device's local timezone (midnight to
     * midnight), or `null` if Health Connect couldn't answer. A complete calendar day, so
     * unlike the checkpoint diff in [StepLedgerRepository] the number doesn't depend on
     * what time the readiness test was taken.
     */
    suspend fun previousDayTotal(now: Instant = Instant.now()): Long? {
        val zone = java.time.ZoneId.systemDefault()
        val yesterday = now.atZone(zone).toLocalDate().minusDays(1)
        return totalSteps(
            yesterday.atStartOfDay(zone).toInstant(),
            yesterday.plusDays(1).atStartOfDay(zone).toInstant(),
        )
    }

    /**
     * Total steps recorded between [start] (inclusive) and [end] (exclusive), or `null` if
     * Health Connect is unavailable, the permission isn't granted, or the query itself
     * fails — same "null means no data, 0 means a real zero-step day" rule as the rest of
     * this feature (see SYNC.md).
     */
    suspend fun totalSteps(start: Instant, end: Instant): Long? {
        val c = client ?: return null
        return try {
            val response = c.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                )
            )
            response[StepsRecord.COUNT_TOTAL] ?: 0L
        } catch (e: Exception) {
            appLogger.i(TAG, "Steps aggregate query failed: ${e.message}")
            null
        }
    }
}
