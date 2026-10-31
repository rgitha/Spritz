package io.github.rgitha.spritz.ui

import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.rgitha.spritz.ble.AerClient
import io.github.rgitha.spritz.data.SpritzRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Phase { IDLE, RUNNING, LIT, FAILED }

/**
 * Lights the device's LEDs so it can be picked out. While they're on, the button
 * itself reads "Lights on" with a ring running down to when they go off, rather
 * than a tick, which would only say the command worked.
 * [onActive] reports when it starts and finishes, so the caller can pause a search
 * and hold off other Identify buttons; [enabled] is false while another one runs.
 */
@Composable
fun IdentifyButton(
    address: String,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    enabled: Boolean = true,
    onActive: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val repo = remember { SpritzRepository.get(context) }
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.IDLE) }

    fun start() {
        scope.launch {
            phase = Phase.RUNNING
            onActive(true)
            val result = runCatching { repo.identify(address) }
            if (result.isSuccess) {
                // The device clears them this long after lighting them.
                phase = Phase.LIT
                delay(AerClient.IDENTIFY_MS)
            } else {
                phase = Phase.FAILED
                Toast.makeText(context, result.exceptionOrNull()!!.userMessage(), Toast.LENGTH_SHORT).show()
                delay(Commands.RESULT_MS)
            }
            phase = Phase.IDLE
            onActive(false)
        }
    }

    // One fixed layout in every state: an icon slot and a label in the same pill.
    // Only the slot's icon, the label and the background change, each with a fade.
    val colors = MaterialTheme.colorScheme
    val canTap = enabled && phase == Phase.IDLE
    val fill by animateColorAsState(
        if (phase == Phase.LIT) colors.secondaryContainer else colors.secondaryContainer.copy(alpha = 0f),
        label = "fill",
    )
    val content = when {
        phase == Phase.LIT -> colors.onSecondaryContainer
        enabled -> colors.primary
        else -> colors.onSurface.copy(alpha = 0.38f)
    }
    Surface(
        onClick = ::start,
        enabled = canTap,
        shape = RoundedCornerShape(20.dp),
        color = fill,
        contentColor = content,
        border = if (outlined) BorderStroke(1.dp, colors.outline) else null,
        modifier = modifier.width(IDENTIFY_WIDTH).height(40.dp).semantics {
            if (phase == Phase.LIT) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Lights on"
            }
        },
    ) {
        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            val failed = colors.onSurface.copy(alpha = 0.38f)
            RaysIcon(phase, if (phase == Phase.FAILED) failed else content)
            Spacer(Modifier.width(8.dp))
            Crossfade(phase == Phase.LIT, label = "label") { lit ->
                Text(if (lit) "Lights on" else "Identify", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * A light with eight rays that shows every state itself: steady when idle; rays
 * lighting in turn round the circle while connecting; while the lights are on, all
 * lit with the centre glowing, one ray going dark every eighth of the time, so the
 * last goes out as the device's lights do; on failure, a short shake.
 */
@Composable
private fun RaysIcon(phase: Phase, color: Color) {
    val loop = rememberInfiniteTransition(label = "rays")
    val chase by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "chase")
    val glow by loop.animateFloat(1f, 0.55f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "glow")
    val left = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(phase) {
        when (phase) {
            Phase.LIT -> {
                left.snapTo(1f)
                left.animateTo(0f, tween(AerClient.IDENTIFY_MS.toInt(), easing = LinearEasing))
            }
            Phase.FAILED -> shake.animateTo(0f, keyframes {
                durationMillis = 400
                -2f at 50
                2f at 150
                -2f at 250
                2f at 350
            })
            else -> left.snapTo(1f)
        }
    }
    Canvas(Modifier.size(16.dp).offset(x = shake.value.dp)) {
        val u = size.width / 24
        drawCircle(color.copy(alpha = color.alpha * if (phase == Phase.LIT) glow else 1f), radius = 3.5f * u)
        repeat(RAYS) { i ->
            val alpha = when (phase) {
                Phase.RUNNING -> {
                    // Each ray lights as the chase reaches it, then fades.
                    val since = ((chase - i.toFloat() / RAYS) % 1f + 1f) % 1f
                    if (since < 0.6f) 1f - since / 0.6f * 0.85f else 0.15f
                }
                // Clockwise from the top, one goes dark each eighth of the time.
                Phase.LIT -> if (1f - left.value < (i + 1f) / RAYS) 1f else 0.12f
                else -> 1f
            }
            val a = Math.toRadians(-90.0 + i * 360.0 / RAYS)
            val dx = kotlin.math.cos(a).toFloat()
            val dy = kotlin.math.sin(a).toFloat()
            drawLine(
                color.copy(alpha = color.alpha * alpha),
                center + Offset(dx * 6.5f * u, dy * 6.5f * u),
                center + Offset(dx * 9.5f * u, dy * 9.5f * u),
                strokeWidth = 2 * u,
                cap = StrokeCap.Round,
            )
        }
    }
}

private const val RAYS = 8

/** Fits "Lights on" with its icon, so the button keeps one size in every state. */
private val IDENTIFY_WIDTH = 120.dp
