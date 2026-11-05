# 场景特效触发测试片段

这一组纯文本片段用来实测「朗读到某句话 → 音效响起 → 屏幕同时绘制特效」。
每个 `.txt` 是一个独立场景，**文件里只有小说正文**，没有表格和标记，可以直接导入阅读 App 朗读；
预期触发清单和验证结论放在本文件与 `触发验证报告.md` 里。

---

## 一、今天新增的这些效果到底怎么触发

### 三条触发路径

```
① 正则替换规则（普通音效）
   正文里出现「爆炸 / 火焰 / 拔刀 …」→ ttsrv-replaces.json 规则插入 (爆炸音效) 标记
   → parseSoundEffects() 得到 SoundEffect(name="爆炸音效", charOffset=…)
   → checkAndPlayEffects() 按句子位置触发 → loadAndPlayEffect()

② AI 识别音效
   AI 音效从 ai_effect_index_kw.json 的 6553 个名字里选中一个（如「地雷爆炸」「军刀出鞘」）
   → AiEffectPick(name, path, at, pos) → 同样落到 loadAndPlayEffect()

③ 本地兜底（音效关掉也能画）
   SceneEffectGenerator.EXTRA_RULES 扫正文关键词 → 段落级生成 SceneEffectCue
   → SCENE_EFFECT_READY 事件 → 三个朗读弹窗延后 1 秒 show()
```

①②共用同一个收口：`BaseReadAloudService.loadAndPlayEffect()`。它在下载音频**之前**同步发一条
`EventBus.SOUND_EFFECT_FIRED(para, ratio, name, fileName, isAiEffect, charOffset)`，
三条路径（含补播队列）一次覆盖，保证画面和声音落在同一句。

### 从事件到画面

```
SoundEffectFired
  → 三个弹窗订阅 → SoundEffectSceneMapper.map()
      ① normalize()：(爆炸音效) / 爆炸.mp3 / 01_通用母库/拟音/地雷爆炸.mp3 → 统一成「爆炸」
      ② 查 app/src/main/assets/fx_sound_bindings.json
         38 个画面效果，每个绑定多个真实音效名，共 502 个；任一命中即画
      ③ 没命中 → ≥2 字具体词兜底（单字泛词不参与，避免「倒车雷达」画闪电）
  → SceneEffectCue(burst=true, kind=…, durationMs=…)
  → SceneEffectView.burst() → onDraw 画瞬时层
```

瞬时类时长：斩击 800ms、命中 750ms、爆炸 1100ms、冲击波 1200ms、火焰/烟花 1800ms；
持续型天气（雨雪雾等）仍是段落级 8 秒。去重：同一音效名 900ms 内只画一次、同屏最多 2 个爆发效果。

### 必须打开的开关

| 开关 | 位置 | 默认 |
|---|---|---|
| **场景特效** `sceneEffectEnabled` | 音画设置 / BGM 配置 | **关**，必须手动打开 |
| 规则音效（正则替换） | 音效规则管理页 / 音效模式 | 视个人配置 |
| AI 音效 `aiEffectEnabled` | 音画设置 | 视个人配置，需 AI 服务可用 |
| 弹幕 `danmakuEnabled` | 只影响场景特效的 AI 增强通道 | 关也能画，只是走本地词表 |

> 只打开「场景特效」+ 规则音效，就能用下面 12 个片段验证绝大多数效果；AI 音效打开后会额外命中
> 「地雷爆炸」「军刀出鞘」「内力比拼」这类真实音效名，走精确绑定表。

---

## 二、12 个场景片段

