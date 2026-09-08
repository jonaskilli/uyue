# -*- coding: utf-8 -*-
"""从 HF 下载 wdndev/webnovel-chinese 的部分分片（Range 截取，避免 4GB 全量）"""
import urllib.request, json, os, sys

OUT = '/tmp/corpus'
os.makedirs(OUT, exist_ok=True)
BASE = ('https://huggingface.co/datasets/wdndev/webnovel-chinese/'
        'resolve/main/data/webnovel_{i}.jsonl')

SHARDS = int(sys.argv[1]) if len(sys.argv) > 1 else 6      # 取几个分片
PER_MB = int(sys.argv[2]) if len(sys.argv) > 2 else 3      # 每片取多少 MB

for i in range(SHARDS):
    dst = f'{OUT}/part{i}.jsonl'
    if os.path.exists(dst) and os.path.getsize(dst) > 100000:
        print(f'part{i} 已存在，跳过')
        continue
    url = BASE.format(i=i)
    req = urllib.request.Request(url, headers={
        'User-Agent': 'test',
        'Range': f'bytes=0-{PER_MB*1024*1024}',
    })
    try:
        r = urllib.request.urlopen(req, timeout=180)
        raw = r.read().decode('utf-8', 'ignore')
    except Exception as e:
        print(f'part{i} FAIL {type(e).__name__} {str(e)[:70]}')
        continue
    # 最后一行可能被截断，丢弃
    if '\n' in raw:
        raw = raw[:raw.rfind('\n')]
    with open(dst, 'w', encoding='utf-8') as f:
        f.write(raw)
    print(f'part{i}: {len(raw)} 字节')

# 统计
books, chapters = {}, 0
for i in range(SHARDS):
    p = f'{OUT}/part{i}.jsonl'
    if not os.path.exists(p):
        continue
    for ln in open(p, encoding='utf-8'):
        ln = ln.strip()
        if not ln:
            continue
        try:
            o = json.loads(ln)
        except Exception:
            continue
        t = o.get('title', '?')
        books.setdefault(t, 0)
        books[t] += 1
        chapters += 1
print(f'\n共 {chapters} 章 / {len(books)} 本')
print('\n书籍样例(按章节数):')
for t, c in sorted(books.items(), key=lambda x: -x[1])[:25]:
    print(f'  {c:4d}  {t}')
