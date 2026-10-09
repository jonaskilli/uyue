# -*- coding: utf-8 -*-
"""
扩大多正则复用面（路线 B）

早先只按【名称相似度】复用（Jaccard ≥ 0.5），只覆盖 61 个音效。
这里改成按【词表内容】匹配：把音效名拆成 2-gram + 单字，去撞大正则的词表，
命中就整条复用该大正则的主体。

多个音效共享同一条场景正则是合理的——一段「挥刀斩去」同时命中
「刀光一闪」「刀剑挥空」符合设计（每段配 1~3 个音效）。

产出写回 effect_regex_source.json，source = reused_broad
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

# 虚词/量词，单字查倒排时忽略（否则「一」会命中几乎所有规则）
STOP = set('一了的在和与或个着也就都而及其之于被把将让使给对从向到有无不上下里外中前后时'
           '这那你我他她它们自己之乎者也矣焉哉大小多少好新老大')


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
    # 双引号/问号硬边界（原作者 bug）——删掉
    s = re.sub(r'\(\?\:\[\^[^\]]*\]\)\*\??', '', s)
    s = re.sub(r'\(\?\:\[\^[^\]]*\]\)\+\??', '', s)
    return s.strip().lstrip('|').lstrip('^')


WORD_RE = re.compile(r'\((?:\?:)?([^()]+)\)')


def rule_words(body):
    """主体里所有词表的词"""
    ws = set()
    for m in WORD_RE.finditer(body):
        for w in m.group(1).split('|'):
            w = w.strip()
            if w and not re.search(r'[\\\[\]()*+?$^]', w):
                ws.add(w)
    return ws


def core_of(name):
    c = name
    for a in sorted(ATTR, key=len, reverse=True):
        if c.endswith(a) and len(c) > len(a):
            return c[:-len(a)]
    return c


def grams(s, n=2):
    if len(s) < n:
        return {s}
    return {s[i:i + n] for i in range(len(s) - n + 1)}


MIN_NAME_SIM = 0.2   # 规则名与音效名的最低相似度，低于此改用嫁接


def gen_from_name(n):
    """按音效名生成基础正则：全串 | 首2字…尾2字（共现约束）"""
    c = core_of(n)
    if len(c) <= 3:
        return '(?:' + c + ')'
    a, b = c[:2], c[-2:]
    gap = r'[^。？！？]{0,10}?'
    return f'(?:{c}|{a}{gap}{b}|{b}{gap}{a})'


def main():
    rules = []
    for fn in ('ttsrv-replaces5.json', 'ttsrv-replaces3.json'):
        for g in fetch(fn):
            for it in g.get('list', []):
                if not it.get('isRegex'):
                    continue
                nm = strip_suffix(effect_name(it.get('replacement', '')))
                if not nm:
                    continue
                body = reuse_body(it['pattern'])
                if not body or len(body) < 8:
                    continue
                try:
                    re.compile(body)
                except re.error:
                    continue
                rules.append((nm, body, rule_words(body)))
    print('可用大正则', len(rules))

    # 倒排：词 -> [ruleIdx]
    inv = {}
    for i, (_, _, ws) in enumerate(rules):
        for w in ws:
            inv.setdefault(w, []).append(i)

    # 规则名 -> 分类（用于同域约束）
    def map_rule(nm):
        n = strip_suffix(nm)
        cands = [n]
        m = re.match(r'^(人|众|一下|一次|一群|连续|不停|不断)(.+)$', n)
        if m and len(m.group(2)) >= 2:
            cands.append(m.group(2))
        for cand in cands:
            hits = []
            for key, (kws, exc) in R.CATS.items():
                if any(k in cand for k in kws) and not any(e in cand for e in exc):
                    hits.append(key.split('_', 1)[0])
            if hits:
                return set(hits)
        return set()

    rule_cats = [map_rule(nm) for nm, _, _ in rules]

    # 音效 -> 分类
    emap = json.load(open(os.path.join(HERE, '..', 'ai_effect_category_map.json'),
                          encoding='utf-8'))['effects']
    fx_cat = {e['name']: set(e.get('categories', [])) for e in emap}

    src = json.load(open(SRC, encoding='utf-8'))
    fx = src['effects']

    # 先清掉上一轮的 reused_broad 标记，否则约束收紧后旧结果会残留
    for _n, _v in list(fx.items()):
        if _v.get('source') == 'reused_broad':
            # 必须重建为「全串+共现」的基础正则。
            # 早先只写回 core 单串，把共现约束丢了，召回反而下降。
            fx[_n] = {'regex': gen_from_name(_n), 'source': 'generated',
                      'core': core_of(_n)}

    n_new = n_skip = 0

    # 统一重建 generated 的基础正则，保证「全串+共现」结构完整
    # （历史上被降级成 core 单串的，这里一并恢复）
    for _n, _v in list(fx.items()):
        if _v.get('source') == 'generated':
            fx[_n] = {'regex': gen_from_name(_n), 'source': 'generated',
                      'core': core_of(_n)}

    n_lowsim = 0
    for n in R.names:
        cur = fx.get(n, {})
        # reused（名称高相似）与 grafted（嫁接）优先级更高，不覆盖
        if cur.get('source') in ('reused', 'grafted'):
            continue
        c = core_of(n)
        if len(c) < 2:
            continue

        # 2-gram 权重高，单字权重低且跳过虚词
        cand = Counter()
        for i in range(len(c) - 1):
            for ri in inv.get(c[i:i + 2], []):
                cand[ri] += 5
        for ch in set(c):
            if ch in STOP:
                continue
            for ri in inv.get(ch, []):
                cand[ri] += 1
        if not cand:
            continue
        # 同域约束：只能复用与本音效同域的大正则。
        # 否则「重刀劈砍」(A域) 会借到「劈柴声」(N域)——两者词表都有「劈砍」，
        # 结果劈柴时触发战斗音效。
        my_dom = {x[0] for x in fx_cat.get(n, set()) if x}
        ri, score = None, 0
        for idx, sc in cand.most_common():
            if sc < 5:
                break
            rc = rule_cats[idx]
            # rc 为空（该规则没归到任何分类）也必须跳过——
            # 否则「劈柴声」这类未归类规则会被任意音效复用
            if my_dom and (not rc or not ({x[0] for x in rc} & my_dom)):
                n_skip += 1
                continue                  # 跨域 或 规则未归类，跳过
            # 名称相似度把关：差异太大说明只是词表偶然撞上，
            # 整条复用会语义漂移（「俯下身子」命中「从床上坐起」），交给嫁接处理
            gn, gr = grams(c), grams(rules[idx][0])
            sim = len(gn & gr) / len(gn | gr) if (gn | gr) else 0
            if sim < MIN_NAME_SIM:
                n_lowsim += 1
                continue
            ri, score = idx, sc
            break
        if ri is None:
            continue

        body = rules[ri][1]
        fx[n] = {'regex': body, 'source': 'reused_broad',
                 'core': c, 'refRule': rules[ri][0], 'score': score}
        n_new += 1

    cnt = Counter(v.get('source') for v in fx.values())
    src['broadReuseCount'] = n_new
    json.dump(src, open(SRC, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print(f'\n按词表内容复用 {n_new} 个')
    print('来源分布:', dict(cnt))

    for n in ['刀光一闪', '剑气纵横', '重刀劈砍', '马嘶', '丹成出炉']:
        if n in fx:
            v = fx[n]
            print(f"  {n:8s} [{v['source']}] <- {v.get('refRule','')}  {v['regex'][:80]}")


if __name__ == '__main__':
    main()
