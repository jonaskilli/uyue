# -*- coding: utf-8 -*-
"""从两套音效配置全量提取音效名，并生成「一个画面效果绑定多个真实音效名」的绑定表。

两套来源：
  A. AI 识别音效库：app/src/main/assets/ai_effect_index_kw.json（带关键词）、
     广播剧音效库_目录索引.json（纯名字）、
     docs/ai_effect_category/ai_effect_category_map.json（名字 + 分类代码 + 文件位置）
  B. 正则替换规则：docs/ai_effect_category/tools/effect_regex_source.json
     （3830 个音效名各带 regex，源自 ttsrv-replaces3.json + ttsrv-replaces5.json）、
     docs/ai_effect_category/tools/classify_source.json（1053 条原始规则分类）

产物（全部落在源码仓库内）：
  docs/ai_effect_category/音效名字全量清单.csv
  docs/ai_effect_category/音效绑定表.md
  app/src/main/assets/fx_sound_bindings.json

用法（在仓库根目录执行）：
  python3 docs/ai_effect_category/tools/gen_fx_sound_bindings.py
"""
import csv
import json
import os
from collections import defaultdict

# 脚本位于 <repo>/docs/ai_effect_category/tools/，向上三级即仓库根目录
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DOCS = os.path.join(ROOT, "docs/ai_effect_category")
ASSETS = os.path.join(ROOT, "app/src/main/assets")


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def new_record():
    return {
        "sources": set(), "dirs": set(), "cats": set(),
        "codes": set(), "files": set(), "keywords": set(), "regex": set(),
    }


records = defaultdict(new_record)


def add(name, sources=None, dirs=None, cats=None, codes=None,
        files=None, keywords=None, regex=None):
    name = str(name).strip()
    if not name:
        return
    r = records[name]
    for key, values in (
        ("sources", sources), ("dirs", dirs), ("cats", cats),
        ("codes", codes), ("files", files), ("keywords", keywords),
        ("regex", regex),
    ):
        if values is None:
            continue
        if isinstance(values, str):
            r[key].add(values)
        else:
            r[key].update(str(v).strip() for v in values if str(v).strip())


# ---------- A1. AI 音效库（带关键词，运行时实际使用的索引） ----------
kw = load(os.path.join(ASSETS, "ai_effect_index_kw.json"))
kw_lines = 0
for section, groups in kw.items():
    for cat, lines in groups.items():
        for line in lines:
            parts = [p.strip() for p in line.split("|")]
            name = parts[0]
            kw_lines += 1
            add(
                name,
                sources="AI音效库-带关键词索引",
                dirs=section, cats=cat,
                keywords=parts[1:],
                files=f"{section}/{cat}/{name}.mp3",
            )

# ---------- A2. AI 音效库（纯名字目录索引） ----------
plain = load(os.path.join(ROOT, "广播剧音效库_目录索引.json"))
plain_count = 0
for section, groups in plain.items():
    for cat, names in groups.items():
        for name in names:
            plain_count += 1
            add(
                name,
                sources="AI音效库-目录索引",
                dirs=section, cats=cat,
                files=f"{section}/{cat}/{name}.mp3",
            )

# ---------- A3. AI 音效分类映射（含分类代码与真实文件位置） ----------
cmap = load(os.path.join(ROOT, "docs/ai_effect_category/ai_effect_category_map.json"))
for item in cmap["effects"]:
    add(
        item["name"],
        sources="AI音效分类映射",
        codes=item.get("categories", []),
        files=item.get("files", []),
        regex=item.get("regex", ""),
    )

# ---------- B. 正则替换规则派生的音效名与 regex ----------
regex_src = load(os.path.join(ROOT, "docs/ai_effect_category/tools/effect_regex_source.json"))
for name, info in regex_src["effects"].items():
    add(
        name,
        sources="正则替换规则-音效正则",
        regex=info.get("regex", ""),
    )
classify = load(os.path.join(ROOT, "docs/ai_effect_category/tools/classify_source.json"))
block = load(os.path.join(ROOT, "docs/ai_effect_category/tools/block_source.json"))


# ---------- 全量清单 ----------
csv_path = os.path.join(DOCS, "音效名字全量清单.csv")
with open(csv_path, "w", encoding="utf-8", newline="") as f:
    w = csv.writer(f)
    w.writerow([
        "序号", "音效名", "来源配置", "一级目录", "二级分类", "分类代码",
        "文件位置", "关键词", "正则触发式",
    ])
    for i, name in enumerate(sorted(records), 1):
        r = records[name]
        w.writerow([
            i, name,
            ";".join(sorted(r["sources"])),
            ";".join(sorted(r["dirs"])),
            ";".join(sorted(r["cats"])),
            ";".join(sorted(r["codes"])),
            ";".join(sorted(r["files"]))[:500],
            ";".join(sorted(r["keywords"]))[:500],
            ";".join(sorted(r["regex"]))[:300],
        ])


