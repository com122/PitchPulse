package com.pitchpulse.app.domain.simulation

import com.pitchpulse.app.data.model.KnownPoint
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.model.TrackPoint
import com.pitchpulse.app.domain.algorithm.PitchProjector
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Truth trajectory of the accuracy-test protocol (see README "地面真值协议"):
 * 1. watch next to the phone at the known point, static 5 min;
 * 2. "我出发了": jog one lap on the touchlines and goal lines;
 * 3. walk the halfway line from one touchline to the other;
 * 4. shuttle across the halfway line between marks 5 m either side, 10 s dwell, 6 dwells;
 * 5. back to the phone: "我回来了", static 3 min side by side.
 */
class AccuracyTestSimulator {

    data class Result(val truth: List<TruthSample>, val routeStart: Long, val routeEnd: Long, val knownPoint: KnownPoint)

    fun generate(pitch: PitchConfig, startMillis: Long, knownPoint: KnownPoint = KnownPoint.HALFWAY_TOUCHLINE_A): Result {
        val l = pitch.lengthMeters.toDouble()
        val w = pitch.widthMeters.toDouble()
        val projector = PitchProjector(pitch)
        val out = ArrayList<TruthSample>()
        // Start 0.3 m inside the pitch next to the phone.
        val home = doubleArrayOf(knownPoint.nx * l, (knownPoint.ny * w).coerceIn(0.3, w - 0.3))
        var x = home[0]; var y = home[1]
        var vx = 0.0; var vy = 0.0
        var t = startMillis

        fun emit() {
            val ll = projector.denormalize(x / l, y / w)
            out += TruthSample(t, ll[0], ll[1], x, y, hypot(vx, vy), 110, TrackPoint.PHASE_FIRST_HALF)
            t += 1000
        }

        fun stay(seconds: Int) {
            vx = 0.0; vy = 0.0
            repeat(seconds) { emit() }
        }

        fun go(tx: Double, ty: Double, speedCap: Double, accel: Double = 2.0) {
            var guard = 0
            while (guard++ < 3600) {
                val dx = tx - x; val dy = ty - y
                val dist = hypot(dx, dy)
                if (dist < 0.05 && hypot(vx, vy) < 0.05) { x = tx; y = ty; vx = 0.0; vy = 0.0; return }
                val desired = if (dist < 0.05) 0.0 else min(speedCap, sqrt(2 * 3.0 * dist))
                val dvx = (if (dist < 0.05) 0.0 else dx / dist * desired) - vx
                val dvy = (if (dist < 0.05) 0.0 else dy / dist * desired) - vy
                val dv = hypot(dvx, dvy)
                val scale = if (dv > accel) accel / dv else 1.0
                vx += dvx * scale; vy += dvy * scale
                // Never overshoot the waypoint.
                if (hypot(vx, vy) > dist) { val f = dist / hypot(vx, vy); vx *= f; vy *= f }
                x += vx; y += vy
                emit()
            }
        }

        stay(300)
        val routeStart = t
        // Lap on the lines, starting along touchline y = 0 towards x = l.
        go(l, 0.0, 3.0); go(l, w, 3.0); go(0.0, w, 3.0); go(0.0, 0.0, 3.0); go(l / 2, 0.0, 3.0)
        // Halfway line, touchline to touchline.
        go(l / 2, w, 1.4)
        // Shuttle across the halfway line.
        go(l / 2 - 5, w / 2, 2.5)
        for (k in 0 until 6) {
            stay(10)
            if (k < 5) go(if (k % 2 == 0) l / 2 + 5 else l / 2 - 5, w / 2, 3.0)
        }
        go(home[0], home[1], 2.5)
        val routeEnd = t
        stay(180)
        return Result(out, routeStart, routeEnd, knownPoint)
    }
}
