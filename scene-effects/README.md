# 场景特效参考实现（持久化目录）

> 本目录**不是临时缓存**，是放在工作区里的正式参考代码包。
> 来源：`https://www.tftgamer.com/#/`，2026-11-04 用游客模式 + 可抓包浏览器（Playwright/Chromium）探测、下载并整理。
> 用途：以后想在「阅读 / 视觉小说 / 朗读」里实现「根据情节飘雪、下雨、飘落叶、花瓣、萤火虫、闪电、运镜」时，**直接让 AI 读本文件 + 本目录代码**，它就能明白架构、数据结构和接入方式，不需要重新抓站。

---

## 1. 一句话结论

这个站点**不让大模型直接写「画 100 片雪花」**，而是：

```
小说情节
  → 大模型只做语义判断：这里是不是真的下雪 / 落叶 / 下雨？室内还是室外？从第几个动作持续到第几个动作？强度几级？
  → 输出结构化 JSON（稳定效果 ID + 时间区间）
  → 校验 / 规范化 / 去重
  → 用稳定 ID 查一张「预设表」，得到确定性渲染轨道 tracks
  → 渲染层按轨道画粒子 / 镜头 / 遮幅 / 色调
```

**语义判断**和**画面渲染**完全解耦。粒子绘制本身很简单，真正复杂的是「什么时候该出现」的判断、区间、校验和去重。

---

## 2. 目录结构

| 路径 | 内容 |
|---|---|
| `README.md` | 本文件：架构说明、数据流、接入指引、给 AI 的复用说明 |
| `presets/effects.json` | **74 个视觉/演出预设**（雨、雪、落叶、花瓣、闪电、镜头、遮幅…），每条含 `tracks` |
| `presets/semantic-effects.json` | **50 条语义效果目录**：`id / name / preset / groups / guide`，LLM 只能从这里选 ID |
| `mapping/semantic-cues.json` | 语义 cue → 效果 ID 的完整映射、情绪映射、优先级、LLM 可选效果过滤规则 |
| `mapping/weather.json` | 天气强度 → 雨/雪粒子参数（数量、速度、风）、室内外规则 |
| `schema/effect-annotation.schema.json` | 大模型输出（语义标注）的 JSON Schema |
| `schema/track.schema.json` | 渲染轨道 `track` 的 JSON Schema（按 `kind` 分支） |
| `pipeline/effect-annotation-prompt.md` | 可直接复制使用的大模型提示词模板（视觉小说版 + 漫画版） |
| `renderer/effect-resolver.ts` | 整理后的可读实现：cue 映射、天气转换、校验、区间合并、group 去重、ID → tracks |
| `renderer/weather-mapper.ts` | 天气区间 → `particles` 轨道参数 |
| `renderer/particle-renderer.ts` | 整理后的可读 Canvas 2D 粒子渲染器（雨/雪/花瓣/落叶/环境），不依赖 PixiJS |
| `android/SceneEffectModels.kt` | Android/Kotlin 数据类：语义标注、天气、效果、轨道 |
| `android/SceneEffectResolver.kt` | Android/Kotlin 版解析：JSON → 当前动作该显示哪些轨道 |
| `android/WeatherOverlay.kt` | Android `View`：把背景之上、对话框之下的雨雪落叶画出来 |
| `original/site/` | 站点原始文件（主 JS、粒子渲染 chunk、特效实验室、舞台、CSS、4 个生图提示词、漫画特效提示词实例） |
| `original/main-bundle-effect-snippets.js` | 从主包按标识符抽取的特效相关片段（压缩原文，带分隔注释） |
| `original/particle-renderer-snippets.js` | 从共享 chunk 抽取的粒子/天气渲染原文片段 |
| `original/semantic-effect-catalog.js` | 语义效果目录 `FB` 的原始定义块 |
| `reference/` | 全站解析任务清单、静态分析记录（背景资料，非本模块必需） |
| `SOURCES.txt` | 来源、抓取方式、文件清单、法律/使用边界 |
| `INTEGRATION_PLAN.md` | 把场景特效接入当前 Legado 朗读/视觉小说项目的完整方案与实施结果 |

---

## 3. 核心设计（必须先理解）

### 3.1 三层分离

| 层 | 职责 | 对应文件 |
|---|---|---|
| 语义层 | 判断「发生了什么」 | `pipeline/effect-annotation-prompt.md`、`schema/effect-annotation.schema.json` |
| 决策层 | cue → 效果 ID、校验、区间、去重、ID → tracks | `mapping/*`、`renderer/effect-resolver.ts`、`android/SceneEffectResolver.kt` |
| 渲染层 | 按 tracks 画粒子/镜头/遮罩 | `presets/effects.json`、`renderer/particle-renderer.ts`、`android/WeatherOverlay.kt` |

