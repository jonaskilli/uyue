# -*- coding: utf-8 -*-
"""
按段落执行三层漏斗匹配（每个段落独立判定，不拼接上下文）

流程（对单个段落）：
    ① commonBlock.all − 本分类 commonExempt   命中 -> 淘汰
    ② classifyRegex（分类正向）                未命中 -> 淘汰
    ③ blockRegex（分类禁止）                   命中 -> 淘汰
    ④ 逐音效 regex 匹配                        记录命中

用法:
    python3 run_match.py                 # 全部已选章节的分类
    python3 run_match.py --domain A      # 只跑 A 域
    python3 run_match.py --force         # 忽略已有结果重跑
"""
import json, os, re, sys, time, datetime

HERE = os.path.dirname(os.path.abspath(__file__))
TEST = os.path.dirname(HERE)
ROOT = os.path.dirname(TEST)
SEL = os.path.join(TEST, 'corpus', 'selected')
IDX = os.path.join(TEST, 'corpus', 'chapters.jsonl')
RES = os.path.join(TEST, 'results')
CATS = os.path.join(ROOT, 'ai_effect_categories.json')
MAP = os.path.join(ROOT, 'ai_effect_category_map.json')
PROGRESS = os.path.join(TEST, 'PROGRESS.md')

MIN_PARA = 6      # 过短段落不计


def log(msg):
    print(f'[{datetime.datetime.now():%H:%M:%S}] {msg}', flush=True)


MIN_SENT = 6


def split_paragraphs(text):
    ps = []
    for p in re.split(r'\n+', text):
        p = p.strip()
        if len(p) >= MIN_PARA:
            ps.append(p)
    return ps


def split_sentences(para):
    """
    段落 -> 句子。

    必须切句：通用禁用集（commonBlock）源自大正则的「逐字扫描禁止」，
    原语义是「句首到命中点之间不出现这些词」，不是「段落中出现即禁止」。
    直接按段落判定会误杀 60% 以上（小说里几乎每段都有「如果/已经/不能」）。
    """
    out = []
    for s in re.split(r'(?<=[。！？!?；;])', para):
        s = s.strip()
        if len(s) >= MIN_SENT:
            out.append(s)
    return out


