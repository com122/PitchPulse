package com.pitchpulse.app.ui.settings

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitchpulse.app.BuildConfig
import com.pitchpulse.app.data.repository.WatchConnectionState
import com.pitchpulse.app.ui.components.SectionCard
import com.pitchpulse.app.ui.theme.NumberStyle
import com.pitchpulse.app.ui.theme.PP
import com.pitchpulse.app.util.Formatters
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val watch by viewModel.watchState.collectAsStateWithLifecycle()
    val recompute by viewModel.recompute.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        containerColor = PP.Background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Settings, null, tint = PP.Grass)
                        Text("  设置与同步", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PP.Background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = settings
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PP.Grass)
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(title = "个人体能参数") {
                SettingSlider(
                    label = "最大心率", value = s.maxHr.toFloat(), range = 150f..220f, steps = 69,
                    display = { "${it.roundToInt()} bpm" },
                ) { v -> viewModel.update { it.copy(maxHr = v.roundToInt()) } }
                SettingSlider(
                    label = "静息心率", value = s.restingHr.toFloat(), range = 35f..100f, steps = 64,
                    display = { "${it.roundToInt()} bpm" },
                    hint = "用于仿真比赛的心率基线（心率储备 = 最大 − 静息）",
                ) { v -> viewModel.update { it.copy(restingHr = v.roundToInt()) } }
                SettingSlider(
                    label = "冲刺速度阈值", value = s.sprintSpeedMs, range = 4.0f..8.0f, steps = 39,
                    display = { "${Formatters.oneDecimal(it)} m/s · ${Formatters.oneDecimal(it * 3.6f)} km/h" },
                ) { v -> viewModel.update { it.copy(sprintSpeedMs = (v * 10).roundToInt() / 10f) } }
                SettingSlider(
                    label = "冲刺最短持续时间", value = s.sprintMinDurationSec, range = 1f..5f, steps = 7,
                    display = { "${Formatters.oneDecimal(it)} 秒" },
                ) { v -> viewModel.update { it.copy(sprintMinDurationSec = (v * 2).roundToInt() / 2f) } }
                SettingSlider(
                    label = "热区模糊半径 σ", value = s.heatmapSigma, range = 1f..6f, steps = 19,
                    display = { "${Formatters.oneDecimal(it)} 格" },
                    hint = "网格 120×80，σ 越大热区越平滑",
                ) { v -> viewModel.update { it.copy(heatmapSigma = (v * 4).roundToInt() / 4f) } }

                val zones = (0 until 5).joinToString("  ") { i ->
                    "Z${i + 1} ${(s.maxHr * (0.5f + i * 0.1f)).roundToInt()}+"
                }
                Text("心率区间：$zones", style = NumberStyle.Small.copy(fontSize = 11.sp), color = PP.Muted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = viewModel::recomputeAll, enabled = recompute == null) {
                        if (recompute != null) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = PP.Grass, strokeWidth = 2.dp)
                            Text("  ${recompute!!.first}/${recompute!!.second}")
                        } else {
                            Icon(Icons.Default.Refresh, null)
                            Text(" 按当前参数重算全部比赛")
                        }
                    }
                    TextButton(onClick = viewModel::reset) { Text("恢复默认") }
                }
            }

            WatchCard(watch)

            SectionCard(title = "关于") {
                Text("PitchPulse v${BuildConfig.VERSION_NAME}", style = NumberStyle.Small)
                Text(
                    "地图：Leaflet（离线内置）+ Esri World Imagery / OpenStreetMap / 高德卫星 XYZ 瓦片，无需 API Key。",
                    style = MaterialTheme.typography.labelSmall, color = PP.Muted,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: (Float) -> String,
    hint: String? = null,
    onCommit: (Float) -> Unit,
) {
    // Local state while dragging; persisted on release to avoid hammering DataStore.
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display(local), style = NumberStyle.Small, color = PP.Grass)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = PP.Grass, activeTrackColor = PP.Grass, activeTickColor = PP.Grass.copy(alpha = 0f), inactiveTickColor = PP.Border.copy(alpha = 0f)),
        )
        if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = PP.Muted)
    }
}

@Composable
private fun WatchCard(state: WatchConnectionState) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Watch, null, tint = PP.Sky, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Galaxy Watch 4 同步", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (color, text) = when (state) {
                        WatchConnectionState.AwaitingWatch -> PP.Amber to "待接入 Watch 4 采集端"
                        is WatchConnectionState.Connected -> PP.Grass to "已连接 ${state.deviceName}"
                    }
                    Box(Modifier.size(8.dp).padding(0.dp)) {
                        Surface(shape = CircleShape, color = color, modifier = Modifier.fillMaxSize()) {}
                    }
                    Text("  $text", style = MaterialTheme.typography.labelMedium, color = color)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("数据契约（每场一个 JSONL 文件，每行一个采样点）", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Surface(shape = RoundedCornerShape(8.dp), color = PP.Background, border = BorderStroke(1.dp, PP.Border)) {
            Text(
                "{\"t\":1732070400000,\"lat\":22.543101,\"lng\":113.942011,\n \"acc\":3.8,\"spd\":2.41,\"hr\":152,\"p\":1}",
                style = NumberStyle.Small.copy(fontSize = 11.sp),
                color = PP.Grass,
                modifier = Modifier.fillMaxWidth().padding(10.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        val fields = listOf(
            "t" to "Long · 毫秒时间戳",
            "lat / lng" to "Double · WGS-84 经纬度",
            "acc" to "Float · GPS 水平精度 (m)，>15 m 的点被过滤",
            "spd" to "Float · 瞬时速度 (m/s)",
            "hr" to "Int · 实时心率 (bpm)",
            "p" to "Int · 1 上半场 / 2 下半场 / 0 中场暂停",
        )
        fields.forEach { (k, v) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(k, style = NumberStyle.Small, color = PP.Text, modifier = Modifier.width(80.dp))
                Text(v, style = MaterialTheme.typography.labelSmall, color = PP.Muted)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "存储位置：filesDir/tracks/{matchId}.jsonl。手表端只需传输该文件，手机端经 WatchSyncRepository.importTrackJsonl() 完成解析、最近球场匹配、指标统计与入库。",
            style = MaterialTheme.typography.labelSmall, color = PP.Muted,
        )
    }
}
