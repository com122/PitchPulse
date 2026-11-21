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

---

## GPS 精度增强：手机放场边当参考站

踢球时手机静止放在场边（最好放在已标定球场的一个点上）全程录制 GPS，赛后离线合并，用手机测到的误差去改正手表轨迹，再做双向平滑。**只做事后处理，不做实时，不需要云。**

### 精度说明（先看这个）

- **做不到厘米级。** 手表是单频 GNSS，没有载波相位 RTK，这里不承诺厘米级。仿真里增强后的轨迹 RMSE 约 1.9–2.8 m，已知点参考站加精度测试协议的结果见 [`docs/accuracy-simulation.md`](docs/accuracy-simulation.md)。
- **真正的差分（DGNSS）要在伪距域为每颗卫星算改正数**，需要两端都有原始观测量（Android `GnssMeasurement`）和同一组卫星。Wear OS Health Services 不提供原始观测，手表是否支持要运行时检查（`GnssCapabilities.hasMeasurements()`），多数手表不支持。所以 **Tier B（伪距差分）没有实现解算器**，见下文。
- **只有「位置结果」时只能做位置域改正**：用手机的位置误差近似手表的误差。这只对两端共有的慢变化误差（电离层、对流层、星历、卫星几何）有效，对多径、人体遮挡、各自滤波器的误差无效，甚至有害。因此改正系数 K 是从数据里估计的，估计不可靠时自动退化为不修正。
- **热区只关心轨迹相对球场的位置。** 手机放在已知点上，可以同时估计手机的绝对偏差，这是收益最大的部分；放在未知点只能消除随时间变化的误差，整体偏移消除不了（界面会提示）。
- **手机放错位置会把错误整体平移带进结果。** 程序会检查手机平均偏差是否超过它自己报告的精度（超过就关闭绝对改正），但偏离 3 m 以内的放错在比赛中检查不出来，只有精度测试协议能发现。

### 各层与开关

| 层 | 做什么 | 何时自动关闭 |
|---|---|---|
| **A 绝对偏差** | 已知点放置时，用手机整场平均偏差 b 平移手表轨迹（K_b = 1） | 未知点放置；手机平均偏差超过它自报精度（可能放错位置）；评估指标变差 > 5% |
| **A 时变误差** | `P' = P − K·(ê_ref − b)`。K 为东、北分开的对角阵（0..1），在手表静止时段（中场休息、赛前等待，≥ 60 s 且速度 < 0.3 m/s）做最小二乘，手机与手表误差走同一个滤波器 | 静止样本 < 120 s；R² < 0.2；留一块交叉验证 R² ≤ 0；交叉验证静止 CEP95 变差 > 5%；参考站只覆盖 < 50% |
| **B 伪距差分（实验）** | 见下文 | 默认关闭，且当前不可用 |
| **C 卡尔曼 + RTS** | 状态 `[e, n, ve, vn]`，前向滤波 + 固定区间后向平滑；`σ = max(acc, 2.5 m)`；χ² 门限 9.21，连续 5 次超门限则重置；有手表多普勒速度时融合；观测噪声缩放系数从新息一致性估计 | 评估指标变差 > 5% |
| **D 球场约束** | 在 ±8 m 内搜索使轨迹落在球场外扩 2 m 内的最小平移 | 已知点参考站可用时默认关闭；界外比例改善 < 30% 或 < 1 个百分点；会把边线附近的密度峰推到线外 |

设置页「GPS 精度增强」里每层都有开关，还有低通周期（默认 60 s）、平滑加速度 σ（默认 2 m/s²）、手表 acc 系数。**复盘页可在「原始 / 增强」之间切换，原始数据始终保留**，「精度报告」卡片列出每一层开了还是关了、为什么，以及前后指标。

> 比赛列表和赛季概览里的数字仍来自原始轨迹（Room 里的摘要没有改）。增强后的里程、冲刺等只在复盘页显示。

### 地面真值协议（精度测试）

用来在真实场地上验证每一层是不是真的有效。App 内「设置 → 参考站模式 / 精度测试」有同样的引导。

1. 手机放在已知点（如中线与 A 侧边线交点），手表放在手机旁，一起静止 **≥ 5 分钟**（手表开始记录运动）。
2. 点「我出发了」，戴着手表沿四条边线（两条边线、两条底线）踩线慢跑一圈。
3. 沿中线从一侧边线走到另一侧边线。
4. 在中线两侧各 5 m 处来回横穿中线：每侧停 10 秒，共停 6 次。
5. 回到手机旁，点「我回来了」，手表和手机并排静止 **3 分钟**，停止录制。
6. 把手表轨迹导入 App，打开复盘页的精度报告。

指标：边线垂距（中位数、P90）、静止 CEP50 / CEP95（离散度和相对已知点）、中线垂距、中线两侧分区误判率。每一层输出「关 / 开」两套指标，让 P90 垂距或 CEP95 变差超过 5% 的层被自动关闭，并写明原因。

