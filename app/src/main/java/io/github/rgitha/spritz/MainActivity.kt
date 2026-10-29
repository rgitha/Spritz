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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.rgitha.spritz.data.DeviceStore
import io.github.rgitha.spritz.ui.AddDeviceScreen
import io.github.rgitha.spritz.ui.DeviceSettingsScreen
import io.github.rgitha.spritz.ui.Removed
import io.github.rgitha.spritz.ui.HomeScreen
import io.github.rgitha.spritz.ui.LogScreen
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
                    openDevice.value?.let { address ->
                        DeviceStore.get(this@MainActivity).updateSettings { it.copy(lastDevice = address) }
                        nav.popBackStack("home", inclusive = false)
                        openDevice.value = null
                    }
                }
                NavHost(nav, startDestination = "home") {
                    composable("home") { entry ->
                        // Set by Device settings after a removal.
                        val removed by entry.savedStateHandle.getStateFlow<String?>(REMOVED, null).collectAsState()
                        HomeScreen(
                            removed = removed?.split('\n')?.let { Removed(it[0], it.getOrElse(1) { "" }) },
                            onAdd = { nav.navigate("add") },
                            onSchedules = { nav.navigate("schedules/$it") },
                            onDeviceSettings = { nav.navigate("device/$it") },
                            onSettings = { nav.navigate("settings") },
                        )
                        LaunchedEffect(removed) { if (removed != null) entry.savedStateHandle[REMOVED] = null }
                    }
                    composable(
                        "device/{address}",
                        arguments = listOf(navArgument("address") { type = NavType.StringType }),
                    ) { entry ->
                        val address = entry.arguments!!.getString("address")!!
                        DeviceSettingsScreen(
                            address = address,
                            onBack = { nav.popBackStack() },
                            onLog = { nav.navigate("log?address=$address") },
                            onRemoved = { r ->
                                nav.getBackStackEntry("home").savedStateHandle[REMOVED] = "${r.name}\n${r.address}"
                                nav.popBackStack("home", inclusive = false)
                            },
                        )
                    }
                    composable("add") {
                        AddDeviceScreen(
                            onBack = { nav.popBackStack() },
                            onAdded = { nav.popBackStack("home", inclusive = false) },
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
                    composable("settings") {
                        SettingsScreen(onBack = { nav.popBackStack() }, onLog = { nav.navigate("log") })
                    }
                    composable(
                        "log?address={address}",
                        arguments = listOf(navArgument("address") { type = NavType.StringType; nullable = true }),
                    ) { entry ->
                        LogScreen(address = entry.arguments?.getString("address"), onBack = { nav.popBackStack() })
                    }
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
        private const val REMOVED = "removed"
    }
}
