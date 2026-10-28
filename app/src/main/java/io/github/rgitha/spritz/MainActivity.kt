package io.github.rgitha.spritz

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.rgitha.spritz.ui.AddDeviceScreen
import io.github.rgitha.spritz.ui.DeviceListScreen
import io.github.rgitha.spritz.ui.DeviceScreen
import io.github.rgitha.spritz.ui.ScheduleScreen
import io.github.rgitha.spritz.ui.SettingsScreen
import io.github.rgitha.spritz.ui.theme.SpritzTheme

class MainActivity : ComponentActivity() {

    /** A device to open, from a notification tap. */
    private val openDevice = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openDevice.value = intent.getStringExtra(EXTRA_DEVICE)
        setContent {
            SpritzTheme {
                val nav = rememberNavController()
                val askNotifications = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                LaunchedEffect(openDevice.value) {
                    openDevice.value?.let {
                        nav.navigate("device/$it") { launchSingleTop = true }
                        openDevice.value = null
                    }
                }
                NavHost(nav, startDestination = "devices") {
                    composable("devices") {
                        DeviceListScreen(
                            onOpen = { nav.navigate("device/$it") },
                            onAdd = { nav.navigate("add") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("add") {
                        AddDeviceScreen(
                            onBack = { nav.popBackStack() },
                            onAdded = { address ->
                                nav.navigate("device/$address") { popUpTo("devices") }
                            },
                        )
                    }
                    composable(
                        "device/{address}",
                        arguments = listOf(navArgument("address") { type = NavType.StringType }),
                    ) { entry ->
                        val address = entry.arguments!!.getString("address")!!
                        DeviceScreen(
                            address = address,
                            onBack = { nav.popBackStack() },
                            onSchedules = { nav.navigate("schedules/$address") },
                        )
                    }
                    composable(
                        "schedules/{address}",
                        arguments = listOf(navArgument("address") { type = NavType.StringType }),
                    ) { entry ->
                        ScheduleScreen(
                            address = entry.arguments!!.getString("address")!!,
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_DEVICE)?.let { openDevice.value = it }
    }

    companion object {
        const val EXTRA_DEVICE = "device"
    }
}
