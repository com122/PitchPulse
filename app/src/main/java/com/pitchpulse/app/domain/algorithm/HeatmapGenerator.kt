package com.pitchpulse.app.domain.algorithm

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 2-D Gaussian kernel density heatmap over a 120 x 80 grid (x = pitch length, y = width).
 * All work runs on [Dispatchers.Default].
 */
object HeatmapGenerator {
    const val GRID_W = 120
    const val GRID_H = 80

    /** Exponent of the non-linear normalisation; < 1 lifts low-density areas. */
    private const val GAMMA = 0.6
    /** Percentile used as the "max" so a single dwell spot doesn't wash out the rest. */
    private const val CLIP_PERCENTILE = 0.995

    /** 256-entry ARGB lookup: transparent → light green → bright yellow → orange → deep red. */
    val LUT: IntArray = buildLut()

    suspend fun generate(points: List<ProjectedPoint>, sigmaCells: Float): Bitmap =
        withContext(Dispatchers.Default) {
            val density = density(points, sigmaCells)
            coroutineContext.ensureActive()
            render(density)
        }

    /** Accumulates exp(−d² / 2σ²) for every point onto the grid (cell centres at (i + 0.5)). */
    suspend fun density(points: List<ProjectedPoint>, sigmaCells: Float): FloatArray {
        val sigma = sigmaCells.coerceIn(0.5f, 12f)
        val grid = FloatArray(GRID_W * GRID_H)
        val radius = ceil(3f * sigma).toInt()
        val twoSigma2 = 2f * sigma * sigma
        for ((k, p) in points.withIndex()) {
            if (k and 1023 == 0) coroutineContext.ensureActive()
            val cx = p.nx * GRID_W
            val cy = p.ny * GRID_H
            val ix = cx.toInt()
            val iy = cy.toInt()
            val x0 = max(0, ix - radius)
            val x1 = min(GRID_W - 1, ix + radius)
            val y0 = max(0, iy - radius)
            val y1 = min(GRID_H - 1, iy + radius)
            if (x0 > x1 || y0 > y1) continue
            for (y in y0..y1) {
                val dy = (y + 0.5f) - cy
                val dy2 = dy * dy
                val row = y * GRID_W
                for (x in x0..x1) {
                    val dx = (x + 0.5f) - cx
                    grid[row + x] += p.weight * exp(-(dx * dx + dy2) / twoSigma2)
                }
            }
        }
        return grid
    }

    /** Non-linear normalisation + LUT colouring into an ARGB_8888 bitmap of GRID_W x GRID_H. */
    fun render(density: FloatArray): Bitmap {
        val norm = normalize(density)
        val pixels = IntArray(norm.size) { LUT[(norm[it] * 255f).toInt().coerceIn(0, 255)] }
        val bmp = Bitmap.createBitmap(GRID_W, GRID_H, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, GRID_W, 0, 0, GRID_W, GRID_H)
        return bmp
    }

    fun normalize(density: FloatArray): FloatArray {
        val nonZero = density.filter { it > 1e-6f }.sorted()
        if (nonZero.isEmpty()) return FloatArray(density.size)
        val ref = nonZero[((nonZero.size - 1) * CLIP_PERCENTILE).toInt()].coerceAtLeast(1e-6f)
        return FloatArray(density.size) { i ->
            val v = (density[i] / ref).coerceIn(0f, 1f)
            v.toDouble().pow(GAMMA).toFloat()
        }
    }

    private fun buildLut(): IntArray {
        // position, alpha, r, g, b
        val stops = arrayOf(
            floatArrayOf(0.00f, 0f, 16f, 185f, 129f),
            floatArrayOf(0.06f, 40f, 74f, 222f, 128f),
            floatArrayOf(0.30f, 150f, 163f, 230f, 53f),
            floatArrayOf(0.55f, 195f, 250f, 214f, 21f),
            floatArrayOf(0.78f, 220f, 249f, 115f, 22f),
            floatArrayOf(1.00f, 240f, 185f, 28f, 28f),
        )
        return IntArray(256) { i ->
            val t = i / 255f
            var s = 0
            while (s < stops.size - 2 && t > stops[s + 1][0]) s++
            val a = stops[s]
            val b = stops[s + 1]
            val f = ((t - a[0]) / (b[0] - a[0])).coerceIn(0f, 1f)
            fun lerp(k: Int) = (a[k] + (b[k] - a[k]) * f).toInt().coerceIn(0, 255)
            if (i == 0) 0 else (lerp(1) shl 24) or (lerp(2) shl 16) or (lerp(3) shl 8) or lerp(4)
        }
    }
}
