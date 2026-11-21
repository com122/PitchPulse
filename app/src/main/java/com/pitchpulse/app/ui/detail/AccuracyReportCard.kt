package com.pitchpulse.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitchpulse.app.data.model.MatchStats
import com.pitchpulse.app.data.model.RefPlacement
import com.pitchpulse.app.domain.algorithm.AccuracyMetrics
import com.pitchpulse.app.domain.algorithm.AccuracyReport
import com.pitchpulse.app.domain.algorithm.EvaluationMode
import com.pitchpulse.app.ui.components.SectionCard
import com.pitchpulse.app.ui.theme.NumberStyle
import com.pitchpulse.app.ui.theme.PP
import com.pitchpulse.app.util.Formatters

/** "精度报告": what each accuracy layer did, why, and the before/after metrics. */
@Composable
fun AccuracyReportCard(
    report: AccuracyReport?,
    error: String?,
    rawStats: MatchStats,
    enhancedStats: MatchStats?,
    overlap: Double?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard(title = "精度报告") {
        if (error != null) {
            Text(error, color = PP.Red, style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        if (report == null) {
            Text("正在计算增强轨迹…", color = PP.Muted, style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }

        // Reference station.
        val ref = report.reference
        if (ref == null) {
            Muted(if (overlap == null) "未找到与本场时间重叠的参考站录制：只做平滑和球场约束（Tier C / D）。" else report.notes.firstOrNull() ?: "参考站数据不可用")
        } else {
            Line(
                "参考站",
                buildString {
                    append(if (ref.placement == RefPlacement.KNOWN_POINT) "已知点 · ${ref.knownPoint?.label}" else "未知点（只修正时变误差）")
                    append(" · 覆盖 ${pct(ref.coverage)}")
                    if (ref.simulated) append(" · 仿真")
                },
            )
            if (ref.placement == RefPlacement.KNOWN_POINT) {
                Line("手机自身偏差", "${m(ref.phoneBias)}（相对已知点），漂移 P95 ${m(ref.phoneWander95)}")
            }
        }
        Line("时间对齐", report.alignment.status.label, if (report.alignment.warning) PP.Amber else PP.Text)
        Line("评估方式", report.mode.label)
        Spacer(Modifier.height(8.dp))

        // Layers.
        for (d in report.layers) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 3.dp)) {
                Icon(
                    if (d.kept) Icons.Default.CheckCircle else Icons.Default.RemoveCircleOutline, null,
                    tint = if (d.kept) PP.Grass else PP.Muted, modifier = Modifier.padding(top = 1.dp).width(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(d.layer.label, style = MaterialTheme.typography.labelLarge, color = if (d.kept) PP.Text else PP.Muted)
                    Text(d.reason, style = MaterialTheme.typography.labelSmall, color = if (d.reason.startsWith("自动关闭")) PP.Amber else PP.Muted)
                }
            }
        }
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 3.dp)) {
            Icon(Icons.Default.RemoveCircleOutline, null, tint = PP.Muted, modifier = Modifier.padding(top = 1.dp).width(18.dp))
            Spacer(Modifier.width(6.dp))
            Column {
                Text("B · 伪距差分（实验）", style = MaterialTheme.typography.labelLarge, color = PP.Muted)
                Text(report.tierBStatus, style = MaterialTheme.typography.labelSmall, color = PP.Muted)
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = PP.Border)
        Spacer(Modifier.height(8.dp))

        // Key numbers.
        if (enhancedStats != null) {
            Line("总里程", "${Formatters.km(rawStats.totalDistanceMeters)} → ${Formatters.km(enhancedStats.totalDistanceMeters)} km")
            Line("冲刺次数", "${rawStats.sprintCount} → ${enhancedStats.sprintCount}")
        }
        MetricsTable(report.mode, report.raw, report.final)

        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起技术细节" else "展开技术细节", color = PP.Grass)
        }
        if (expanded) Details(report)
    }
}

