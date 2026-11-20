package com.pitchpulse.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitchpulse.app.ui.theme.NumberStyle
import com.pitchpulse.app.ui.theme.PP

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PP.Card),
        border = BorderStroke(1.dp, PP.Border),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = PP.Text)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 10.dp))
            }
            content()
        }
    }
}

/** Label + large monospaced value + unit. */
@Composable
fun StatTile(label: String, value: String, unit: String, modifier: Modifier = Modifier, accent: androidx.compose.ui.graphics.Color = PP.Text) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = PP.CardHigh,
        border = BorderStroke(1.dp, PP.Border),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = PP.Muted)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = NumberStyle.Large.copy(fontSize = 22.sp), color = accent, maxLines = 1)
                Text(" $unit", style = NumberStyle.Small.copy(fontSize = 11.sp), color = PP.Muted, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
fun Tag(text: String, color: androidx.compose.ui.graphics.Color = PP.Grass) {
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.15f), border = BorderStroke(1.dp, color.copy(alpha = 0.5f))) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
