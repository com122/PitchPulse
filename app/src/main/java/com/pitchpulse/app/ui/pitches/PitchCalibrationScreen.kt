package com.pitchpulse.app.ui.pitches

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.domain.algorithm.PitchMarkings
import com.pitchpulse.app.ui.components.PitchCompass
import com.pitchpulse.app.ui.components.SectionCard
import com.pitchpulse.app.ui.theme.NumberStyle
import com.pitchpulse.app.ui.theme.PP
import com.pitchpulse.app.util.Formatters
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PitchCalibrationScreen(viewModel: PitchCalibrationViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.setNetworkAvailable(isOnline(context))
        viewModel.events.collect { msg ->
            if (msg == PitchCalibrationViewModel.SAVED) {
                Toast.makeText(context, "球场已保存", Toast.LENGTH_SHORT).show()
                onBack()
            } else {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        containerColor = PP.Background,
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "新增球场" else "编辑球场", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = PP.Grass, strokeWidth = 2.dp)
                        Spacer(Modifier.width(16.dp))
                    } else {
                        TextButton(onClick = viewModel::save) {
                            Icon(Icons.Default.Check, null, tint = PP.Grass)
                            Text(" 保存", color = PP.Grass)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PP.Background),
            )
        },
    ) { padding ->
        if (!state.loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PP.Grass)
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                CalibrationMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = state.mode == mode,
                        onClick = { viewModel.setMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, CalibrationMode.entries.size),
                        colors = segColors(),
                    ) { Text(mode.label) }
                }
            }
            Spacer(Modifier.height(8.dp))
            when (state.mode) {
                CalibrationMode.MAP -> MapMode(state, viewModel)
                CalibrationMode.MANUAL -> ManualMode(state, viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapMode(state: CalibrationUiState, viewModel: PitchCalibrationViewModel) {
    val center = remember(state.layer) { viewModel.displayCenter(state) }
    val overlay = viewModel.overlayJson(state)

    Column(Modifier.fillMaxSize()) {
        if (!state.networkAvailable) {
            OfflineBanner("当前无网络，卫星瓦片无法加载。可切换到「手动参数模式」完成标定。")
        } else if (state.tilesFailing) {
            OfflineBanner("${state.layer.label} 瓦片加载失败，请尝试切换图层或使用手动参数模式。")
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            MapLayer.entries.forEachIndexed { i, layer ->
                SegmentedButton(
                    selected = state.layer == layer,
                    onClick = { viewModel.setLayer(layer) },
                    shape = SegmentedButtonDefaults.itemShape(i, MapLayer.entries.size),
                    colors = segColors(),
                ) { Text(layer.label, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, PP.Border), color = PP.Card) {
                MapWebView(
                    modifier = Modifier.fillMaxSize(),
                    layerId = state.layer.id,
                    centerLat = center[0],
                    centerLng = center[1],
                    overlayJson = overlay,
                    fitRequest = state.fitRequest,
                    onMapClick = viewModel::onMapClick,
                    onTileStatus = viewModel::onTileStatus,
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                shape = RoundedCornerShape(8.dp),
                color = PP.Background.copy(alpha = 0.85f),
            ) {
                Text(
                    when (state.corners.size) {
                        4 -> "已解算 ✓ 再次点击将重新标定"
                        else -> "依次点击球场 4 个角旗杆（${state.corners.size}/4）"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = PP.Grass,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().height(300.dp).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::undoCorner, enabled = state.corners.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null); Text(" 撤销")
                }
                OutlinedButton(onClick = viewModel::clearCorners, enabled = state.corners.isNotEmpty()) {
                    Icon(Icons.Default.ClearAll, null); Text(" 清除")
                }
            }
            if (state.layer.gcj02) {
                Text("高德卫星为 GCJ-02 坐标，拾取点已自动纠偏为 WGS-84 入库。", style = MaterialTheme.typography.labelSmall, color = PP.Amber)
            }
            NameField(state, viewModel)
            if (state.hasGeometry) {
                ResultSummary(state)
                TypeChips(state) { viewModel.setPitchType(it, applyDefaultSize = false) }
                LabeledSlider("朝向微调", state.bearingDegrees, 0f..359.9f, "°", viewModel::setBearing)
                LabeledSlider("长度", state.lengthMeters, 20f..130f, "m", viewModel::setLength)
                LabeledSlider("宽度", state.widthMeters, 12f..95f, "m", viewModel::setWidth)
            }
        }
    }
}

@Composable
private fun ManualMode(state: CalibrationUiState, viewModel: PitchCalibrationViewModel) {
    val markings = remember(state.pitchType, state.lengthMeters, state.widthMeters) {
        PitchMarkings.forPitch(state.pitchType, state.lengthMeters, state.widthMeters)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.networkAvailable) {
            OfflineBanner("离线兜底模式：无需地图，直接设置尺寸、朝向与参考坐标。")
        }
        SectionCard(title = "朝向罗盘预览（拖动表盘可旋转）") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PitchCompass(
                    markings = markings,
                    bearingDegrees = state.bearingDegrees,
                    modifier = Modifier.width(240.dp),
                    onBearingChange = viewModel::setBearing,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "黄色箭头为球场纵轴朝向（相对正北顺时针 ${Formatters.oneDecimal(state.bearingDegrees)}°）",
                style = MaterialTheme.typography.labelSmall, color = PP.Muted,
            )
        }
        NameField(state, viewModel)
        TypeChips(state) { viewModel.setPitchType(it, applyDefaultSize = true) }
        LabeledSlider("长度", state.lengthMeters, 20f..130f, "m", viewModel::setLength)
        LabeledSlider("宽度", state.widthMeters, 12f..95f, "m", viewModel::setWidth)
        LabeledSlider("朝向角", state.bearingDegrees, 0f..359.9f, "°", viewModel::setBearing)
        Text("参考坐标（WGS-84 球场中心）", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = state.latText, onValueChange = viewModel::setLatText, label = { Text("纬度") },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = state.latText.toDoubleOrNull()?.let { it in -90.0..90.0 } != true,
                textStyle = NumberStyle.Small,
            )
            OutlinedTextField(
                value = state.lngText, onValueChange = viewModel::setLngText, label = { Text("经度") },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = state.lngText.toDoubleOrNull()?.let { it in -180.0..180.0 } != true,
                textStyle = NumberStyle.Small,
            )
        }
        Text(
            "提示：坐标可从任意地图 App 长按复制；若来源是高德/腾讯地图（GCJ-02），建议改用地图四点标定以自动纠偏。",
            style = MaterialTheme.typography.labelSmall, color = PP.Muted,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NameField(state: CalibrationUiState, viewModel: PitchCalibrationViewModel) {
    OutlinedTextField(
        value = state.name,
        onValueChange = viewModel::setName,
        label = { Text("球场名称") },
        placeholder = { Text("${state.pitchType} 人制球场") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ResultSummary(state: CalibrationUiState) {
    Surface(shape = RoundedCornerShape(10.dp), color = PP.Grass.copy(alpha = 0.12f), border = BorderStroke(1.dp, PP.Grass.copy(alpha = 0.5f))) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("解算结果", style = MaterialTheme.typography.labelLarge, color = PP.Grass)
            Text(
                "${Formatters.oneDecimal(state.lengthMeters)} × ${Formatters.oneDecimal(state.widthMeters)} m · 朝向 ${Formatters.oneDecimal(state.bearingDegrees)}°",
                style = NumberStyle.Medium,
            )
            Text(String.format(Locale.US, "中心 %.6f, %.6f (WGS-84)", state.centerLat, state.centerLng), style = NumberStyle.Small, color = PP.Muted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeChips(state: CalibrationUiState, onSelect: (Int) -> Unit) {
    Column {
        Text("赛制", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PitchConfig.SUPPORTED_TYPES.forEach { t ->
                FilterChip(selected = state.pitchType == t, onClick = { onSelect(t) }, label = { Text("$t 人制") })
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("${Formatters.oneDecimal(value)} $unit", style = NumberStyle.Small, color = PP.Grass)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = PP.Grass, activeTrackColor = PP.Grass),
        )
    }
}

@Composable
private fun OfflineBanner(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = PP.Amber.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, PP.Amber.copy(alpha = 0.5f)),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudOff, null, tint = PP.Amber)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = PP.Amber)
        }
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

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
