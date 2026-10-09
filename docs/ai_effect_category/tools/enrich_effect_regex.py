# -*- coding: utf-8 -*-
"""
词表嫁接：把大正则人工调过的主体词表，嫁接到音效自己的匹配正则上。

背景：3830 个音效按名字自动生成的正则召回很低（A01/A03/A05 命中 0），
因为小说里的表达（「挥剑」「劈砍」）在音效名里没有字面体现。
而 1053 条大正则里有人工调好的主体词表，例如「刀剑斩击」的：
    (兵器|利器|武器|匕首|枪|刀|剑|刃|锥|镰) .{0,20}? (斩|砍|劈)(过|至|往|向|出)

嫁接后，音效「刀光一闪」变成：
    (兵器|利器|武器|匕首|枪|刀|剑|刃)[^。？！]{0,10}?(一闪|闪过|刀光|寒光)

产出覆盖写回 effect_regex_source.json（source 标记为 grafted / generated）。
"""
import json, os, re, sys, urllib.request
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import category_rules as R

BASE = 'https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/'
SRC = os.path.join(HERE, 'effect_regex_source.json')

ATTR = ['不可辨', '远景', '近景', '室内', '室外', '短段', '长段', '短促', '缓慢', '急促',
        '连续', '不停', '一阵', '一声', '一下', '一次', '一步', '一遍', '几声', '两声', '三声',
        '几下', '两下', '三下', '几步', '两步', '三步', '四步', '多次', '单次', '二次',
        '三刀', '两刀', '一刀', '一小段', '短', '长']


def fetch(name):
    return json.loads(urllib.request.urlopen(BASE + name + '?download=true',
                                             timeout=120).read().decode())


def effect_name(rep):
    m = re.search(r'\$\d*\((.+?)\)', rep or '')
    return m.group(1) if m else ''


def strip_suffix(n):
    return re.sub(r'音效$', '', n).strip()


def remove_lookaround(pat):
    out, i, n = [], 0, len(pat)
    while i < n:
        if pat[i:i + 3] in ('(?!', '(?<'):
            depth, j = 0, i
            while j < n:
                c = pat[j]
                if c == '\\':
                    j += 2
                    continue
                if c == '(':
                    depth += 1
                elif c == ')':
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            i = j + 1
        else:
            out.append(pat[i])
            i += 1
    return ''.join(out)


def reuse_body(pat):
    s = remove_lookaround(pat)
    s = s.replace('(?:^|)', '')
    # 双引号/问号硬边界（原作者 bug，会导致引号后漏配）——删掉
    s = re.sub(r'\(\?\:\[\^[^\]]*\]\)\*\??', '', s)
    s = re.sub(r'\(\?\:\[\^[^\]]*\]\)\+\??', '', s)
    return s.strip().lstrip('|').lstrip('^')


# (刀|剑)[^？?]{0,15}?(脱手|掉落)  ->  ("刀|剑", 15, "脱手|掉落")
# 注意词表可能是 (A|B) 也可能是 (?:A|B)，冒号可选
SLOT_RE = re.compile(r'\(([^()]+)\)\s*\[\^\？\?\]\{0,(\d+)\}\?\s*\(([^()]+)\)')


def extract_slots(pat):
    s = reuse_body(pat)
    out = []
    for m in SLOT_RE.finditer(s):
        a = re.sub(r'^\?:', '', m.group(1))
        b = re.sub(r'^\?:', '', m.group(3))
        out.append((a, int(m.group(2)), b))
    return out


def core_of(name):
    c = name
    for a in sorted(ATTR, key=len, reverse=True):
        if c.endswith(a) and len(c) > len(a):
            return c[:-len(a)]
    return c


