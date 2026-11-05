# -*- coding: utf-8 -*-
"""离线预测 12 个小说片段会触发哪些画面特效。

模拟的正是运行时链路：
  正则替换规则 / AI 音效名 → SoundEffectSceneMapper.normalize()
    → fx_sound_bindings.json 精确绑定表 → SceneEffectCue(kind)
  未精确命中 → SoundEffectSceneMapper.RULES 的 ≥2 字关键词兜底

用法（仓库根目录或本目录均可）：
  python3 docs/visual_effects/触发测试片段/验证触发.py

输出：同目录 触发验证报告.md
"""
import json
import os
import re
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
ASSETS = os.path.join(ROOT, "app/src/main/assets")
KOTLIN = os.path.join(ROOT, "app/src/main/java/io/legado/app/model/SoundEffectSceneMapper.kt")
REGEX_SRC = os.path.join(ROOT, "docs/ai_effect_category/tools/effect_regex_source.json")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def normalize(s):
    s = s.strip()
    if s.startswith("("):
        s = s[1:]
    if s.endswith(")"):
        s = s[:-1]
    s = s.split("/")[-1]
    for suf in (".mp3", ".json"):
        if s.endswith(suf):
            s = s[:-len(suf)]
    if s.endswith("音效"):
        s = s[:-2]
    return s.strip()


# ---------- 1. 精确绑定表（与运行时同一个 JSON） ----------
bindings = json.loads(read(os.path.join(ASSETS, "fx_sound_bindings.json")))["bindings"]
exact = {}
for kind, names in bindings.items():
    for n in names:
        exact.setdefault(normalize(n), kind)

# ---------- 2. 关键词兜底词表（直接从 Kotlin 源码解析，避免两份词表不一致） ----------
kt = read(KOTLIN)
rules = []
for kind, words_blob in re.findall(
    r"SceneEffectKind\.(\w+),\s*listOf\((.*?)\)\s*,\s*\d+\s*\)", kt, re.S
):
    words = re.findall(r'"([^"]+)"', words_blob)
    if words:
        rules.append((kind, [w for w in words if len(w) >= 2]))
if not rules:
    raise RuntimeError("未能从 SoundEffectSceneMapper.kt 解析 RULES 词表")

# ---------- 3. 正则替换规则（工作区副本，只取绑定表里的名字） ----------
regex_src = json.loads(read(REGEX_SRC))["effects"]


def java_regex_to_python(p):
    # JSON 里是 \u4e00 这类字面转义，Python re 不认，先转成真实字符
    return re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), p)


regex_rules = []
for name, info in regex_src.items():
    if normalize(name) not in exact:
        continue
    try:
        regex_rules.append((name, re.compile(java_regex_to_python(info.get("regex", "")))))
    except re.error:
        continue

# ---------- 4. 逐片段预测 ----------
files = sorted(f for f in os.listdir(HERE) if f.endswith(".txt"))
report = defaultdict(dict)   # 文件 -> kind -> (来源, 证据)
kind_scenes = defaultdict(set)

for fn in files:
    text = read(os.path.join(HERE, fn))

    # a. 精确绑定：音效名（或其别名）原样出现在正文里 → AI 音效 / 规则标记归一化后命中
    for name, kind in exact.items():
        if len(name) >= 2 and name in text:
            report[fn].setdefault(kind, ("精确绑定", name))
            kind_scenes[kind].add(fn)

    # b. 正则规则：规则 regex 命中正文 → 会插入 (xx音效) 标记
    for name, pattern in regex_rules:
        if pattern.search(text):
            kind = exact.get(normalize(name))
            if kind:
                report[fn].setdefault(kind, ("正则规则", name))
                kind_scenes[kind].add(fn)

    # c. 关键词兜底：≥2 字词命中正文
    for kind, words in rules:
        hit = next((w for w in words if w in text), None)
        if hit:
            report[fn].setdefault(kind, ("关键词兜底", hit))
            kind_scenes[kind].add(fn)

# ---------- 5. 输出报告 ----------
all_kinds = list(bindings.keys())
covered = [k for k in all_kinds if kind_scenes.get(k)]
missing = [k for k in all_kinds if not kind_scenes.get(k)]

md = [
    "# 片段触发验证报告",
    "",
    "由 `验证触发.py` 生成，模拟 `SoundEffectSceneMapper` 的精确绑定 + 关键词兜底，",
    "并用 `docs/ai_effect_category/tools/effect_regex_source.json` 的正则做规则匹配预测。",
    "",
    f"- 片段数：**{len(files)}**",
    f"- 画面效果总数：**{len(all_kinds)}**",
    f"- 预计可触发：**{len(covered)}**",
    f"- 尚未覆盖：**{len(missing)}** {('（' + '、'.join(missing) + '）') if missing else ''}",
    "",
    "> 这是离线预测：真机上还取决于设备里的 `ttsrv-replaces.json` 规则、AI 音效开关与网络、",
    "> 以及「场景特效」总开关是否打开。预测命中只说明文本里有能触发的词/音效名。",
    "",
    "## 每个片段预计触发的效果",
    "",
]
for fn in files:
    items = report[fn]
    md += [f"### {fn}", "", f"预计触发 **{len(items)}** 种效果：", "",
           "| 效果 | 命中来源 | 命中词 / 音效名 |", "|---|---|---|"]
    for kind in sorted(items, key=lambda k: all_kinds.index(k)):
        src, evidence = items[kind]
        md.append(f"| `{kind}` | {src} | {evidence} |")
    md.append("")

md += ["## 效果 → 出现在哪些片段", "", "| 效果 | 片段数 | 片段 |", "|---|---:|---|"]
for kind in all_kinds:
    scenes = sorted(kind_scenes.get(kind, set()))
    md.append(f"| `{kind}` | {len(scenes)} | {'、'.join(s.split('_')[0] for s in scenes) or '—'} |")

out = os.path.join(HERE, "触发验证报告.md")
with open(out, "w", encoding="utf-8") as f:
    f.write("\n".join(md) + "\n")

print("片段数:", len(files))
print("RULES 组数:", len(rules), "精确绑定名:", len(exact), "可用正则:", len(regex_rules))
print("预计可触发效果:", len(covered), "/", len(all_kinds))
print("未覆盖:", missing)
for fn in files:
    print(" ", fn, len(report[fn]), "种")
print("报告:", out)
