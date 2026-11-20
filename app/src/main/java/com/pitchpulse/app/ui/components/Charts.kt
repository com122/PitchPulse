package com.pitchpulse.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitchpulse.app.data.model.HrZoneDurations
import com.pitchpulse.app.data.model.SegmentDistance
import com.pitchpulse.app.ui.theme.NumberStyle
import com.pitchpulse.app.ui.theme.PP
import com.pitchpulse.app.util.Formatters

private val ZONE_LABELS = listOf("Z1", "Z2", "Z3", "Z4", "Z5")
private val ZONE_RANGES = listOf("50-60%", "60-70%", "70-80%", "80-90%", "90-100%")

/** Horizontal bars for the 5 HR zones with minutes and percentage. Pure Compose Canvas. */
@Composable
fun HrZoneChart(zones: HrZoneDurations, maxHr: Int, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 4 downTo 0) {
            val share = zones.share(i)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(64.dp)) {
                    Text(ZONE_LABELS[i], style = NumberStyle.Medium, color = PP.ZoneColors[i])
                    Text(
                        "${(maxHr * (0.5f + i * 0.1f)).toInt()}+",
                        style = NumberStyle.Small.copy(fontSize = 10.sp),
                        color = PP.Muted,
                    )
                }
                Canvas(Modifier.weight(1f).height(16.dp)) {
                    val r = CornerRadius(size.height / 2, size.height / 2)
                    drawRoundRect(Color(0xFF21262D), cornerRadius = r)
                    if (share > 0f) {
                        drawRoundRect(
                            PP.ZoneColors[i],
                            size = Size((size.width * share).coerceAtLeast(size.height), size.height),
                            cornerRadius = r,
                        )
                    }
                }
                Column(Modifier.width(92.dp).padding(start = 10.dp), horizontalAlignment = Alignment.End) {
                    Text("${Formatters.minutes(zones.zonesMs[i])} 分", style = NumberStyle.Small, color = PP.Text)
                    Text("${Formatters.percent(share)} · ${ZONE_RANGES[i]}", style = NumberStyle.Small.copy(fontSize = 10.sp), color = PP.Muted)
                }
            }
        }
        if (zones.belowZ1Ms > 0) {
            Text(
                "低于 Z1（<50% 最大心率）：${Formatters.minutes(zones.belowZ1Ms)} 分 · ${Formatters.percent(zones.belowZ1Ms.toFloat() / zones.totalMs)}",
                style = NumberStyle.Small.copy(fontSize = 11.sp),
                color = PP.Muted,
            )
        }
    }
}

/**
 * Bar chart of distance per 5-minute block with a trend line, first half green and second half
 * blue, so the drop-off between halves is visible. [highlightPhase] dims the other half.
 */
@Composable
fun SegmentDistanceChart(
    segments: List<SegmentDistance>,
    modifier: Modifier = Modifier,
    highlightPhase: Int? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = PP.Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(PP.Grass, "上半场")
            Legend(PP.Sky, "下半场")
            Legend(PP.Amber, "趋势")
        }
        Canvas(Modifier.fillMaxWidth().height(200.dp).padding(top = 8.dp)) {
            if (segments.isEmpty()) return@Canvas
            val leftPad = 40f
            val bottomPad = 30f
            val chartW = size.width - leftPad
            val chartH = size.height - bottomPad
            val maxV = (segments.maxOf { it.distanceMeters }.coerceAtLeast(50f) / 100f).let { kotlin.math.ceil(it) * 100f }

            // Grid + y labels
            for (k in 0..4) {
                val v = maxV * k / 4
                val y = chartH - chartH * k / 4
                drawLine(PP.Border, Offset(leftPad, y), Offset(size.width, y), 1f,
                    pathEffect = if (k == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                val t = measurer.measure(v.toInt().toString(), labelStyle)
                drawText(t, topLeft = Offset(leftPad - t.size.width - 6f, y - t.size.height / 2))
            }

            val slot = chartW / segments.size
            val barW = slot * 0.62f
            val trend = Path()
            segments.forEachIndexed { i, seg ->
                val h = chartH * seg.distanceMeters / maxV
                val left = leftPad + i * slot + (slot - barW) / 2
                val base = if (seg.phase == 1) PP.Grass else PP.Sky
                val dim = highlightPhase != null && highlightPhase != seg.phase
                drawRoundRect(
                    color = if (dim) base.copy(alpha = 0.25f) else base,
                    topLeft = Offset(left, chartH - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(4f, 4f),
                )
                val cx = left + barW / 2
                if (i == 0) trend.moveTo(cx, chartH - h) else trend.lineTo(cx, chartH - h)
                val step = if (segments.size > 12) 2 else 1
                if (i % step == 0) {
                    val t = measurer.measure("${seg.startMinute}'", labelStyle)
                    drawText(t, topLeft = Offset(cx - t.size.width / 2, chartH + 6f))
                }
            }
            drawPath(trend, PP.Amber, style = Stroke(width = 3f))
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = NumberStyle.Small.copy(fontSize = 11.sp), color = PP.Muted, modifier = Modifier.padding(start = 4.dp))
    }
}
