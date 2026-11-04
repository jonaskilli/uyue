# 把场景特效接入 Legado 朗读/视觉小说 · 完整实施方案

> 目标：复用项目里已上线的「AI 弹幕」整套机制，让**遇到场景描写就播放一段时间的特效**
> （飘雪、下雨、落叶、花瓣、闪电、萤火虫、风），显示在朗读弹窗 / 大图界面 / 视觉小说舞台上。
>
> 前置资料：本目录 `README.md`（站点特效原理）、`tftgamer-site/FEATURES.md`（站点功能）、
> `tftgamer-site/prompt/effect_semantics_selection_user_prompt.txt`（站点真实标注提示词）。
>
> 编写日期：2026-11-04。本方案已按步骤落地到 `/workspace` 项目源码。

---

## 1. 结论先行

**弹幕那套底完全适用，而且是最佳选择**，因为它已经解决了场景特效最难的四个问题：

1. **提前生成**：整章按字数切区间，队列始终领先朗读一个区间，不会读到才卡住；
2. **段落锚点**：每条内容绑定 `contentList` 下标，朗读到该段才触发；
3. **切章/重入保护**：章节 key、内容 hash、段落级缓存、`firedAnchors` 防重复发射；
4. **渲染浮层**：全屏透明 View、触摸穿透、Choreographer 显式帧循环（专门修过「只画第一帧」的坑）。

场景特效相比弹幕只多两件事：**内容不是文字而是特效类型+强度**，**播放不是飘过一次而是持续一段时间**。
其余调度、缓存、事件、锚点、生命周期一律照搬。

---

## 2. 弹幕链路拆解（复用的底）

来自提交 `fc502ac` / `aa5edad`：

| 层 | 文件 | 关键机制 |
|---|---|---|
| 数据模型 | `model/DanmakuModels.kt` | `DanmakuItem(para,text)`、`DanmakuReady(key,items)`、`DanmakuStore` 内存暂存 + 增量合并 |
| 生成 | `service/DanmakuGenerator.kt` | 整章区间 AI 调用（复用 `BgmAIService.callOpenAIApi`）；段落级 JSON 缓存落盘 `/storage/emulated/0/Download/chajian/danmaku/`；`contentHash` 失效；JSON 容错解析；锚点聚类重排 |
| 调度 | `service/BaseReadAloudService.kt` 2284~2464 | `scheduleDanmaku()` 每段开头调用；`danmakuPending` 区间队列；`ensureDanmakuWorker()` 串行生成；条数按区间字数占比分摊；`clearDanmakuRuntime()` 切章清理 |
| 事件 | `constant/EventBus.kt` | `DANMAKU_READY` 全量下发；`VN_PARAGRAPH` 段落起点（由 `HttpReadAloudService` / `TTSReadAloudService` 广播） |
| 显示 | `ui/book/read/config/DanmakuView.kt` | Canvas 自绘 + `Choreographer` 显式帧循环；多轨道；`dt` 上限 0.1s；`onWindowVisibilityChanged` 暂停；`clearDanmaku()` |
| 接入 | `ReadAloudDialog` / `VnStageDialog` / `FullscreenAiImageDialog` | 三个界面各有一份 `DanmakuView`（布局最后一个子 View，最上层）；`initDanmaku()` 从 Store 恢复；`observeLiveBus()` 订阅事件；`VN_PARAGRAPH` 里按锚点 `show()`，延后 1 秒且切章保护 |
| 配置 | `BgmConfigDialog` + `dialog_bgm_config.xml` + `AppConfig` + `PreferKey` | `switch_danmaku_enable` 开关；`danmakuEnabled` / `danmakuTrackCount` |

关键时序：

```
段落 nowSpeak 开始
  → postEvent(VN_PARAGRAPH, VnParagraph(text, nowSpeak))
  → scheduleDanmaku() 排区间（领先一个区间）→ worker 生成 → DANMAKU_READY
  → UI 收到 VN_PARAGRAPH：取 para <= nowSpeak 且未发射过的条目
  → postDelayed 1s → DanmakuView.show(text) → 多轨道飘过 ~6.5s
切章 → READ_ALOUD_CHAPTER_CHANGED → 清列表 / firedAnchors / 在屏内容
```

---

## 3. 场景特效的差异点与对策

