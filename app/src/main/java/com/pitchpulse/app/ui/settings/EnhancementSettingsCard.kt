package com.pitchpulse.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pitchpulse.app.data.model.EnhancementSettings
import com.pitchpulse.app.ui.components.SectionCard
import com.pitchpulse.app.ui.theme.NumberStyle
import com.pitchpulse.app.ui.theme.PP
import kotlin.math.roundToInt

@Composable
fun EnhancementSettingsCard(
    s: EnhancementSettings,
    onChange: ((EnhancementSettings) -> EnhancementSettings) -> Unit,
    onReset: () -> Unit,
    onOpenReference: () -> Unit,
) {
    SectionCard(title = "GPS 精度增强") {
        Text(
            "每一层都会在复盘时与「关」对比：让边线垂距 P90 或静止 CEP95 变差超过 5% 的层会被自动关闭，原始数据始终保留。",
            style = MaterialTheme.typography.labelSmall, color = PP.Muted,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onOpenReference, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = PP.CardHigh, contentColor = PP.Grass),
        ) { Icon(Icons.Default.SatelliteAlt, null); Text("  参考站模式 / 精度测试") }
        Spacer(Modifier.height(8.dp))
        Toggle("A · 参考站位置域改正", "用场边手机的误差改正手表轨迹（需要参考站录制）", s.tierA) { v -> onChange { it.copy(tierA = v) } }
        Toggle("B · 伪距差分（实验）", "需要手机和手表都有 GNSS 原始观测；Wear OS 手表目前不提供，打开也不会生效", s.tierB) { v -> onChange { it.copy(tierB = v) } }
        Toggle("C · 卡尔曼 + RTS 平滑", "赛后双向平滑，剔除跳点，速度/距离/冲刺用平滑结果", s.tierC) { v -> onChange { it.copy(tierC = v) } }
        Toggle("D · 球场约束平移", "没有已知点参考站时，在 ±8 m 内平移使轨迹落在球场内", s.tierD) { v -> onChange { it.copy(tierD = v) } }
        Toggle("融合手表多普勒速度", "手表提供 spd 时用于平滑和冲刺判定", s.useDeviceSpeed) { v -> onChange { it.copy(useDeviceSpeed = v) } }
        Toggle("自适应观测噪声", "按新息一致性从数据估计噪声大小，而不是直接相信 acc", s.adaptiveNoise) { v -> onChange { it.copy(adaptiveNoise = v) } }
        Toggle("复盘默认显示增强轨迹", null, s.defaultEnhanced) { v -> onChange { it.copy(defaultEnhanced = v) } }

        Param("参考站低通截止周期", s.lowPassPeriodSec, 20f..180f, 15, { "${it.roundToInt()} s" }) { v -> onChange { it.copy(lowPassPeriodSec = (v / 10).roundToInt() * 10f) } }
        Param("平滑加速度 σ", s.accelSigma, 1f..4f, 11, { "%.2f m/s²".format(it) }) { v -> onChange { it.copy(accelSigma = (v * 4).roundToInt() / 4f) } }
        Param("手表 acc 系数", s.accScale, 0.5f..3f, 9, { "× %.2f".format(it) }) { v -> onChange { it.copy(accScale = (v * 4).roundToInt() / 4f) } }
        TextButton(onClick = onReset) { Text("恢复默认") }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = PP.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = PP.Grass, checkedThumbColor = PP.Background))
    }
}

@Composable
private fun Param(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, display: (Float) -> String, onCommit: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display(local), style = NumberStyle.Small, color = PP.Grass)
        }
        Slider(
            value = local, onValueChange = { local = it }, onValueChangeFinished = { onCommit(local) },
            valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = PP.Grass, activeTrackColor = PP.Grass),
        )
    }
}
