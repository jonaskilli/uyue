# -*- coding: utf-8 -*-
"""
导出音效分类体系 -> 上级目录的两个 JSON

    python3 export.py

产出：
    ../ai_effect_categories.json    分类定义（120 个，含 classifyRegex / blockRegex 待填位）
    ../ai_effect_category_map.json  3830 个音效 -> 分类ID 列表（含全部题材版本文件路径）

手工修改音效归属请改同目录 manual_overrides.json，不要直接改产出的 JSON，
否则下次运行会被覆盖。
"""
import json, os, sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import category_rules as R  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.abspath(os.path.join(HERE, '..'))
OVERRIDES = os.path.join(HERE, 'manual_overrides.json')


def split_id(key):
    """'A01_刀剑挥击破空' -> ('A01', '刀剑挥击破空')"""
    return key.split('_', 1) if '_' in key else (key, key)


def to_regex(words):
    """词表 -> 交替正则。长词在前，避免短词截断长词"""
    ws = sorted({w for w in words if w}, key=lambda w: (-len(w), w))
    return '(?:' + '|'.join(ws) + ')' if ws else ''


def load_block():
    """从 ttsrv 大正则提炼出的禁止词（block_source.json）"""
    p = os.path.join(HERE, 'block_source.json')
    if not os.path.exists(p):
        return {}, {}
    with open(p, encoding='utf-8') as f:
        d = json.load(f)
    return d.get('common', {}), d.get('perCategory', {})


def load_effect_regex():
    """每个音效的匹配正则（effect_regex_source.json）"""
    p = os.path.join(HERE, 'effect_regex_source.json')
    if not os.path.exists(p):
        return {}
    with open(p, encoding='utf-8') as f:
        return json.load(f).get('effects', {})


def load_classify():
    """从 ttsrv 大正则主体部分提炼出的正向词（classify_source.json）"""
    p = os.path.join(HERE, 'classify_source.json')
    if not os.path.exists(p):
        return {}
    with open(p, encoding='utf-8') as f:
        return json.load(f).get('perCategory', {})


def load_manual():
    """手工补的正向词 / 禁止词（manual_block.json），供大正则覆盖不到的分类使用"""
    p = os.path.join(HERE, 'manual_block.json')
    if not os.path.exists(p):
        return {}
    with open(p, encoding='utf-8') as f:
        d = json.load(f)
    return {k: v for k, v in d.items() if not k.startswith('_')}


def suggest_by_gram(names, final):
    """
    自学习归类兜底：用已归类的音效训练「2-gram -> 分类」关联，预测未归类的。

    关键词表覆盖不到的音效（如「万剑归宗」「丹成出炉」），
    其 2-gram（万剑/剑归/归宗）在已归类音效里往往有明确的分类倾向。
    用 IDF 压低「轻响/一下/落地」这类泛词的影响。
    """
    from collections import Counter, defaultdict
    gram_cat, df = defaultdict(Counter), Counter()

    def grams(n):
        return {n[i:i + 2] for i in range(len(n) - 1)} if len(n) >= 2 else {n}

    for n in names:
        if not final.get(n):
            continue
        for g in grams(n):
            df[g] += 1
            for c in final[n]:
                gram_cat[g][c] += 1

    out = {}
    for n in names:
        if final.get(n):
            continue
        votes = Counter()
        for g in grams(n):
            idf = 1.0 / (1 + df.get(g, 0))
            for c, w in gram_cat[g].most_common(6):
                votes[c] += w * idf
        if votes:
            top = votes.most_common(1)[0]
            if top[1] >= 0.5:                 # 票数过低就不猜
                out[n] = [top[0]]
    return out


def compute_exempt(pwords, gwords):
    """
    正向词自动豁免。

    只认一个方向：**禁用词 g 是正向词 p 的子串**（g ⊆ p）才豁免。
    例：E08 正向有「回忆」→ 禁用「回忆」失效；
        E08 正向有「记忆深处」→ 禁用「记忆」失效。

    反向（p ⊆ g）**不豁免**。否则单字正向词会吃掉长禁用词：
    A01 正向有单字「用」，若允许 p ⊆ g，则「用来/用作/用于/作用为/作用是」
    会全部被豁免，该分类的禁用保护直接失效。
    这类语义豁免改由 manual_block.json 的 exempt 字段显式声明。
    """
    ex = set()
    for g in gwords:
        for p in pwords:
            if g in p:
                ex.add(g)
                break
    return sorted(ex)


def load_overrides():
    if not os.path.exists(OVERRIDES):
        return {}
    with open(OVERRIDES, encoding='utf-8') as f:
        data = json.load(f)
    return data.get('effects', data)


