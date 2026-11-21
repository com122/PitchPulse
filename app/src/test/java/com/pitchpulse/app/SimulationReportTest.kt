package com.pitchpulse.app

import com.pitchpulse.app.data.model.CleanPointProbe
import com.pitchpulse.app.data.model.PlayerPosition
import com.pitchpulse.app.data.model.RefPlacement
import com.pitchpulse.app.data.model.UserSettings
import com.pitchpulse.app.domain.algorithm.EnhancementLayer
import com.pitchpulse.app.domain.algorithm.MatchStatsCalculator
import com.pitchpulse.app.domain.simulation.GnssErrorSimulator
import com.pitchpulse.app.domain.simulation.MatchSimulator
import com.pitchpulse.app.sim.Scenario
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Per-layer gains on simulated sessions with known truth. Writes
 * `app/build/reports/accuracy-simulation.md` (copied into docs/ for the README).
 */
class SimulationReportTest {

    private val calc = MatchStatsCalculator()
    private val A = setOf(EnhancementLayer.A_BIAS, EnhancementLayer.A_TIME)
    private val C = setOf(EnhancementLayer.C)

    private fun truthDistance(sc: Scenario) = sc.truth.zipWithNext()
        .filter { (a, b) -> a.phase == b.phase && a.phase != 0 }
        .sumOf { (a, b) -> calc.countedStep(hypot(b.x - a.x, b.y - a.y), 1000) }

    private data class Row(val name: String, val raw: Double, val a: Double?, val c: Double, val ac: Double?, val auto: Double, val active: String,
                           val dTruth: Double, val dRaw: Double, val dAuto: Double, val sTruth: Int, val sRaw: Int, val sAuto: Int)

    private fun row(sc: Scenario): Row {
        val auto = sc.run()
        return Row(
            name = sc.name,
            raw = sc.rmse(sc.run(layers = emptySet()).track),
            a = if (sc.reference != null) sc.rmse(sc.run(layers = A).track) else null,
            c = sc.rmse(sc.run(layers = C).track),
            ac = if (sc.reference != null) sc.rmse(sc.run(layers = A + C).track) else null,
            auto = sc.rmse(auto.track),
            active = auto.report.activeLayers.joinToString("+") { it.name.replace("A_BIAS", "A偏差").replace("A_TIME", "A时变") }.ifEmpty { "无" },
            dTruth = truthDistance(sc),
            dRaw = calc.analyze(sc.watch).stats.totalDistanceMeters.toDouble(),
            dAuto = calc.analyzeFromClean(sc.watch, auto.clean).stats.totalDistanceMeters.toDouble(),
            sTruth = CleanPointProbe.truthSprints(sc, calc, smooth3 = true),
            sRaw = calc.analyze(sc.watch).stats.sprintCount,
            sAuto = calc.analyzeFromClean(sc.watch, auto.clean).stats.sprintCount,
        )
    }

