# PitchPulse · 足球赛后热区与体能分析助手

Android 手机端（Kotlin + Jetpack Compose + Material 3 + MVVM + Coroutines/Flow）。
未来作为 Samsung Galaxy Watch 4 (Wear OS) 采集端的伴侣 App；当前阶段通过内置**仿真比赛生成器**和 **JSONL / GPX 文件导入**，在没有手表的情况下也能跑通全流程。

## 构建

| 项目 | 版本 |
|---|---|
| minSdk / compileSdk / targetSdk | 26 / 34 / 34 |
| AGP / Gradle | 8.5.2 / 8.7 |
| Kotlin / Compose Compiler | 1.9.24 / 1.5.14 |
| Compose BOM | 2024.06.00 |
| Room (KSP) / DataStore | 2.6.1 / 1.1.1 |
| JDK | 17 |

```bash
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
```

现成的 APK：GitHub Actions（`.github/workflows/android.yml`）每次推送 `main` 都会构建，APK 在 Actions 运行页的 Artifacts 里；发布 Release 时还会自动把 APK 挂到该 Release 的附件中。

不依赖任何需要 API Key 的商业 SDK。Leaflet 1.9.4 已打包进 `assets/map`（BSD-2 许可，见 `LICENSE-leaflet.txt`），瓦片来自 Esri World Imagery / OpenStreetMap / 高德卫星 XYZ 地址，装好即可用。

## 功能

- **比赛记录**：赛季概览（总场次、累计里程、场均心率、最高时速），比赛卡片附迷你热区缩略图。FAB 菜单：
  - 生成仿真比赛：可选边锋 / 中场 / 后卫 / 前锋，生成 90 分钟 1Hz 数据，包含位置跑位、冲刺、15 分钟中场休息、下半场换边、体能衰减和 2-4 m 相关 GPS 噪声。还会故意掺入低精度点和跳点，用来检验过滤算法。
  - 导入文件：通过系统文件选择器导入 `.jsonl` 或 `.gpx`。GPX 没有阶段字段，会自动识别中场休息。
- **赛后复盘**：Compose Canvas 按比例绘制球场标线（边线、中线、中圈、大小禁区、点球点、罚球弧），叠加热区。
  - 可切换全场 / 上半场 / 下半场；下半场换边校正开关（180° 翻转，实时重算）；图层可切换热区图 / 跑动轨迹线（按速度着色）/ 高速冲刺点。
  - 2×3 核心指标、心率 5 区间分布、每 5 分钟跑动距离柱状图 + 趋势线（纯 Canvas 绘制）。
  - 导出战报：把热区图和指标合成一张 1080px 宽的长图，经 MediaStore 存入 `Pictures/PitchPulse`。
- **我的球场**：预置"标准 7 人制演示球场"，支持增、改、删。删除球场后，关联的比赛会自动重新匹配最近的球场。
  - 地图四点标定：在卫星图上点 4 个角旗，自动解算中心、长宽和朝向，用绿色矩形实时预览，可微调角度和赛制。
  - 手动参数模式（离线兜底）：滑块设置长宽和朝向，填写参考坐标，罗盘实时预览，拖动表盘可旋转。无网络时自动切到此模式。
- **设置与同步**：最大心率、静息心率、冲刺阈值（速度和最短持续时间）、热区模糊半径，用 DataStore 持久化；可一键按新参数重算全部比赛；Watch 4 同步状态卡片和数据契约说明。

## 数据契约（预留给 Watch 4）

底层统一使用 **WGS-84**。轨迹按场次存为 `filesDir/tracks/{matchId}.jsonl`，每行一个 `TrackPoint`，不写入 SQLite：

```json
{"t":1732070400000,"lat":22.543101,"lng":113.942011,"acc":3.8,"spd":2.41,"hr":152,"p":1}
```

| 字段 | 类型 | 含义 |
|---|---|---|
| `t` | Long | 毫秒时间戳 |
| `lat` / `lng` | Double | WGS-84 经纬度 |
| `acc` | Float | GPS 水平精度 (m) |
| `spd` | Float | 瞬时速度 (m/s) |
| `hr` | Int | 心率 (bpm) |
| `p` | Int | 1 上半场 / 2 下半场 / 0 中场或暂停 |

`PitchConfig`：`id, name, pitchType(5/7/8/11), centerLat, centerLng, lengthMeters, widthMeters, bearingDegrees`（纵轴相对正北的顺时针角度）。

手表端只需要传输 JSONL 文件。`WatchSyncRepository.importTrackJsonl(file)` 负责后续全部步骤：解析（跳过坏行）→ 计算轨迹重心并匹配最近球场（500 m 内没有球场时，用 PCA 根据轨迹自动估算一个）→ 统计指标 → 写入 Room → 生成缩略图。当前实现类 `MockWatchSyncRepository` 的连接状态固定为"待接入 Watch 4 采集端"。

## 核心算法（`domain/algorithm`）

| 类 | 说明 |
|---|---|
| `CoordTransform` | WGS-84 ⇄ GCJ-02。反算用不动点迭代，往返误差 < 1 mm。只在高德卫星图层拾取点时使用 |
| `MatchStatsCalculator` | 过滤 `acc > 15` 和 `p == 0` 的点 → 过滤隐含速度 > 11 m/s 的跳点（连续 5 次拒绝后重新锚定）→ 每秒位移 < 0.5 m 不计里程 → 3 点平滑速度。输出里程、有效时长、心率、最高时速、冲刺（≥ 阈值且 ≥ 2 s）、高强度跑动（≥ 14.4 km/h）、心率 5 区间、每 5 分钟分段 |
| `PitchProjector` | 局部东/北米坐标 → 旋转矩阵回正 bearing → 除以长宽归一化到 [0,1]²；剔除 [-0.05, 1.05] 以外的点；下半场可做 `(1-nx, 1-ny)` 翻转 |
| `HeatmapGenerator` | 在 `Dispatchers.Default` 上运行，120×80 网格叠加高斯核 `exp(-d²/2σ²)`，以 99.5 分位为上限做 γ=0.6 非线性归一化，再经 256 级 LUT（透明→浅绿→明黄→橙→深红）生成 ARGB Bitmap |
| `PitchCornerSolver` | 4 个角点按极角排序，对边取平均，解算中心、长宽和朝向 |
| `PitchEstimator` / `HalfTimeDetector` | 根据轨迹估算球场；为 GPX 自动划分上下半场 |

## 目录

```
app/src/main/java/com/pitchpulse/app/
├── data/
│   ├── model/        PitchConfig, TrackPoint, MatchStats …
│   ├── local/        Room: PitchEntity, MatchEntity, DAO, 数据库
│   ├── file/         JSONL 读写、GPX 解析
│   ├── settings/     DataStore
│   ├── repository/   Pitch / Match / Thumbnail / Import / WatchSync
│   └── export/       战报长图 + MediaStore
├── domain/
│   ├── algorithm/    见上表
│   └── simulation/   MatchSimulator
├── ui/               matches / detail / pitches / settings / components / theme / navigation
└── di/AppContainer.kt
```
