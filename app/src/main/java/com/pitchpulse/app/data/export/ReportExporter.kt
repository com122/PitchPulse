package com.pitchpulse.app.data.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pitchpulse.app.data.model.MatchRecord
import com.pitchpulse.app.data.model.MatchStats
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.domain.algorithm.PitchMarkings
import com.pitchpulse.app.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Renders the post-match report as one tall bitmap and saves it to the system gallery. */
object ReportExporter {

    private const val W = 1080
    private const val PAD = 48f

    private val BG = Color.parseColor("#0D1117")
    private val CARD = Color.parseColor("#161B22")
    private val GREEN = Color.parseColor("#10B981")
    private val TEXT = Color.parseColor("#E6EDF3")
    private val MUTED = Color.parseColor("#8B949E")
    private val ZONE_COLORS = intArrayOf(
        Color.parseColor("#60A5FA"), Color.parseColor("#34D399"), Color.parseColor("#FBBF24"),
        Color.parseColor("#F97316"), Color.parseColor("#EF4444"),
    )

    data class Input(
        val match: MatchRecord,
        val pitch: PitchConfig,
        val stats: MatchStats,
        val segmentLabel: String,
        val swapEnabled: Boolean,
        val heatmap: Bitmap?,
        val sprintThresholdKmh: Float,
    )

    suspend fun render(input: Input): Bitmap = withContext(Dispatchers.Default) {
        val pitchW = W - 2 * PAD
        val aspect = (input.pitch.widthMeters / input.pitch.lengthMeters).coerceIn(0.4f, 0.9f)
        val pitchH = pitchW * aspect
        val zonesH = 5 * 64f + 90f
        val chartH = 420f
        val metricsH = 2 * 180f + 24f
        val height = (190f + 40f + pitchH + 80f + metricsH + 40f + zonesH + 40f + chartH + 120f).toInt()

        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)

        val title = paint(TEXT, 54f, bold = true)
        val sub = paint(MUTED, 30f)
        val mono = paint(TEXT, 56f, bold = true, mono = true)
        val label = paint(MUTED, 28f)

        // Header
        var y = PAD + 50f
        c.drawText("PitchPulse 赛后战报", PAD, y, title)
        y += 52f
        val r = input.match
        c.drawText("${Formatters.dateTime(r.startTime)} · ${input.pitch.name}", PAD, y, sub)
        y += 42f
        val tags = buildList {
            add("${input.pitch.pitchType} 人制")
            r.position?.let { add(it.label) }
            add(input.segmentLabel)
            if (input.swapEnabled) add("换边校正")
        }.joinToString(" · ")
        c.drawText(tags, PAD, y, paint(GREEN, 30f, bold = true))
        y += 46f

        // Pitch + heatmap
        val pitchRect = RectF(PAD, y, PAD + pitchW, y + pitchH)
        AndroidPitchPainter.drawGrass(c, pitchRect)
        input.heatmap?.let {
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            c.drawBitmap(it, null, pitchRect, p)
        }
        AndroidPitchPainter.drawLines(c, pitchRect, PitchMarkings.forPitch(input.pitch.pitchType, input.pitch.lengthMeters, input.pitch.widthMeters))
        y += pitchH + 50f

        // Metrics 2 x 3
        val s = input.stats
        val metrics = listOf(
            "总里程" to "${Formatters.km(s.totalDistanceMeters)} km",
            "平均心率" to "${s.avgHr} bpm",
            "最高心率" to "${s.maxHr} bpm",
            "最高时速" to "${Formatters.oneDecimal(s.maxSpeedKmh)} km/h",
            "冲刺次数" to "${s.sprintCount} 次",
            "高强度跑动" to "${Formatters.meters(s.highIntensityDistanceMeters)} m",
        )
        val cellW = (W - 2 * PAD - 2 * 24f) / 3f
        val cellH = 180f
        metrics.forEachIndexed { i, (k, v) ->
            val col = i % 3
            val row = i / 3
            val left = PAD + col * (cellW + 24f)
            val top = y + row * (cellH + 24f)
            card(c, RectF(left, top, left + cellW, top + cellH))
            c.drawText(k, left + 28f, top + 56f, label)
            fitText(c, v, left + 28f, top + 132f, cellW - 56f, mono)
        }
        y += metricsH + 40f