### 数据格式

- 手表轨迹 `filesDir/tracks/{matchId}.jsonl`：原 7 个字段不变，新增 **可选** 字段 `tu`（GNSS UTC 毫秒）和 `svs`（使用的卫星，如 `["G05","E11"]`）。字段缺失时不写出，旧文件照常解析。
- 参考站 `filesDir/refs/{id}.jsonl`（每行一个定位：`t` GNSS UTC、`lat`、`lng`、`acc`、`nu`/`nv` 使用/可见卫星数、`cn` 平均 C/N0、`sv` 使用的卫星）、`{id}.meta.json`（放置方式、已知点、关联球场、协议打点）、可选 `{id}.gnss.txt`（手机支持时的 GNSS 原始观测，Google GnssLogger 文本格式，可用 RTKLIB 等工具处理）。**不改 Room 表结构。**
- 时间对齐：手机用 `Location.getTime()`（GPS_PROVIDER 的 GNSS UTC）。手表有 `tu` 时按 `tu` 对齐，并报告 `t − tu` 的偏移和波动，超过 0.5 s 报警；**没有 `tu` 时对齐误差无法从数据中验证**，报告里会明确提示。

### 权限

| 权限 | 为什么需要 |
|---|---|
| `ACCESS_FINE_LOCATION`（和 `ACCESS_COARSE_LOCATION`） | 参考站要记录手机自己的 GPS 定位 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_LOCATION` | 录制放在前台服务里，锁屏也不中断（Android 14 要求声明位置类型） |
| `POST_NOTIFICATIONS` | Android 13+ 显示「参考站录制中」的常驻通知 |

只在你点「开始录制」时才申请，数据只存在本机。

### Tier B（伪距差分）的现状

做了的：手机端保存 GnssLogger 格式的原始观测和导航电文；`domain/gnss` 里有原始日志解析器和按 Android 公式计算 GPS 伪距（`t_Rx = TimeNanos + TimeOffsetNanos − (FullBiasNanos + BiasNanos)`），有单元测试；设置页有实验开关；可用性检查会说明为什么不可用。

**没做的：逐卫星改正数和 WLS 解算器没有实现。** 即使实现了也用不上：手表这一端不提供伪距，没有可以施加改正的数据。接入条件齐备后（手表确有原始观测），建议先用 RTKLIB（BSD-2）的 DGPS 模式离线处理这两份日志验证，再决定是否内置。

### 实地测试清单

- [ ] 装 APK，设置里确认「GPS 精度增强」各层状态；用一个已用卫星图标定好的球场。
- [ ] 开阔处试录 10 分钟：手机平放或固定在已知点，参考站页面看「最新精度」应 ≤ 5 m、使用卫星 ≥ 8；锁屏后再看通知仍在。
- [ ] 做一次完整的**精度测试协议**，手表端录同一时段，导出 GPX/JSONL 导入。
- [ ] 复盘页「精度报告」：看每层是开是关；「边线垂距 P90」「静止 CEP95」增强后应变小。若某层被自动关闭，看它写的原因。
- [ ] 手机故意放偏 3–5 m 再做一次，确认「绝对偏差」被关闭或指标变差时被自动关闭。
- [ ] 同一场地、不同天气各做一次：共享误差（电离层等）随时间变化，结果应有差异，这是预期的。
- [ ] 手机停止录制后确认「参考站模式」列表里有这条记录，时长和定位数合理。
- [ ] 手表导出的文件没有 `tu` 字段时，精度报告会提示「无法验证对齐」；保证手表和手机的系统时间都是自动同步的网络时间。

### 已知限制

- **所有数字来自仿真**，没有在真机或真实场地上验证过；界面（前台服务、权限流程、精度报告）只做了编译，没有在设备上跑过。
- `:wear` 手表端模块还不存在，所以 `tu`、`svs` 目前没有数据来源，导入的 GPX / 现有手表文件都没有这两个字段。
- 没有多普勒速度时（如 GPX），冲刺次数会略高于真值（仿真里 +0% 到 +25%），低可信。
- 白噪声仿真里「原始」距离被噪声拉长到真值的约 2.6 倍，是仿真模型的副作用，见 `docs/accuracy-simulation.md`。
- 增强指标里的 CEP / 垂距 / 误判率只有在精度测试协议下才是真实精度；普通比赛里是代理指标。

### 参考项目与许可

思路参考了 [xPitch](https://github.com/ismailsunni/xpitch)（无许可证，未复制代码）、RunnerUp（GPL-3.0，未复制）和 soccermatics（GPL，未复制）。原始日志格式与伪距公式参考 Google [gps-measurement-tools](https://github.com/google/gps-measurement-tools)（Apache-2.0）的 GnssLogger；代码均为本仓库自写。
