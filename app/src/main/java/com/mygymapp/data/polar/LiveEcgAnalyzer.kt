package com.mygymapp.data.polar

import kotlin.math.abs

/**
 * Incremental (streaming) version of Pan-Tompkins R-peak detection and
 * RR-based irregularity classification. State is kept across samples so
 * per-sample cost is O(1) aside from the two moving-average windows.
 */
class LiveEcgAnalyzer(private val sampleRate: Int = 130) {

    // Pan-Tompkins pipeline state
    private val lpWindow = (sampleRate / 15).coerceAtLeast(2)
    private val hpWindow = (sampleRate / 5).coerceAtLeast(4)
    private val integrationWindow = (0.150 * sampleRate).toInt().coerceAtLeast(1)
    private val refractoryPeriod = (0.25 * sampleRate).toInt()

    private val lpBuf = ArrayDeque<Double>()
    private var lpSum = 0.0
    private val hpBuf = ArrayDeque<Double>()
    private var hpSum = 0.0
    private val derivBuf = ArrayDeque<Double>()
    private val intBuf = ArrayDeque<Double>()
    private var intSum = 0.0

    private var sampleIndex = 0
    private var lastPeakIndex = -refractoryPeriod - 1
    private var threshold = 0.0
    private var observedMaxIntegrated = 0.0

    // RR intervals collected in real time (peaks come from internal detector)
    private val rrIntervals = mutableListOf<Int>()
    private val irregularityFlags = mutableListOf<Irreg>()

    enum class Irreg { NONE, PREMATURE, PAUSE, UNEVEN }

    data class Snapshot(
        val beats: Int,
        val regularPct: Double,   // 0..100
        val premature: Int,
        val pauses: Int,
        val uneven: Int,
    ) {
        val irregularities: Int get() = premature + pauses + uneven
    }

    @Synchronized
    fun reset() {
        lpBuf.clear(); lpSum = 0.0
        hpBuf.clear(); hpSum = 0.0
        derivBuf.clear()
        intBuf.clear(); intSum = 0.0
        sampleIndex = 0
        lastPeakIndex = -refractoryPeriod - 1
        threshold = 0.0
        observedMaxIntegrated = 0.0
        rrIntervals.clear()
        irregularityFlags.clear()
    }

    /**
     * Process one raw ECG sample. Returns true if a new R-peak was detected.
     */
    @Synchronized
    fun onSample(voltage: Int): Boolean {
        val x = voltage.toDouble()
        sampleIndex++

        // Low-pass moving average
        lpBuf.addLast(x); lpSum += x
        if (lpBuf.size > lpWindow) lpSum -= lpBuf.removeFirst()
        val lp = lpSum / lpBuf.size

        // High-pass = lp - moving average of lp (wider window)
        hpBuf.addLast(lp); hpSum += lp
        if (hpBuf.size > hpWindow) hpSum -= hpBuf.removeFirst()
        val hp = lp - (hpSum / hpBuf.size)

        // 5-point derivative (keeps last 5 hp values)
        derivBuf.addLast(hp)
        if (derivBuf.size > 5) derivBuf.removeFirst()
        if (derivBuf.size < 5) return false
        val d = derivBuf.toList()
        val deriv = (-d[0] - 2 * d[1] + 2 * d[3] + d[4]) / 8.0

        // Square
        val squared = deriv * deriv

        // Moving-window integration
        intBuf.addLast(squared); intSum += squared
        if (intBuf.size > integrationWindow) intSum -= intBuf.removeFirst()
        val integrated = intSum / intBuf.size

        // Seed threshold from first ~2 seconds of data
        if (sampleIndex < sampleRate * 2) {
            if (integrated > observedMaxIntegrated) observedMaxIntegrated = integrated
            threshold = observedMaxIntegrated * 0.3
            return false
        }

        // Decay the observed max toward the current integrated value so that a
        // rare artifact spike doesn't permanently elevate the threshold. Over
        // ~10 s (1300 samples @130Hz) the max fades by ~50% if no new spike.
        observedMaxIntegrated *= MAX_DECAY_PER_SAMPLE
        if (integrated > observedMaxIntegrated) observedMaxIntegrated = integrated

        // Search-back: if no peak has been detected for >1.5s, the threshold
        // is probably stuck too high. Lower it aggressively so the next real
        // QRS can trip it.
        val samplesSinceLastPeak = sampleIndex - lastPeakIndex
        if (samplesSinceLastPeak > sampleRate * 3 / 2) {
            threshold *= SEARCHBACK_DECAY_PER_SAMPLE
            // Never let threshold drop below a small fraction of the recent max
            val floor = observedMaxIntegrated * 0.05
            if (threshold < floor) threshold = floor
        }

        // Peak detection with refractory period
        val isPeak = integrated > threshold &&
                samplesSinceLastPeak > refractoryPeriod
        if (isPeak) {
            val rrMs = (samplesSinceLastPeak.toDouble() / sampleRate * 1000.0).toInt()
            lastPeakIndex = sampleIndex
            // Adapt threshold toward current peak. Target = 40% of integrated at peak.
            threshold = 0.75 * threshold + 0.25 * integrated * 0.4
            if (rrMs in 300..2500) {
                recordRr(rrMs)
            }
            return true
        }
        return false
    }

