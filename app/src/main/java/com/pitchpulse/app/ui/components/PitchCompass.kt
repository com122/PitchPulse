package com.pitchpulse.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.pitchpulse.app.domain.algorithm.PitchMarkings
import com.pitchpulse.app.ui.theme.PP
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Compass rose (north up) with the pitch drawn rotated to its bearing, to scale.
 * When [onBearingChange] is set, dragging around the dial rotates the pitch.
 */
@Composable
fun PitchCompass(
    markings: PitchMarkings,
    bearingDegrees: Float,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
    onBearingChange: ((Float) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    val callback = rememberUpdatedState(onBearingChange)
    val dragModifier = if (onBearingChange != null) {
        Modifier.pointerInput(Unit) {
            fun angleAt(p: Offset): Float {
                val cx = size.width / 2f
                val cy = size.height / 2f
                // Compass angle: 0 = up (north), clockwise positive.
                val deg = Math.toDegrees(atan2((p.x - cx).toDouble(), (cy - p.y).toDouble())).toFloat()
                return ((deg % 360f) + 360f) % 360f
            }
            detectDragGestures(
                onDragStart = { callback.value?.invoke(angleAt(it)) },
                onDrag = { change, _ -> callback.value?.invoke(angleAt(change.position)) },
            )
        }
    } else Modifier

    Canvas(modifier.aspectRatio(1f).then(dragModifier)) {
        val c = center
        val radius = min(size.width, size.height) / 2f * 0.92f
        drawCircle(PP.CardHigh, radius, c)
        drawCircle(PP.Border, radius, c, style = Stroke(2f))
        for (k in 0 until 72) {
            val a = Math.toRadians(k * 5.0)
            val major = k % 6 == 0
            val inner = radius * if (major) 0.88f else 0.94f
            drawLine(
                if (major) PP.Muted else PP.Border,
                Offset(c.x + inner * sin(a).toFloat(), c.y - inner * cos(a).toFloat()),
                Offset(c.x + radius * sin(a).toFloat(), c.y - radius * cos(a).toFloat()),
                if (major) 2f else 1f,
            )
        }
        if (showLabels) {
            val style = TextStyle(color = PP.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (label, deg) ->
                val t = measurer.measure(label, if (label == "N") style.copy(color = PP.Red) else style)
                val a = Math.toRadians(deg)
                val r = radius * 0.76f
                drawText(t, topLeft = Offset(
                    c.x + r * sin(a).toFloat() - t.size.width / 2,
                    c.y - r * cos(a).toFloat() - t.size.height / 2,
                ))
            }
        }

        // Pitch: long axis along the bearing. Drawn horizontally (length along x) then rotated so
        // that +x points at the bearing: canvas rotation of (bearing − 90°).
        val maxLen = radius * 1.25f
        val scale = min(maxLen / markings.length, maxLen / markings.width)
        val w = markings.length * scale
        val h = markings.width * scale
        rotate(bearingDegrees - 90f, c) {
            val rect = Rect(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
            drawGrass(rect, 6)
            drawPitchMarkings(rect, markings, Color.White.copy(alpha = 0.85f), 1.5f)
            // Arrow at the end the bearing points to.
            drawLine(PP.Amber, Offset(rect.right, c.y), Offset(rect.right + radius * 0.12f, c.y), 4f)
        }
        // Bearing needle
        val a = Math.toRadians(bearingDegrees.toDouble())
        drawCircle(PP.Amber, 5f, Offset(c.x + radius * sin(a).toFloat(), c.y - radius * cos(a).toFloat()))
    }
}