| 文件 | 场景 | 主要想看的效果 |
|---|---|---|
| `01_城门爆破.txt` | 夜袭城门、连环爆破 | EXPLOSION / IMPACT / SHOCKWAVE / SMOKE / DUST / FIRE / SPARKLES / COMIC |
| `02_火场与余烬.txt` | 柴房失火、泼水救火 | FIRE / SMOKE / EMBERS / FOUNTAIN / BUBBLES / RAYS / FOG |
| `03_校场比武.txt` | 校场刀剑对决 | SLASH / IMPACT / SPARKLES / COMIC / CONFETTI |
| `04_内力与天雷.txt` | 山巅内力比拼、落雷 | SHOCKWAVE / LIGHTNING / WIND / FOG / BIRDS_CROW / BOKEH |
| `05_雨夜追击.txt` | 暴雨长巷追逐 | RAIN / WIND / LIGHTNING / FOUNTAIN / BUBBLES / FOG / IMPACT |
| `06_雪岭行军.txt` | 风雪翻山 | SNOW / WIND / BIRDS_CROW / FOG / EMBERS / STARS / ZZZ |
| `07_春日山园.txt` | 樱花园、溪边黄昏 | PETALS / BUTTERFLIES / BIRD_FLOCK / FIREFLIES / DANDELION / FEATHERS / LEAVES / BOKEH |
| `08_星夜庆典.txt` | 河滩流星与烟花 | STARS / METEORS / FIREWORKS / CONFETTI / SPARKLES / BOKEH |
| `09_江畔水声.txt` | 渡口涨水 | FOUNTAIN / BUBBLES / BIRDS_GULL / FEATHERS / BOKEH / FOUNTAIN |
| `10_书斋一日.txt` | 学生被先生问书 | HEART / SWEAT / ANGER / QUESTION / LIGHTBULB / ZZZ / COMIC |
| `11_清晨市井.txt` | 早点摊开张 | SMOKE / FOG / RAYS / FIRE / BUBBLES / LEAVES / BUTTERFLIES / BIRD_FLOCK / ZZZ |
| `12_大战终章.txt` | 综合长片段：从薄雾到烟花收尾 | 几乎全部核心效果串联，适合一次看完整条链路 |

`12_大战终章.txt` 最长，把「爆炸 → 火焰 → 刀光 → 内力冲击波 → 天雷 → 暴雨 → 余烬 → 星空流星 → 烟花欢呼」
按时间顺序串起来，适合做一次整体验收。

---

## 三、怎么实测

1. 把某个 `.txt` 内容当成一个章节导入阅读 App（或复制进任意书籍的正文）。
2. 打开「场景特效」开关；想测 AI 音效就再打开 AI 音效。
3. 开始朗读，读到「地雷爆炸」「军刀出鞘」「内力比拼」这类句子时，声音和画面应同时出现。
4. 看日志更准：`AppLog` 里同一刻会看到 `[音效] 播放: xxx`，画面浮层则画对应的爆炸/刀光/冲击波。
5. 逐个片段对照 `触发验证报告.md` 的预期表，检查哪些效果出现了、哪些没出现。

**先离线预测一遍再上真机：**

```bash
python3 docs/visual_effects/触发测试片段/验证触发.py
```

脚本用运行时同一份 `fx_sound_bindings.json`、直接从 `SoundEffectSceneMapper.kt` 解析出来的关键词表、
以及 `effect_regex_source.json` 的正则，逐片段算出「预计会画哪些效果、由哪条路径命中」，
输出 `触发验证报告.md`（含每个效果出现在哪些片段、还有哪些效果没被覆盖）。

**当前预测结果：12 个片段共覆盖 38 / 38 种画面效果**，单个片段最多 28 种（`12_大战终章.txt`）。
133 个命中里 122 个走「精确绑定」（音效名或短别名原样出现在正文里，如 `地雷爆炸`、`军刀出鞘`、`火焰`），
8 个走关键词兜底，3 个由工作区正则规则副本直接命中。

> 预测口径是「文本里有没有能触发的词/音效名」，不是真机跑过。
> 比喻句也会命中，例如「密得像暴雨砸在铁皮上」会让 RAIN 触发——这正是关键词兜底的真实行为；
> 如果觉得画面过于频繁，收窄 `fx_sound_bindings.json` 或 `SoundEffectSceneMapper.RULES` 里的词即可。

---

## 四、注意

- 真机的正则规则以设备 `/storage/emulated/0/Download/chajian/mingwuyan/ttsrv-replaces.json` 为准，
  工作区里的 `effect_regex_source.json` 是它的派生副本，预测以副本为准，个别规则名可能不同。
- 绑定表可直接编辑：`app/src/main/assets/fx_sound_bindings.json` 里给某个效果加音效名即可，
  不用改 Kotlin、不用重新编译（但改完要重新打包 APK 才能进到手机里）。
- 想改画面本身：`SceneEffectExtraRenderer.kt` 的 `drawExplosion / drawSlash / drawImpact / drawShockwave`，
  或在 `app/src/main/assets/fx/<效果名小写>.png` + 同名 `.json` 放开源序列帧素材，会自动替换算法绘制。
- 如果某句只出声音不出画面，先看「场景特效」总开关，再看音效名是否被 `normalize()` 后落在绑定表里
  （`(爆炸音效)` → `爆炸`，`地雷爆炸.mp3` → `地雷爆炸`）。
