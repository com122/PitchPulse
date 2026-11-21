package com.pitchpulse.app.ui.reference

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitchpulse.app.data.model.KnownPoint
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.model.RefPlacement
import com.pitchpulse.app.data.model.ReferenceSessionMeta
import com.pitchpulse.app.domain.algorithm.PitchMarkings
import com.pitchpulse.app.service.RecorderState
import com.pitchpulse.app.ui.components.PitchCanvas
import com.pitchpulse.app.ui.components.SectionCard
import com.pitchpulse.app.ui.components.Tag
import com.pitchpulse.app.ui.theme.NumberStyle
import com.pitchpulse.app.ui.theme.PP
import com.pitchpulse.app.util.Formatters
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceStationScreen(viewModel: ReferenceStationViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val pitches by viewModel.pitches.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val rec by viewModel.recorder.collectAsStateWithLifecycle()
    val setup by viewModel.setup.collectAsStateWithLifecycle()

    val permissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) viewModel.start(context)
        else Toast.makeText(context, "参考站需要「精确位置」权限才能记录 GPS", Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(rec.error) { rec.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }

    Scaffold(
        containerColor = PP.Background,
        topBar = {
            TopAppBar(
                title = { Text("参考站模式", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PP.Background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "踢球时把手机静止放在场边（最好放在已标定球场的一个点上），全程录制 GPS。赛后用手机的误差去改正手表轨迹。",
                style = MaterialTheme.typography.bodySmall, color = PP.Muted,
            )
            if (rec.recording) {
                RecordingCard(rec, onStop = { viewModel.stop(context) }, onMarkStart = { viewModel.markStart(context) }, onMarkEnd = { viewModel.markEnd(context) })
            } else {
                SetupCard(
                    pitches = pitches,
                    setup = setup,
                    onPitch = viewModel::setPitch,
                    onPlacement = viewModel::setPlacement,
                    onKnownPoint = viewModel::setKnownPoint,
                    onProtocol = viewModel::setProtocol,
                    onStart = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (granted) viewModel.start(context) else launcher.launch(permissions)
                    },
                )
            }
            if (setup.protocol || rec.protocol) ProtocolGuide(rec)
            SessionsCard(sessions, rec.sessionId, viewModel::delete)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SetupCard(
    pitches: List<PitchConfig>,
    setup: ReferenceSetup,
    onPitch: (String) -> Unit,
    onPlacement: (RefPlacement) -> Unit,
    onKnownPoint: (KnownPoint) -> Unit,
    onProtocol: (Boolean) -> Unit,
    onStart: () -> Unit,
) {
    SectionCard(title = "开始录制") {
        Text("球场", style = MaterialTheme.typography.labelLarge)
        if (pitches.isEmpty()) Text("请先在「球场」页添加并标定球场", color = PP.Red, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pitches.forEach { p -> FilterChip(selected = setup.pitchId == p.id, onClick = { onPitch(p.id) }, label = { Text(p.name) }) }
        }
        Spacer(Modifier.height(8.dp))
        Text("放置方式", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            RefPlacement.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = setup.placement == p, onClick = { onPlacement(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, RefPlacement.entries.size),
                ) { Text(p.label) }
            }
        }
        if (setup.placement == RefPlacement.UNKNOWN_POINT) {
            Warning("未知点模式只能消除随时间变化的误差，消除不了整体偏移。想让热区对准球场，请把手机放在已知点上。")
        } else {
            Spacer(Modifier.height(8.dp))
            Text("已知点（手机放在这个点上，尽量贴地或固定在同一位置）", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KnownPoint.entries.forEach { k -> FilterChip(selected = setup.knownPoint == k, onClick = { onKnownPoint(k) }, label = { Text(k.label) }) }
            }
            pitches.firstOrNull { it.id == setup.pitchId }?.let { KnownPointDiagram(it, setup.knownPoint) }
            Text(
                "A 侧 = 面朝 2、3 号角旗所在球门时左手边的边线（图中上方）。标定越准，改正越准：已知点的位置来自你的球场标定。",
                style = MaterialTheme.typography.labelSmall, color = PP.Muted,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("精度测试模式", style = MaterialTheme.typography.bodyMedium)
                Text("按地面真值协议跑一圈，得到真实的精度指标", style = MaterialTheme.typography.labelSmall, color = PP.Muted)
            }
            Switch(
                checked = setup.protocol, onCheckedChange = onProtocol,
                colors = SwitchDefaults.colors(checkedTrackColor = PP.Grass, checkedThumbColor = PP.Background),
            )
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onStart, enabled = setup.pitchId != null, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = PP.Grass, contentColor = PP.Background),
        ) {
            Icon(Icons.Default.FiberManualRecord, null); Text("  开始录制")
        }
        Text(
            "录制使用系统 GPS（不依赖 Google 服务），每秒 1 次，可以锁屏。手机支持时还会保存 GNSS 原始观测（GnssLogger 格式）。",
            style = MaterialTheme.typography.labelSmall, color = PP.Muted, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun KnownPointDiagram(pitch: PitchConfig, point: KnownPoint) {
    val markings = remember(pitch) { PitchMarkings.forPitch(pitch.pitchType, pitch.lengthMeters, pitch.widthMeters) }
    PitchCanvas(markings, modifier = Modifier.padding(vertical = 8.dp)) { rect ->
        val c = Offset(rect.left + point.nx.toFloat() * rect.width, rect.top + point.ny.toFloat() * rect.height)
        drawCircle(PP.Amber, 10f, c)
        drawCircle(Color.White, 10f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        // Corner numbers 1..4 at (0,0), (1,0), (1,1), (0,1).
        listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f).forEach { (x, y) ->
            drawCircle(PP.Sky, 5f, Offset(rect.left + x * rect.width, rect.top + y * rect.height))
        }
    }
}

@Composable
private fun RecordingCard(rec: RecorderState, onStop: () -> Unit, onMarkStart: () -> Unit, onMarkEnd: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rec.recording) {
        while (rec.recording) { now = System.currentTimeMillis(); delay(1000) }
    }
    SectionCard(title = "录制中 · ${rec.pitchName}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FiberManualRecord, null, tint = PP.Red)
            Text("  ${Formatters.duration(now - rec.startedAt)}", style = NumberStyle.Large)
        }
        Spacer(Modifier.height(6.dp))
        StatusLine("放置", if (rec.placement == RefPlacement.KNOWN_POINT) "已知点 · ${rec.knownPoint?.label}" else "未知点")
        StatusLine("已记录定位", "${rec.fixCount}")
        StatusLine("最新精度", rec.lastFix?.let { "%.1f m".format(it.acc) } ?: "等待首次定位…")
        StatusLine("卫星（参与定位/可见）", "${rec.satsUsed} / ${rec.satsVisible}")
        StatusLine("平均 C/N0", if (rec.meanCn0 > 0) "%.1f dB-Hz".format(rec.meanCn0) else "--")
        StatusLine(
            "GNSS 原始观测",
            when (rec.measurementsSupported) {
                true -> "支持 · 已记录 ${rec.rawEpochs} 个历元"
                false -> "本机不支持"
                null -> "检测中"
            },
        )
        rec.lastFix?.let { if (it.acc > 10f) Warning("当前精度 > 10 m 的定位不会用于改正。请把手机放在开阔处，远离看台、围网和树。") }
        if (rec.protocol) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMarkStart, enabled = rec.routeStart == null && rec.fixCount > 0) {
                    Text(if (rec.routeStart == null) "我出发了" else "已出发 ✓")
                }
                OutlinedButton(onClick = onMarkEnd, enabled = rec.routeStart != null && rec.routeEnd == null) {
                    Text(if (rec.routeEnd == null) "我回来了" else "已返回 ✓")
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onStop, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = PP.Red, contentColor = Color.White),
        ) { Icon(Icons.Default.Stop, null); Text("  停止并保存") }
    }
}

@Composable
private fun ProtocolGuide(rec: RecorderState) {
    val steps = listOf(
        "手机放在已知点上，手表放在手机旁边，一起静止至少 5 分钟（手表开始记录运动）。" to (rec.recording && rec.fixCount >= 300),
        "点「我出发了」，戴着手表沿四条边线（两条边线 + 两条底线）踩线慢跑一圈。" to (rec.routeStart != null),
        "沿中线从一侧边线走到另一侧边线。" to false,
        "在中线两侧各 5 m 处来回横穿中线：每侧停 10 秒，共停 6 次。" to false,
        "回到手机旁，点「我回来了」，手表和手机并排静止 3 分钟，然后停止录制。" to (rec.routeEnd != null),
        "把手表轨迹导入 App：复盘页的「精度报告」会显示边线垂距、静止 CEP、分区误判率，以及每层的开/关对比。" to false,
    )
    SectionCard(title = "精度测试步骤（地面真值协议）") {
        steps.forEachIndexed { i, (text, done) ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("${i + 1}.", style = NumberStyle.Small, color = if (done) PP.Grass else PP.Muted, modifier = Modifier.width(22.dp))
                Text(text + if (done) "  ✓" else "", style = MaterialTheme.typography.bodySmall, color = if (done) PP.Grass else PP.Text)
            }
        }
    }
}