def main():
    rules = []
    for fn in ('ttsrv-replaces5.json', 'ttsrv-replaces3.json'):
        for g in fetch(fn):
            for it in g.get('list', []):
                if not it.get('isRegex'):
                    continue
                nm = strip_suffix(effect_name(it.get('replacement', '')))
                if nm:
                    rules.append((nm, it['pattern']))
    print('规则', len(rules))

    # 每条规则的 slot（主体词表 -> 动作词表）
    rule_slots = []
    for nm, pat in rules:
        rule_slots.append(extract_slots(pat))
    n_slot = sum(1 for s in rule_slots if s)
    print(f'提取到 slot 的规则: {n_slot}')

    # 倒排分开建：前段词表(A，主体) 与 后段词表(B，动作)
    # 必须分开——早先合并成一个倒排时，「剑划破空/掌风破空/棍棒破空」
    # 都因为共享「破空」而借到弓弩规则的主体词表，语义全错。
    invA, invB = {}, {}
    for i, slots in enumerate(rule_slots):
        for a, _, b in slots:
            for w in a.split('|'):
                if len(w) >= 2:
                    invA.setdefault(w, []).append(i)
            for w in b.split('|'):
                if len(w) >= 2:
                    invB.setdefault(w, []).append(i)

    src = json.load(open(SRC, encoding='utf-8'))
    fx = src['effects']

    # reused（名称高相似）/ reused_broad（词表匹配+同域，由 reuse_broad.py 产出）
    # 优先级高于嫁接，这里跳过不覆盖。
    # 想回退整条复用时，把它们的 source 改回 generated 再跑本脚本即可。

    # 规则/音效 的分类（同域约束）
    def map_rule(nm):
        for cand in [nm, re.sub(r'^(人|众|一下|一次|一群|连续|不停|不断)', '', nm)]:
            hits = []
            for key, (kws, exc) in R.CATS.items():
                if any(k in cand for k in kws) and not any(e in cand for e in exc):
                    hits.append(key.split('_', 1)[0])
            if hits:
                return set(hits)
        return set()

    rule_cats = [map_rule(nm) for nm, _ in rules]
    emap = json.load(open(os.path.join(HERE, '..', 'ai_effect_category_map.json'),
                          encoding='utf-8'))['effects']
    fx_cat = {e['name']: set(e.get('categories', [])) for e in emap}

    n_graft = 0

    for n in R.names:
        if fx.get(n, {}).get('source') in ('reused', 'reused_broad'):
            continue                       # 整条复用的优先级更高，不动
        c = core_of(n)
        if len(c) < 2:
            continue
        a_n, b_n = c[:2], c[-2:]

        # 主体优先：首2字 / 全串 去查前段词表(A)，权重高
        # 动作次之：尾2字 去查后段词表(B)，权重低
        cand = Counter()
        for i in invA.get(a_n, []):
            cand[i] += 3
        for i in invA.get(c, []):
            cand[i] += 3
        for i in invB.get(b_n, []):
            cand[i] += 1
        if not cand:
            continue                      # 主体对不上就不嫁接，宁可只用音效名

        # 同域约束 + 规则必须已归类
        my_dom = {x[0] for x in fx_cat.get(n, set()) if x}
        ri = score = None
        for idx, sc in cand.most_common():
            if sc < 3:
                break
            rc = rule_cats[idx]
            if my_dom and (not rc or not ({x[0] for x in rc} & my_dom)):
                continue                  # 跨域 或 规则未归类
            ri, score = idx, sc
            break
        if ri is None:
            continue
        slots = rule_slots[ri]
        if not slots:
            continue
        # 取间隔最大的一段作为主体结构
        a_grp, gap, b_grp = max(slots, key=lambda s: s[1])

        a_set = [w for w in a_grp.split('|') if len(w) >= 1]
        b_set = [w for w in b_grp.split('|') if len(w) >= 1]
        # 动作侧：自家词 + 借来的词（限制数量，避免过长）
        own = [c] if len(c) <= 3 else [c, b_n]
        borrowed = [w for w in b_set if len(w) >= 2][:6]
        tail = '|'.join(dict.fromkeys(own + borrowed))
        head = '|'.join(dict.fromkeys(a_set[:14]))
        g = min(gap, 12)
        gap_pat = r'[^。？！？]{0,%d}?' % g
        rx = f'(?:{head}){gap_pat}(?:{tail})'
        try:
            re.compile(rx)
        except re.error:
            continue
        fx[n] = {'regex': rx, 'source': 'grafted',
                 'core': c, 'refRule': rules[ri][0], 'shared': score}
        n_graft += 1

    src['graftedCount'] = n_graft
    src['generatedCount'] = sum(1 for v in fx.values() if v.get('source') == 'generated')
    src['reuseCount'] = sum(1 for v in fx.values() if v.get('source') == 'reused')
    json.dump(src, open(SRC, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print(f'\n嫁接 {n_graft} 个 / 复用 {src["reuseCount"]} / 其余 {src["generatedCount"]}')

    for n in ['刀剑挥空', '内力运转', '岩石崩碎', '马嘶', '切菜']:
        if n in fx:
            v = fx[n]
            print(f'  {n:8s} [{v["source"]}] {v["regex"][:110]}')


if __name__ == '__main__':
    main()