### 3.2 稳定 ID，不让模型自由发挥

大模型输出的是 `a02`、`a03`、`e13` 这种**固定 ID**，不是绘图指令。所有 ID 必须在 `presets/semantic-effects.json` 里存在。好处：

- 同一个 `a03` 永远对应同一套落叶动画，可缓存、可调试、可手动覆盖；
- 模型不能发明新特效；
- 换渲染引擎（Web → Android）只需换「ID → tracks → 绘制」的最后一环。

### 3.3 确定性预设轨道（tracks）

一个效果 = 一组 `tracks`，每条 track 是一个渲染指令：

```json
{ "kind": "particles", "variant": "rain", "count": 180, "at": 0, "duration": 7 }
{ "kind": "ambient",  "variant": "petals", "count": 100, "size": 1, "opacity": 0.9, "duration": 7, "at": 0 }
{ "kind": "camera",   "scale": 1.15, "panX": 90, "angle": 0, "duration": 5 }
{ "kind": "overlay",  "variant": "lightning", "strength": 0.75, "duration": 8, "at": 0 }
```

一共 17 种 `kind`：`cutin / scrutiny / eyelids / opening / camera / shake / blur / flash / particles / ambient / overlay / tone / actor / text / birds / distortion / brightness`。
完整定义见 `schema/track.schema.json`。

### 3.4 时间轴 + 区间 + hold

- 语义标注里 `a[]` 是「逐动作」效果；`w[]` 是「天气区间」，用 `[章节号, 动作序号]` 表示起止。
- `hold: true` 的效果会一直持续到动作结束，不是播 7 秒就消失。
- 雨/雪是**区间型**：整个区间内持续渲染，区间结束立即停止并恢复。
- 渲染循环用 `performance.now()` 累加时间，`document.hidden` 时暂停；`prefers-reduced-motion` 下关闭 flash / 眼睑 / 负片等强刺激效果。

### 3.5 校验与降级

站点用 Zod 做严格校验，关键规则（整理在 `renderer/effect-resolver.ts`）：

- `w[].t` 只能是 `r`（雨）或 `s`（雪）；
- `lv` 只能是 `1..4`，字符串 `"2"` / 布尔都会 coerce；
- `l / w / indoors` 布尔 → `0/1`；
- 天气区间不能重叠；重叠且类型冲突时**留空重叠部分**，保留非冲突部分；
- `id` 简写 `e1` → `e01` 自动补零；
- 未知 ID、目录外 ID、需要指定人物的效果却没给 `cId` → 丢弃或回退；
- 同一 `group` 在同一动作里只保留一个效果（避免雨 + 暴雨 + 雷雨同时叠）。

---

## 4. 完整数据流

```
                    ┌─────────────────────────────────────────┐
小说原文 / storyScript│  动作列表：chapterSeq, order, fromLine,   │
                    │  toLine, cues[], tone                   │
                    └──────────────────┬──────────────────────┘
                                       │
                         effectAnnotation（大模型）
                                       │
                    ┌──────────────────▼──────────────────────┐
                    │ 输出 JSON                                │
                    │ {                                        │
                    │   "a": [ {"cs":1,"o":3,"cues":[          │
                    │            {"kind":"falling_leaves"}]} ], │
                    │   "w": [ {"s":[1,2],"e":[1,12],          │
                    │            "t":"r","lv":3,"l":0,         │
                    │            "w":1,"indoors":0} ],          │
                    │   "op": {"id":"cinema"}                  │
                    │ }                                        │
                    └──────────────────┬──────────────────────┘
                                       │
              Zde()  天气对象 → {t,lv,l,w,indoors}
              pue()/Iw()  规范化 + 校验 + 区间合并
                                       │
              Gde()  按动作找覆盖它的天气区间
                     cues 按优先级排序 → fF 映射 → 效果 ID
                     group 去重 → fx:[{id:"a03"}]
                                       │
              i3(id) = dc(id)?.tracks
                       ?? effects.json.find(id === preset)?.tracks
                       （structuredClone 后返回，a18 注入 color/opacity）
                                       │
              weather-mapper：w.t/lv/w/indoors
                       → 雨/雪 particles track
                       （count、fallSpeed、wind）
                                       │
              particle-renderer / WeatherOverlay
                       requestAnimationFrame / Choreographer
                       → 画粒子、镜头、遮幅
```

