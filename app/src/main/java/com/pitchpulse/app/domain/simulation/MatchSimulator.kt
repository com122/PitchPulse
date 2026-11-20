package com.pitchpulse.app.domain.simulation

import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.model.PlayerPosition
import com.pitchpulse.app.data.model.TrackPoint
import com.pitchpulse.app.data.model.UserSettings
import com.pitchpulse.app.domain.algorithm.PitchProjector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Generates a full 90-minute, 1 Hz match for one player around a calibrated pitch.
 *
 * Model:
 * - The team's block moves up and down the pitch (Ornstein–Uhlenbeck process with counter-attack
 *   jumps) and the ball drifts laterally. Each role has a home position, a range of movement with
 *   the block and a lateral ball-following factor.
 * - The player repeatedly picks a waypoint near the tactical target and a movement mode
 *   (stand / walk / jog / run / sprint, role-weighted). Sprints follow role-specific runs
 *   (wingers down the flank, forwards into the box, defenders recovering).
 * - Acceleration-limited kinematics, fatigue in the second half, 15-minute half-time off the pitch,
 *   and teams switch ends at half-time.
 * - Heart rate follows a first-order response to running load with cardiac drift.
 * - GPS noise: AR(1) correlated 2–4 m error plus occasional low-accuracy fixes and position jumps.
 */
class MatchSimulator(private val random: Random = Random.Default) {

    private enum class Mode(val minSpeed: Float, val maxSpeed: Float) {
        STAND(0f, 0.3f), WALK(1.0f, 1.8f), JOG(2.2f, 3.6f), RUN(4.0f, 5.2f), SPRINT(5.9f, 7.9f)
    }

    private data class Role(
        val anchorX: Double,
        val anchorY: Double,
        val shiftX: Double,
        val followY: Double,
        val spreadX: Double,
        val spreadY: Double,
        /** Weights for STAND, WALK, JOG, RUN, SPRINT. */
        val weights: DoubleArray,
    )

    private fun roleFor(position: PlayerPosition, side: Double): Role = when (position) {
        PlayerPosition.DEFENDER -> Role(0.22, 0.5 + 0.17 * side, 0.17, 0.25, 0.05, 0.07,
            doubleArrayOf(0.21, 0.41, 0.27, 0.08, 0.03))
        PlayerPosition.MIDFIELDER -> Role(0.48, 0.5 + 0.08 * side, 0.22, 0.45, 0.08, 0.09,
            doubleArrayOf(0.11, 0.32, 0.39, 0.135, 0.045))
        PlayerPosition.WINGER -> Role(0.62, 0.5 + 0.36 * side, 0.25, 0.12, 0.09, 0.05,
            doubleArrayOf(0.14, 0.35, 0.28, 0.13, 0.09))
        PlayerPosition.FORWARD -> Role(0.76, 0.5 + 0.05 * side, 0.17, 0.30, 0.07, 0.10,
            doubleArrayOf(0.18, 0.37, 0.26, 0.11, 0.08))
    }

