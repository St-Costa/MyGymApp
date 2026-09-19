package com.mygymapp.data.scale

/**
 * Tail average over the raw weight notify samples of a scale connection session.
 *
 * The scale streams weight packets continuously while the user stands on it, so the
 * head of the sample list holds ramp-up (stepping on) and mid-session movement —
 * averaging the whole session dragged the saved weight up to ~0.4 kg off the value
 * shown on the scale's display. Averaging only the tail keeps the smoothing (the
 * 0.1 kg-quantized packets are jittery one by one) while excluding ancient history:
 * a re-weigh later in the same connection naturally falls inside the window, which
 * is what makes same-connection re-weighs last-wins without ever clearing the list.
 */
object ScaleWeightAverager {
    /**
     * How many of the most recent samples enter the average — ~the final 1–3 s of a
     * weighing at the VT701's few-packets-per-second notify rate. Sessions shorter
     * than the window average whatever samples exist.
     */
    const val TAIL_WINDOW = 10

    /** Null when there is nothing to average yet (no sample received). */
    fun average(samples: List<Double>, windowSize: Int = TAIL_WINDOW): Double? {
        if (samples.isEmpty()) return null
        val tail = if (samples.size <= windowSize) samples else samples.takeLast(windowSize)
        return tail.average()
    }
}
