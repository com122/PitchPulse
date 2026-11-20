package com.pitchpulse.app.ui.detail

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitchpulse.app.domain.algorithm.PitchMarkings
import com.pitchpulse.app.domain.algorithm.ProjectedPoint
import com.pitchpulse.app.ui.components.HrZoneChart
import com.pitchpulse.app.ui.components.PitchCanvas
import com.pitchpulse.app.ui.components.SectionCard
import com.pitchpulse.app.ui.components.SegmentDistanceChart
import com.pitchpulse.app.ui.components.StatTile
import com.pitchpulse.app.ui.components.Tag
import com.pitchpulse.app.ui.theme.PP
import com.pitchpulse.app.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchDetailScreen(viewModel: MatchDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.exportReport()
        else Toast.makeText(context, "需要存储权限才能保存到相册", Toast.LENGTH_SHORT).show()
    }

    fun requestExport() {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else viewModel.exportReport()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is DetailEvent.Toast -> Toast.makeText(context, e.text, Toast.LENGTH_LONG).show()
                DetailEvent.Closed -> onBack()
            }
        }
    }

    Scaffold(
        containerColor = PP.Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("赛后复盘", fontWeight = FontWeight.Bold)
                        state.match?.let {
                            Text(Formatters.dateTime(it.startTime), style = MaterialTheme.typography.labelSmall, color = PP.Muted)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    if (state.exporting) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = PP.Grass, strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                    } else {
                        TextButton(onClick = ::requestExport, enabled = state.heatmap != null) {
                            Icon(Icons.Default.IosShare, null, tint = PP.Grass)
                            Text(" 导出战报", color = PP.Grass)
                        }
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "删除比赛", tint = PP.Muted) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PP.Background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.error != null -> Text(state.error!!, color = PP.Red, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = PP.Grass)
                else -> DetailContent(state, viewModel)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = PP.Card,
            title = { Text("删除这场比赛？") },
            text = { Text("比赛摘要和原始轨迹文件都会被删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteMatch() }) { Text("删除", color = PP.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailContent(state: MatchDetailUiState, viewModel: MatchDetailViewModel) {
    val match = state.match ?: return
    val pitch = state.pitch ?: return
    val markings = remember(pitch) { PitchMarkings.forPitch(pitch.pitchType, pitch.lengthMeters, pitch.widthMeters) }
    val heatmap = remember(state.heatmap) { state.heatmap?.asImageBitmap() }
    val s = state.stats

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pitch.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
            Tag("${pitch.pitchType}人制")
            match.position?.let { Tag(it.label, PP.Sky) }
            Tag(match.source.label, PP.Muted)
        }

        // Pitch + layer
        SectionCard {
            Box {
                PitchCanvas(
                    markings = markings,
                    heatmap = if (state.layer == PitchLayer.HEATMAP) heatmap else null,
                    overlay = when (state.layer) {
                        PitchLayer.HEATMAP -> null
                        PitchLayer.TRACK -> { rect -> drawTrack(rect, state.projected, state.settings.sprintSpeedMs) }
                        PitchLayer.SPRINTS -> { rect -> drawSprints(rect, state.sprintPaths) }
                    },
                )
                if (state.rendering) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = PP.Grass, strokeWidth = 3.dp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.swapSecondHalf) "进攻方向 → （下半场已 180° 翻转对齐）" else "原始场地坐标（上下半场方向相反）",
                style = MaterialTheme.typography.labelSmall, color = PP.Muted,
            )
            Spacer(Modifier.height(12.dp))

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                MatchSegment.entries.forEachIndexed { i, seg ->
                    SegmentedButton(
                        selected = state.segment == seg,
                        onClick = { viewModel.selectSegment(seg) },
                        shape = SegmentedButtonDefaults.itemShape(i, MatchSegment.entries.size),
                        colors = segColors(),
                    ) { Text(seg.label) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("下半场换边校正（180°翻转）", style = MaterialTheme.typography.bodyMedium)
                    Text("对下半场点执行 (1-x, 1-y) 中心对称", style = MaterialTheme.typography.labelSmall, color = PP.Muted)
                }
                Switch(
                    checked = state.swapSecondHalf,
                    onCheckedChange = viewModel::setSwap,
                    colors = SwitchDefaults.colors(checkedTrackColor = PP.Grass, checkedThumbColor = PP.Background),
                )
            }
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PitchLayer.entries.forEachIndexed { i, layer ->
                    SegmentedButton(
                        selected = state.layer == layer,
                        onClick = { viewModel.selectLayer(layer) },
                        shape = SegmentedButtonDefaults.itemShape(i, PitchLayer.entries.size),
                        colors = segColors(),
                    ) { Text(layer.label, maxLines = 1) }
                }
            }
            if (state.layer == PitchLayer.SPRINTS) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "共 ${state.sprintPaths.size} 段冲刺（≥ ${Formatters.oneDecimal(state.settings.sprintSpeedMs * 3.6f)} km/h 且 ≥ ${Formatters.oneDecimal(state.settings.sprintMinDurationSec)} 秒），● 起点 ◆ 终点",
                    style = MaterialTheme.typography.labelSmall, color = PP.Muted,
                )
            }
        }

        // Metrics 2 x 3
        SectionCard(title = "核心指标 · ${state.segment.label}") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("总里程", Formatters.km(s.totalDistanceMeters), "km", Modifier.weight(1f), PP.Grass)
                StatTile("均心率", if (s.avgHr > 0) s.avgHr.toString() else "--", "bpm", Modifier.weight(1f))
                StatTile("最高心率", if (s.maxHr > 0) s.maxHr.toString() else "--", "bpm", Modifier.weight(1f), PP.Red)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("最高时速", Formatters.oneDecimal(s.maxSpeedKmh), "km/h", Modifier.weight(1f), PP.Amber)
                StatTile("冲刺次数", s.sprintCount.toString(), "次", Modifier.weight(1f), PP.Orange)
                StatTile("高强度跑动", Formatters.meters(s.highIntensityDistanceMeters), "m", Modifier.weight(1f), PP.Sky)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "有效时长 ${Formatters.duration(s.effectiveDurationMs)} · 冲刺总距离 ${Formatters.meters(s.sprintDistanceMeters)} m · " +
                    "高强度 = ≥14.4 km/h · 有效点 ${s.validPointCount}/${s.rawPointCount}",
                style = MaterialTheme.typography.labelSmall, color = PP.Muted,
            )
        }

        SectionCard(title = "心率区间分布 · 最大心率 ${state.settings.maxHr} bpm") {
            if (s.hrZones.totalMs == 0L) {
                Text("该时段没有心率数据", color = PP.Muted, style = MaterialTheme.typography.bodySmall)
            } else {
                HrZoneChart(s.hrZones, state.settings.maxHr)
            }
        }

        SectionCard(title = "每 5 分钟跑动距离") {
            val full = state.fullStats
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("上半场 ${Formatters.km(full.firstHalfDistanceMeters)} km", style = MaterialTheme.typography.labelMedium, color = PP.Grass)
                Text("下半场 ${Formatters.km(full.secondHalfDistanceMeters)} km", style = MaterialTheme.typography.labelMedium, color = PP.Sky)
                if (full.firstHalfDistanceMeters > 0f && full.secondHalfDistanceMeters > 0f) {
                    val change = (full.secondHalfDistanceMeters / full.firstHalfDistanceMeters - 1f) * 100f
                    Text(
                        "${if (change >= 0) "+" else ""}${Formatters.oneDecimal(change)}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (change < 0) PP.Orange else PP.Grass,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SegmentDistanceChart(full.segments, highlightPhase = state.segment.phase)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun segColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = PP.Grass.copy(alpha = 0.2f),
    activeContentColor = PP.Grass,
    activeBorderColor = PP.Grass,
    inactiveContainerColor = PP.Card,
    inactiveContentColor = PP.Muted,
    inactiveBorderColor = PP.Border,
)

private fun toScreen(rect: Rect, p: ProjectedPoint) =
    Offset(rect.left + p.nx * rect.width, rect.top + p.ny * rect.height)

private fun speedColor(speed: Float, sprint: Float): Color = when {
    speed >= sprint -> PP.Red
    speed >= 4.0f -> PP.Orange
    speed >= 2.0f -> PP.Amber
    else -> PP.Sky.copy(alpha = 0.7f)
}

/** Track polyline coloured by speed band (4 batched paths). */
private fun DrawScope.drawTrack(rect: Rect, points: List<ProjectedPoint>, sprintSpeed: Float) {
    if (points.size < 2) return
    val bands = listOf(0f, 2f, 4f, sprintSpeed)
    val paths = List(4) { Path() }
    for (i in 1 until points.size) {
        val b = points[i]
        if (b.breakBefore) continue
        val a = points[i - 1]
        val band = bands.indexOfLast { b.speedMs >= it }.coerceAtLeast(0)
        val pa = toScreen(rect, a)
        val pb = toScreen(rect, b)
        paths[band].moveTo(pa.x, pa.y)
        paths[band].lineTo(pb.x, pb.y)
    }
    val colors = listOf(PP.Sky.copy(alpha = 0.45f), PP.Amber.copy(alpha = 0.75f), PP.Orange, PP.Red)
    val widths = listOf(1.5f, 2f, 2.5f, 3f)
    for (k in 0 until 4) {
        drawPath(paths[k], colors[k], style = Stroke(width = widths[k], cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    // Legend dots at the start and end.
    drawCircle(Color.White, 5f, toScreen(rect, points.first()))
    drawCircle(PP.Red, 5f, toScreen(rect, points.last()))
}

/** Each sprint as a red path with start dot and end diamond; per-sample dots coloured by speed. */
private fun DrawScope.drawSprints(rect: Rect, sprints: List<List<ProjectedPoint>>) {
    for (run in sprints) {
        val path = Path()
        run.forEachIndexed { i, p ->
            val o = toScreen(rect, p)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, PP.Red.copy(alpha = 0.8f), style = Stroke(width = 3f, cap = StrokeCap.Round))
        run.forEach { p -> drawCircle(speedColor(p.speedMs, 0f).copy(alpha = 0.9f), 3.5f, toScreen(rect, p)) }
        val start = toScreen(rect, run.first())
        drawCircle(Color.White, 5.5f, start)
        val end = toScreen(rect, run.last())
        val d = 7f
        val diamond = Path().apply {
            moveTo(end.x, end.y - d); lineTo(end.x + d, end.y); lineTo(end.x, end.y + d); lineTo(end.x - d, end.y); close()
        }
        drawPath(diamond, PP.Amber)
    }
}