    companion object {
        // ~0.99947 per sample → ~half-life of 10s at 130Hz. Prevents a single
        // artifact from permanently raising the max-tracked integrated value.
        private const val MAX_DECAY_PER_SAMPLE = 0.99947
        // Aggressive multiplicative decay applied only when the detector is
        // stuck (no peak for >1.5s). ~0.99 per sample → halves in ~70 samples.
        private const val SEARCHBACK_DECAY_PER_SAMPLE = 0.99
    }

    private fun recordRr(rrMs: Int) {
        rrIntervals.add(rrMs)
        irregularityFlags.add(Irreg.NONE)

        // Classify the previous beat now that we know the next RR
        // (PAC requires looking at the following beat's duration).
        val i = rrIntervals.size - 1
        if (rrMs > 2000) {
            irregularityFlags[i] = Irreg.PAUSE
            return
        }

        // Compute local median on last up-to-10 beats (excluding current)
        val windowStart = (i - 10).coerceAtLeast(0)
        val window = rrIntervals.subList(windowStart, i)
        if (window.size < 5) return
        val localMedian = window.sorted()[window.size / 2]

        // Look back one: was the previous RR premature?
        val prevIdx = i - 1
        if (prevIdx >= 1) {
            val prev = rrIntervals[prevIdx]
            if (irregularityFlags[prevIdx] == Irreg.NONE && prev < localMedian * 0.85) {
                if (rrMs > localMedian * 1.10) {
                    irregularityFlags[prevIdx] = Irreg.PREMATURE
                } else {
                    irregularityFlags[prevIdx] = Irreg.UNEVEN
                }
            }
        }
        // Any beat outside ±20% of median is a candidate "uneven", but we only
        // confirm it when TWO consecutive RRs deviate in the same direction —
        // this filters respiratory sinus arrhythmia at rest and isolated
        // single-sample artifacts while still catching real patterns
        // (bigeminy, couplets, sustained arrhythmias).
        val currentCandidate = abs(rrMs - localMedian) > localMedian * 0.20 && rrMs > localMedian
        if (currentCandidate && prevIdx >= 0 && irregularityFlags[prevIdx] == Irreg.NONE) {
            val prev = rrIntervals[prevIdx]
            val prevDeviation = prev - localMedian
            val curDeviation = rrMs - localMedian
            // Both on the "long" side of the median
            if (prevDeviation > localMedian * 0.20 && curDeviation > localMedian * 0.20) {
                irregularityFlags[prevIdx] = Irreg.UNEVEN
                irregularityFlags[i] = Irreg.UNEVEN
            }
        }
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val beats = rrIntervals.size
        val pauses = irregularityFlags.count { it == Irreg.PAUSE }
        val premature = irregularityFlags.count { it == Irreg.PREMATURE }
        val uneven = irregularityFlags.count { it == Irreg.UNEVEN }
        val irregularities = pauses + premature + uneven
        val regularPct = if (beats > 0) {
            ((beats - irregularities).toDouble() / beats) * 100.0
        } else 100.0
        return Snapshot(
            beats = beats,
            regularPct = regularPct,
            premature = premature,
            pauses = pauses,
            uneven = uneven,
        )
    }
}