        // HR zones
        card(c, RectF(PAD, y, W - PAD, y + zonesH))
        c.drawText("心率区间分布（最大心率百分比）", PAD + 28f, y + 56f, paint(TEXT, 32f, bold = true))
        var zy = y + 100f
        val barLeft = PAD + 150f
        val barRight = W - PAD - 230f
        val names = listOf("Z1 50-60%", "Z2 60-70%", "Z3 70-80%", "Z4 80-90%", "Z5 90%+")
        for (i in 0 until 5) {
            val share = s.hrZones.share(i)
            c.drawText(names[i].substring(0, 2), PAD + 28f, zy + 30f, paint(ZONE_COLORS[i], 30f, bold = true))
            val track = RectF(barLeft, zy + 6f, barRight, zy + 34f)
            c.drawRoundRect(track, 14f, 14f, paint(Color.parseColor("#21262D"), 0f))
            c.drawRoundRect(RectF(barLeft, zy + 6f, barLeft + (barRight - barLeft) * share, zy + 34f), 14f, 14f, paint(ZONE_COLORS[i], 0f))
            val txt = "${Formatters.minutes(s.hrZones.zonesMs[i])} 分 · ${Formatters.percent(share)}"
            c.drawText(txt, barRight + 20f, zy + 30f, paint(TEXT, 26f, mono = true))
            zy += 64f
        }
        y += zonesH + 40f

        // 5-minute distance chart
        card(c, RectF(PAD, y, W - PAD, y + chartH))
        c.drawText("每 5 分钟跑动距离 (m)", PAD + 28f, y + 56f, paint(TEXT, 32f, bold = true))
        val segs = s.segments
        if (segs.isNotEmpty()) {
            val chartLeft = PAD + 40f
            val chartRight = W - PAD - 28f
            val chartTop = y + 90f
            val chartBottom = y + chartH - 60f
            val maxV = segs.maxOf { it.distanceMeters }.coerceAtLeast(1f)
            val slot = (chartRight - chartLeft) / segs.size
            segs.forEachIndexed { i, seg ->
                val h = (chartBottom - chartTop) * seg.distanceMeters / maxV
                val color = if (seg.phase == 1) GREEN else Color.parseColor("#38BDF8")
                val left = chartLeft + i * slot + slot * 0.15f
                c.drawRoundRect(RectF(left, chartBottom - h, left + slot * 0.7f, chartBottom), 6f, 6f, paint(color, 0f))
                if (i % 2 == 0) {
                    c.drawText("${seg.startMinute}'", left, chartBottom + 36f, paint(MUTED, 22f, mono = true))
                }
            }
            c.drawText("上半场", chartRight - 260f, y + 56f, paint(GREEN, 26f, bold = true))
            c.drawText("下半场", chartRight - 130f, y + 56f, paint(Color.parseColor("#38BDF8"), 26f, bold = true))
        }
        y += chartH + 60f

        c.drawText(
            "有效时长 ${Formatters.duration(s.effectiveDurationMs)} · 冲刺阈值 ${Formatters.oneDecimal(input.sprintThresholdKmh)} km/h · 生成于 PitchPulse",
            PAD, y, paint(MUTED, 24f),
        )
        bmp
    }

    /** Saves a PNG into Pictures/PitchPulse. Pre-Android 10 requires WRITE_EXTERNAL_STORAGE. */
    suspend fun saveToGallery(context: Context, bitmap: Bitmap, displayName: String): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PitchPulse")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在相册中创建图片")
            try {
                resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    ?: error("无法写入图片")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PitchPulse")
            dir.mkdirs()
            val file = File(dir, "$displayName.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, file.absolutePath)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
            uri ?: Uri.fromFile(file)
        }
    }

    private fun card(c: Canvas, rect: RectF) {
        c.drawRoundRect(rect, 28f, 28f, paint(CARD, 0f))
    }

    private fun fitText(c: Canvas, text: String, x: Float, y: Float, maxWidth: Float, base: Paint) {
        val p = Paint(base)
        while (p.measureText(text) > maxWidth && p.textSize > 20f) p.textSize -= 2f
        c.drawText(text, x, y, p)
    }

    private fun paint(color: Int, size: Float, bold: Boolean = false, mono: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
}