# ---------- 一个画面效果 → 多个真实音效名 ----------
# 与 SoundEffectSceneMapper.RULES 同源，按「具体 → 泛化」排序，第一个命中生效。
RULES = [
    ("EXPLOSION", ["爆炸", "爆裂", "炸开", "炸毁", "爆破", "轰隆", "轰然", "轰炸", "炸", "砰", "嘭", "轰", "炮击", "炮弹", "手雷", "榴弹"]),
    ("SLASH", ["拔刀", "出鞘", "剑气", "刀光", "剑鸣", "挥刀", "挥剑", "一刀斩", "斩下", "斩击", "劈下", "劈开", "砍下", "刀锋", "剑锋", "比武", "过招", "剑", "刀", "斩", "劈", "砍", "弓箭", "箭矢", "长矛", "唰"]),
    ("IMPACT", ["暴击", "重击", "重创", "击中", "命中", "撞击", "打斗", "格挡", "拳脚", "一拳", "一掌", "交击", "对斩", "拳", "掌", "踢", "咚", "哐"]),
    ("SHOCKWAVE", ["冲击波", "气浪", "震波", "震荡", "余波", "威压", "地动山摇", "威压", "灵压", "内力", "真气", "法术", "术法", "结界", "灵力"]),
    ("FIRE", ["火焰", "烈火", "燃烧", "着火", "火球", "火海", "焚烧", "熊熊", "火舌", "火苗", "篝火", "火光", "起火", "喷火", "火"]),
    ("LIGHTNING", ["雷电", "闪电", "惊雷", "雷鸣", "雷击", "雷声", "电闪", "落雷", "雷"]),
    ("FIREWORKS", ["烟花", "焰火", "礼花", "爆竹", "鞭炮", "绽放"]),
    ("SPARKLES", ["闪光", "闪耀", "亮晶晶", "晶莹", "闪烁", "熠熠", "寒光", "火花", "金光", "光芒"]),
    ("RAYS", ["光束", "光柱", "晨光", "霞光", "夕照", "光线", "阳光洒"]),
    ("SMOKE", ["浓烟", "烟雾", "烟尘", "炊烟", "青烟", "轻烟", "硝烟", "烟"]),
    ("FOG", ["浓雾", "迷雾", "雾气", "白雾", "薄雾", "雾霭", "起雾", "云雾"]),
    ("EMBERS", ["火星", "余烬", "灰烬", "飞灰", "炭火"]),
    ("RAIN", ["暴雨", "倾盆", "大雨", "雨声", "雨点", "雨幕", "下雨", "雨"]),
    ("SNOW", ["暴雪", "大雪", "雪花", "飘雪", "下雪", "落雪", "风雪", "雪"]),
    ("WIND", ["狂风", "大风", "风声", "呼啸", "刮风", "起风", "风"]),
    ("PETALS", ["花瓣", "落花", "樱花", "花雨", "桃花落"]),
    ("LEAVES", ["落叶", "秋叶", "枯叶", "叶落", "树叶飘"]),
    ("FIREFLIES", ["萤火虫", "流萤", "萤光"]),
    ("STARS", ["星空", "星辰", "繁星", "星河", "银河", "满天星", "星光"]),
    ("METEORS", ["流星", "陨星", "流星雨", "划过天际", "星雨"]),
    ("DUST", ["尘土", "扬尘", "黄沙", "沙尘", "飞沙", "灰土", "尘烟"]),
    ("FOUNTAIN", ["喷泉", "水花", "水柱", "浪花", "溅起", "水雾", "飞溅"]),
    ("BUBBLES", ["气泡", "水泡", "冒泡", "泡沫", "吐泡泡"]),
    ("BUTTERFLIES", ["蝴蝶", "彩蝶", "粉蝶", "蝶舞", "蝶翼", "花蝶"]),
    ("BIRD_FLOCK", ["鸟群", "群鸟", "雁阵", "大雁", "飞鸟", "惊鸟", "雀群"]),
    ("BIRDS_CROW", ["乌鸦", "寒鸦", "鸦群", "老鸹", "乌鹊"]),
    ("BIRDS_GULL", ["海鸥", "白鸥", "鸥鸟", "海鸟", "沙鸥"]),
    ("FEATHERS", ["羽毛", "白羽", "羽落", "翎毛", "鹤羽"]),
    ("DANDELION", ["蒲公英", "柳絮", "飞絮", "杨花", "芦花", "绒球"]),
    ("BOKEH", ["光斑", "光点", "微光", "朦胧", "斑驳", "光影", "粼粼"]),
    ("CONFETTI", ["彩纸", "彩带", "纸屑", "庆祝", "欢呼", "喜庆", "彩旗"]),
    ("HEART", ["心动", "怦然", "脸红", "心跳加速", "脸颊发烫"]),
    ("SWEAT", ["冷汗", "冒汗", "汗珠", "汗流", "捏了把汗"]),
    ("ANGER", ["青筋", "怒火", "恼怒", "气得", "暴跳", "咬牙切齿"]),
    ("QUESTION", ["满头问号", "疑惑不解", "一头雾水", "纳闷", "不明所以"]),
    ("LIGHTBULB", ["灵光一闪", "恍然", "顿悟", "茅塞顿开", "灵机一动"]),
    ("ZZZ", ["睡意", "打盹", "呼噜", "入眠", "困倦", "打哈欠"]),
    ("COMIC", ["哐当", "哗啦", "咔嚓", "噼啪", "嗖"]),
]

