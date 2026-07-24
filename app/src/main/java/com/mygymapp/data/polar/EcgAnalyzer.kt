package com.mygymapp.data.polar

import android.util.Log
import java.io.DataInputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

data class EcgAnalysisResult(
    val beatsDetected: Int,
    val durationSeconds: Double,
    val avgHr: Double,
    val sessionRmssd: Double,
    val pacCount: Int,
    val pauseCount: Int,
    val irregularBeats: Int,
    val sdnn: Double,
    val pnn50: Double,
    val poincareSd1: Double,
    val poincareSd2: Double,
    val poincareRatio: Double,
    val afibSuspicionEpisodes: Int,
) {
    val hasAnything: Boolean get() = beatsDetected > 0
}

/**
 * Reads a recorded ECG file and extracts R-peaks (Pan-Tompkins simplified),
 * then derives RR intervals and simple arrhythmia markers.
 *
 * PAC = premature atrial contraction: short RR (<85% of local median) followed
 *       by compensatory long RR (>110% of median).
 * Pause = RR > 2000 ms.
 * Irregular = any RR that deviates >20% from local median but isn't a PAC.
 */
@Singleton
class EcgAnalyzer @Inject constructor() {
    companion object {
        private const val TAG = "EcgAnalyzer"
        private const val MAGIC = "MYGMECG1"
    }

    fun analyze(file: File): EcgAnalysisResult? {
        if (!file.exists()) {
            Log.w(TAG, "ECG analysis: file does not exist: ${file.absolutePath}")
            return null
        }
        if (file.length() < 32) {
            Log.w(TAG, "ECG analysis: file too small (${file.length()} bytes), header only")
            return null
        }

        val (sampleRate, voltages) = readFile(file) ?: return null
        if (voltages.size < sampleRate * 5) {
            Log.w(TAG, "ECG too short for analysis (${voltages.size} samples, ${voltages.size.toDouble() / sampleRate}s)")
            return null
        }
        Log.i(TAG, "ECG analyze: ${voltages.size} samples (${voltages.size.toDouble() / sampleRate}s @${sampleRate}Hz)")

        val peaks = panTompkinsDetect(voltages, sampleRate)
        if (peaks.size < 2) {
            Log.w(TAG, "ECG analyze: only ${peaks.size} peaks detected")
            return null
        }

        val rrIntervals = peaks.zipWithNext { a, b ->
            ((b - a).toDouble() / sampleRate * 1000.0).toInt()
        }.filter { it in 300..2500 }

        if (rrIntervals.size < 5) {
            Log.w(TAG, "ECG analyze: only ${rrIntervals.size} valid RR intervals out of ${peaks.size} peaks")
            return null
        }
        Log.i(TAG, "ECG analyze: ${peaks.size} peaks → ${rrIntervals.size} valid RR intervals")

        val durationSec = voltages.size.toDouble() / sampleRate
        val avgHr = 60000.0 / rrIntervals.average()

        // RMSSD across the whole session
        val diffs = rrIntervals.zipWithNext { a, b -> (b - a).toDouble().pow(2) }
        val rmssd = sqrt(diffs.average())

        // Arrhythmia markers: sliding window of local median.
        // Uneven requires TWO consecutive RRs deviating in the same direction,
        // to filter respiratory sinus arrhythmia and single-sample artifacts.
        var pacCount = 0
        var pauseCount = 0
        var irregularBeats = 0
        val windowSize = 10
        var uncountedConsecutive = false
        for (i in rrIntervals.indices) {
            val rr = rrIntervals[i]
            if (rr > 2000) {
                pauseCount++
                uncountedConsecutive = false
                continue
            }
            val start = (i - windowSize / 2).coerceAtLeast(0)
            val end = (start + windowSize).coerceAtMost(rrIntervals.size)
            if (end - start < 5) { uncountedConsecutive = false; continue }
            val localMedian = rrIntervals.subList(start, end).sorted().let { it[it.size / 2] }

            if (rr < localMedian * 0.85) {
                val next = rrIntervals.getOrNull(i + 1)
                if (next != null && next > localMedian * 1.10) {
                    pacCount++
                    uncountedConsecutive = false
                } else {
                    // Single short RR without compensatory pause — skip (not confirmed)
                    uncountedConsecutive = false
                }
            } else if (abs(rr - localMedian) > localMedian * 0.20 && rr > localMedian) {
                if (uncountedConsecutive) {
                    // Confirm both the previous and current beat
                    irregularBeats += 2
                    uncountedConsecutive = false
                } else {
                    uncountedConsecutive = true
                }
            } else {
                uncountedConsecutive = false
            }
        }

        // SDNN — overall HRV
        val meanRR = rrIntervals.average()
        val sdnn = sqrt(rrIntervals.map { (it - meanRR).pow(2) }.average())

        // pNN50 — % of consecutive RR pairs differing by >50ms
        val pairs = rrIntervals.zipWithNext()
        val nn50 = pairs.count { (a, b) -> abs(a - b) > 50 }
        val pnn50 = if (pairs.isNotEmpty()) (nn50.toDouble() / pairs.size) * 100.0 else 0.0

        // Poincare SD1 / SD2
        val sd1 = sqrt(0.5) * sqrt(pairs.map { (a, b) -> (b - a).toDouble().pow(2) }.average())
        val sd2Squared = 2 * sdnn.pow(2) - 0.5 * pairs.map { (a, b) -> (b - a).toDouble().pow(2) }.average()
        val sd2 = if (sd2Squared > 0) sqrt(sd2Squared) else 0.0
        val poincareRatio = if (sd1 > 0) sd2 / sd1 else 0.0

        // AFib screening: sustained RR irregularity without repetitive pattern.
        // Sliding window of 30 consecutive RR intervals; high coefficient of variation
        // + low autocorrelation at lag 1 suggests AFib-like rhythm.
        var afibEpisodes = 0
        val windowLen = 30
        if (rrIntervals.size >= windowLen) {
            var inEpisode = false
            var i = 0
            while (i + windowLen <= rrIntervals.size) {
                val w = rrIntervals.subList(i, i + windowLen)
                val wMean = w.average()
                val wSd = sqrt(w.map { (it - wMean).pow(2) }.average())
                val cv = if (wMean > 0) wSd / wMean else 0.0
                // Lag-1 autocorrelation: in AFib it's near 0 (random), in sinus near positive
                val wPairs = w.zipWithNext()
                val covariance = wPairs.map { (a, b) -> (a - wMean) * (b - wMean) }.average()
                val autoCorr = if (wSd > 0) covariance / (wSd * wSd) else 0.0

                val irregularWindow = cv > 0.12 && abs(autoCorr) < 0.20
                if (irregularWindow && !inEpisode) {
                    afibEpisodes++
                    inEpisode = true
                } else if (!irregularWindow) {
                    inEpisode = false
                }
                i += windowLen / 2 // 50% overlap
            }
        }

        return EcgAnalysisResult(
            beatsDetected = peaks.size,
            durationSeconds = durationSec,
            avgHr = avgHr,
            sessionRmssd = rmssd,
            pacCount = pacCount,
            pauseCount = pauseCount,
            irregularBeats = irregularBeats,
            sdnn = sdnn,
            pnn50 = pnn50,
            poincareSd1 = sd1,
            poincareSd2 = sd2,
            poincareRatio = poincareRatio,
            afibSuspicionEpisodes = afibEpisodes,
        )
    }

