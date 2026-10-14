package com.mygymapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.mygymapp.data.util.Breadcrumbs
import com.mygymapp.data.util.describeRoute
import com.mygymapp.ui.navigation.AppNavigation
import com.mygymapp.ui.theme.MyGymAppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission granted or denied — service still runs, notification may not show */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MyGymAppTheme {
                val navController = rememberNavController()
                // Every destination change goes into the crash-report action trail, with the
                // real ids substituted into the route pattern (see Breadcrumbs).
                DisposableEffect(navController) {
                    val listener = NavController.OnDestinationChangedListener { _, destination, args ->
                        val values = destination.arguments.keys.associateWith { key ->
                            @Suppress("DEPRECATION")
                            args?.get(key)
                        }
                        Breadcrumbs.onScreen(describeRoute(destination.route, values))
                    }
                    navController.addOnDestinationChangedListener(listener)
                    onDispose { navController.removeOnDestinationChangedListener(listener) }
                }
                AppNavigation(navController = navController)
            }
        }
    }
}