    fun generate(
        pitch: PitchConfig,
        position: PlayerPosition,
        settings: UserSettings,
        kickoffMillis: Long,
    ): List<TrackPoint> {
        val projector = PitchProjector(pitch)
        val length = pitch.lengthMeters.toDouble()
        val width = pitch.widthMeters.toDouble()
        val side = if (random.nextBoolean()) 1.0 else -1.0
        val role = roleFor(position, side)

        val half1 = (45 + random.nextInt(1, 4)) * 60
        val halfTime = 15 * 60
        val half2 = (45 + random.nextInt(1, 5)) * 60

        val gpsSigma = random.nextDouble(2.0, 4.0)
        val hrBaseline = random.nextDouble(-0.03, 0.03)

        val out = ArrayList<TrackPoint>(half1 + halfTime + half2 + 10)

        // Player state in pitch meters (x along length, y across width).
        var x = role.anchorX * length
        var y = role.anchorY * width
        var vx = 0.0
        var vy = 0.0
        var teamX = 0.0
        var ballY = 0.5
        var exertion = 0.35
        var noiseE = gaussian() * gpsSigma
        var noiseN = gaussian() * gpsSigma

        var mode = Mode.STAND
        var modeSpeed = 0.0
        var wpX = x
        var wpY = y
        var modeTimeLeft = 3.0

        var t = kickoffMillis

        var intensityDrift = 0.0

        fun emit(phase: Int, hrTarget: Double) {
            // Heart rate: first-order lag towards the load-derived target, plus a slow random
            // component for the intensity of each passage of play.
            intensityDrift += -intensityDrift / 120.0 + 0.006 * gaussian()
            val target = hrTarget + if (phase == TrackPoint.PHASE_PAUSED) 0.0 else intensityDrift
            val tau = if (target > exertion) 12.0 else 40.0
            exertion += (target - exertion) / tau
            val reserve = (settings.maxHr - settings.restingHr).toDouble()
            val hr = (settings.restingHr + reserve * (exertion + hrBaseline) + gaussian() * 1.5)
                .coerceIn(settings.restingHr + 10.0, settings.maxHr - 1.0)

            // GPS: AR(1) correlated noise. rho = 0.995 gives a slowly wandering 2–4 m error with
            // ~0.4 m step-to-step change, similar to a wrist GNSS receiver.
            val rho = 0.995
            val k = sqrt(1 - rho * rho) * gpsSigma
            noiseE = rho * noiseE + k * gaussian()
            noiseN = rho * noiseN + k * gaussian()

            val ll = projector.denormalize(x / length, y / width)
            var lat = ll[0]
            var lng = ll[1]
            var acc = (gpsSigma * 1.2 + kotlin.math.abs(gaussian()) * 0.6).toFloat()
            // Convert local noise (meters) into degrees around this point.
            val mPerDegLat = 111_132.0
            val mPerDegLng = 111_320.0 * cos(Math.toRadians(lat))
            var extraE = noiseE
            var extraN = noiseN
            val roll = random.nextDouble()
            if (roll < 0.004) {
                // Poor fix: flagged by accuracy.
                acc = random.nextDouble(16.0, 30.0).toFloat()
                extraE += gaussian() * 12
                extraN += gaussian() * 12
            } else if (roll < 0.006) {
                // Multipath jump with a plausible accuracy value: must be caught by the speed filter.
                val ang = random.nextDouble(0.0, 2 * PI)
                val dist = random.nextDouble(25.0, 60.0)
                extraE += cos(ang) * dist
                extraN += sin(ang) * dist
            }
            lat += extraN / mPerDegLat
            lng += extraE / mPerDegLng

            val speed = hypot(vx, vy)
            val spd = max(0.0, speed + gaussian() * 0.12).toFloat()
            out += TrackPoint(t = t, lat = lat, lng = lng, acc = acc, spd = spd, hr = hr.toInt(), p = phase)
            t += 1000
        }

        fun step(targetX: Double, targetY: Double, speedCap: Double, accel: Double) {
            val dx = targetX - x
            val dy = targetY - y
            val dist = hypot(dx, dy)
            // Brake so we arrive without overshooting (decel 4 m/s²).
            val desired = if (dist < 0.05) 0.0 else min(speedCap, sqrt(2 * 4.0 * dist))
            val dvx = (if (dist < 0.05) 0.0 else dx / dist * desired) - vx
            val dvy = (if (dist < 0.05) 0.0 else dy / dist * desired) - vy
            val dv = hypot(dvx, dvy)
            val scale = if (dv > accel) accel / dv else 1.0
            vx += dvx * scale
            vy += dvy * scale
            x = (x + vx).coerceIn(-3.0, length + 3.0)
            y = (y + vy).coerceIn(-3.0, width + 3.0)
        }

        fun toPitch(ax: Double, ay: Double, phase: Int): Pair<Double, Double> =
            if (phase == TrackPoint.PHASE_FIRST_HALF) ax * length to ay * width
            else (1 - ax) * length to (1 - ay) * width

        fun playHalf(seconds: Int, phase: Int) {
            for (s in 0 until seconds) {
                val progress = s.toDouble() / seconds
                val fatigue = if (phase == TrackPoint.PHASE_FIRST_HALF) 1.0 - 0.05 * progress
                else 0.92 - 0.12 * progress

                // Team block and ball.
                teamX += -teamX / 40.0 + 0.11 * gaussian()
                if (random.nextDouble() < 1.0 / 90) teamX += if (teamX > 0) -0.9 else 0.9
                teamX = teamX.coerceIn(-1.0, 1.0)
                ballY = (ballY + (0.5 - ballY) / 30.0 + 0.06 * gaussian()).coerceIn(0.05, 0.95)

                val tacX = (role.anchorX + role.shiftX * teamX).coerceIn(0.04, 0.96)
                val tacY = (role.anchorY + (ballY - 0.5) * role.followY).coerceIn(0.05, 0.95)

                modeTimeLeft -= 1.0
                val reached = hypot(wpX - x, wpY - y) < 1.5
                if (modeTimeLeft <= 0 || (mode != Mode.STAND && reached)) {
                    mode = pickMode(role.weights, fatigue)
                    when (mode) {
                        Mode.STAND -> {
                            wpX = x; wpY = y; modeSpeed = 0.3
                            modeTimeLeft = random.nextDouble(2.0, 8.0)
                        }
                        Mode.SPRINT -> {
                            val (dx, dy) = sprintDirection(position, teamX, role, side, phase)
                            val runLen = random.nextDouble(20.0, 40.0) * fatigue
                            wpX = (x + dx * runLen).coerceIn(1.0, length - 1.0)
                            wpY = (y + dy * runLen).coerceIn(1.0, width - 1.0)
                            modeSpeed = random.nextDouble(mode.minSpeed.toDouble(), mode.maxSpeed.toDouble()) *
                                (0.9 + 0.1 * fatigue)
                            modeTimeLeft = hypot(wpX - x, wpY - y) / modeSpeed + 4
                        }
                        else -> {
                            val ax = (tacX + gaussian() * role.spreadX).coerceIn(0.03, 0.97)
                            val ay = (tacY + gaussian() * role.spreadY).coerceIn(0.03, 0.97)
                            val (px, py) = toPitch(ax, ay, phase)
                            wpX = px; wpY = py
                            modeSpeed = random.nextDouble(mode.minSpeed.toDouble(), mode.maxSpeed.toDouble()) *
                                (0.82 + 0.18 * fatigue)
                            val d = hypot(wpX - x, wpY - y)
                            if (d < 3.0) {
                                mode = Mode.STAND; wpX = x; wpY = y; modeSpeed = 0.3
                                modeTimeLeft = random.nextDouble(2.0, 6.0)
                            } else {
                                modeTimeLeft = d / modeSpeed + 5
                            }
                        }
                    }
                }

                if (mode == Mode.STAND) {
                    // Small shuffles while waiting.
                    wpX += gaussian() * 0.15
                    wpY += gaussian() * 0.15
                }
                step(wpX, wpY, modeSpeed, if (mode == Mode.SPRINT) 3.8 else 2.6)

                val speed = hypot(vx, vy)
                val load = (0.50 + 0.105 * speed).coerceAtMost(1.0)
                val drift = if (phase == TrackPoint.PHASE_SECOND_HALF) 0.03 * progress else 0.0
                emit(phase, load + drift)
            }
        }

        fun halfTimeBreak(seconds: Int) {
            val benchX = length * 0.5 + random.nextDouble(-8.0, 8.0)
            val benchY = -width * 0.12
            mode = Mode.STAND
            for (s in 0 until seconds) {
                val goingBack = s > seconds - 60
                val (tx, ty) = if (goingBack) {
                    val (px, py) = toPitch(role.anchorX, role.anchorY, TrackPoint.PHASE_SECOND_HALF)
                    px to py
                } else benchX to benchY
                step(tx, ty, 1.3, 1.5)
                emit(TrackPoint.PHASE_PAUSED, 0.22)
            }
            wpX = x; wpY = y; modeTimeLeft = 2.0
            teamX = 0.0; ballY = 0.5
        }

        playHalf(half1, TrackPoint.PHASE_FIRST_HALF)
        halfTimeBreak(halfTime)
        playHalf(half2, TrackPoint.PHASE_SECOND_HALF)
        return out
    }