def main():
    args = sys.argv[1:]
    domain = args[args.index('--domain') + 1] if '--domain' in args else None
    force = '--force' in args

    cats = {c['id']: c for c in json.load(open(CATS, encoding='utf-8'))['categories']}
    cb = json.load(open(CATS, encoding='utf-8'))['commonBlock']
    # all 只存了 regex，词表从四类合并
    common = sorted({w for k in ('global', 'tail', 'local', 'scan')
                     for w in cb[k]['words']})
    emap = json.load(open(MAP, encoding='utf-8'))['effects']

    # 章节文本
    chapters = {}
    for l in open(IDX, encoding='utf-8'):
        o = json.loads(l)
        chapters[o['id']] = o

    # 分类 -> 音效
    fx_by_cat = {}
    for e in emap:
        if not e.get('regex'):
            continue
        try:
            cre = re.compile(e['regex'])
        except re.error:
            continue
        for cid in e.get('categories', []):
            fx_by_cat.setdefault(cid, []).append((e['name'], cre))

    os.makedirs(RES, exist_ok=True)
    files = sorted(f for f in os.listdir(SEL) if f.endswith('.json'))
    todo = [f for f in files if not domain or cats.get(f[:-5], {}).get('domain') == domain]
    log(f'待跑分类 {len(todo)} 个')

    for fn in todo:
        cid = fn[:-5]
        dst = os.path.join(RES, f'{cid}.jsonl')
        if os.path.exists(dst) and not force:
            log(f'{cid} 已有结果，跳过（--force 可重跑）')
            continue
        sel = json.load(open(os.path.join(SEL, fn), encoding='utf-8'))
        c = cats[cid]

        exempt = set(c.get('commonExempt', []))
        common_words = [w for w in common if w not in exempt]
        common_re = re.compile('(?:' + '|'.join(sorted(common_words, key=lambda w: (-len(w), w))) + ')')
        cls_re = re.compile(c['classifyRegex']) if c.get('classifyRegex') else None
        blk_re = re.compile(c['blockRegex']) if c.get('blockRegex') else None
        effects = fx_by_cat.get(cid, [])

        n_para = n_common = n_cls = n_blk = n_match = 0
        t0 = time.time()
        with open(dst, 'w', encoding='utf-8') as out:
            for ch in sel['chapters']:
                src = chapters.get(ch['id'])
                if not src:
                    continue
                for pi, para in enumerate(split_paragraphs(src['text'])):
                    for si, p in enumerate(split_sentences(para)):
                        n_para += 1
                        base = {'book': src['book'], 'chapter': src['chapter'],
                                'paraIdx': pi, 'sentIdx': si, 'text': p}
                        if cls_re and not cls_re.search(p):
                            n_cls += 1
                            out.write(json.dumps(dict(base, stage='blocked_classify'),
                                                 ensure_ascii=False) + '\n')
                            continue
                        if blk_re:
                            m = blk_re.search(p)
                            if m:
                                n_blk += 1
                                out.write(json.dumps(dict(base, stage='blocked_block',
                                                          hit=m.group()), ensure_ascii=False) + '\n')
                                continue
                        # 音效匹配。
                        # 通用禁用只作用于「匹配点之前」——这是大正则的原始语义
                        # （逐字扫描禁止：句首到命中点之间不出现这些词）。
                        # 全句判定会把「他挥剑，如果…」这类句子误杀。
                        hits, blocked_hit = [], None
                        for nm, rx in effects:
                            m = rx.search(p)
                            if not m:
                                continue
                            mm = common_re.search(p[:m.start()])
                            if mm:
                                if blocked_hit is None:
                                    blocked_hit = mm.group()
                                continue
                            hits.append(nm)
                        if hits:
                            n_match += 1
                            stage = 'matched'
                        elif blocked_hit:
                            n_common += 1
                            stage = 'blocked_common'
                        else:
                            stage = 'no_effect'
                        out.write(json.dumps(dict(base, stage=stage,
                                                  effects=hits, hit=blocked_hit),
                                             ensure_ascii=False) + '\n')
        summary = {'category': cid, 'name': c['name'], 'domain': c['domain'],
                   'effectCount': len(effects), 'paragraphs': n_para,
                   'blocked_common': n_common, 'blocked_classify': n_cls,
                   'blocked_block': n_blk, 'matched': n_match,
                   'no_effect': n_para - n_common - n_cls - n_blk - n_match,
                   'seconds': round(time.time() - t0, 1),
                   'finishedAt': datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
        json.dump(summary, open(os.path.join(RES, f'{cid}.summary.json'), 'w', encoding='utf-8'),
                  ensure_ascii=False, indent=1)
        log(f"{cid} {c['name']}: 段落{n_para} 通用禁{n_common} 未过正向{n_cls} "
            f"分类禁{n_blk} 命中{n_match} 无音效{summary['no_effect']} ({summary['seconds']}s)")

    write_progress()


def write_progress():
    """刷新 PROGRESS.md：让中断后能接着跑"""
    cats = {c['id']: c for c in json.load(open(CATS, encoding='utf-8'))['categories']}
    rows = []
    for f in sorted(os.listdir(RES)):
        if not f.endswith('.summary.json'):
            continue
        s = json.load(open(os.path.join(RES, f), encoding='utf-8'))
        rows.append(s)
    lines = ['# 测试进度（断点续跑）', '',
             '本文件由 `tools/run_match.py` 自动刷新，请勿手工编辑。',
             '中断后直接重跑同一条命令即可：已完成的分类会自动跳过（`--force` 强制重跑）。', '',
             f'最后更新：{datetime.datetime.now():%Y-%m-%d %H:%M:%S}',
             f'已完成分类：**{len(rows)} / 120**', '',
             '| 分类 | 名称 | 音效数 | 段落 | 通用禁 | 未过正向 | 分类禁 | 命中 | 无音效 | 耗时 |',
             '|---|---|---|---|---|---|---|---|---|---|']
    for s in rows:
        lines.append(f"| {s['category']} | {s['name']} | {s['effectCount']} | {s['paragraphs']} | "
                     f"{s['blocked_common']} | {s['blocked_classify']} | {s['blocked_block']} | "
                     f"**{s['matched']}** | {s['no_effect']} | {s['seconds']}s |")
    pending = [cid for cid in sorted(cats) if not os.path.exists(os.path.join(RES, f'{cid}.summary.json'))]
    lines += ['', f'## 未完成（{len(pending)}）', '',
              '、'.join(f'{c}' for c in pending) if pending else '（全部完成）']
    open(PROGRESS, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