### 关键映射（务必看对应 JSON）

- `mapping/semantic-cues.json`：`falling_petals → a02`、`falling_leaves → a03`、`fireflies → a04`、`embers → a05`、`lightning → a07`、`wind → a17`、`heat → a01`、`night → a08` …
- `mapping/weather.json`：
  - `light / moderate / heavy / violent` → `lv = 1 / 2 / 3 / 4`
  - 雨 `count = [90, 180, 340, 600][lv-1]`，雪 `count = [60, 110, 230, 400][lv-1]`
  - `fallSpeed = [0.6, 1.0, 1.4, 1.9][lv-1]`
  - `wind = w.w ? 1 : 0`
  - **`indoors === 1` 直接不生成降水粒子**
  - `l === 1` 时额外叠 `lightning` overlay

---

## 5. 渲染实现要点

### 5.1 站点原始实现（Web）

- 引擎：**PixiJS / WebGL**（`original/site/MusicCatalogPicker-tesK0yHr.js`，文件名虽叫 MusicCatalogPicker，实际打包了特效舞台）。
- 雨/雪：离屏 `canvas` 画 128×128 的雨丝渐变纹理和 3~4 种雪花形状纹理 → 转 Pixi Texture → `populate()` 预创建 `count` 个 Sprite，分近景/远景两个 Container，**不在每帧 new 对象**。
- 每帧 `draw()` 用确定性伪随机（`Math.sin(i*127.1 + seed*311.7) * 43758.5453 % 1`）算第 i 个粒子的位置：
  - `x = (baseX + t*wind*speed + sin摆动) % width`
  - `y = (baseY + t*fallSpeed) % height`
  - 取模回卷，所以粒子无限循环；
  - 雨滴按 `atan2(wind, fallSpeed)` 倾斜；雪花带翻转、缩放、透明度变化。
- 花瓣/落叶：另一套 `ambient` 渲染，`Path2D` 画 4~6 种不对称花瓣 / 3 种漫画秋叶轮廓生成纹理；`petals` 分支加正弦横摆、旋转、skew；`leaves` 分支下落更快、旋转更明显，并按法线换色模拟叶片正反面。
- `fog / smoke / wind / rays / bubbles / fireflies / embers / dust` 都在同一个 `Cn()` 的 `switch(variant)` 里用 Graphics 画圆、椭圆、贝塞尔曲线、光束。
- 天气变化时才重建粒子池：比较 `[t, lv, l, w, indoors]`，相同则复用，不同则 `removeChildren().destroy()` 后重新 `populate`。

原始片段见 `original/particle-renderer-snippets.js`；可读等价实现见 `renderer/particle-renderer.ts`。

### 5.2 落地到 Android

**不需要 PixiJS**。Android 用 `Canvas` + `postInvalidateOnAnimation()` 就够，见 `android/WeatherOverlay.kt`：

- 一个自定义 `View` 放在「背景图 / 立绘之上、对话框之下」；
- `setCue(cue)` 时按 `lv` 取数量、按时间算位置；
- `indoor = true` 直接不画雨雪；
- 用 `hash(i, seed)` 代替每帧 `Random`，保证旋转屏幕/重绘不跳变；
- `Choreographer` 或 `postInvalidateOnAnimation()` 驱动，页面不可见时停。

---

## 6. 接入当前 Legado Android 项目的步骤

1. **加数据类**：复制 `android/SceneEffectModels.kt` 到 `app/src/main/java/io/legado/app/model/`（或你的 VN 包）。
2. **加解析器**：复制 `android/SceneEffectResolver.kt`，把 `presets/effects.json` + `presets/semantic-effects.json` + `mapping/*.json` 放进 `assets/scene-effects/`，启动时加载。
3. **加渲染 View**：复制 `android/WeatherOverlay.kt`；在 `dialog_vn_stage.xml`（或视觉小说舞台布局）里，背景/立绘之后、对话框之前插入：
   ```xml
   <io.legado.app.xxx.WeatherOverlay
       android:id="@+id/weather_overlay"
       android:layout_width="match_parent"
       android:layout_height="match_parent" />
   ```
4. **接语义来源**（三选一，按复杂度递增）：
   - 最简单：复用项目已有的 `SoundEffectRule` / BGM AI 的段落语义结果，写关键词规则（`雪 → snow lv2`、`落叶 → leaves`）；
   - 中等：新增一次 AI 调用，用 `pipeline/effect-annotation-prompt.md` 输出结构化 JSON，再用 `SceneEffectResolver` 解析；
   - 完整：照 `reference/pipeline-tasks.txt` 建 `effectAnnotation` 任务，接入全书解析流水线。
