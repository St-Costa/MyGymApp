package com.mygymapp.data.model

/**
 * Poliquin tempo: 4-phase rep prescription (see docs/polar research + proposal).
 *
 * Order is fixed: 1 = eccentric/negativa (lowering), 2 = pause at the bottom in the
 * stretched position, 3 = concentric (lifting), 4 = pause at the top in the contracted
 * position. Each phase is either a whole-second duration (0..5) or [TempoDuration.Explosive]
 * ("X" = as fast as possible / maximal intent, concentric typically).
 *
 * `0-0-0-0` (all zeros) means disabled — no tempo prescribed, nothing shown during the
 * workout. Zero phases are skipped by the loop animation; X phases animate as a fast
 * 0.35s fill (see TempoAnimation). */
sealed interface TempoDuration {
    data class Seconds(val value: Int) : TempoDuration {
        init {
            require(value in 0..MAX_TEMPO_SECONDS) { "tempo seconds must be 0..$MAX_TEMPO_SECONDS" }
        }
    }

    data object Explosive : TempoDuration
}

const val MAX_TEMPO_SECONDS = 5

/** File-string token for a single phase: "0".."5" or "X". */
fun TempoDuration.toToken(): String = when (this) {
    is TempoDuration.Seconds -> value.toString()
    TempoDuration.Explosive -> "X"
}

fun tempoDurationFromToken(token: String): TempoDuration? = when (token.trim().uppercase()) {
    "X" -> TempoDuration.Explosive
    else -> token.trim().toIntOrNull()
        ?.takeIf { it in 0..MAX_TEMPO_SECONDS }
        ?.let { TempoDuration.Seconds(it) }
}

/**
 * Step +1/-1 along the fixed scale X-0-1-2-3-4-5 with no wrap: clamps at X going down
 * (below X is unreachable) and at 5 going up. Down from 0 lands on X, up from X lands on 0.
 */
fun TempoDuration.stepped(delta: Int): TempoDuration {
    if (this is TempoDuration.Explosive) {
        return if (delta > 0) TempoDuration.Seconds(0) else TempoDuration.Explosive
    }
    val seconds = (this as TempoDuration.Seconds).value
    if (delta < 0) {
        return if (seconds == 0) TempoDuration.Explosive else TempoDuration.Seconds(seconds - 1)
    }
    return if (seconds >= MAX_TEMPO_SECONDS) {
        TempoDuration.Seconds(MAX_TEMPO_SECONDS)
    } else {
        TempoDuration.Seconds(seconds + 1)
    }
}

fun TempoDuration.animationDurationMs(): Long = when (this) {
    is TempoDuration.Seconds -> value * 1000L
    TempoDuration.Explosive -> 350L
}

fun TempoDuration.isSkipped(): Boolean = this is TempoDuration.Seconds && value == 0

data class Tempo(
    val eccentric: TempoDuration = TempoDuration.Seconds(0),
    val pauseBottom: TempoDuration = TempoDuration.Seconds(0),
    val concentric: TempoDuration = TempoDuration.Seconds(0),
    val pauseTop: TempoDuration = TempoDuration.Seconds(0),
) {
    val isDisabled: Boolean
        get() = eccentric.isSkipped() && pauseBottom.isSkipped() &&
            concentric.isSkipped() && pauseTop.isSkipped()

    fun phaseAt(index: Int): TempoDuration = when (index) {
        0 -> eccentric
        1 -> pauseBottom
        2 -> concentric
        3 -> pauseTop
        else -> throw IndexOutOfBoundsException("tempo phase index must be 0..3")
    }

    fun withPhaseAt(index: Int, value: TempoDuration): Tempo = when (index) {
        0 -> copy(eccentric = value)
        1 -> copy(pauseBottom = value)
        2 -> copy(concentric = value)
        3 -> copy(pauseTop = value)
        else -> throw IndexOutOfBoundsException("tempo phase index must be 0..3")
    }

    /** Non-zero phases in order — the exact sequence the loop animation plays. */
    fun activePhases(): List<Pair<Int, TempoDuration>> =
        (0..3).map { it to phaseAt(it) }.filter { (_, d) -> !d.isSkipped() }

    fun toFileString(): String =
        "${eccentric.toToken()}-${pauseBottom.toToken()}-${concentric.toToken()}-${pauseTop.toToken()}"

    companion object {
        fun disabled(): Tempo = Tempo()

        fun default(): Tempo = Tempo(
            eccentric = TempoDuration.Seconds(4),
            pauseBottom = TempoDuration.Seconds(0),
            concentric = TempoDuration.Seconds(1),
            pauseTop = TempoDuration.Seconds(0),
        )

        /** Parses "4-0-X-0" (also accepts "/" or " " separators, lowercase x). Null on invalid. */
        fun fromFileString(raw: String?): Tempo? {
            if (raw.isNullOrBlank()) return null
            val tokens = raw.trim().split(Regex("[-/\\s]+")).filter { it.isNotEmpty() }
            if (tokens.size != 4) return null
            val durations = tokens.map { tempoDurationFromToken(it) ?: return null }
            return Tempo(durations[0], durations[1], durations[2], durations[3])
        }
    }
}
