package com.mygymapp.data.polar

import kotlin.math.abs

/**
 * Incremental (streaming) version of Pan-Tompkins R-peak detection and
 * RR-based irregularity classification. State is kept across samples so
 * per-sample cost is O(1) aside from the two moving-average windows.
 *
 * Internal buffers are primitive DoubleArray rings — an ArrayDeque<Double>
 * autoboxed every sample, which at 130 Hz for four windows meant ~650
 * allocations per second of live ECG. Snapshot counters (pauses/premature/
 * uneven) are maintained incrementally as flags are set, so snapshot() is
 * O(1) instead of three list scans.
 */
class LiveEcgAnalyzer(private val sampleRate: Int = 130) {

    // Pan-Tompkins pipeline state
    private val lpWindow = (sampleRate / 15).coerceAtLeast(2)
    private val hpWindow = (sampleRate / 5).coerceAtLeast(4)
    private val integrationWindow = (0.150 * sampleRate).toInt().coerceAtLeast(1)
    private val refractoryPeriod = (0.25 * sampleRate).toInt()

    // Running-sum ring buffers. `size` counts filled slots (grows up to
    // capacity, then stays); `head` is the index of the oldest sample.
    private val lpBuf = DoubleArray(lpWindow)
    private var lpHead = 0
    private var lpSize = 0
    private var lpSum = 0.0

    private val hpBuf = DoubleArray(hpWindow)
    private var hpHead = 0
    private var hpSize = 0
    private var hpSum = 0.0

    private val derivBuf = DoubleArray(5)
    private var derivHead = 0
    private var derivSize = 0

    private val intBuf = DoubleArray(integrationWindow)
    private var intHead = 0
    private var intSize = 0
    private var intSum = 0.0

    private var sampleIndex = 0
    private var lastPeakIndex = -refractoryPeriod - 1
    private var threshold = 0.0
    private var observedMaxIntegrated = 0.0

    // RR intervals collected in real time (peaks come from internal detector)
    private val rrIntervals = mutableListOf<Int>()
    private val irregularityFlags = mutableListOf<Irreg>()

    // Incremental counters, kept in sync with irregularityFlags.
    private var pauseCount = 0
    private var prematureCount = 0
    private var unevenCount = 0

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

    /**
     * Suppress uneven-beat flagging during intense effort. Uneven detection is
     * only reliable in a relatively relaxed range (< 70% of HRmax); above that
     * physiological RR variability from heavy breathing, valsalva and muscle
     * artefacts fires false positives that aren't clinically meaningful. Pass
     * `true` while under load, `false` when resting.
     */
    @Synchronized
    fun setUnevenSuppressed(suppress: Boolean) {
        suppressUneven = suppress
    }
    private var suppressUneven: Boolean = false

    @Synchronized
    fun reset() {
        // Reset ring buffers (only counters/sums, arrays are overwritten in-place)
        lpHead = 0; lpSize = 0; lpSum = 0.0
        hpHead = 0; hpSize = 0; hpSum = 0.0
        derivHead = 0; derivSize = 0
        intHead = 0; intSize = 0; intSum = 0.0
        sampleIndex = 0
        lastPeakIndex = -refractoryPeriod - 1
        threshold = 0.0
        observedMaxIntegrated = 0.0
        rrIntervals.clear()
        irregularityFlags.clear()
        pauseCount = 0
        prematureCount = 0
        unevenCount = 0
    }

