# -*- coding: utf-8 -*-
"""
建章节索引 + 按分类挑选最相关的章节

用法:
    python3 select_chapters.py                 # 全部 120 个分类
    python3 select_chapters.py --domain A      # 只做 A 域
    python3 select_chapters.py --per 20        # 每分类选 20 章
"""
import json, os, re, sys, glob

HERE = os.path.dirname(os.path.abspath(__file__))
TEST = os.path.dirname(HERE)
RAW = os.path.join(TEST, 'corpus', 'raw')
IDX = os.path.join(TEST, 'corpus', 'chapters.jsonl')
SEL = os.path.join(TEST, 'corpus', 'selected')
CATS = os.path.join(os.path.dirname(TEST), 'ai_effect_categories.json')


def build_index():
    if os.path.exists(IDX):
        n = sum(1 for _ in open(IDX, encoding='utf-8'))
        print(f'索引已存在 {n} 章，跳过')
        return
    seen, out = set(), []
    for p in sorted(glob.glob(os.path.join(RAW, '*.jsonl'))):
        for ln in open(p, encoding='utf-8'):
            ln = ln.strip()
            if not ln:
                continue
            try:
                o = json.loads(ln)
            except Exception:
                continue
            key = (o.get('title', ''), o.get('chapter', ''))
            if key in seen:
                continue
            seen.add(key)
            out.append({'id': len(out), 'book': key[0],
                        'chapter': key[1], 'text': o.get('text', '')})
    with open(IDX, 'w', encoding='utf-8') as f:
        for c in out:
            f.write(json.dumps(c, ensure_ascii=False) + '\n')
    print(f'索引建立完成: {len(out)} 章')


def main():
    args = sys.argv[1:]
    domain = args[args.index('--domain') + 1] if '--domain' in args else None
    per = int(args[args.index('--per') + 1]) if '--per' in args else 20

    build_index()
    chapters = [json.loads(l) for l in open(IDX, encoding='utf-8')]
    cats = json.load(open(CATS, encoding='utf-8'))['categories']
    os.makedirs(SEL, exist_ok=True)

    todo = [c for c in cats if not domain or c['domain'] == domain]
    print(f'待处理分类 {len(todo)} 个，每类选 {per} 章')

    for c in todo:
        dst = os.path.join(SEL, f"{c['id']}.json")
        if os.path.exists(dst):
            print(f"  {c['id']} 已存在，跳过")
            continue
        rx = c.get('classifyRegex') or ''
        if not rx:
            print(f"  {c['id']} 无正向正则，跳过")
            continue
        try:
            cre = re.compile(rx)
        except re.error as e:
            print(f"  {c['id']} 正则编译失败: {e}")
            continue
        scored = []
        for ch in chapters:
            n = len(cre.findall(ch['text']))
            if n:
                scored.append((n, ch['id'], ch['book'], ch['chapter']))
        scored.sort(reverse=True)
        picked = [{'id': i, 'book': b, 'chapter': ch, 'score': s}
                  for s, i, b, ch in scored[:per]]
        json.dump({'category': c['id'], 'name': c['name'], 'chapters': picked},
                  open(dst, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        tot = len(scored)
        print(f"  {c['id']} {c['name']}: 命中 {tot} 章，取 {len(picked)} 章"
              f"（最高分 {scored[0][0] if scored else 0}）")


if __name__ == '__main__':
    main()