    private fun readFile(file: File): Pair<Int, IntArray>? {
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val magic = ByteArray(8)
                input.readFully(magic)
                if (String(magic) != MAGIC) {
                    Log.e(TAG, "Bad magic in ECG file: ${String(magic)}")
                    return null
                }
                val sampleRate = input.readInt()
                input.readLong() // start timestamp (unused for now)
                val remaining = file.length() - 20
                val sampleCount = (remaining / 2).toInt()
                val voltages = IntArray(sampleCount)
                for (i in 0 until sampleCount) {
                    voltages[i] = input.readShort().toInt()
                }
                sampleRate to voltages
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read ECG file", e)
            null
        }
    }

    /** Simplified Pan-Tompkins R-peak detection. */
    private fun panTompkinsDetect(voltages: IntArray, sampleRate: Int): List<Int> {
        val n = voltages.size
        if (n < sampleRate * 2) return emptyList()

        // Step 1: crude bandpass via (low-pass 15Hz then high-pass 5Hz) implemented as moving averages
        val asDouble = DoubleArray(n) { voltages[it].toDouble() }
        val lp = movingAverage(asDouble, (sampleRate / 15).coerceAtLeast(2))
        // Pre-compute the long MA ONCE (previous code called it inside the lambda → O(n²))
        val lpLongMa = movingAverage(lp, sampleRate / 5)
        val hp = DoubleArray(n) { lp[it] - lpLongMa[it] }

        // Step 2: derivative
        val deriv = DoubleArray(n) { i ->
            if (i < 2 || i >= n - 2) 0.0
            else (-hp[i - 2] - 2 * hp[i - 1] + 2 * hp[i + 1] + hp[i + 2]) / 8.0
        }

        // Step 3: square
        val squared = DoubleArray(n) { deriv[it] * deriv[it] }

        // Step 4: moving window integration (150ms)
        val winSize = (0.150 * sampleRate).toInt().coerceAtLeast(1)
        val integrated = movingAverage(squared, winSize)

        // Step 5: adaptive threshold peak detection with refractory period
        val peaks = mutableListOf<Int>()
        val refractory = (0.25 * sampleRate).toInt() // 250 ms
        val maxVal = integrated.max()
        if (maxVal <= 0) return emptyList()
        var threshold = maxVal * 0.3
        var lastPeak = -refractory - 1
        for (i in 1 until n - 1) {
            if (integrated[i] > threshold &&
                integrated[i] > integrated[i - 1] &&
                integrated[i] >= integrated[i + 1] &&
                i - lastPeak > refractory
            ) {
                peaks.add(i)
                lastPeak = i
                // Adapt threshold toward current peak
                threshold = max(maxVal * 0.1, 0.75 * threshold + 0.25 * integrated[i] * 0.4)
            }
        }
        return peaks
    }

    private fun movingAverage(data: DoubleArray, window: Int): DoubleArray {
        if (window <= 1) return data
        val n = data.size
        val out = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            sum += data[i]
            if (i >= window) sum -= data[i - window]
            out[i] = sum / minOf(window, i + 1)
        }
        return out
    }
}
