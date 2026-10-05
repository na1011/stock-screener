# 0콜: .factcache 1,218 companyfacts에서 원본 10-K/10-Q(및 20-F/40-F) accn 중
# us-gaap 팩트가 0인 것(다른 택소노미만 있는 것)을 센다. 보충 술어가 MALFORMED로 영구 실패시킬 후보.
import json, os, sys, collections
D = '/Users/na1011/dev/stock-screener/.factcache'
ORIG = {'10-K','10-Q','20-F','40-F','10-KT','10-QT'}
res = []
tax_counter = collections.Counter()
n = 0
for fn in sorted(os.listdir(D)):
    if not fn.endswith('.json'): continue
    try:
        j = json.load(open(os.path.join(D, fn)))
    except Exception as e:
        print('ERR', fn, e, file=sys.stderr); continue
    n += 1
    by_accn = collections.defaultdict(lambda: collections.Counter())
    form_of = {}
    filed_of = {}
    for tax, concepts in (j.get('facts') or {}).items():
        tax_counter[tax] += 1
        for cname, c in concepts.items():
            for unit, arr in (c.get('units') or {}).items():
                for f in arr:
                    a = f.get('accn'); 
                    by_accn[a][tax] += 1
                    form_of.setdefault(a, f.get('form')); filed_of.setdefault(a, f.get('filed'))
    for a, cnt in by_accn.items():
        if form_of.get(a) in ORIG and cnt.get('us-gaap', 0) == 0 and (filed_of.get(a) or '') >= '2017-01-01':
            res.append((fn[:-5], j.get('cik'), a, form_of[a], filed_of[a], dict(cnt)))
print('files', n)
print('taxonomies', dict(tax_counter))
print('orig accn with us-gaap 0 (filed>=2017):', len(res))
for r in res: print(*r, sep='\t')