@Composable
private fun SessionsCard(sessions: List<ReferenceSessionMeta>, recordingId: String?, onDelete: (String) -> Unit) {
    SectionCard(title = "已录制的参考站数据") {
        if (sessions.isEmpty()) {
            Text("还没有录制。生成「手表 + 手机参考站」仿真比赛也会在这里出现一条仿真记录。", style = MaterialTheme.typography.bodySmall, color = PP.Muted)
        }
        sessions.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${Formatters.dateTime(s.startedAt)} · ${s.pitchName.ifBlank { "未关联球场" }}", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                        Tag(if (s.placement == RefPlacement.KNOWN_POINT) s.knownPoint?.label ?: "已知点" else "未知点")
                        if (s.protocol) Tag("精度测试", PP.Sky)
                        if (s.hasMeasurements) Tag("原始观测", PP.Amber)
                        if (s.simulated) Tag("仿真", PP.Muted)
                    }
                    val dur = if (s.firstFixAt != null && s.lastFixAt != null) Formatters.duration(s.lastFixAt - s.firstFixAt) else "--"
                    Text("${s.fixCount} 个定位 · 时长 $dur", style = NumberStyle.Small, color = PP.Muted)
                }
                if (s.id != recordingId) {
                    IconButton(onClick = { onDelete(s.id) }) { Icon(Icons.Default.Delete, "删除", tint = PP.Muted) }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = PP.Muted)
        Text(value, style = NumberStyle.Small)
    }
}

@Composable
private fun Warning(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp), color = PP.Amber.copy(alpha = 0.12f), border = BorderStroke(1.dp, PP.Amber.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = PP.Amber, modifier = Modifier.padding(8.dp))
    }
}
