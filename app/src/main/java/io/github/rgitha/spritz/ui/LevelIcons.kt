package io.github.rgitha.spritz.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import io.github.rgitha.spritz.data.SavedDevice
import io.github.rgitha.spritz.notify.Notifier

/*
 * Level icons drawn to fill: a battery and a refill drop. Each is laid out on a small
 * grid (24 wide) and scaled to the size given; [stroke] is in grid units, so small
 * icons can use a thicker outline. Pass null for an empty outline.
 */

/** A battery, filled from the bottom; draw it 24:40. */
@Composable
fun BatteryIcon(percent: Int?, fill: Color, modifier: Modifier, outline: Color = LocalContentColor.current, stroke: Float = 2f) {
    Canvas(modifier) {
        val u = size.width / 24f
        drawRect(outline, Offset(8 * u, 0.5f * u), Size(8 * u, 4 * u))
        drawRoundRect(
            outline,
            Offset(1.5f * u, 4.5f * u),
            Size(21 * u, 34.5f * u),
            CornerRadius(4 * u),
            style = Stroke(stroke * u),
        )
        val p = (percent ?: 0).coerceIn(0, 100) / 100f
        if (p > 0) {
            val inset = 1.5f * u + stroke * u
            val bottom = 39f * u - inset
            val h = (bottom - (4.5f * u + inset)) * p
            drawRect(fill, Offset(inset, bottom - h), Size(size.width - 2 * inset, h))
        }
    }
}

/** A drop, filled from the bottom; draw it 24:33. */
@Composable
fun RefillIcon(percent: Int?, fill: Color, modifier: Modifier, outline: Color = LocalContentColor.current, stroke: Float = 2f) {
    Canvas(modifier) {
        val u = size.width / 24f
        val drop = Path().apply {
            moveTo(12 * u, 2.5f * u)
            cubicTo(12 * u, 2.5f * u, 3 * u, 13.5f * u, 3 * u, 22 * u)
            arcTo(Rect(3 * u, 13 * u, 21 * u, 31 * u), 180f, -180f, false)
            cubicTo(21 * u, 13.5f * u, 12 * u, 2.5f * u, 12 * u, 2.5f * u)
            close()
        }
        val p = (percent ?: 0).coerceIn(0, 100) / 100f
        if (p > 0) {
            val h = 28.5f * u * p
            clipPath(drop) { drawRect(fill, Offset(0f, 31 * u - h), Size(size.width, h)) }
        }
        drawPath(drop, outline, style = Stroke(stroke * u, join = StrokeJoin.Round))
    }
}

/** At or below the level that raises an alert. */
val SavedDevice.batteryLow: Boolean
    get() = status?.let { it.batteryCritical || (it.batteryPercent ?: 100) <= Notifier.LOW_BATTERY_PERCENT } == true

val SavedDevice.refillLow: Boolean get() = (refillPercent ?: 100) <= Notifier.LOW_REFILL_PERCENT