# 只按「音效名本身」匹配，按 RULES 顺序（具体 → 泛化）第一个命中生效。
# 不把关键词、二级分类、mp3 路径、分类代码放进匹配串：那些会把整个目录的音效
# （如「举枪」「上膛」「丧尸低吟」）误绑成爆炸/冲击波，造成朗读时画面乱闪。
# 未绑定的名字运行时仍可由 SoundEffectSceneMapper 的关键词表兜底。
BIND_RULES = [(k, [w for w in words if len(w) >= 2]) for k, words in RULES]
# 明显会被双字词误伤的复合名：雷达/电视雪花/羽毛球/流星锤 等不是雷电、下雪、羽毛、流星
EXCLUDE_NAME_PARTS = (
    "雷达", "电视雪花", "雪花底声", "雪花屏", "羽毛球", "流星锤",
    "吹风机", "风扇", "屏风", "风衣", "古风", "风景", "烟火气", "火漆", "火机",
)

bindings = defaultdict(set)
unbound = []
for name in records:
    if any(x in name for x in EXCLUDE_NAME_PARTS):
        unbound.append(name)
        continue
    kind = None
    for k, words in BIND_RULES:
        if any(w in name for w in words):
            kind = k
            break
    if kind:
        bindings[kind].add(name)
    else:
        unbound.append(name)

# 二次补绑：抽象/天气类效果在音效库里名字少且不含具体词，用宽词再挑几个真实名字，
# 保证 38 个画面效果「每个都绑定多个音效」，而不是只有核心几类有绑定。
WIDE = {
    "SNOW": ["雪", "冰霜", "凝霜"],
    "PETALS": ["花瓣", "落花", "樱"],
    "FIREFLIES": ["萤火", "流萤"],
    "FOG": ["雾"],
    "DUST": ["尘", "沙"],
    "BUTTERFLIES": ["蝴蝶", "蝶"],
    "QUESTION": ["问号", "疑惑", "纳闷"],
    "HEART": ["心跳", "心动", "怦然", "脸红"],
    "ANGER": ["怒", "青筋", "暴跳"],
    "STARS": ["星空", "星辰", "繁星", "星河"],
    "BOKEH": ["光斑", "朦胧", "斑驳"],
    "DANDELION": ["蒲公英", "柳絮", "飞絮"],
}
bound_all = set().union(*bindings.values()) if bindings else set()
for kind, words in WIDE.items():
    picked = sorted(
        n for n in records
        if n not in bound_all
        and not any(x in n for x in EXCLUDE_NAME_PARTS)
        and any(w in n for w in words)
    )[:12]
    if picked:
        bindings[kind].update(picked)
        bound_all.update(picked)

# 补「规则标记名」：正则替换规则插入的是 (爆炸音效) / (火焰音效) 这类短名，
# 它们不在音效库正名里，但正是最常见的触发输入，直接挂到对应效果上做精确命中。
for k, words in RULES:
    bindings[k].update(w.strip() for w in words[:4] if len(w.strip()) >= 2)


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


# 跨效果去重，按 RULES 顺序「先出现者优先」，避免同一个名字被两个效果抢
seen = set()
clean_bindings = {}
for k, _ in RULES:
    arr = []
    for n in sorted(bindings.get(k, set())):
        key = normalize(n)
        if key and key not in seen:
            seen.add(key)
            arr.append(n)
    clean_bindings[k] = arr