| 差异 | 对策 |
|---|---|
| 内容是「雪/雨/落叶/闪电」而非文字 | 新增 `SceneEffectCue(para, kind, level, durationMs, indoors, wind, lightning)` |
| 要「播放一段时间」而非飘过一次 | `SceneEffectView` 维护当前 cue + 起始时间，到 `durationMs` 自动淡出停止；新 cue 到来时平滑替换 |
| 不能每章只 12 条、也不能乱加 | 本地关键词规则只在实际出现场景描写时产出；AI 增强可选；同段落同类去重；同一时刻只播一组主天气 |
| 室内不该下雪 | cue 带 `indoors`，室内段落跳过雨雪；AI 提示词明确要求判断室内外 |
| 不该盖住台词/按钮 | `SceneEffectView` 放在**背景之上、所有 UI 之下**（布局子 View 顺序），且不设 clickable 触摸穿透；绘制区域避开底部 22% 台词区 |
| 弹幕可能没开 | 场景特效有独立开关；调度器条件改为 `danmakuEnabled || sceneEffectEnabled`，两者共享同一区间队列但各自独立生成/缓存/广播 |
| 用户可能没配 AI Provider | **本地关键词规则是主路径**，零配置可用；AI 只作可选增强（复用弹幕开关判断用户已接受 AI 调用） |

---

## 4. 总体架构

```
BaseReadAloudService.scheduleDanmaku()   ← 每段开头，统一区间调度
        │
        ├─ danmakuPending 区间队列（领先朗读一个区间）
        │
        └─ ensureDanmakuWorker() 串行处理每个区间
                ├─ [弹幕开]  DanmakuGenerator.generateRange()  → DanmakuStore → DANMAKU_READY
                └─ [特效开]  SceneEffectGenerator.generateRange()
                                  ├─ 本地关键词规则（必跑，零成本）
                                  ├─ [AI 增强] 复用 BgmAIService.callOpenAIApi
                                  └─ 段落级缓存 → SceneEffectStore → SCENE_EFFECT_READY

UI（三个界面）
  initSceneEffect()                     从 Store 恢复本章 cues
  SCENE_EFFECT_READY                    全量更新 cues，切章清空
  VN_PARAGRAPH                          取 para <= nowSpeak 且未发射的 cue
                                        → postDelayed 1s（与弹幕一致，避免剧透）
                                        → SceneEffectView.show(cue) 播放 durationMs
  切章                                  clearSceneEffects()

SceneEffectView（背景之上、UI 之下）
  Choreographer 显式帧循环
  淡入 0.6s / 淡出 0.8s / 到期自动停止
  rain / snow / petals / leaves / lightning / fireflies / embers / wind
```

---

## 5. 数据模型

```kotlin
enum class SceneEffectKind { RAIN, SNOW, PETALS, LEAVES, LIGHTNING, FIREFLIES, EMBERS, WIND }

data class SceneEffectCue(
    val para: Int,             // 触发段落下标
    val kind: SceneEffectKind, // 主特效
    val level: Int = 2,        // 1..4，映射粒子数量与速度
    val durationMs: Long = 8000L,
    val indoors: Boolean = false,
    val wind: Boolean = false,
    val lightning: Boolean = false,  // 与雨雪叠加的闪电
    val source: String = "local"     // local / ai，便于统计
)

data class SceneEffectReady(val key: String, val items: List<SceneEffectCue>)
object SceneEffectStore { put / merge / get / clear }   // 完全镜像 DanmakuStore
```

`level → 粒子参数`（来自 `mapping/weather.json`）：

| kind | lv1 | lv2 | lv3 | lv4 | fallSpeed |
|---|---|---|---|---|---|
| RAIN | 90 | 180 | 340 | 600 | 0.6 / 1.0 / 1.4 / 1.9 |
| SNOW | 60 | 110 | 230 | 400 | 同上 |
| PETALS / LEAVES | 40 | 70 | 100 | 140 | — |

---

## 6. 逐步骤实施清单

### Step 1 — 事件与配置
- `constant/EventBus.kt`：加 `SCENE_EFFECT_READY = "sceneEffectReady"`。
- `constant/PreferKey.kt`：加 `sceneEffectEnabled`。
- `help/config/AppConfig.kt`：加 `var sceneEffectEnabled`（默认 false）。
- `ui/book/read/config/BgmConfigDialog.kt`：初始化开关、监听写回；打开时若朗读运行中触发一次手动生成。
- `res/layout/dialog_bgm_config.xml`：在弹幕开关下方加一行「场景特效（雪/雨/落叶/闪电，按剧情播放）」+ `ThemeSwitch`。

**验收**：开关可持久化，重启 App 后保持。

### Step 2 — 数据模型与内存暂存
- 新建 `model/SceneEffectModels.kt`：`SceneEffectKind`、`SceneEffectCue`、`SceneEffectReady`、`SceneEffectStore`。
- `SceneEffectStore.merge()` 镜像 `DanmakuStore`：`key` 不同则替换，相同则按 `para+kind` 去重合并。

