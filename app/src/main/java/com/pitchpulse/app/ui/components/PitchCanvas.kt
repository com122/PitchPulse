package com.pitchpulse.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pitchpulse.app.domain.algorithm.PitchMarkings
import com.pitchpulse.app.ui.theme.PP
import kotlin.math.acos
import kotlin.math.roundToInt

/**
 * To-scale football pitch drawn with Compose Canvas (length along x, width along y),
 * with an optional heatmap bitmap stretched over the playing area and an overlay callback that
 * receives the pitch rectangle in pixels (for track lines / sprint markers).
 */
@Composable
fun PitchCanvas(
    markings: PitchMarkings,
    modifier: Modifier = Modifier,
    heatmap: ImageBitmap? = null,
    mini: Boolean = false,
    overlay: (DrawScope.(Rect) -> Unit)? = null,
) {
    val aspect = (markings.length / markings.width).coerceIn(1.1f, 2.5f)
    Canvas(modifier = modifier.fillMaxWidth().aspectRatio(aspect)) {
        val inset = if (mini) 2f else size.minDimension * 0.03f
        val rect = Rect(inset, inset, size.width - inset, size.height - inset)
        drawGrass(rect, if (mini) 6 else 10)
        heatmap?.let {
            drawImage(
                image = it,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(it.width, it.height),
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                filterQuality = FilterQuality.High,
            )
        }
        drawPitchMarkings(rect, markings, Color.White.copy(alpha = 0.85f), if (mini) 1f else 2.2f)
        overlay?.invoke(this, rect)
    }
}

fun DrawScope.drawGrass(rect: Rect, stripes: Int) {
    drawRect(PP.Background, Offset.Zero, size)
    val sw = rect.width / stripes
    for (i in 0 until stripes) {
        drawRect(
            color = if (i % 2 == 0) PP.PitchDark else PP.PitchLight,
            topLeft = Offset(rect.left + i * sw, rect.top),
            size = Size(sw + 0.5f, rect.height),
        )
    }
}

fun DrawScope.drawPitchMarkings(rect: Rect, m: PitchMarkings, color: Color, strokePx: Float) {
    val sx = rect.width / m.length
    val sy = rect.height / m.width
    val s = minOf(sx, sy)
    val stroke = Stroke(width = strokePx)
    fun x(mx: Float) = rect.left + mx * sx
    fun y(my: Float) = rect.top + my * sy
    fun box(x0: Float, y0: Float, x1: Float, y1: Float) {
        val l = minOf(x(x0), x(x1)); val r = maxOf(x(x0), x(x1))
        val t = minOf(y(y0), y(y1)); val b = maxOf(y(y0), y(y1))
        drawRect(color, Offset(l, t), Size(r - l, b - t), style = stroke)
    }
    val cy = m.width / 2

    drawRect(color, rect.topLeft, rect.size, style = stroke)
    drawLine(color, Offset(x(m.length / 2), rect.top), Offset(x(m.length / 2), rect.bottom), strokePx)
    drawOval(
        color,
        Offset(x(m.length / 2 - m.centerCircleRadius), y(cy - m.centerCircleRadius)),
        Size(2 * m.centerCircleRadius * sx, 2 * m.centerCircleRadius * sy),
        style = stroke,
    )
    drawCircle(color, radius = strokePx * 1.6f, center = Offset(x(m.length / 2), y(cy)))

    for (side in 0..1) {
        val goalX = if (side == 0) 0f else m.length
        val dir = if (side == 0) 1f else -1f
        box(goalX, cy - m.penaltyAreaWidth / 2, goalX + dir * m.penaltyAreaDepth, cy + m.penaltyAreaWidth / 2)
        if (m.goalAreaDepth > 0f) {
            box(goalX, cy - m.goalAreaWidth / 2, goalX + dir * m.goalAreaDepth, cy + m.goalAreaWidth / 2)
        }
        val spotX = goalX + dir * m.penaltySpotDistance
        drawCircle(color, radius = strokePx * 1.3f, center = Offset(x(spotX), y(cy)))
        if (m.penaltyArcRadius > 0f) {
            val d = m.penaltyAreaDepth - m.penaltySpotDistance
            if (d < m.penaltyArcRadius) {
                val half = Math.toDegrees(acos((d / m.penaltyArcRadius).toDouble())).toFloat()
                drawArc(
                    color = color,
                    startAngle = if (side == 0) -half else 180f - half,
                    sweepAngle = 2 * half,
                    useCenter = false,
                    topLeft = Offset(x(spotX - m.penaltyArcRadius), y(cy - m.penaltyArcRadius)),
                    size = Size(2 * m.penaltyArcRadius * sx, 2 * m.penaltyArcRadius * sy),
                    style = stroke,
                )
            }
        }
        val goalDepth = (6f / s).coerceAtMost(2f)
        box(goalX, cy - m.goalWidth / 2, goalX - dir * goalDepth, cy + m.goalWidth / 2)
    }
    val r = m.cornerArcRadius
    val arcSize = Size(2 * r * sx, 2 * r * sy)
    drawArc(color, 0f, 90f, false, Offset(x(-r), y(-r)), arcSize, style = stroke)
    drawArc(color, 90f, 90f, false, Offset(x(m.length - r), y(-r)), arcSize, style = stroke)
    drawArc(color, 180f, 90f, false, Offset(x(m.length - r), y(m.width - r)), arcSize, style = stroke)
    drawArc(color, 270f, 90f, false, Offset(x(-r), y(m.width - r)), arcSize, style = stroke)
}
