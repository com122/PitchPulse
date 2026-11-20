package com.pitchpulse.app.data.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.pitchpulse.app.domain.algorithm.PitchMarkings
import kotlin.math.acos

/**
 * Pitch drawing for android.graphics.Canvas (used by the exported report).
 * Layout: pitch length along x, width along y, same as the in-app Compose canvas.
 */
object AndroidPitchPainter {

    private val GRASS_DARK = Color.parseColor("#0F3D2E")
    private val GRASS_LIGHT = Color.parseColor("#12473A")
    private val LINE = Color.argb(220, 255, 255, 255)

    fun drawGrass(c: Canvas, rect: RectF) {
        val stripes = 10
        val sw = rect.width() / stripes
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until stripes) {
            p.color = if (i % 2 == 0) GRASS_DARK else GRASS_LIGHT
            c.drawRect(rect.left + i * sw, rect.top, rect.left + (i + 1) * sw, rect.bottom, p)
        }
    }

    fun drawLines(c: Canvas, rect: RectF, m: PitchMarkings) {
        val sx = rect.width() / m.length
        val sy = rect.height() / m.width
        val s = minOf(sx, sy)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LINE; style = Paint.Style.STROKE; strokeWidth = (s * 0.18f).coerceIn(2f, 5f)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINE; style = Paint.Style.FILL }
        fun X(mx: Float) = rect.left + mx * sx
        fun Y(my: Float) = rect.top + my * sy
        val cy = m.width / 2

        c.drawRect(rect, p)
        c.drawLine(X(m.length / 2), rect.top, X(m.length / 2), rect.bottom, p)
        c.drawOval(RectF(X(m.length / 2 - m.centerCircleRadius), Y(cy - m.centerCircleRadius),
            X(m.length / 2 + m.centerCircleRadius), Y(cy + m.centerCircleRadius)), p)
        c.drawCircle(X(m.length / 2), Y(cy), s * 0.4f + 2f, fill)

        for (side in 0..1) {
            val goalX = if (side == 0) 0f else m.length
            val dir = if (side == 0) 1f else -1f
            // Penalty area
            val paX = goalX + dir * m.penaltyAreaDepth
            c.drawRect(RectF(minOf(X(goalX), X(paX)), Y(cy - m.penaltyAreaWidth / 2), maxOf(X(goalX), X(paX)), Y(cy + m.penaltyAreaWidth / 2)), p)
            // Goal area
            if (m.goalAreaDepth > 0f) {
                val gaX = goalX + dir * m.goalAreaDepth
                c.drawRect(RectF(minOf(X(goalX), X(gaX)), Y(cy - m.goalAreaWidth / 2), maxOf(X(goalX), X(gaX)), Y(cy + m.goalAreaWidth / 2)), p)
            }
            // Penalty spot
            val spotX = goalX + dir * m.penaltySpotDistance
            c.drawCircle(X(spotX), Y(cy), s * 0.3f + 2f, fill)
            // Penalty arc: the part of the circle around the spot outside the penalty area.
            if (m.penaltyArcRadius > 0f) {
                val d = m.penaltyAreaDepth - m.penaltySpotDistance
                if (d < m.penaltyArcRadius) {
                    val half = Math.toDegrees(acos((d / m.penaltyArcRadius).toDouble())).toFloat()
                    val oval = RectF(X(spotX - m.penaltyArcRadius), Y(cy - m.penaltyArcRadius), X(spotX + m.penaltyArcRadius), Y(cy + m.penaltyArcRadius))
                    val start = if (side == 0) -half else 180f - half
                    c.drawArc(oval, start, 2 * half, false, p)
                }
            }
            // Goal
            val goalDepth = 1.5f
            c.drawRect(RectF(minOf(X(goalX), X(goalX - dir * goalDepth)), Y(cy - m.goalWidth / 2), maxOf(X(goalX), X(goalX - dir * goalDepth)), Y(cy + m.goalWidth / 2)), p)
        }
        // Corner arcs
        val r = m.cornerArcRadius
        c.drawArc(RectF(X(-r), Y(-r), X(r), Y(r)), 0f, 90f, false, p)
        c.drawArc(RectF(X(m.length - r), Y(-r), X(m.length + r), Y(r)), 90f, 90f, false, p)
        c.drawArc(RectF(X(m.length - r), Y(m.width - r), X(m.length + r), Y(m.width + r)), 180f, 90f, false, p)
        c.drawArc(RectF(X(-r), Y(m.width - r), X(r), Y(m.width + r)), 270f, 90f, false, p)
    }
}