**验收**：单元级逻辑与弹幕一致，切章后 `get(旧key)` 返回空。

### Step 3 — 生成器（本地规则 + AI 增强 + 缓存）
- 新建 `service/SceneEffectGenerator.kt`：
  - `getCacheFile()` → `/storage/emulated/0/Download/chajian/scene_effect/<书名>/<章节>.json`；
  - `generateRange(bookName, chapterTitle, contentHash, paragraphs, aiEnhanced)`：
    1. 读段落级缓存（格式 `{"contentHash","records":{"12":[{...}]}}`，hash 不一致整体失效）；
    2. 对无记录的段落跑**本地关键词规则**；
    3. 若 `aiEnhanced` 且有 BGM AI Provider，再调一次 `BgmAIService.callOpenAIApi`，解析 JSON 数组，校验 `para ∈ 本区间`、`kind` 在白名单、`level ∈ 1..4`；
    4. 合并去重（同 para 同 kind 只留一条，AI 覆盖本地），写回缓存；
    5. 返回本区间全量 cues，按 para 升序。
  - 本地规则表：

    | 关键词 | kind | level 线索 |
    |---|---|---|
    | 飘雪/雪花/下雪/大雪/暴雪/雪 | SNOW | 暴/鹅毛→4，大→3，小/细→1，默认2 |
    | 下雨/雨幕/雨点/暴雨/细雨/雨 | RAIN | 暴/倾盆→4，大→3，细/微→1，默认2 |
    | 落叶/树叶/秋叶/叶子飘 | LEAVES | 默认2 |
    | 花瓣/落花/樱花/花瓣雨 | PETALS | 默认2 |
    | 闪电/电闪/雷鸣/惊雷 | LIGHTNING | 默认3，duration 1.5s |
    | 萤火虫/流萤 | FIREFLIES | 默认2 |
    | 火星/余烬/篝火 | EMBERS | 默认2 |
    | 狂风/大风/风声呼啸 | WIND | 狂→4，默认2 |

  - 室内判定：`屋内|室内|房里|殿内|车厢|洞内|帐内` → `indoors=true`，**雨雪/落叶/花瓣类直接跳过**；闪电、火星、风仍可（室内不播闪电较合理，规则里也跳过）。
  - 每个段落最多 1 条主特效，避免堆叠。

**验收**：不配任何 AI 时，含「雪」的段落仍能产出 `SNOW` cue；重读同章不重复写缓存。

### Step 4 — 接入朗读服务调度
- `service/BaseReadAloudService.kt`：
  - `scheduleDanmaku()` 首行条件由 `if (!AppConfig.danmakuEnabled) return` 改为 `if (!AppConfig.danmakuEnabled && !AppConfig.sceneEffectEnabled) return`；
  - `ensureDanmakuWorker()` 区间循环里，弹幕段用 `if (AppConfig.danmakuEnabled)` 包住，新增 `if (AppConfig.sceneEffectEnabled)` 分支调用 `SceneEffectGenerator.generateRange(...)` → `SceneEffectStore.merge` → `postEvent(SCENE_EFFECT_READY, ...)`；
  - `clearDanmakuRuntime()` 里同时 `SceneEffectStore.clear()` 并广播空列表；
  - `triggerDanmakuGenerationManual()` 保持可用（开关打开时立即重排）。
- 不改 `HttpReadAloudService` / `TTSReadAloudService`：`VN_PARAGRAPH` 已存在，直接复用。

**验收**：只开特效不开弹幕时，区间仍会调度并广播 `SCENE_EFFECT_READY`。

### Step 5 — 渲染层
- 新建 `ui/book/read/config/SceneEffectView.kt`：
  - 全屏透明 `View`，不设 clickable/focusable；
  - **复制 `DanmakuView` 的 Choreographer 显式帧循环**（这是弹幕踩过的坑：`postInvalidateOnAnimation` 在部分弹窗窗口不续帧）；`dt` 上限 0.1s；`onWindowVisibilityChanged` 暂停/恢复；
  - `show(cue)`：设置当前 cue、`startMs = uptimeMillis()`，启动帧循环；若已有同类特效则延长而非重启；
  - `clearSceneEffects()`：清状态、停帧循环、`invalidate()`；
  - `onDraw()`：按 `t` 计算淡入 0.6s / 淡出 0.8s，到 `durationMs` 自动清空；绘制高度取 `height * 0.78f` 避开底部台词区；
  - 绘制实现：雨（细长线 + 风向倾角）、雪（圆点 + 横摆）、花瓣/落叶（`drawOval` + `canvas.rotate` 旋转 + 正反面换色）、闪电（全屏白闪 + 折线，1.2s 内衰减）、萤火虫/火星（发光圆点上升）、风（曲线）；
  - 确定性伪随机 `hash(i, seed)`（与 `renderer/particle-renderer.ts` 同款），位置取模回卷，不每帧 new 对象；
  - `reduced motion` / 电量优化：粒子数按 `level` 上限收敛，`active` 为空即停帧循环。

