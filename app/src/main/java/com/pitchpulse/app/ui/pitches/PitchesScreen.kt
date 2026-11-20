package com.pitchpulse.app.ui.pitches

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Stadium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitchpulse.app.domain.algorithm.PitchMarkings
import com.pitchpulse.app.ui.components.PitchCompass
import com.pitchpulse.app.ui.components.Tag
import com.pitchpulse.app.ui.theme.NumberStyle
import com.pitchpulse.app.ui.theme.PP
import com.pitchpulse.app.util.Formatters
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PitchesScreen(viewModel: PitchesViewModel, onEdit: (String?) -> Unit) {
    val items by viewModel.pitches.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<PitchItem?>(null) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        containerColor = PP.Background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Stadium, null, tint = PP.Grass)
                        Text("  我的球场 · 标定中心", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PP.Background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(null) },
                containerColor = PP.Grass,
                contentColor = PP.Background,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("新增球场") },
            )
        },
    ) { padding ->
        val list = items
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PP.Grass)
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (list.isEmpty()) {
                item {
                    Text(
                        "还没有球场。点击「新增球场」在卫星图上点击 4 个角旗完成标定，或手动填写尺寸与朝向。",
                        color = PP.Muted, modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            }
            items(list, key = { it.pitch.id }) { item ->
                PitchCard(item, onEdit = { onEdit(item.pitch.id) }, onDelete = { pendingDelete = item })
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = PP.Card,
            title = { Text("删除「${item.pitch.name}」？") },
            text = {
                Text(
                    if (item.matchCount > 0) "有 ${item.matchCount} 场比赛关联此球场。删除后它们会自动重新匹配最近的球场（附近没有时将根据轨迹自动识别一个新球场）。"
                    else "该球场没有关联比赛。",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(item); pendingDelete = null }) { Text("删除", color = PP.Red) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun PitchCard(item: PitchItem, onEdit: () -> Unit, onDelete: () -> Unit) {
    val p = item.pitch
    val markings = remember(p) { PitchMarkings.forPitch(p.pitchType, p.lengthMeters, p.widthMeters) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PP.Card),
        border = BorderStroke(1.dp, PP.Border),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            PitchCompass(markings, p.bearingDegrees, Modifier.width(92.dp), showLabels = false)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag("${p.pitchType}人制")
                    Tag("${item.matchCount} 场比赛", PP.Sky)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${Formatters.oneDecimal(p.lengthMeters)} × ${Formatters.oneDecimal(p.widthMeters)} m · 朝向 ${Formatters.oneDecimal(p.bearingDegrees)}°",
                    style = NumberStyle.Small, color = PP.Text,
                )
                Text(
                    String.format(Locale.US, "%.6f, %.6f", p.centerLat, p.centerLng),
                    style = NumberStyle.Small, color = PP.Muted,
                )
            }
            Column {
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "编辑", tint = PP.Grass) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除", tint = PP.Muted) }
            }
        }
    }
}