    @Test
    fun `simulation report`() {
        val scenarios = listOf(
            Scenario.match("比赛·已知点参考站·共享误差", 21, GnssErrorSimulator.Model.shared(Random(21))),
            Scenario.match("比赛·已知点·边锋", 22, GnssErrorSimulator.Model.shared(Random(22)), position = PlayerPosition.WINGER),
            Scenario.match("比赛·未知点参考站", 23, GnssErrorSimulator.Model.shared(Random(23)), placement = RefPlacement.UNKNOWN_POINT),
            Scenario.match("比赛·无参考站", 24, GnssErrorSimulator.Model.shared(Random(24)), placement = null),
            Scenario.match("比赛·无共享误差", 25, GnssErrorSimulator.Model()),
            Scenario.match("比赛·只有各自慢变误差", 26, GnssErrorSimulator.Model(watchSlowSigma = 2.0, phoneSlowSigma = 1.5)),
            Scenario.match("比赛·手机偏离已知点 4 m", 27, GnssErrorSimulator.Model.shared(Random(27), biasRange = 0.4..0.6), phoneOffsetMeters = doubleArrayOf(3.0, 2.8)),
            run {
                val sim = MatchSimulator(Random(28))
                val truth = sim.generateTruth(Scenario.PITCH, PlayerPosition.MIDFIELDER, UserSettings(), Scenario.KICKOFF)
                Scenario("比赛·旧版相关噪声模型·无参考站", Scenario.PITCH, truth, sim.applyLegacyNoise(truth).map { it.copy(tu = it.t) }, null, GnssErrorSimulator.Model())
            },
        )
        val rows = scenarios.map { row(it) }
        val proto = Scenario.protocol("精度测试·已知点", 31, GnssErrorSimulator.Model.shared(Random(31)))
        val pr = proto.run().report

        val sb = StringBuilder()
        sb.appendLine("# 精度增强仿真报告")
        sb.appendLine()
        sb.appendLine("由 `SimulationReportTest` 生成（`./gradlew testDebugUnitTest`）。轨迹 RMSE 为手表比赛中（p ≠ 0）位置相对真值的水平均方根误差，单位 m；" +
            "「自动」为默认设置下经过逐层自动开关后的结果。仿真条件：7 人制 60×40 m，1 Hz；共享误差 = 1–3 m 偏差 + 3 个 1–10 min 周期分量（每轴 RMS 1–3 m）；" +
            "手表独立白噪声 2 m/轴、每小时约 30 次 8–30 m 多径跳点；手机白噪声 1.2 m/轴。")
        sb.appendLine()
        sb.appendLine("| 场景 | 原始 | 仅 A | 仅 C | A+C | 自动 | 自动启用的层 | 距离 真值/原始/自动 (m) | 冲刺 真值/原始/自动 |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|")
        for (r in rows) {
            fun f(v: Double?) = v?.let { "%.2f".format(it) } ?: "—"
            sb.appendLine("| ${r.name} | ${f(r.raw)} | ${f(r.a)} | ${f(r.c)} | ${f(r.ac)} | **${f(r.auto)}** | ${r.active} | " +
                "%.0f / %.0f / %.0f | ${r.sTruth} / ${r.sRaw} / ${r.sAuto} |".format(r.dTruth, r.dRaw, r.dAuto))
        }
        sb.appendLine()
        sb.appendLine("## 精度测试协议（有地面真值）")
        sb.appendLine()
        sb.appendLine("| 指标 | 原始 | 自动增强 |")
        sb.appendLine("|---|---|---|")
        fun m(v: Double?) = v?.let { "%.2f m".format(it) } ?: "—"
        fun p(v: Double?) = v?.let { "%.1f%%".format(it * 100) } ?: "—"
        sb.appendLine("| 边线垂距 中位数 | ${m(pr.raw.sidelineMedian)} | ${m(pr.final.sidelineMedian)} |")
        sb.appendLine("| 边线垂距 P90 | ${m(pr.raw.sidelineP90)} | ${m(pr.final.sidelineP90)} |")
        sb.appendLine("| 中线垂距 P90 | ${m(pr.raw.halfwayP90)} | ${m(pr.final.halfwayP90)} |")
        sb.appendLine("| 中线两侧分区误判率 | ${p(pr.raw.zoneMisclass)} | ${p(pr.final.zoneMisclass)} |")
        sb.appendLine("| 静止 CEP50 / CEP95（相对已知点） | ${m(pr.raw.staticAbsCep50)} / ${m(pr.raw.staticAbsCep95)} | ${m(pr.final.staticAbsCep50)} / ${m(pr.final.staticAbsCep95)} |")
        sb.appendLine("| 静止 CEP50 / CEP95（离散度） | ${m(pr.raw.staticCep50)} / ${m(pr.raw.staticCep95)} | ${m(pr.final.staticCep50)} / ${m(pr.final.staticCep95)} |")
        sb.appendLine()
        sb.appendLine("各层决策：")
        sb.appendLine()
        pr.layers.forEach { sb.appendLine("- ${it.layer.label}：${if (it.kept) "开" else "关"} — ${it.reason}") }
        sb.appendLine()
        sb.appendLine("## 读法与限制")
        sb.appendLine()
        sb.appendLine("- 「仅 A」剩下的误差主要是手表自己的白噪声（约 2.8 m 水平 RMS），参考站改不掉；再加 C 平滑才能降下来。")
        sb.appendLine("- 「无共享误差」「只有各自慢变误差」两行里 A 的时变改正被门控关闭（R² < 0.2），结果不变差。")
        sb.appendLine("- 「手机偏离已知点 4 m」：合理性检查发现参考站平均偏差超过手机自报精度，关闭了绝对偏差改正。比赛中没有真值，偏离 3 m 以内的放错检查不出来，" +
            "会把错误整体平移带进结果；精度测试协议能发现（单元测试 `slightly misplaced phone`）。")
        sb.appendLine("- 「只有各自慢变误差」：手机自身的慢变误差在整场平均后约 0.5 m，被当成共享偏差改到手表上，RMSE 比只用 C 高约 4%（低于 5% 关闭阈值，比赛中没有真值能发现它）。")
        sb.appendLine("- 「旧版相关噪声模型」：误差的相关时间约 200 s，平滑无法降低位置 RMSE（略增约 3%），但把距离误差从 +5% 降到约 0%。")
        sb.appendLine("- **「原始」距离在白噪声场景里约为真值的 2.6 倍，是仿真噪声模型的副作用**：逐秒独立的 2 m 白噪声会把路径拉长很多，" +
            "真实手表芯片内部有滤波，不会这么夸张。不要把这几行的距离变化当作实际收益；更接近真实的是最后一行（相关噪声）：原始 +5%，增强后约 0%。")
        sb.appendLine("- 这是仿真，不是实测。真实手表的误差结构（多径、人体遮挡、芯片内部滤波）可能与模型不同，必须用精度测试协议在真实场地上验证。")

        val out = File("build/reports/accuracy-simulation.md")
        out.parentFile?.mkdirs()
        out.writeText(sb.toString())
        println(sb)

        // Sanity: the automatic result is never worse than raw by more than the 5 % switch-off threshold.
        for (r in rows) assertTrue("${r.name}: auto ${r.auto} vs raw ${r.raw}", r.auto <= r.raw * 1.05)
    }
}