5. **推进时刷新**：朗读/VN 推进到某个动作/段落时，调 `resolver.resolve(action)` 得到 `List<Track>`，再 `weatherOverlay.setTracks(tracks)`。
6. **开关与降级**：加「减少动态效果」开关；`indoors` / `lv` / `hold` 的规则按 `mapping/weather.json` 执行。

> 建议 MVP：只做 `rain / snow / petals / leaves` 四种 + `indoors` 屏蔽 + 按段落区间生效。满意后再把 `camera / shake / overlay / tone` 从 74 个预设里逐步搬过来。

---

## 7. 给 AI 的复用指令（可直接复制）

```
请阅读 /workspace/scene-effects/README.md，以及该目录下的：
  presets/effects.json、presets/semantic-effects.json、
  mapping/semantic-cues.json、mapping/weather.json、
  schema/effect-annotation.schema.json、schema/track.schema.json、
  renderer/effect-resolver.ts、renderer/weather-mapper.ts、
  renderer/particle-renderer.ts、
  android/SceneEffectModels.kt、android/SceneEffectResolver.kt、
  android/WeatherOverlay.kt。
按 README 第 6 节把场景特效接入当前 Android 项目。
要求：语义判断与渲染分离；效果用稳定 ID；indoors 不生成雨雪；
天气按 lv 1..4 映射粒子数量与速度；粒子用确定性伪随机 + 取模回卷；
不要改动无关模块。
```

---

## 8. 原始代码清单（`original/site/`）

> 下表文件名均位于 `original/site/` 目录下；带 `*` 的是同一批实例文件。


| 文件 | 说明 |
|---|---|
| `original/site/index-BLGBg1UO.js` | 站点主包，含 `fF / Zde / Iw / Gde / i3 / dJ / FB / oue / v$` 等全部决策逻辑 |
| `original/site/MusicCatalogPicker-tesK0yHr.js` | 共享 chunk，含 PixiJS 与雨/雪/落叶/环境粒子渲染器 |
| `original/site/EffectsLabPage-CqcLXRS5.js` / `.css` | 特效实验室：预设编辑、参数滑杆 |
| `original/site/PerformanceStage-BuS5_8CA.js` / `.css` | 视觉小说演出舞台 |
| `original/site/MangaPerformancePage-rTKJLrsl.js` / `.css` | 漫画演出页 |
| `original/site/PerformancePage-CQEEinIi.js` / `.css` | 演出页 |
| `original/site/OpeningLabPage-f-gUlNk_.js` / `.css` | 开场演出实验室 |
| `original/site/parser.worker-hbO5_fU2.js` | 解析 worker（背景资料） |
| `original/site/index.html` / `original/site/favicon.svg` | 入口 |
| `original/site/plot_scene_image_user_prompt.txt` 等 4 个 | 生图/美术风格提示词（背景资料） |
| `original/site/10_mangaEffects_chapter_1_*.prompt.txt` / `.response.txt`、`16_mangaEffects_*` | 漫画特效标注的真实请求/响应实例（两批） |

`original/main-bundle-effect-snippets.js`、`original/particle-renderer-snippets.js`、`original/semantic-effect-catalog.js` 是从上面压缩文件里按标识符抽出来的片段，便于对照，不需要直接编译。

---

## 9. 限制与注意

- 全部来自**公开游客模式**，没有登录绕过、没有越权、没有触发付费生成；演示项目是公版小说包。
- 站点的大模型/生图请求是**用户自配 provider 直连**，不是站点后端接口；本目录记录的是前端决策逻辑与渲染逻辑。
- 原始 JS 是压缩混淆产物，变量名无语义；`renderer/*.ts` 和 `android/*.kt` 是**等价可读重写**，不是逐字反编译，行为一致但不等同于原文件。
- 本目录目前是工作区里的新文件夹，**未提交 git、未推送**；需要时再单独提交。
- `.tftgamer_probe/` 是探测时的临时缓存（含浏览器、HAR、65MB 演示项目包），与本目录独立；本目录只保存「场景特效」相关代码，不含那 65MB 数据包。

---

## 10. 快速验证

```bash
cd /workspace/scene-effects
python3 -c "import json;[print(f,len(json.load(open(f)))) for f in ['presets/effects.json','presets/semantic-effects.json','mapping/semantic-cues.json','mapping/weather.json']]"
ls original/site | wc -l
```

预期：74 个预设、50 条语义效果、2 个映射文件可正常解析，`original/site/` 有 20+ 个原始文件。