**验收**：View 单独放在任意界面能持续播放 8 秒后自动停止，旋转屏幕不跳变。

### Step 6 — 三处布局接入（层级最关键）
在 `dialog_read_aloud.xml`、`dialog_vn_stage.xml`、`dialog_fullscreen_image.xml` 里，
把 `SceneEffectView` 插在**背景/图片之后、其它 UI 之前**（VN 舞台即 `iv_vn_background` 之后），
`android:visibility="gone"`，`match_parent`。

> 不能插在 `DanmakuView` 之后：那样雨雪会盖住弹幕和台词文字。
> 放在最底层画面之上，才有「场景里在下雪」而不是「屏幕上贴了层动画」的效果。

**验收**：特效可见，弹幕与台词始终在特效之上；点击按钮不被拦截。

### Step 7 — 三个界面订阅与触发
`ReadAloudDialog` / `VnStageDialog` / `FullscreenAiImageDialog` 各自：
- 加字段 `currentSceneEffects`、`sceneEffectKey`、`firedSceneAnchors`；
- `initSceneEffect()`：按 `AppConfig.sceneEffectEnabled` 设置 `sceneEffectView.visibility`，从 `SceneEffectStore` 恢复本章；
- `observeLiveBus()`：
  - `SCENE_EFFECT_READY`：key 变化则清 fired 与在屏特效，然后全量更新列表；
  - `READ_ALOUD_CHAPTER_CHANGED`：清列表 / fired / `clearSceneEffects()`；
  - `VN_PARAGRAPH`：取 `para <= p.index && firedSceneAnchors.add(para)` 的 cue，`postDelayed(1000L)` 后校验 key 与开关，再 `sceneEffectView.show(cue)`。
- 与弹幕共用 1 秒延迟，保证「读到描写时才出现」，不提前剧透。

**验收**：朗读到含「雪」的段落约 1 秒后开始飘雪，8 秒后停止；切章立刻停止。

### Step 8 — 与站点语义模型对齐（可选增强，本轮不做）
- 若以后要更精准，可把 `tftgamer-site/prompt/effect_semantics_selection_user_prompt.txt`
  的 cue 目录接进 AI 增强提示词，并把 `scene-effects/mapping/weather.json` 的 lv→粒子参数表复制到生成器常量；
- 本轮已用同一套 `lv 1..4` 数值，数据结构预留了 `indoors / wind / lightning`，可直接升级。

### Step 9 — 验证
- `./gradlew :app:compileAppReleaseKotlin --offline --no-daemon`（环境已就绪）；
- 静态检查：新增 id 与 ViewBinding 引用一致、XML 可解析；
- 手工验收清单：开关、无 AI 兜底、切章、三界面、触摸穿透、长时间播放不掉帧。

---

## 7. 播放时序（单条 cue）

```
VN_PARAGRAPH(para=N)
  → 命中 cue(para<=N, 未发射)
  → postDelayed 1000ms            与弹幕一致，避免比朗读早
  → SceneEffectView.show(cue)     startMs = now
  → 帧循环每帧 invalidate
     t: 0 ───── 0.6s 淡入 ───── durationMs-0.8s ───── 0.8s 淡出 ───── 停止
  → clear 状态、停帧循环
  → 下一条 cue 到来：平滑替换（同类则延长 duration）
切章 / 关闭开关 → clearSceneEffects() 立即停止
```

---

## 8. 视觉效果设计要点

1. **层级**：背景 → 场景特效 → 立绘/台词/控制 → 弹幕。特效是「场景的一部分」，不是 UI 贴片。
2. **避让**：绘制高度 `height * 0.78f`，底部台词区干净。
3. **克制**：同一时刻只有一组主天气；闪电作为叠加轨道；不因每句话都出现「风」而刷屏。
4. **淡入淡出**：避免特效突然出现/消失吓人，也避免切章硬切。
5. **性能**：粒子数上限 600（lv4 雨），确定性 hash 不分配对象，`dt` 封顶 0.1s，页面不可见即停帧循环。
6. **省电/无障碍**：默认开关关闭；可后续接系统「减少动态效果」。

---

## 9. 风险与回退

