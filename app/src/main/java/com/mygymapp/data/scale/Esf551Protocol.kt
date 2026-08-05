package com.mygymapp.data.scale

import java.util.UUID

/**
 * VitaFit VT701 speaks the VTrump "SenHe" scale protocol (bundled in the
 * VitaFit app as vtble-scale-sdk-android-v4.2.6, class VTDeviceScaleSenhe).
 * Reverse-engineered by decompiling the VitaFit APK and cross-checking
 * against real device captures — see docs/POLAR.md-style notes in this
 * package for context. Not derived from any GPL/proprietary source; only
 * the wire format (a fact, not copyrightable expression) is reproduced.
 */
object VtrumpSenheProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val HEADER: Byte = 0x5A
    private const val CMD_WEIGHT: Int = 0x10
    private const val CMD_BODY_COMPOSITION: Int = 0x11

    /** Sentinels the scale sends in the impedance field when no valid BIA reading exists. */
    private const val IMPEDANCE_SENTINEL_NONE_1 = 0xFFFF
    private const val IMPEDANCE_SENTINEL_NONE_2 = 0x55AA

    /**
     * Parses a raw notify payload from the fff1 characteristic.
     *
     * Common frame shape: [0]=0x5a header, [1]=length, [2]=deviceType,
     * [3]=command, [4]=status/type byte (weight-lock flag for CMD_WEIGHT,
     * differs by command), trailing byte=0xaa.
     *
     * CMD_WEIGHT (0x10): [7] low nibble=unit, high nibble=decimal accuracy;
     * [8:10]=weight as big-endian uint16, divided by 10^accuracy.
     * weightStable is true when [4]==3 (locked/settled reading).
     *
     * CMD_BODY_COMPOSITION (0x11): [9:11]=impedance as big-endian uint16,
     * sentinel 0xffff/0x55aa means "no valid BIA contact yet".
     */
    fun parse(payload: ByteArray): ScaleReading? {
        if (payload.isEmpty() || payload[0] != HEADER) return null
        if (payload.size < 5) return null
        val command = payload[3].toInt() and 0xFF

        return when (command) {
            CMD_WEIGHT -> parseWeight(payload)
            CMD_BODY_COMPOSITION -> parseBodyComposition(payload)
            else -> null
        }
    }

    private fun parseWeight(payload: ByteArray): ScaleReading? {
        if (payload.size < 10) return null
        val weightType = payload[4].toInt() and 0xFF
        val accuracyRaw = (payload[7].toInt() and 0xFF) shr 4
        val unitCode = payload[7].toInt() and 0x0F
        val weightRaw = ((payload[8].toInt() and 0xFF) shl 8) or (payload[9].toInt() and 0xFF)
        val weightKg = weightRaw / Math.pow(10.0, accuracyRaw.toDouble())
        return ScaleReading(
            weightKg = weightKg,
            weightStable = weightType == 3,
            impedanceOhm = null,
            displayUnit = DisplayUnit.fromCode(unitCode),
        )
    }

    private fun parseBodyComposition(payload: ByteArray): ScaleReading? {
        if (payload.size < 11) return null
        val impedanceRaw = ((payload[9].toInt() and 0xFF) shl 8) or (payload[10].toInt() and 0xFF)
        val impedance = if (impedanceRaw == IMPEDANCE_SENTINEL_NONE_1 || impedanceRaw == IMPEDANCE_SENTINEL_NONE_2) {
            null
        } else {
            impedanceRaw
        }
        return ScaleReading(
            weightKg = null,
            weightStable = true,
            impedanceOhm = impedance,
            displayUnit = null,
        )
    }
}

enum class DisplayUnit {
    KG,
    LB,
    JIN,
    STONE,
    ;

    companion object {
        fun fromCode(code: Int): DisplayUnit = when (code) {
            0 -> JIN
            2 -> LB
            3 -> STONE
            else -> KG
        }
    }
}

data class ScaleReading(
    val weightKg: Double?,
    val weightStable: Boolean,
    val impedanceOhm: Int?,
    val displayUnit: DisplayUnit?,
)