def main():
    CATS, names, uniq = R.CATS, R.names, R.uniq
    ov = load_overrides()
    common_block, per_cat_block = load_block()

    # ---- 自动打标 ----
    hit = defaultdict(list)          # 分类key -> [音效名]
    for n in names:
        for c, (kws, exc) in CATS.items():
            if any(k in n for k in kws) and not any(e in n for e in exc):
                hit[c].append(n)

    auto = {n: sorted(split_id(c)[0] for c in CATS if n in set(hit[c]))
            for n in names}

    # ---- 应用手工覆盖 ----
    final = {}
    for n in names:
        cs = list(auto[n])
        rule = ov.get(n)
        if rule:
            if 'set' in rule:                       # 完全覆盖
                cs = list(rule['set'])
            else:
                cs = [c for c in cs if c not in rule.get('remove', [])]
                cs += [c for c in rule.get('add', []) if c not in cs]
        final[n] = sorted(set(cs))

    # 自学习兜底：给关键词归类不到的音效补部分类
    suggested = suggest_by_gram(names, final)
    for n, cs in suggested.items():
        final[n] = cs

    uncovered = sorted(n for n in names if not final[n])

    # ---- 1. 分类定义 ----
    cats = []
    gwords = {w for v in common_block.values() for w in v}   # 通用禁用全量
    manual = load_manual()
    auto_cls = load_classify()                               # 大正则主体提炼的正向词

    filled = filled_cls = 0
    for key in sorted(CATS):
        cid, cname = split_id(key)
        kws, exc = CATS[key]
        bw = per_cat_block.get(cid, {})          # {global/tail/local/scan: [词]} 自动提炼
        auto_w = [w for v in bw.values() for w in v]
        man = manual.get(cid, {})
        # 正向词：手工优先，其次用大正则主体提炼的
        man_c = man.get('classify', []) or auto_cls.get(cid, [])
        man_b = man.get('block', [])             # 手工禁止词

        block_w = sorted(set(auto_w) | set(man_b))
        if block_w:
            filled += 1
        if man_c:
            filled_cls += 1

        cats.append({
            "id": cid,
            "name": cname,
            "domain": cid[0],
            "domainName": R.DOMAINS.get(cid[0], ""),
            "effectCount": sum(1 for n in names if cid in final[n]),
            "classifyRegex": to_regex(man_c),    # 正向：段落属于本类
            "classifyWords": man_c,
            "blockRegex": to_regex(block_w),     # 本分类特有禁止（通用集见 commonBlock）
            "blockWordCount": len(block_w),
            "blockWords": {k: v for k, v in bw.items() if v},   # 自动提炼，按来源保留
            "blockWordsManual": man_b,                          # 手工补的
            # 正向词自动豁免 + 手工补的豁免（手工优先，覆盖自动算不出的语义豁免）
            "commonExempt": sorted(set(compute_exempt(man_c, gwords))
                                   | set(man.get('exempt', []))),
            "keywords": kws,                     # 仅打标用，运行时不使用
            "exclude": exc,                      # 仅打标用，运行时不使用
        })

    common_out = {
        k: {"regex": to_regex(v), "count": len(v), "words": v}
        for k, v in common_block.items()
    }
    common_out["all"] = {
        "regex": to_regex([w for v in common_block.values() for w in v]),
        "count": len({w for v in common_block.values() for w in v}),
    }

    meta = {
        "version": 1,
        "created": "2026-09-08",
        "sourceIndex": "广播剧音效库_目录索引.json",
        "rawEffectCount": sum(len(v) for v in uniq.values()),
        "uniqueEffectCount": len(names),
        "domainCount": len(R.DOMAINS),
        "categoryCount": len(cats),
        "coveredCount": len(names) - len(uncovered),
        "uncoveredCount": len(uncovered),
        "coverage": f"{(len(names) - len(uncovered)) * 100 // max(len(names), 1)}%",
        "manualOverrideCount": len([k for k in ov if k in set(names)]),
        "autoSuggestedCount": len(suggested),
        "blockFilledCount": filled,
        "classifyFilledCount": filled_cls,
        "exemptMode": "auto：正向词与通用禁用词有子串重叠即自动豁免，见各分类 commonExempt",
        "note": "keywords/exclude 仅用于生成初版映射；运行时用 classifyRegex / blockRegex / commonExempt。",
    }
    with open(os.path.join(OUT, 'ai_effect_categories.json'), 'w', encoding='utf-8') as f:
        json.dump({"meta": meta, "domains": R.DOMAINS,
                   "commonBlock": common_out, "categories": cats},
                  f, ensure_ascii=False, indent=2)

    # ---- 2. 音效映射 ----
    erx = load_effect_regex()
    effects = []
    n_rx = 0
    for n in sorted(names):
        e = {
            "name": n,
            "categories": final[n],
            "files": [f"{c}/{s}/{orig}.mp3" for c, s, orig in uniq[n]],
        }
        if n in suggested:
            e["autoSuggested"] = True
        r = erx.get(n)
        if r and r.get("regex"):
            e["regex"] = r["regex"]
            e["regexSource"] = r.get("source", "")
            if r.get("refRule"):
                e["refRule"] = r["refRule"]
            n_rx += 1
        effects.append(e)
    with open(os.path.join(OUT, 'ai_effect_category_map.json'), 'w', encoding='utf-8') as f:
        json.dump({"version": 1, "total": len(effects), "regexCount": n_rx,
                   "uncovered": uncovered, "effects": effects},
                  f, ensure_ascii=False, indent=2)

    print(f"分类 {len(cats)} 个 / 音效 {len(effects)} 个")
    print(f"覆盖 {meta['coverage']}  未覆盖 {len(uncovered)}  手工覆盖 {meta['manualOverrideCount']}")
    print(f"已填禁止正则 {filled}/{len(cats)} 个分类（另有通用集 {common_out.get('all', {}).get('count', 0)} 词）")
    print(f"已填正向正则 {filled_cls}/{len(cats)} 个分类")
    if n_rx:
        print(f"音效匹配正则 {n_rx}/{len(effects)} 个")
    for fn in ('ai_effect_categories.json', 'ai_effect_category_map.json'):
        p = os.path.join(OUT, fn)
        print(f"  {fn}  {os.path.getsize(p)} bytes")


if __name__ == '__main__':
    main()