    /**
     * Process one raw ECG sample. Returns true if a new R-peak was detected.
     */
    @Synchronized
    fun onSample(voltage: Int): Boolean {
        val x = voltage.toDouble()
        sampleIndex++

        // Low-pass moving average
        val evicted = pushLp(x)
        lpSum += x - evicted
        val lp = lpSum / lpSize

        // High-pass = lp - moving average of lp (wider window)
        val hpEvicted = pushHp(lp)
        hpSum += lp - hpEvicted
        val hp = lp - (hpSum / hpSize)

        // 5-point derivative (keeps last 5 hp values)
        pushDeriv(hp)
        if (derivSize < 5) return false
        val deriv = (-derivAt(0) - 2 * derivAt(1) + 2 * derivAt(3) + derivAt(4)) / 8.0

        // Square
        val squared = deriv * deriv

        // Moving-window integration
        val intEvicted = pushInt(squared)
        intSum += squared - intEvicted
        val integrated = intSum / intSize

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

    // ─── Ring-buffer helpers ──────────────────────────────────────────────────
    // Each pushXxx returns the evicted value (0.0 while still filling) so the
    // caller can update its running sum in constant time.

    private fun pushLp(v: Double): Double {
        if (lpSize < lpWindow) {
            lpBuf[(lpHead + lpSize) % lpWindow] = v
            lpSize++
            return 0.0
        }
        val evicted = lpBuf[lpHead]
        lpBuf[lpHead] = v
        lpHead = (lpHead + 1) % lpWindow
        return evicted
    }

    private fun pushHp(v: Double): Double {
        if (hpSize < hpWindow) {
            hpBuf[(hpHead + hpSize) % hpWindow] = v
            hpSize++
            return 0.0
        }
        val evicted = hpBuf[hpHead]
        hpBuf[hpHead] = v
        hpHead = (hpHead + 1) % hpWindow
        return evicted
    }

    private fun pushDeriv(v: Double) {
        if (derivSize < 5) {
            derivBuf[(derivHead + derivSize) % 5] = v
            derivSize++
        } else {
            derivBuf[derivHead] = v
            derivHead = (derivHead + 1) % 5
        }
    }

    private fun derivAt(i: Int): Double = derivBuf[(derivHead + i) % 5]

    private fun pushInt(v: Double): Double {
        if (intSize < integrationWindow) {
            intBuf[(intHead + intSize) % integrationWindow] = v
            intSize++
            return 0.0
        }
        val evicted = intBuf[intHead]
        intBuf[intHead] = v
        intHead = (intHead + 1) % integrationWindow
        return evicted
    }

    companion object {
        // ~0.99947 per sample → ~half-life of 10s at 130Hz. Prevents a single
        // artifact from permanently raising the max-tracked integrated value.
        private const val MAX_DECAY_PER_SAMPLE = 0.99947
        // Aggressive multiplicative decay applied only when the detector is
        // stuck (no peak for >1.5s). ~0.99 per sample → halves in ~70 samples.
        private const val SEARCHBACK_DECAY_PER_SAMPLE = 0.99
    }

    // Update irregularityFlags[idx] and keep the incremental counters in sync.
    // Only transitions NONE→X are expected here (Pauses are set once at insert,
    // premature/uneven can be set later on the previous beat), but the helper
    // handles X→Y just in case.
    private fun setFlag(idx: Int, new: Irreg) {
        val old = irregularityFlags[idx]
        if (old == new) return
        when (old) {
            Irreg.PAUSE -> pauseCount--
            Irreg.PREMATURE -> prematureCount--
            Irreg.UNEVEN -> unevenCount--
            Irreg.NONE -> {}
        }
        when (new) {
            Irreg.PAUSE -> pauseCount++
            Irreg.PREMATURE -> prematureCount++
            Irreg.UNEVEN -> unevenCount++
            Irreg.NONE -> {}
        }
        irregularityFlags[idx] = new
    }

    private fun recordRr(rrMs: Int) {
        rrIntervals.add(rrMs)
        irregularityFlags.add(Irreg.NONE)

        // Classify the previous beat now that we know the next RR
        // (PAC requires looking at the following beat's duration).
        val i = rrIntervals.size - 1
        if (rrMs > 2000) {
            setFlag(i, Irreg.PAUSE)
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
                    setFlag(prevIdx, Irreg.PREMATURE)
                } else {
                    setFlag(prevIdx, Irreg.UNEVEN)
                }
            }
        }
        // Any beat outside ±20% of median is a candidate "uneven", but we only
        // confirm it when TWO consecutive RRs deviate in the same direction —
        // this filters respiratory sinus arrhythmia at rest and isolated
        // single-sample artifacts while still catching real patterns
        // (bigeminy, couplets, sustained arrhythmias).
        // Additionally, during active high-intensity effort (suppressUneven==true)
        // we don't flag uneven beats at all — the variability under load is
        // dominated by physiology, not arrhythmia.
        if (suppressUneven) return
        val currentCandidate = abs(rrMs - localMedian) > localMedian * 0.20 && rrMs > localMedian
        if (currentCandidate && prevIdx >= 0 && irregularityFlags[prevIdx] == Irreg.NONE) {
            val prev = rrIntervals[prevIdx]
            val prevDeviation = prev - localMedian
            val curDeviation = rrMs - localMedian
            // Both on the "long" side of the median
            if (prevDeviation > localMedian * 0.20 && curDeviation > localMedian * 0.20) {
                setFlag(prevIdx, Irreg.UNEVEN)
                setFlag(i, Irreg.UNEVEN)
            }
        }
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val beats = rrIntervals.size
        val irregularities = pauseCount + prematureCount + unevenCount
        val regularPct = if (beats > 0) {
            ((beats - irregularities).toDouble() / beats) * 100.0
        } else 100.0
        return Snapshot(
            beats = beats,
            regularPct = regularPct,
            premature = prematureCount,
            pauses = pauseCount,
            uneven = unevenCount,
        )
    }
}
