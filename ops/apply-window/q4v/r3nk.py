"""운영(플래그 참) 대 대조군(플래그 거짓) 지표 대조 — 자연키로 삽입 판을 찾고 구간 규칙을 건다. 위반이 있으면 exit 1.
사용: r3nk.py <prod-dir> <ctrl-dir> <us-trade-dates.txt> <diff-groups.tsv> [기대 삽입 수]
"""
import sys, collections, datetime, bisect
P, C, TD, DG = sys.argv[1:5]; expected_ins = int(sys.argv[5]) if len(sys.argv) > 5 else None
DER = ('_PCTL', '_SECTOR_PCTL', '_SECTOR_REL', '_SECTOR_MEDIAN', '_SECTOR_N')
B = {'PER_MEDIAN_5Y', 'PBR_MEDIAN_5Y', 'PSR_MEDIAN_5Y', 'PER_DISCOUNT_5Y', 'PBR_DISCOUNT_5Y', 'PSR_DISCOUNT_5Y'}
d = datetime.date.fromisoformat
days = [l.strip() for l in open(TD) if l.strip()]
def plus_bars(date, n):
    i = bisect.bisect_left(days, date.isoformat()); return d(days[i + n]) if i + n < len(days) else None
fails = []
fs = lambda p: [l.rstrip('\n').split('\t') for l in open(p)]
prod, ctrl = fs(f'{P}/fs.tsv'), fs(f'{C}/fs.tsv')
nk = lambda r: (r[1], r[4], r[5], r[6])
cmap = {nk(r): r for r in ctrl}; pkeys = {nk(r) for r in prod}
only_c = set(cmap) - pkeys
print('재무 행: 운영', len(prod), '대조군', len(ctrl), '운영에만', len(pkeys - set(cmap)), '대조군에만', len(only_c))
if only_c: fails.append(f'대조군에만 있는 판 {len(only_c)}')
valdiff = [r[2] for r in prod if nk(r) in cmap and r[18] != cmap[nk(r)][18]]
print('같은 판인데 값(전 컬럼 해시)이 다른 행', len(valdiff), collections.Counter(valdiff).most_common(8))
if valdiff: fails.append(f'같은 판 값 차이 {len(valdiff)}')
labels = collections.defaultdict(set)
for r in prod: labels[(r[1], r[4], r[5])].add(r[6])
intervals = collections.defaultdict(list)
for r in prod:
    if nk(r) in cmap: continue
    later = sorted(x for x in labels[(r[1], r[4], r[5])] if x > r[6])
    intervals[r[1]].append((d(r[6]), d(later[0]) if later else None))
n_ins = sum(len(v) for v in intervals.values())
print('삽입 판', n_ins, '종목', len(intervals), '끝없는 구간', sum(1 for v in intervals.values() for _, b in v if b is None))
if expected_ins is not None and n_ins != expected_ins: fails.append(f'삽입 판 {n_ins} ≠ refill note 이른판 합 {expected_ins}')
kr = {l.split('\t')[0] for l in open(f'{P}/stock-market.tsv') if l.rstrip('\n').split('\t')[1] in ('KOSPI', 'KOSDAQ')}
gp = {tuple(l.split('\t')[:2]): int(l.split('\t')[2]) for l in open(f'{P}/groups.tsv')}
gc = {tuple(l.split('\t')[:2]): int(l.split('\t')[2]) for l in open(f'{C}/groups.tsv')}
def rows(path):
    for line in open(path):
        sid, code, td, v = line.rstrip('\n').split('\t'); yield (int(sid), code, td), v
cnt_p, cnt_c = collections.Counter(), collections.Counter()
out = collections.Counter(); ex = collections.defaultdict(list)
def judge(sid, code, td):
    sid = str(sid)
    if any(code.endswith(s) for s in DER): return '국내 파생 차이' if sid in kr else '파생(보고만)'
    if sid in kr: return '국내 원본 차이'
    if sid not in intervals: return '삽입 없는 종목의 원본 차이'
    t = d(td)
    if code in B:
        return '(b) 안' if any(t >= x and (y is None or plus_bars(y, 1260) is None or t <= plus_bars(y, 1260)) for x, y in intervals[sid]) else '(b) 밖'
    return '(a) 안' if any(t >= x and (y is None or t < y) for x, y in intervals[sid]) else '(a) 밖'
it0, it1 = rows(f'{C}/iv.tsv'), rows(f'{P}/iv.tsv')
a, b = next(it0, None), next(it1, None)
while a or b:
    if b is None or (a and a[0] < b[0]):
        cnt_c[(str(a[0][0]), a[0][1])] += 1; k = judge(*a[0]); out[k] += 1; ex[k].append(('대조군만',) + a[0]); a = next(it0, None)
    elif a is None or b[0] < a[0]:
        cnt_p[(str(b[0][0]), b[0][1])] += 1; k = judge(*b[0]); out[k] += 1; ex[k].append(('운영만',) + b[0]); b = next(it1, None)
    else:
        cnt_c[(str(a[0][0]), a[0][1])] += 1; cnt_p[(str(b[0][0]), b[0][1])] += 1
        if a[1] != b[1]:
            k = judge(*a[0]); out[k] += 1; ex[k].append(('값',) + a[0])
        a, b = next(it0, None), next(it1, None)
dg = [tuple(l.rstrip('\n').split('\t')) for l in open(DG)]
bad_extract = [g for g in dg if cnt_p[g] != gp.get(g, 0) or cnt_c[g] != gc.get(g, 0)]
print('차이 묶음', len(dg), '추출 행 수가 묶음 수와 다른 묶음', len(bad_extract))
if not dg: print('(차이 묶음 0)')
if bad_extract: fails.append(f'추출 불일치 묶음 {len(bad_extract)}')
print('행 차이 판정', dict(out))
for k in [k for k in out if k.endswith('밖') or k.startswith('국내') or k.startswith('삽입 없는')]:
    fails.append(f'{k} {out[k]}'); print('  위반 예', k, ex[k][:8])
print('판정:', 'OK 규칙 밖 0' if not fails else '위반 — ' + ' · '.join(fails))
sys.exit(1 if fails else 0)
