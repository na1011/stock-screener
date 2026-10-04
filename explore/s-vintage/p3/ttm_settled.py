"""소비자 층 평가 — 현 편입 · 미국 지표 창의 거래일마다 TTM 지배주주 순이익(sumControllingNetIncome 모사)을
스윕 TSV마다 짓고, 원장이 그날 주는 TTM 지배주주와 대조한다.

TTM(스윕) = 그날 보이는 판(pub ≤ day)의 최근 연속 네 분기 지배주주 합 — 하나라도 null이면 넷 다 연결 합(창 단위 폴백).
TTM(원장) = 최근 분기가 Q4면 지배(FY) · Qk면 지배(작년 FY) − 지배(작년 k분기 누계) + 지배(올해 k분기 누계).
  지배(창) = NetIncomeLoss, 없으면 ProfitLoss − 비지배 — 그날 이하 공시된 최신 값(±7일 창).
TTM_TRUTH=post면 원장 TTM을 「사후 진실」(캐시 시점까지의 최신 공시 — FINDINGS 3절 합의 평가 수단)로 짓는다.
틀림 = |TTM − 원장| > max(1% · max(|둘|), 50만) · 빈 = TTM null인데 원장 있음 · 미정 = 원장 없음.
네 분기가 안 서는 날(연속 아님 · 모자람)은 따로 센다 — 소비자는 그때 FY 계열로 간다(여기서 안 다룬다)."""
ANNUAL = {'10-K', '10-K/A', '20-F', '20-F/A', '40-F', '40-F/A'}
import bisect, collections, csv, datetime as dt, json, sys, os
PF = {'10-K', '10-K/A', '10-Q', '10-Q/A', '20-F', '20-F/A', '40-F', '40-F/A', '6-K', '6-K/A'}  # 어댑터 PERIODIC_FORMS와 같게 — DEF 14A 보수-성과 표가 NetIncomeLoss에 연결값을 싣는다

S = sys.argv[1]
tsvs = sys.argv[2:]
import os
cur = [l.split('|')[0] for l in open(os.environ.get('TTM_POP', f'{S}/cur.txt')).read().split()]
import os
if os.environ.get('FROM'):
    _x = dt.date.fromisoformat(os.environ['FROM']); days = []
    while _x <= dt.date.fromisoformat(os.environ['TO']):
        if _x.weekday() < 5: days.append(_x.isoformat())
        _x += dt.timedelta(days=int(os.environ.get('STEP', '1')))
else:
    days = [l.strip() for l in open(os.environ.get('TTM_DAYS', f'{S}/us_days.txt')) if l.strip()]
OFF = int(os.environ.get('TTM_OFFSET', '0'))
num = lambda x: float(x) if x not in ('', None) else None
d = dt.date.fromisoformat

cache = {}


def series(t, tag, s, e):
    key = (t, tag, s, e)
    if key not in cache:
        g = led(t)
        xs = sorted((x['filed'], x['accn'], x['val']) for x in g.get(tag, {}).get('units', {}).get('USD', [])
                    if x.get('form') in PF and 'start' in x and ((e - s).days < 300 or x.get('form') in ANNUAL) and abs((d(x['start']) - s).days) <= 7 and abs((d(x['end']) - e).days) <= 7)
        cache[key] = ([x[0] for x in xs], [x[2] for x in xs])
    return cache[key]


ledgers = {}


def led(t):
    if t not in ledgers:
        ledgers.clear()
        cache.clear()
        ledgers[t] = json.load(open(f'.factcache/{t}.json'))['facts'].get('us-gaap', {})
    return ledgers[t]


def asof(t, tag, s, e, day):
    f, v = series(t, tag, s, e)
    i = bisect.bisect_right(f, day)
    return v[i - 1] if i else None


def ctrl(t, s, e, day):
    x = asof(t, 'NetIncomeLoss', s, e, day)
    p, n = asof(t, 'ProfitLoss', s, e, day), asof(t, 'NetIncomeLossAttributableToNoncontrollingInterest', s, e, day)
    ident = p - n if p is not None and n is not None else None
    if x is not None:
        # 어댑터 mistaggedControlling과 같은 두 규칙: 직접 == 총액이고 비지배 ≠ 0 · 보통주 귀속 == 항등식(정확히)
        if ident is not None and x != ident:
            ac = asof(t, 'NetIncomeLossAvailableToCommonStockholdersBasic', s, e, day)
            if (x == p and n != 0) or (ac is not None and ac == ident):
                return ident
        return x
    return ident