    private fun pickMode(weights: DoubleArray, fatigue: Double): Mode {
        // Tired players stand more and run less.
        val w = weights.copyOf()
        val f3 = fatigue * fatigue * fatigue
        w[2] *= fatigue; w[3] *= f3; w[4] *= f3
        w[0] *= 1 + 2.5 * (1 - fatigue)
        w[1] += (weights[3] + weights[4]) * (1 - f3)
        var r = random.nextDouble() * w.sum()
        for (i in w.indices) {
            r -= w[i]
            if (r <= 0) return Mode.entries[i]
        }
        return Mode.WALK
    }

    /** Unit vector (pitch frame) for a role-specific sprint. */
    private fun sprintDirection(
        position: PlayerPosition,
        teamX: Double,
        role: Role,
        side: Double,
        phase: Int,
    ): Pair<Double, Double> {
        // Attack frame: +x towards the opponent goal, +y towards the right touchline.
        val (ax, ay) = when (position) {
            PlayerPosition.WINGER ->
                if (teamX < -0.4) -1.0 to 0.0 else 1.0 to (0.15 * side * if (random.nextBoolean()) 1 else -1)
            PlayerPosition.FORWARD ->
                if (random.nextDouble() < 0.7) 1.0 to (0.5 - role.anchorY + gaussian() * 0.3) * 1.5
                else 0.0 to (if (random.nextBoolean()) 1.0 else -1.0)
            PlayerPosition.DEFENDER ->
                if (teamX < 0.2) -1.0 to gaussian() * 0.4 else 1.0 to side * 0.2
            PlayerPosition.MIDFIELDER -> {
                val a = random.nextDouble(0.0, 2 * PI)
                cos(a) + (if (teamX > 0) 0.4 else -0.4) to sin(a)
            }
        }
        val n = hypot(ax, ay).coerceAtLeast(1e-6)
        val ux = ax / n
        val uy = ay / n
        return if (phase == TrackPoint.PHASE_SECOND_HALF) -ux to -uy else ux to uy
    }

    private fun gaussian(): Double {
        // Box–Muller.
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2 * PI * u2)
    }
}