bindings_json = {
    "version": 1,
    "source": "ai_effect_index_kw.json + 广播剧音效库_目录索引.json + ai_effect_category_map.json + effect_regex_source.json",
    "totalNames": len(records),
    "boundNames": sum(len(v) for v in clean_bindings.values()),
    # 38 个画面效果全部建 key（库里暂时没有对应音效名的为空数组，可手工补）
    "bindings": clean_bindings,
}
assets_path = os.path.join(ASSETS, "fx_sound_bindings.json")
with open(assets_path, "w", encoding="utf-8") as f:
    json.dump(bindings_json, f, ensure_ascii=False, separators=(",", ":"))

# ---------- 可读的绑定表说明 ----------
md = [
    "# 两套音效配置 → 全量音效名 → 画面效果绑定表",
    "",
    "生成脚本：`docs/ai_effect_category/tools/gen_fx_sound_bindings.py`（仓库根目录执行可重跑）",
    "",
    "## 两套配置",
    "",
    "| 配置 | 文件 | 内容 |",
    "|---|---|---|",
    f"| AI 识别音效库 | `app/src/main/assets/ai_effect_index_kw.json` | 16 个一级目录 / 74 个二级分类 / {kw_lines} 条 `正名|关键词` |",
    f"| AI 识别音效库（纯名字） | `广播剧音效库_目录索引.json` | 15 个一级目录 / 62 个二级分类 / {plain_count} 个名字 |",
    f"| AI 分类映射（含位置） | `docs/ai_effect_category/ai_effect_category_map.json` | {cmap['total']} 个名字，带分类代码与 mp3 路径 |",
    f"| 正则替换规则 | `docs/ai_effect_category/tools/effect_regex_source.json` | {len(regex_src['effects'])} 个音效名各带 regex（源自 ttsrv-replaces3/5.json） |",
    f"| 正则规则原始分类 | `docs/ai_effect_category/tools/classify_source.json` | {classify['ruleCount']} 条规则 / {len(classify['perCategory'])} 类，来源 {block.get('source','')} |",
    "",
    f"**去重后音效名总数：{len(records)}**，全量清单见 `docs/ai_effect_category/音效名字全量清单.csv`（含一级目录、二级分类、分类代码、文件位置、关键词、正则触发式）。",
    "",
    "## 一个效果绑定多个音效名",
    "",
    f"绑定表：`app/src/main/assets/fx_sound_bindings.json`，38 个画面效果全部建项，"
    f"其中 {len([k for k, v in bindings_json['bindings'].items() if v])} 个效果绑定了真实音效名，"
    f"合计 {bindings_json['boundNames']} 个；空数组的几类（音效库里没有对应名字）靠运行时关键词兜底，"
    f"也可以直接往数组里加音效名。全量名单里其余 {len(unbound)} 个音效应画什么由关键词表决定。",
    "",
    "| 画面效果 | 绑定音效数 | 示例（前 12 个） |",
    "|---|---:|---|",
]
for kind, names in sorted(bindings_json["bindings"].items(), key=lambda x: (-len(x[1]), x[0])):
    sample = "、".join(names[:12]) if names else "（暂空，靠关键词兜底，可手工补音效名）"
    md.append(f"| `{kind}` | {len(names)} | {sample} |")
md += [
    "",
    "## 触发方式",
    "",
    "音效一触发（`loadAndPlayEffect()` 收口）→ `SoundEffectFired` 事件 → "
    "`SoundEffectSceneMapper` 先用**精确音效名**查绑定表，命中即画；"
    "没命中再用关键词表兜底。名字会先规范化（去掉「音效」后缀、`.mp3`/`.json` 扩展名、取路径末段），"
    "因此正则规则的 `(爆炸音效)` 与 AI 库的 `爆炸.mp3` 都能命中同一组。",
    "",
    "要调整某个效果对应哪些音效，直接改 `fx_sound_bindings.json` 里的数组即可，无需改 Kotlin、无需重新编译。",
]
with open(os.path.join(DOCS, "音效绑定表.md"), "w", encoding="utf-8") as f:
    f.write("\n".join(md) + "\n")

print("AI 带关键词条目:", kw_lines)
print("AI 纯名字条目:", plain_count)
print("去重音效名总数:", len(records))
print("已绑定:", bindings_json["boundNames"], "未绑定:", len(unbound))
print("有绑定的效果数:", len([k for k, v in clean_bindings.items() if v]))
for kind, names in sorted(clean_bindings.items(), key=lambda x: -len(x[1]))[:10]:
    print(" ", kind, len(names))
print("产物:", csv_path, assets_path, os.path.join(DOCS, "音效绑定表.md"))
