# OfferPilot · Uber Eats 智能订单筛选助手

Android 11–15（优先 Android 15）· Kotlin · Jetpack Compose · 无需 Root

Uber Driver 弹出配送订单时，OfferPilot 通过无障碍服务读取报价、距离和时间，按你的个人规则计算，并在订单消失前用**语音 / 悬浮提示 / 震动**给出建议。

> **只识别、只建议，不会点击。** 第一版不执行任何自动接单或拒单，无障碍配置里也没有声明手势能力。全部计算在本地完成，应用没有网络权限。

## 当前状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 0 | 项目、权限、无障碍服务、快照调试工具 | ✅ 已完成代码，**真机验证待你完成**（见 [docs/PHASE0.md](docs/PHASE0.md)） |
| Phase 1 | 数据模型、解析器、规则引擎、设置、识别、语音 + 悬浮、去重 | ✅ 已完成；Uber 适配器标记为「待验证」 |
| Phase 2+ | OCR 兜底、多样式适配、城外 / 返程、统计 | 未开始（返程公式已在引擎中实现并测试） |

**重要：** 截至目前还没有 Uber Driver 真实订单弹窗的控件快照。适配器 `UberDriverAdapter` 不使用任何控件 ID，只依赖「屏幕上同时出现金额 + 距离 + 时间」这一通用特征和可配置关键字，遇到歧义时宁可输出「无法判断」。拿到真机快照后需要按实际文字校准，见 [docs/PHASE0.md](docs/PHASE0.md)。

## 安装

1. 在手机上打开仓库的 **Releases → latest**，下载 `OfferPilot-debug.apk`（每次推送 main 后由 CI 自动更新；也可在 Actions 运行页的 Artifacts 下载）。
2. 安装（需允许“安装未知应用”）。
3. 打开 OfferPilot → 「状态」页 →「打开系统无障碍设置」→ 开启 **OfferPilot 订单判断**。
   - Android 13+ 侧载应用的开关如果是灰色：系统设置 → 应用 → OfferPilot → 右上角 ⋮ → **允许受限设置**，再回去开启。
4. 确认 TTS 引擎支持中文（系统设置 → 文字转语音）。

## 使用

- **状态**：服务是否开启、是否正在监测 Uber Driver、最近一次识别结果；语音 / 震动 / 悬浮 / 音量 / 调试模式开关。
- **规则**：油耗、维护费、油价、期望净时薪、最低订单金额、判定倍率；自动列出达标报价表；高级里可切换简单规则。
- **模拟**：不用打开 Uber 就能测试规则（内置 PRD 案例 A/B/C），也可以粘贴屏幕文字测试适配器；服务开启时可以直接预览悬浮层和语音。
- **调试**：目标包名、控件快照抓取 / 查看 / 分享 / 删除。

### 计算规则（默认：车辆成本 + 目标净时薪）

只需要在「规则」页填：**百公里油耗**、**百公里维护费用**（以及油价），再设一个**期望每小时净赚**（默认 $18）。

```
每公里车辆成本 = (百公里油耗 × 油价 + 百公里维护费) / 100
最低报价      = 公里 × 每公里车辆成本 + 小时 × 期望净时薪
ratio        = 报价 / 最低报价
ratio < 1.0 → 不推荐（红）   1.0 ≤ ratio < 1.2 → 勉强合格（黄）   ≥ 1.2 → 值得考虑（绿）
数据缺失 / 歧义 / 不合理 / 置信度低 / 过期 → 无法判断（灰），不给肯定推荐
```

ratio = 1 正好等于你期望的净时薪。两个倍率（1.0 / 1.2）都可以手动调。「规则」页会根据你的车自动列出不同时长/距离的达标报价。悬浮提示会附带预计净时薪，例如 `不推荐 · $9 / 30km · 净$3/h`。

默认值：油耗 10 L/100km、油价 $1.36/L、维护 $10/100km（合计 $0.236/km）、净时薪 $18（$0.30/分钟）：

