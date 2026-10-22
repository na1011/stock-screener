"""F2b 실측 재현(0콜) — 계획 F2B-PLAN-1023.md의 ★ 수를 낸다.

  python3 -I measure.py <members.txt(미국 현 편입 1,000)> <kr 원응답 디렉터리(kr_probe.py가 받은 것)>

미국은 저장소 .factcache(companyfacts), 국내는 fnlttSinglAcntAll CFS(없으면 OFS) 2025 사업 · 2026 반기 원응답이다.
"""
import glob, json, os, sys
from collections import Counter, defaultdict
from datetime import date

CACHE = os.path.join(os.path.dirname(__file__), '../../../../stock-screener/.factcache')
CACHE = os.environ.get('FACTCACHE', '/Users/na1011/dev/stock-screener/.factcache')
members = [l.strip() for l in open(sys.argv[1]) if l.strip()]
KR = sys.argv[2]

US = {
    'NI_P': ['NetIncomeLoss'], 'NI_T': ['ProfitLoss'], 'NI_C': ['NetIncomeLossAvailableToCommonStockholdersBasic'],
    'CO_P': ['IncomeLossFromContinuingOperations'],
    'CO_T': ['IncomeLossFromContinuingOperationsIncludingPortionAttributableToNoncontrollingInterest'],
    'CO_N': ['IncomeLossFromContinuingOperationsAttributableToNoncontrollingEntity'],
    'CO_AC': ['NetIncomeLossFromContinuingOperationsAvailableToCommonShareholdersBasic'],
    'DI_T': ['IncomeLossFromDiscontinuedOperationsNetOfTax', 'DiscontinuedOperationIncomeLossFromDiscontinuedOperationNetOfTax'],
    'DI_P': ['IncomeLossFromDiscontinuedOperationsNetOfTaxAttributableToReportingEntity'],
    'DI_N': ['IncomeLossFromDiscontinuedOperationsNetOfTaxAttributableToNoncontrollingInterest'],
    'NCI': ['NetIncomeLossAttributableToNoncontrollingInterest', 'ProfitLossAttributableToNoncontrollingInterest'],
    'EPS': ['EarningsPerShareBasic'], 'EPS_CO': ['IncomeLossFromContinuingOperationsPerBasicShare'],
}


def latest(g, tags):
    """(start, end) → 가장 늦게 공시된 값. 앞 태그가 이긴다."""
    out = {}
    for i, tag in enumerate(tags):
        for arr in (g.get(tag) or {}).get('units', {}).values():
            for f in arr:
                if 'start' not in f:
                    continue
                k = (f['start'], f['end'])
                cur = out.get(k)
                if cur is None or cur[2] > i or (cur[2] == i and f['filed'] > cur[0]):
                    out[k] = (f['filed'], f['val'], i)
    return {k: v[1] for k, v in out.items()}


def days(k):
    return (date.fromisoformat(k[1]) - date.fromisoformat(k[0])).days


def nz(x):
    return x is not None and x != 0


def parent_di(r, nci_never):
    """지배주주 귀속 중단영업 — 정확한 갈래만. 못 정하면 None."""
    if r['DI_P'] is not None:
        return r['DI_P']
    if r['DI_T'] is not None and r['DI_N'] is not None:
        return r['DI_T'] - r['DI_N']
    if r['NI_P'] is not None and r['CO_P'] is not None:
        return r['NI_P'] - r['CO_P']
    if None not in (r['NI_P'], r['CO_AC'], r['NI_C']) and abs(r['NI_C'] - r['NI_P']) <= 5e5:
        return r['NI_P'] - r['CO_AC']
    if None not in (r['CO_T'], r['CO_N'], r['NI_P']):
        return r['NI_P'] - (r['CO_T'] - r['CO_N'])
    if r['NCI'] == 0 and r['DI_T'] is not None:
        return r['DI_T']
    if nci_never and r['DI_T'] is not None:
        return r['DI_T']
    return None


