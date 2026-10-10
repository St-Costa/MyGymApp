package com.mygymapp.ui.theme

import androidx.compose.ui.graphics.Color
import com.mygymapp.data.model.ExerciseType

val Background = Color(0xFF121212)
val Surface = Color(0xFF1E1E1E)
val SurfaceVariant = Color(0xFF2C2C2C)
val Primary = Color(0xFFBB86FC)
val PrimaryVariant = Color(0xFF9C64E0)
val Secondary = Color(0xFF03DAC6)
val OnBackground = Color(0xFFE1E1E1)
val OnSurface = Color(0xFFE1E1E1)
val OnPrimary = Color(0xFF000000)

val ForzaColor = Color(0xFFFCA105)
val StretchColor = Color(0xFF42A5F5)
val CardioColor = Color(0xFFE05A5A)
val GitgraphGreen = Color(0xFF4CAF50)
val GitgraphRed = Color(0xFFF44336)
val GitgraphEmpty = Color(0xFF2C2C2C)

/** "Completed with no data" (skipped) indicator — neutral gray so it doesn't read as the same
 * warning color as CardioColor or a negative tonnage/RM change (both use GitgraphRed). */
val SkippedColor = Color(0xFF9E9E9E)

/** Text on [SkippedColor] buttons ("Salta") — near-black for contrast on the mid gray. */
val OnSkipped = Color(0xFF1E1E1E)

/**
 * Shared signal colors (Sprint 6): one value per meaning instead of the same hex
 * copy-pasted across a dozen UI files. Canvas-ink neutrals (White/Black + alpha) and
 * chart-local series hues stay inline where they are used — only meanings shared by
 * several screens live here.
 */
val SignalGreen = Color(0xFF66BB6A)
val SignalAmber = Color(0xFFFFCA28)
val SignalRed = Color(0xFFEF5350)
val SignalOrange = Color(0xFFFF9800)
val TrendBlue = Color(0xFF4FC3F7)

/** Border / accent color associated with an exercise type. */
fun ExerciseType.accentColor(): Color = when (this) {
    ExerciseType.FORZA -> ForzaColor
    ExerciseType.STRETCH -> StretchColor
    ExerciseType.CARDIO -> CardioColor
}