def load(p):
    by = collections.defaultdict(lambda: collections.defaultdict(list))
    for r in csv.DictReader(open(p), delimiter='\t'):
        by[r['ticker']][(r['fy'], r['fp'])].append(r)
    for t in by:
        for v in by[t].values():
            v.sort(key=lambda r: r['pub'])
    return by


data = [load(p) for p in tsvs]


def visible(v, day):
    b = None
    for r in v:
        if r['pub'] <= day:
            b = r
        else:
            break
    return b


def truth(t, lab, fy, fp, day):
    """원장 TTM 지배주주 — 최근 분기 (fy, fp)."""
    def win(y, p):
        v = lab.get((str(y), p))
        return (d(v[-1]['start']), d(v[-1]['end'])) if v else None
    if fp == 'Q4':
        w = win(fy, 'FY')
        return ctrl(t, *w, day) if w else None
    k = int(fp[1])
    fyp, q1p, qkp, q1, qk = win(fy - 1, 'FY'), win(fy - 1, 'Q1'), win(fy - 1, fp), win(fy, 'Q1'), win(fy, fp)
    if None in (fyp, q1p, qkp, q1, qk):
        return None
    a = ctrl(t, *fyp, day)
    b = ctrl(t, q1p[0], qkp[1], day)
    c = ctrl(t, q1[0], qk[1], day)
    return a - b + c if None not in (a, b, c) else None


MAG = {'커짐': collections.Counter(), '작아짐': collections.Counter()}
res = [collections.Counter() for _ in tsvs]
wrong_st = [collections.defaultdict(set) for _ in tsvs]
pair = collections.Counter()
SAMPLE = []
for t in cur:
    try:
        led(t)
    except FileNotFoundError:
        continue
    for di, day in enumerate(days):
        outs = []
        errs = []
        for i, by in enumerate(data):
            lab = by.get(t, {})
            qs = []
            for (fy, fp), v in lab.items():
                if fp in ('Q1', 'Q2', 'Q3', 'Q4'):
                    r = visible(v, day)
                    if r:
                        qs.append((r['end'], fy, fp, r))
            qs.sort(reverse=True)
            qs = qs[OFF:OFF + 4]
            ok = len(qs) == 4 and all(70 <= (d(qs[j][0]) - d(qs[j + 1][0])).days <= 110 for j in range(3))
            if not ok:
                res[i]['네 분기 안 섬'] += 1
                outs.append(None)
                errs.append(None)
                continue
            nic = [num(q[3]['netIncomeControlling']) for q in qs]
            ni = [num(q[3]['netIncome']) for q in qs]
            ttm = sum(nic) if None not in nic else (sum(ni) if None not in ni else None)
            mode = os.environ.get('TTM_TRUTH')
            if mode == 'settled':
                fyv = data[0].get(t, {}).get((qs[0][1], 'FY'))
                H = fyv[0]['pub'] if fyv else None
                tr = truth(t, data[0].get(t, {}), int(qs[0][1]), qs[0][2], H) if H else None
                if H is None:
                    res[i]['정리 안 됨'] += 1; outs.append(None); errs.append(None); continue
            else:
                tr = truth(t, lab, int(qs[0][1]), qs[0][2], '9999-12-31' if mode == 'post' else day)
            if tr is None:
                c = '미정(원장 없음)'
            elif ttm is None:
                c = '빈'
            elif abs(ttm - tr) > max(0.01 * max(abs(ttm), abs(tr)), 5e5):
                c = '틀림'
            else:
                c = '맞음'
            res[i][c] += 1
            if c in ('틀림', '빈'):
                wrong_st[i][c].add(t)
            outs.append(c)
            errs.append(abs(ttm - tr) if (ttm is not None and tr is not None) else None)
        if len(outs) == 2 and outs[0] == outs[1] == '틀림' and None not in errs and abs(errs[0] - errs[1]) > max(0.01 * max(errs), 5e5):
            MAG['커짐' if errs[1] > errs[0] else '작아짐'][t] += 1
        if len(outs) == 2 and None not in outs and outs[0] != outs[1]:
            pair[(outs[0], outs[1])] += 1
            if True:
                SAMPLE.append((t, day, outs[0], outs[1]))
for p, r, w in zip(tsvs, res, wrong_st):
    print(p.split('/')[-1], dict(r), {k: len(v) for k, v in w.items()})
for k, v in MAG.items():
    if v: print(f'둘 다 틀림 중 오차 {k}: {sum(v.values())}셀-일 · {len(v)}종목', v.most_common(12))
if pair:
    print('기준 → 변형 갈림(셀-일):', dict(pair))
    by_t = collections.Counter((s[0], s[2], s[3]) for s in SAMPLE)
    print('갈린 종목 상위:', by_t.most_common(30))
