# 로컬 캐시(p1rs/cache-1006 · 10-06 수집 · 0콜)의 submissions에서 최근 2년 정기 폼 밖 「기간 보고」(10-KT·10-QT·NT 제외) 수를 센다.
import json, os, sys, glob, collections
root = sys.argv[1]
cnt = collections.Counter(); who = collections.defaultdict(set)
files = glob.glob(os.path.join(root, '*'))
for f in files:
    try:
        d = json.load(open(f))
    except Exception as e:
        print('ERR', f, e); continue
    r = d.get('filings', {}).get('recent', d)
    forms = r.get('form', []); dates = r.get('filingDate', []); xb = r.get('isXBRL', [])
    for i, fm in enumerate(forms):
        if dates[i] >= '2024-10-01' and fm in ('10-KT', '10-QT', '10-KT/A', '10-QT/A'):
            cnt[fm] += 1; who[fm].add((os.path.basename(f), dates[i], xb[i] if i < len(xb) else None))
print('files', len(files)); print(dict(cnt))
for k, v in who.items():
    for x in sorted(v): print(k, *x)