@Composable
private fun MetricsTable(mode: EvaluationMode, raw: AccuracyMetrics, fin: AccuracyMetrics) {
    val rows = buildList {
        if (mode == EvaluationMode.PROTOCOL) {
            add(Triple("边线垂距 中位数", raw.sidelineMedian, fin.sidelineMedian))
            add(Triple("边线垂距 P90", raw.sidelineP90, fin.sidelineP90))
            add(Triple("中线垂距 P90", raw.halfwayP90, fin.halfwayP90))
            add(Triple("静止 CEP50（相对已知点）", raw.staticAbsCep50, fin.staticAbsCep50))
            add(Triple("静止 CEP95（相对已知点）", raw.staticAbsCep95, fin.staticAbsCep95))
        }
        add(Triple("静止 CEP50（离散度）", raw.staticCep50, fin.staticCep50))
        add(Triple("静止 CEP95（离散度）", raw.staticCep95, fin.staticCep95))
    }.filter { it.second != null || it.third != null }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("指标", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = PP.Muted)
        Text("原始", Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, color = PP.Muted)
        Text("增强", Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, color = PP.Muted)
    }
    for ((name, a, b) in rows) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(a?.let { m(it) } ?: "--", Modifier.width(64.dp), style = NumberStyle.Small)
            Text(b?.let { m(it) } ?: "--", Modifier.width(64.dp), style = NumberStyle.Small,
                color = if (a != null && b != null && b < a) PP.Grass else PP.Text)
        }
    }
    val fracRows = listOfNotNull(
        if (mode == EvaluationMode.PROTOCOL && (raw.zoneMisclass != null || fin.zoneMisclass != null)) Triple("中线两侧分区误判率", raw.zoneMisclass, fin.zoneMisclass) else null,
        Triple("界外时间比例（> 2 m）", raw.oobFraction, fin.oobFraction),
    )
    for ((name, a, b) in fracRows) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(a?.let { pct(it) } ?: "--", Modifier.width(64.dp), style = NumberStyle.Small)
            Text(b?.let { pct(it) } ?: "--", Modifier.width(64.dp), style = NumberStyle.Small)
        }
    }
    if (mode == EvaluationMode.MATCH) {
        Muted("比赛中没有地面真值：静止离散度来自中场休息等静止时段，界外比例用于检查整体偏移。要得到真实精度，请做一次「精度测试」。")
    }
}

@Composable
private fun Details(r: AccuracyReport) {
    r.tierA?.let { a ->
        Line("K_E / K_N / K_b", "%.2f / %.2f / %.1f".format(a.kE, a.kN, a.kB))
        Line("R² / 交叉验证 R²", "%.2f / %s".format(a.r2, if (a.cvR2.isNaN()) "--" else "%.2f".format(a.cvR2)))
        Line("静止样本", "${a.stationarySec.toInt()} s · ${a.blocks} 块")
        Line("交叉验证静止 CEP95", "${m(a.cvCep95Off)} → ${m(a.cvCep95On)}")
        Line("偏差 b", "(${"%.2f".format(a.biasE)}, ${"%.2f".format(a.biasN)}) m")
        a.lowCommonSatFraction?.let { Line("共视卫星 < 4 的历元", pct(it)) }
    }
    r.tierAApplied?.let { Line("改正量 中位数 / P90 / 最大", "${m(it.magnitudeMedian)} / ${m(it.magnitudeP90)} / ${m(it.magnitudeMax)}") }
    r.tierC?.let { c ->
        Line("χ² 剔除 / 重置 / 分段", "${c.rejected} / ${c.resets} / ${c.segments}")
        Line("观测噪声缩放 / 平均 NIS", "%.2f / %.2f".format(c.rScale, c.meanNis))
        Line("速度来源", c.speedSource)
    }
    r.tierD?.let { Line("球场约束", it.reason) }
    r.alignment.clockOffsetMs?.let { Line("手表时钟 − GNSS", "${it} ms（波动 ${r.alignment.clockJitterMs} ms）") }
    r.notes.forEach { Muted("· $it") }
}

@Composable
private fun Line(label: String, value: String, color: Color = PP.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = PP.Muted)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = PP.Muted, modifier = Modifier.padding(vertical = 2.dp))
}

private fun m(v: Double) = "%.2f m".format(v)
private fun pct(v: Double) = "%.1f%%".format(v * 100)
