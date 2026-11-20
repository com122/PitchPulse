package com.pitchpulse.app.ui.matches

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitchpulse.app.data.model.MatchRecord
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.model.PlayerPosition
import com.pitchpulse.app.domain.algorithm.PitchMarkings
import com.pitchpulse.app.ui.components.PitchCanvas
import com.pitchpulse.app.ui.components.Tag
import com.pitchpulse.app.ui.theme.NumberStyle
import com.pitchpulse.app.ui.theme.PP
import com.pitchpulse.app.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchesScreen(viewModel: MatchesViewModel, onOpenMatch: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val thumbVersion by viewModel.thumbnailVersion.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var fabExpanded by rememberSaveable { mutableStateOf(false) }
    var showSimDialog by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.import(uri)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is MatchesEvent.Message -> snackbar.showSnackbar(e.text)
                is MatchesEvent.OpenMatch -> onOpenMatch(e.matchId)
            }
        }
    }

    Scaffold(
        containerColor = PP.Background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SportsSoccer, null, tint = PP.Grass)
                        Text("  比赛记录", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PP.Background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (fabExpanded) {
                    FabAction("生成仿真比赛", Icons.Default.SportsSoccer) {
                        fabExpanded = false; showSimDialog = true
                    }
                    FabAction("导入 JSONL / GPX", Icons.Default.FileOpen) {
                        fabExpanded = false
                        picker.launch(arrayOf("*/*"))
                    }
                }
                FloatingActionButton(
                    onClick = { fabExpanded = !fabExpanded },
                    containerColor = PP.Grass,
                    contentColor = PP.Background,
                ) {
                    Icon(if (fabExpanded) Icons.Default.Close else Icons.Default.Add, "操作菜单")
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { OverviewCard(state.overview) }
                if (!state.loading && state.matches.isEmpty()) {
                    item { EmptyHint() }
                }
                items(state.matches, key = { it.id }) { match ->
                    val pitch = state.pitches.firstOrNull { it.id == match.pitchId }
                    MatchCard(
                        match = match,
                        pitch = pitch,
                        thumbKey = thumbVersion,
                        loadThumb = { viewModel.thumbnail(match) },
                        onClick = { onOpenMatch(match.id) },
                    )
                }
            }
            state.busyMessage?.let { BusyOverlay(it) }
        }
    }

    if (showSimDialog) {
        SimulationDialog(
            pitches = state.pitches,
            onDismiss = { showSimDialog = false },
            onConfirm = { pitchId, position ->
                showSimDialog = false
                viewModel.simulate(pitchId, position)
            },
        )
    }
}

@Composable
private fun FabAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(8.dp), color = PP.CardHigh, border = BorderStroke(1.dp, PP.Border)) {
            Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
        }
        SmallFloatingActionButton(onClick = onClick, containerColor = PP.CardHigh, contentColor = PP.Grass) {
            Icon(icon, label)
        }
    }
}

@Composable
private fun OverviewCard(o: SeasonOverview) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = PP.Card),
        border = BorderStroke(1.dp, PP.Grass.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("赛季概览 · 全部记录", style = MaterialTheme.typography.labelLarge, color = PP.Grass)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OverviewItem("总场次", o.matchCount.toString(), "场")
                OverviewItem("累计里程", Formatters.oneDecimal(o.totalKm), "km")
                OverviewItem("场均心率", if (o.avgHr > 0) o.avgHr.toString() else "--", "bpm")
                OverviewItem("最高时速", Formatters.oneDecimal(o.topSpeedKmh), "km/h")
            }
        }
    }
}

@Composable
private fun OverviewItem(label: String, value: String, unit: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = PP.Muted)
        Text(value, style = NumberStyle.Large, color = PP.Text)
        Text(unit, style = NumberStyle.Small, color = PP.Muted)
    }
}

@Composable
private fun MatchCard(
    match: MatchRecord,
    pitch: PitchConfig?,
    thumbKey: Int,
    loadThumb: suspend () -> Bitmap?,
    onClick: () -> Unit,
) {
    val thumb: ImageBitmap? by produceState<ImageBitmap?>(null, match.id, thumbKey, pitch) {
        value = loadThumb()?.asImageBitmap()
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PP.Card),
        border = BorderStroke(1.dp, PP.Border),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${Formatters.date(match.startTime)}  ${Formatters.time(match.startTime)}",
                    style = MaterialTheme.typography.labelMedium, color = PP.Muted,
                )
                Text(
                    match.pitchName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (match.pitchType > 0) Tag("${match.pitchType}人制")
                    match.position?.let { Tag(it.label, PP.Sky) }
                    Tag(match.source.label, PP.Muted)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MiniStat(Formatters.km(match.stats.totalDistanceMeters), "km")
                    MiniStat(if (match.stats.avgHr > 0) match.stats.avgHr.toString() else "--", "bpm")
                    MiniStat(match.stats.sprintCount.toString(), "冲刺")
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(118.dp)) {
                val markings = pitch?.let { PitchMarkings.forPitch(it.pitchType, it.lengthMeters, it.widthMeters) }
                    ?: PitchMarkings.forPitch(7, 60f, 40f)
                PitchCanvas(markings, heatmap = thumb, mini = true)
            }
        }
    }
}

@Composable
private fun MiniStat(value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = NumberStyle.Medium, color = PP.Text)
        Text(" $unit", style = NumberStyle.Small, color = PP.Muted, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun EmptyHint() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.SportsSoccer, null, tint = PP.Muted, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text("还没有比赛记录", style = MaterialTheme.typography.titleMedium)
        Text(
            "点击右下角 + 生成一场仿真比赛，或导入 JSONL / GPX 轨迹文件",
            style = MaterialTheme.typography.bodySmall, color = PP.Muted,
        )
    }
}

@Composable
private fun BusyOverlay(message: String) {
    Box(
        Modifier.fillMaxSize().clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = PP.CardHigh, border = BorderStroke(1.dp, PP.Border)) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = PP.Grass, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(14.dp))
                Text(message)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimulationDialog(
    pitches: List<PitchConfig>,
    onDismiss: () -> Unit,
    onConfirm: (String, PlayerPosition) -> Unit,
) {
    var pitchId by rememberSaveable { mutableStateOf(pitches.firstOrNull()?.id) }
    var position by rememberSaveable { mutableStateOf(PlayerPosition.MIDFIELDER) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PP.Card,
        title = { Text("生成仿真比赛") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "围绕所选球场生成 90 分钟 1Hz 完整比赛：含位置跑位偏向、冲刺、15 分钟中场休息、下半场换边与 2-4 米 GPS 噪声。",
                    style = MaterialTheme.typography.bodySmall, color = PP.Muted,
                )
                Text("踢球位置", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerPosition.entries.forEach { p ->
                        FilterChip(selected = position == p, onClick = { position = p }, label = { Text(p.label) })
                    }
                }
                Text("球场", style = MaterialTheme.typography.labelLarge)
                if (pitches.isEmpty()) {
                    Text("请先在「球场」页添加一个球场", color = PP.Red)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pitches.forEach { p ->
                        FilterChip(
                            selected = pitchId == p.id,
                            onClick = { pitchId = p.id },
                            label = { Text("${p.name} · ${p.pitchType}人制", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = pitchId != null, onClick = { pitchId?.let { onConfirm(it, position) } }) { Text("生成并入库") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