present = 0
win = Counter()
win_t = defaultdict(set)
five = Counter()
five_unres = defaultdict(int)
score = Counter()
score_err = [0.0, 0.0]
for t in members:
    p = os.path.join(CACHE, t + '.json')
    if not os.path.exists(p):
        continue
    present += 1
    g = json.load(open(p)).get('facts', {}).get('us-gaap', {})
    s = {k: latest(g, v) for k, v in US.items()}
    nci_never = not s['NCI'] and not s['DI_N'] and not s['CO_N']
    # 창: 최근 네 분기(3개월 팩트 기말) · 최근 두 FY
    ni_keys = set(s['NI_P']) | set(s['NI_T'])
    q = [k for k in ni_keys if 80 <= days(k) <= 100]
    ends = sorted({k[1] for k in q})[-4:]
    fy = sorted([k for k in ni_keys if 350 <= days(k) <= 380], key=lambda k: k[1])[-2:]
    for kind, keys in (('Q', [k for k in q if k[1] in ends]), ('FY', fy)):
        for k in keys:
            r = {n: s[n].get(k) for n in US}
            if nz(r['DI_T']) or nz(r['DI_P']):
                win[kind + ' 중단≠0'] += 1
                win_t[kind].add(t)
                if abs(r['DI_T'] or r['DI_P']) > 0.1 * abs(r['NI_P'] or r['NI_T'] or 1):
                    win_t[kind + ' 10%'].add(t)
                if parent_di(r, nci_never) is None:
                    win[kind + ' 지배 미정'] += 1
                    win_t[kind + ' 지배 미정'].add(t)
    # 5년(2021-07 뒤 기말) 지배 미정
    for k in set(s['DI_T']) | set(s['DI_P']):
        if k[1] < '2021-07-01' or not (80 <= days(k) <= 100 or 350 <= days(k) <= 380):
            continue
        r = {n: s[n].get(k) for n in US}
        if nz(r['DI_T']) or nz(r['DI_P']):
            five['칸'] += 1
            if parent_di(r, nci_never) is None:
                five['미정 칸'] += 1
                five_unres[t] += 1
    # 버린 대안 채점: DI_P := DI_T(비지배 몫 0 가정) — 답(DI_P 직접)을 아는 칸 · NCI≠0 · DI_N 없음
    for k, dp in s['DI_P'].items():
        dt = s['DI_T'].get(k)
        if dt in (None, 0) or s['DI_N'].get(k) is not None or s['NCI'].get(k) in (None, 0):
            continue
        score['n'] += 1
        score['DI_T = DI_P'] += abs(dt - dp) <= max(5e5, 0.005 * abs(dp))
    # 같은 가정을 계속영업 답(CO_P 직접)으로: 지금(NI_P) 대비 가까워지나
    for k, co in s['CO_P'].items():
        di = s['DI_T'].get(k)
        ni = s['NI_P'].get(k)
        if di in (None, 0) or ni is None or s['NCI'].get(k) in (None, 0) or s['DI_N'].get(k) is not None:
            continue
        b, c = abs(ni - di - co), abs(ni - co)
        score['co n'] += 1
        score['co closer'] += b < c
        score['co farther'] += b > c
        score_err[0] += b
        score_err[1] += c

print('미국 캐시', present, '/', len(members))
print('창', dict(win), {k: len(v) for k, v in win_t.items()})
print('창 지배 미정 종목', sorted(win_t['Q 지배 미정'] | win_t['FY 지배 미정']))
print('5년', dict(five), '종목', len(five_unres))
print('버린 대안 채점', dict(score), f'절대오차 {score_err[0] / 1e9:.1f}B vs 지금 {score_err[1] / 1e9:.1f}B')

# 국내
A = {
    'CO_T': 'ifrs-full_ProfitLossFromContinuingOperations', 'DI_T': 'ifrs-full_ProfitLossFromDiscontinuedOperations',
    'CO_P': 'ifrs-full_IncomeFromContinuingOperationsAttributableToOwnersOfParent',
    'CO_P2': 'ifrs-full_ProfitLossFromContinuingOperationsAttributableToOrdinaryEquityHoldersOfParentEntity',
    'DI_P': 'ifrs-full_IncomeFromDiscontinuedOperationsAttributableToOwnersOfParent',
    'DI_P2': 'ifrs-full_ProfitLossFromDiscontinuedOperationsAttributableToOrdinaryEquityHoldersOfParentEntity',
    'CO_N': 'ifrs-full_ProfitLossFromContinuingOperationsAttributableToNoncontrollingInterests',
    'DI_N': 'ifrs-full_ProfitLossFromDiscontinuedOperationsAttributableToNoncontrollingInterests',
    'NI_T': 'ifrs-full_ProfitLoss', 'NI_P': 'ifrs-full_ProfitLossAttributableToOwnersOfParent',
    'NCI': 'ifrs-full_ProfitLossAttributableToNoncontrollingInterests',
}


def num(x):
    try:
        return float(str(x).replace(',', ''))
    except ValueError:
        return None


st = Counter()
kr_t = set()
kr_10 = set()
kr_50 = set()
kr = Counter()
for f in sorted(glob.glob(os.path.join(KR, '*_*_*.json'))):
    b = json.load(open(f))
    st[(b.get('status'), b.get('_fs_div'))] += 1
    if b.get('status') != '000':
        continue
    t, y, code = os.path.basename(f)[:-5].split('_')
    col = 'thstrm_amount' if code == '11011' else 'thstrm_add_amount'
    v = {}
    for r in b['list']:
        if r.get('sj_div') not in ('IS', 'CIS'):
            continue
        for k, aid in A.items():
            if r.get('account_id') == aid and k not in v:
                v[k] = num(r.get(col))
    if v.get('NI_P') is None and v.get('CO_P') is not None and v.get('DI_P') is not None:
        kr['지배 순이익 줄 없음 · 계속+중단 지배 있음(보고서)'] += 1
    di = v.get('DI_T')
    if not nz(di):
        continue
    kr_t.add(t)
    ni = v.get('NI_T')
    if ni and abs(di) > 0.1 * abs(ni):
        kr_10.add(t)
    if ni and abs(di) > 0.5 * abs(ni):
        kr_50.add(t)
    kr['중단≠0 보고서'] += 1
    exact = (v.get('CO_P') is not None or v.get('CO_P2') is not None or v.get('DI_P') is not None
             or v.get('DI_P2') is not None or (v.get('CO_N') is not None and v.get('CO_T') is not None)
             or (v.get('DI_N') is not None))
    kr['지배 정확'] += exact
    kr['비지배 0'] += (not exact) and v.get('NCI') in (None, 0)
print('국내 응답', dict(st))
print('국내 중단≠0 종목', len(kr_t), '10%↑', len(kr_10), '50%↑', len(kr_50), dict(kr))
