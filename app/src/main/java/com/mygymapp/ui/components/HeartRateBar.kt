package com.mygymapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mygymapp.data.polar.ConnectionState
import com.mygymapp.data.polar.HrZone
import com.mygymapp.data.polar.PolarManager

fun trimpColor(trimp: Double): Color = when {
    trimp < 50 -> Color(0xFF66BB6A)   // green
    trimp < 100 -> Color(0xFFFFCA28)  // yellow
    trimp < 200 -> Color(0xFFFF9800)  // orange
    else -> Color(0xFFEF5350)         // red
}

private val RedLight = Color(0xFFEF5350)

// Compose's default Text reserves extra space below the baseline for font metrics
// (includeFontPadding), which reads as visibly lopsided next to a 22dp icon at this font
// size — more empty space under the number than above it. Trimming that padding and
// pinning lineHeight to the font size keeps the row's visual center where CenterVertically
// expects it to be.
private fun tightNumberStyle(fontSize: androidx.compose.ui.unit.TextUnit) = TextStyle(
    fontSize = fontSize,
    lineHeight = fontSize,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

// Same palette as the sync server's dashboard "Time in HR zone" chart, so the live
// in-session widget and the post-sync retrospective view read consistently.
fun hrZoneColor(zone: HrZone): Color = when (zone) {
    HrZone.BELOW_Z1 -> Color(0xFF4A5063)
    HrZone.Z1 -> Color(0xFF5B9DFF)
    HrZone.Z2 -> Color(0xFF3ECF7E)
    HrZone.Z3 -> Color(0xFFE0B23E)
    HrZone.Z4 -> Color(0xFFFF9F6B)
    HrZone.Z5 -> Color(0xFFE05A5A)
}

fun hrZoneLabel(zone: HrZone): String = when (zone) {
    HrZone.BELOW_Z1 -> "<Z1"
    HrZone.Z1 -> "Z1"
    HrZone.Z2 -> "Z2"
    HrZone.Z3 -> "Z3"
    HrZone.Z4 -> "Z4"
    HrZone.Z5 -> "Z5"
}

/**
 * Shows current HR. Hides itself when Polar is not connected.
 * Call [PolarManager.onSetCompleted] to trigger recovery tracking.
 */
@Composable
fun HeartRateBar(
    modifier: Modifier = Modifier,
    viewModel: HeartRateBarViewModel = hiltViewModel(),
) {
    val polarManager = viewModel.polarManager
    val connectionState by polarManager.connectionState.collectAsState()
    val heartRate by polarManager.heartRate.collectAsState()
    val trimp by polarManager.sessionTrimp.collectAsState()
    val currentZone by polarManager.currentHrZone.collectAsState()
    val currentZonePercent by polarManager.currentHrZonePercent.collectAsState()

    if (connectionState != ConnectionState.CONNECTED) return

    val hr = heartRate ?: return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Heart icon + BPM, left-aligned
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = RedLight,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$hr",
                    style = tightNumberStyle(28.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // TRIMP, horizontally centered
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${trimp.toInt()}",
                    style = tightNumberStyle(16.sp),
                    fontWeight = FontWeight.Bold,
                    color = trimpColor(trimp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "T",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Zone chip, right-aligned
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                currentZone?.let { zone ->
                    ZoneChip(zone = zone, percent = currentZonePercent)
                }
            }
        }
    }
}

/** Compact always-visible zone badge, e.g. "Z3 · 74%". */
@Composable
private fun ZoneChip(zone: HrZone, percent: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(hrZoneColor(zone).copy(alpha = 0.22f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(hrZoneColor(zone)),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "${hrZoneLabel(zone)} · $percent%",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
