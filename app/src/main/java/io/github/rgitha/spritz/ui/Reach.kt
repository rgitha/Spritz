package io.github.rgitha.spritz.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rgitha.spritz.ble.BlePermissions
import io.github.rgitha.spritz.ble.Presence
import io.github.rgitha.spritz.data.SavedDevice
import kotlinx.coroutines.delay

/** Whether the device can be reached, from its advertising and the last exchange with it. */
enum class Reach { CONNECTED, DISCONNECTED, UNKNOWN }

/** Disconnected if either signal says no; connected if one says yes; otherwise unknown. */
fun reach(exchangeOk: Boolean?, advertising: Boolean?): Reach = when {
    exchangeOk == false || advertising == false -> Reach.DISCONNECTED
    exchangeOk == true || advertising == true -> Reach.CONNECTED
    else -> Reach.UNKNOWN
}

/** [d]'s reach, kept current as time passes without hearing it. */
@Composable
fun rememberReach(d: SavedDevice): Reach {
    val seen by Presence.lastSeen.collectAsState()
    val since by Presence.scanSince.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            now = System.currentTimeMillis()
        }
    }
    return reach(d.exchangeOk, Presence.advertising(d.address, seen, since, now))
}

@Composable
fun bluetoothOn(): Boolean = Presence.bluetoothOn.collectAsState().value

/** Whether the app has its Bluetooth permissions. */
@Composable
fun bluetoothAllowed(): Boolean = Presence.permitted.collectAsState().value

/**
 * Asks for the Bluetooth permissions. When Android no longer shows the request (after
 * it was denied twice) the answer comes back at once, so app settings open instead.
 */
@Composable
fun rememberBluetoothAsk(): () -> Unit {
    val context = LocalContext.current
    var askedAt by remember { mutableLongStateOf(0L) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        Presence.recheck(context)
        if (result.values.any { !it } && System.currentTimeMillis() - askedAt < NO_DIALOG_MS) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }
    }
    return {
        askedAt = System.currentTimeMillis()
        launcher.launch(BlePermissions.required)
    }
}

/** An answer faster than this means no dialog was shown. */
private const val NO_DIALOG_MS = 400L

/** Three dots lighting in turn: something is about to show here. */
@Composable
fun WaitingDots(color: Color, dot: Dp = 6.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(
        horizontalArrangement = Arrangement.spacedBy(dot * 0.66f),
        modifier = modifier.semantics { contentDescription = "Status not known yet" },
    ) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.2f,
                targetValue = 0.2f,
                animationSpec = infiniteRepeatable(
                    keyframes {
                        durationMillis = 1200
                        0.2f at 0
                        1f at 480
                        0.2f at 960
                    },
                    initialStartOffset = StartOffset(i * 200),
                ),
                label = "dot$i",
            )
            Box(Modifier.size(dot).alpha(a).background(color, CircleShape))
        }
    }
}
