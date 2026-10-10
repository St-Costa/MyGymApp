package com.mygymapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mygymapp.ui.theme.MyGymAppTheme

/**
 * Required by Health Connect, not by this app's own navigation — never reached through
 * normal in-app browsing. Health Connect's permission-request screen links to this activity
 * (both directly on Android 13 and earlier via the `androidx.health.ACTION_SHOW_PERMISSIONS_
 * RATIONALE` intent-filter, and via the `ViewPermissionUsageActivity` activity-alias on
 * Android 14+ — both declared in AndroidManifest.xml) as the app's explanation of what it
 * does with health data and why it's asking. Its mere *existence*, declared correctly, is
 * also a hard requirement for the permission dialog to appear at all: without it, Health
 * Connect's `PermissionsActivity` opens and immediately self-closes with no dialog shown and
 * no error — exactly what happened before this file existed (see CHANGELOG).
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyGymAppTheme {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Steps and health data", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "MyGymApp reads your daily step count via Health Connect, " +
                                "once a day together with the morning readiness test with the " +
                                "Polar heart-rate strap.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "This data stays on the device unless you configured and enabled " +
                                "syncing to your personal server in the app's Options " +
                                "(self-hosted sync, not a third-party service). No " +
                                "data is shared with anyone else.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "You can revoke this permission at any time from Settings → " +
                                "Privacy → Permission manager, or from the Health Connect app itself.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
