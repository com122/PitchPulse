package com.pitchpulse.app.data.file

import android.util.Xml
import com.pitchpulse.app.data.model.TrackPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Minimal GPX 1.0/1.1 track parser. Reads `<trkpt lat lon>` with `<time>`, and the common
 * extensions: heart rate (`gpxtpx:hr`, `ns3:hr`, ...), `speed` and `hdop`.
 * Phases are not part of GPX; they are assigned later by HalfTimeDetector.
 */
object GpxParser {

    class GpxException(message: String) : Exception(message)

    fun parse(input: InputStream): List<TrackPoint> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)

        val out = ArrayList<TrackPoint>()
        var inPoint = false
        var lat = 0.0
        var lng = 0.0
        var time: Long? = null
        var hr = 0
        var speed = 0f
        var hdop: Float? = null
        var text = StringBuilder()
        var missingTime = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    if (parser.name == "trkpt") {
                        inPoint = true
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: Double.NaN
                        lng = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: Double.NaN
                        time = null; hr = 0; speed = 0f; hdop = null
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val value = text.toString().trim()
                    if (inPoint) {
                        when (parser.name) {
                            "time" -> time = parseTime(value)
                            "hr", "heartrate" -> hr = value.toDoubleOrNull()?.toInt() ?: 0
                            "speed" -> speed = value.toFloatOrNull() ?: 0f
                            "hdop" -> hdop = value.toFloatOrNull()
                            "trkpt" -> {
                                inPoint = false
                                val t = time
                                if (t == null) {
                                    missingTime++
                                } else if (!lat.isNaN() && !lng.isNaN()) {
                                    out += TrackPoint(
                                        t = t,
                                        lat = lat,
                                        lng = lng,
                                        // Rough HDOP → meters conversion (UERE ≈ 4 m).
                                        acc = hdop?.let { (it * 4f).coerceIn(1f, 50f) } ?: 5f,
                                        spd = speed,
                                        hr = hr,
                                        p = TrackPoint.PHASE_FIRST_HALF,
                                    )
                                }
                            }
                        }
                    }
                    text = StringBuilder()
                }
            }
            event = parser.next()
        }
        if (out.isEmpty()) {
            throw GpxException(
                if (missingTime > 0) "GPX 轨迹点缺少 <time> 时间戳，无法分析" else "GPX 文件中没有找到 <trkpt> 轨迹点",
            )
        }
        return out.sortedBy { it.t }
    }

    private fun parseTime(s: String): Long? =
        runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}