| 案例 | 输入 | 车辆成本 | 时间部分 | 最低报价 | 预计净时薪 | 结论 |
|---|---|---|---|---|---|---|
| A | $9 · 29.92km · 42min | $7.06 | $12.60 | $19.66 | $2.77/h | 不推荐 |
| B | $33.01 · 40.99km · 63min | $9.67 | $18.90 | $28.57 | $22.22/h | 勉强合格 |
| C | $7 · 3km · 10min | $0.71 | $3.00 | $3.71 | $37.75/h | 值得考虑 |

「规则」页的「高级」里可以切回 PRD 3.1 的简单规则 `max(公里 × $0.80, 分钟 × $0.50)`，三个案例的结论相同（A 不推荐、B 勉强合格、C 值得考虑）。

这是你自己的筛选规则，不是 Uber 官方报酬标准。最低工资保障按结算周期计算，没有加进单笔报价。

## 架构

```
core/   纯 Kotlin/JVM，不依赖 Android，可直接单元测试
  model/     DeliveryOffer、EvaluationResult、Verdict、ScreenText
  parser/    OfferParser（金额/距离/时间）、OfferAdapter、UberDriverAdapter（待验证）
  rules/     OfferEvaluator、EvaluatorSettings
  tracking/  OfferTracker（去重、不完整订单等待、消失即失效）
  format/    OfferMessages（悬浮 / 语音文案）
  OfferPipeline  识别 → 跟踪 → 计算

app/    Android
  accessibility/  OfferMonitorService、NodeReader、MonitorState
  overlay/        OverlayController（TYPE_ACCESSIBILITY_OVERLAY，不可触摸）
  speech/         Speaker（TextToSpeech，导航语音通道）
  feedback/       Haptics
  settings/       AppSettings、SettingsRepository（DataStore）
  debug/          SnapshotStore（控件快照）
  capture/        OcrFallback 接口（Phase 2，未实现）
  action/         OfferActionHandler（只有 NoOp 实现）
  ui/             Compose 界面
```

`OfferEvaluator` 只接收 `DeliveryOffer`，与无障碍服务完全解耦；Uber 改版时只需调整 `UberDriverAdapter` / `UberAdapterConfig`。

### 安全降级

- 只读取目标包名（默认 `com.ubercab.driver`，可在调试页修改）窗口的文字；其他应用只看包名，用来判断“已离开 Uber”。
- 价格 / 距离 / 时间任一缺失、出现多个无法区分的候选、多个接单按钮、数值不合理（≤0、超大、平均速度 > 130 km/h）、置信度 < 0.6、数据超过 30 秒 → 「无法判断」。
- 订单弹窗消失或切到其他应用 → 立即撤下悬浮提示并停止语音。
- 同一订单 20 秒内不重复播报；字段不完整时先等 0.7 秒看是否补全，避免先报“不完整”再报结论。
- 事件节流（每 150ms 最多读一次）、后台线程读取、节点数上限 1500，任何异常都只记录并降级，不影响 Uber Driver。

## 构建

```bash
./gradlew :core:test              # 核心单元测试（只需 JDK 17+）
./gradlew :app:assembleDebug      # 需要 Android SDK 35
```

CI（`.github/workflows/android.yml`）在每次推送时跑测试并产出 APK。

## 测试

`core/src/test` 共 53 个单元测试，覆盖 PRD 10.1 全部项目：`$9.00` 价格提取、`29.92 km` 距离、`1 hr 3 min` 时长、英里换算、缺失值、错误格式、负数 / 零 / 超大值、多个金额、公式边界（ratio 恰为 1.0 / 1.2）、返程成本，以及适配器歧义处理、去重、订单消失、文案。

PRD 10.2 的集成测试（真实弹窗识别、切 App、权限撤销等）需要真机，见 [docs/PHASE0.md](docs/PHASE0.md) 的验证清单。

## 隐私

无网络权限；默认不保存截图；快照只在你主动操作（或主动开启自动快照）时保存在本机 `Android/data/com.offerpilot.app/files/snapshots/`；不读取地址、姓名、订单号。

## 许可

本项目参考了 GKD 的思路（监听界面 → 读取无障碍节点 → 匹配规则），但没有复制 GKD 代码。