| 风险 | 处理 |
|---|---|
| 改动朗读服务影响弹幕 | 只把首行守卫改成「两者任一开启」，弹幕原逻辑用 `if (danmakuEnabled)` 原样包住，不删不改 |
| 三个 Dialog 代码重复 | 与现有弹幕实现保持同样的「各写一份」风格，避免引入新基类牵动更多文件 |
| 特效盖住按钮 | View 不设 clickable/focusable；绘制高度避开底部；默认 gone |
| 用户没配 AI | 本地规则主路径，不依赖任何 Provider |
| 缓存写外部存储失败 | 全部 `runCatching`，失败仅影响复用不影响本次播放 |
| 效果不理想 | 开关默认关闭；关闭即完全不调度、不绘制、不广播 |

---

## 10. 涉及文件总表

**新增**
```
app/src/main/java/io/legado/app/model/SceneEffectModels.kt
app/src/main/java/io/legado/app/service/SceneEffectGenerator.kt
app/src/main/java/io/legado/app/ui/book/read/config/SceneEffectView.kt
```

**修改**
```
app/src/main/java/io/legado/app/constant/EventBus.kt
app/src/main/java/io/legado/app/constant/PreferKey.kt
app/src/main/java/io/legado/app/help/config/AppConfig.kt
app/src/main/java/io/legado/app/service/BaseReadAloudService.kt
app/src/main/java/io/legado/app/ui/book/read/config/BgmConfigDialog.kt
app/src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt
app/src/main/java/io/legado/app/ui/book/read/config/VnStageDialog.kt
app/src/main/java/io/legado/app/ui/book/read/config/FullscreenAiImageDialog.kt
app/src/main/res/layout/dialog_bgm_config.xml
app/src/main/res/layout/dialog_read_aloud.xml
app/src/main/res/layout/dialog_vn_stage.xml
app/src/main/res/layout/dialog_fullscreen_image.xml
```

---

## 11. 实施结果（本轮已完成）

按 Step 1~7 全部落地，Step 8（把站点 cue 目录接进 AI 提示词）作为后续增强暂未做。

**新增 3 个文件**
```
app/src/main/java/io/legado/app/model/SceneEffectModels.kt
app/src/main/java/io/legado/app/service/SceneEffectGenerator.kt
app/src/main/java/io/legado/app/ui/book/read/config/SceneEffectView.kt
```

**修改 11 个文件**
```
constant/EventBus.kt                  SCENE_EFFECT_READY
constant/PreferKey.kt                 sceneEffectEnabled
help/config/AppConfig.kt              sceneEffectEnabled
service/BaseReadAloudService.kt       共享区间队列、独立生成与广播、切章清理
ui/.../BgmConfigDialog.kt             开关初始化与监听
ui/.../ReadAloudDialog.kt             初始化/订阅/锚点播放/半屏全屏同步
ui/.../VnStageDialog.kt               同上
ui/.../FullscreenAiImageDialog.kt     同上
res/layout/dialog_bgm_config.xml      开关 UI
res/layout/dialog_read_aloud.xml      背景之上插入特效层
res/layout/dialog_vn_stage.xml        同上
res/layout/dialog_fullscreen_image.xml 同上
```

**方案外补的两处必要修正**
1. `MutableMap.putIfAbsent` 需 API 24，项目 minSdk 23 → 改为 `getOrPut`。
2. `ReadAloudDialog` 半屏模式：`sceneEffectView` 与 `danmakuView` 同为 `match_parent`，
   半屏窗口必须一并 `GONE`（否则撑满整屏、面板错位）；全屏模式同步按开关恢复 `VISIBLE`。
   另外切章的 `READ_ALOUD_CHAPTER_CHANGED` 里补了清特效。

**验证**
- `./gradlew :app:compileAppReleaseKotlin :app:processAppReleaseResources --offline --no-daemon`
  → `BUILD SUCCESSFUL`，无新增代码 warning；
- 4 个 XML 解析通过；ViewBinding 新 id `scene_effect_view` 在 3 个布局存在、被 3 个界面引用；
- **未打包 APK**（本轮未要求）。

**怎么用**
1. 朗读界面 → BGM 配置弹窗 → 打开「场景特效」开关（默认关闭）。
2. 朗读到含雪/雨/落叶/花瓣/闪电/萤火虫/火星/风的段落，延后 1 秒开始播放，8 秒后淡出停止。
3. 不配任何 AI 也能用（本地关键词规则主路径）；弹幕开关同时打开时，
   复用 BGM 的 AI Provider 做可选增强，能更准地判断室内外、强度与比喻。
4. 切章、关闭开关、切到半屏模式都会立即停止。
