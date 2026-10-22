"""F2b v1.1 실측(0콜) — 반증 지적을 운영 술어로 다시 잰다.

  python3 -I measure2.py <us_members.txt> <ifrs_cf 디렉터리> [FACTCACHE]

- 비지배 판정은 운영 `noNoncontrollingIncomeBy`(그 기간 시작 뒤로 비지배 손익을 안 냈는가)와 같다.
- 지배주주 순이익은 운영 `controllingIncome` 꼴(직접 → 연결 − 비지배 → 연결 마지막 수단).
- Q4 지배 중단 = FY − 같은 해 9개월 누계(그 9개월을 실은 공시에 중단 줄이 없고 FY ≠ 0이면 미정).
- 정렬은 (end, start)로 결정적이다.
"""
import json, os, sys
from collections import Counter, defaultdict
from datetime import date

members = [l.strip() for l in open(sys.argv[1]) if l.strip()]
IFRS = sys.argv[2]
CACHE = sys.argv[3] if len(sys.argv) > 3 else '/Users/na1011/dev/stock-screener/.factcache'

GAAP = {
    'NI_P': ['NetIncomeLoss'], 'NI_T': ['ProfitLoss'], 'NI_C': ['NetIncomeLossAvailableToCommonStockholdersBasic'],
    'CO_P': ['IncomeLossFromContinuingOperations'],
    'CO_T': ['IncomeLossFromContinuingOperationsIncludingPortionAttributableToNoncontrollingInterest'],
    'CO_N': ['IncomeLossFromContinuingOperationsAttributableToNoncontrollingEntity'],
    'CO_AC': ['NetIncomeLossFromContinuingOperationsAvailableToCommonShareholdersBasic'],
    'DI_T': ['IncomeLossFromDiscontinuedOperationsNetOfTax', 'DiscontinuedOperationIncomeLossFromDiscontinuedOperationNetOfTax'],
    'DI_P': ['IncomeLossFromDiscontinuedOperationsNetOfTaxAttributableToReportingEntity'],
    'DI_N': ['IncomeLossFromDiscontinuedOperationsNetOfTaxAttributableToNoncontrollingInterest'],
    'NCI': ['NetIncomeLossAttributableToNoncontrollingInterest', 'ProfitLossAttributableToNoncontrollingInterest',
            'NetIncomeLossAttributableToRedeemableNoncontrollingInterest',
            'NetIncomeLossAttributableToNonredeemableNoncontrollingInterest'],
}
IFRS_TAGS = {
    'NI_P': ['ProfitLossAttributableToOwnersOfParent'], 'NI_T': ['ProfitLoss'], 'NI_C': [],
    'CO_P': ['IncomeFromContinuingOperationsAttributableToOwnersOfParent',
             'ProfitLossFromContinuingOperationsAttributableToOrdinaryEquityHoldersOfParentEntity'],
    'CO_T': ['ProfitLossFromContinuingOperations'],
    'CO_N': ['ProfitLossFromContinuingOperationsAttributableToNoncontrollingInterests'],
    'CO_AC': [],
    'DI_T': ['ProfitLossFromDiscontinuedOperations'],
    'DI_P': ['IncomeFromDiscontinuedOperationsAttributableToOwnersOfParent',
             'ProfitLossFromDiscontinuedOperationsAttributableToOrdinaryEquityHoldersOfParentEntity'],
    'DI_N': ['ProfitLossFromDiscontinuedOperationsAttributableToNoncontrollingInterests'],
    'NCI': ['ProfitLossAttributableToNoncontrollingInterests'],
}


def d(s):
    return date.fromisoformat(s)


def days(k):
    return (d(k[1]) - d(k[0])).days


def load(g, tags):
    """(start, end) → (최신 값, 그 공시) · accn별 (start, end) 집합."""
    best = {}
    by_accn = defaultdict(set)
    for i, tag in enumerate(tags):
        for arr in (g.get(tag) or {}).get('units', {}).values():
            for f in arr:
                if 'start' not in f:
                    continue
                k = (f['start'], f['end'])
                by_accn[f['accn']].add(k)
                cur = best.get(k)
                if cur is None or cur[2] > i or (cur[2] == i and f['filed'] > cur[0]):
                    best[k] = (f['filed'], f['val'], i, f['accn'])
    return {k: v[1] for k, v in best.items()}, by_accn


def nz(x):
    return x is not None and x != 0


def analyse(g, T):
    s, acc = {}, {}
    for n, tags in T.items():
        s[n], acc[n] = load(g, tags)
    nci_ends = [d(k[1]) for k in s['NCI']] + [d(k[1]) for k in s['DI_N']] + [d(k[1]) for k in s['CO_N']]
    last_nci = max(nci_ends) if nci_ends else None

    def no_nci_by(k):
        return last_nci is None or last_nci < d(k[0])

    def nic(r, k):
        if r['NI_P'] is not None:
            return r['NI_P']
        if r['NI_T'] is not None and r['NCI'] is not None:
            return r['NI_T'] - r['NCI']
        if r['NI_T'] is not None and no_nci_by(k):
            return r['NI_T']
        return None

    def dip(r, k):
        """지배 중단 · 갈래 이름. 정확한 갈래만."""
        if r['DI_P'] is not None:
            return r['DI_P'], 'DI_P'
        if r['DI_T'] is not None and r['DI_N'] is not None:
            return r['DI_T'] - r['DI_N'], 'DI_T-DI_N'
        n = nic(r, k)
        if n is not None and r['CO_P'] is not None:
            return n - r['CO_P'], 'NIC-CO_P'
        if n is not None and r['CO_AC'] is not None and r['NI_C'] is not None and abs(r['NI_C'] - n) <= 5e5:
            return n - r['CO_AC'], 'NIC-CO_AC'
        if n is not None and r['CO_T'] is not None and r['CO_N'] is not None:
            return n - (r['CO_T'] - r['CO_N']), 'NIC-(CO_T-CO_N)'
        if r['DI_T'] is not None and no_nci_by(k):
            return r['DI_T'], 'no NCI by'
        return None, None

    def row(k):
        return {n: s[n].get(k) for n in T}

    def has_di_line(accn, k):
        return any(k in acc[n].get(accn, ()) for n in ('DI_T', 'DI_P', 'DI_N'))

    return s, acc, row, dip, nic, has_di_line


def quarter_q4(s, acc, row, dip, has_di_line, fy):
    """FY 라벨의 Q4 지배 중단 · 상태."""
    r = row(fy)
    fy_di, _ = dip(r, fy)
    fy_any = nz(r['DI_T']) or nz(r['DI_P'])
    start = fy[0]
    nine = [k for k in set(s['NI_P']) | set(s['NI_T']) if k[0] == start and 260 <= days(k) <= 285]
    if not fy_any:
        return 0, 'FY 중단 없음'
    if fy_di is None:
        return None, 'FY 지배 미정'
    if not nine:
        return None, '9개월 누계 없음'
    k9 = sorted(nine, key=lambda k: (k[1], k[0]))[-1]
    # 9개월을 실은 공시들 중 가장 이른 것(Q3 10-Q — FY 공시일 앞에 보이던 판)
    accns = sorted(a for n in ('NI_P', 'NI_T') for a, ks in acc[n].items() if k9 in ks)
    q3 = accns[0] if accns else None
    if q3 is None or not has_di_line(q3, k9):
        return None, 'Q3 뒤 재분류(9개월 공시에 줄 없음)'
    r9 = row(k9)
    nine_di, _ = dip(r9, k9)
    if nine_di is None:
        return None, '9개월 지배 미정'
    return fy_di - nine_di, 'FY − 9개월'


def run(name, files, T, ns):
    win = Counter()
    lost = set()
    lost_q4 = Counter()
    pop = set()
    branch = Counter()
    five = Counter()
    five_lost = set()
    q4stat = Counter()
    for t, path in files:
        g = json.load(open(path)).get('facts', {}).get(ns, {})
        if not g:
            continue
        s, acc, row, dip, nic, has_di_line = analyse(g, T)
        ni_keys = sorted(set(s['NI_P']) | set(s['NI_T']), key=lambda k: (k[1], k[0]))
        q = [k for k in ni_keys if 80 <= days(k) <= 100]
        fys = [k for k in ni_keys if 350 <= days(k) <= 380]
        # 최근 TTM: 마지막 FY 뒤의 분기 + 그 FY의 Q4(잔차) + 그 앞 분기 — 기말 4개
        ends = sorted({k[1] for k in q} | {k[1] for k in fys})[-4:]
        any_di = False
        unresolved = False
        recent_q = [k for k in q if k[1] >= '2024-07-01']
        if len(recent_q) < 3 and fys:
            # 분기 계열이 안 서는 발행사(연차 · 반기) — TTM은 FY 한 행이다(FinancialMath ANNUAL 기저)
            r = row(fys[-1])
            if nz(r['DI_T']) or nz(r['DI_P']):
                any_di = True
                v, how = dip(r, fys[-1])
                q4stat['연차 기저 FY ' + str(how)] += 1
                unresolved = v is None
            ends = []
        for e in ends:
            fk = [k for k in fys if k[1] == e]
            qk = [k for k in q if k[1] == e]
            if qk and not fk:
                r = row(qk[-1])
                if nz(r['DI_T']) or nz(r['DI_P']):
                    any_di = True
                    v, how = dip(r, qk[-1])
                    branch[how] += 1
                    if v is None:
                        unresolved = True
            elif fk:
                v, how = quarter_q4(s, acc, row, dip, has_di_line, fk[-1])
                if how != 'FY 중단 없음':
                    any_di = True
                    q4stat[how] += 1
                    if v is None:
                        unresolved = True
                        lost_q4[how] += 1
        if any_di:
            pop.add(t)
        if unresolved:
            lost.add(t)
        # 5년 FY의 Q4 상태
        for fk in fys:
            if fk[1] < '2021-07-01':
                continue
            v, how = quarter_q4(s, acc, row, dip, has_di_line, fk)
            if how != 'FY 중단 없음':
                five[how] += 1
                if v is None:
                    five_lost.add(t)
    print(f'== {name}: 파일 {len(files)}')
    print('  최근 TTM에 중단 ≠ 0', len(pop), '· 지배 계속영업을 못 정해 미표시', len(lost), sorted(lost))
    print('  분기 칸 갈래', dict(branch))
    print('  최근 Q4 상태', dict(q4stat), '미정 사유', dict(lost_q4))
    print('  5년 FY의 Q4 상태', dict(five), '미정 있는 종목', len(five_lost))


us_files = [(t, os.path.join(CACHE, t + '.json')) for t in members if os.path.exists(os.path.join(CACHE, t + '.json'))]
run('미국 us-gaap 편입', us_files, GAAP, 'us-gaap')
ifrs_files = sorted((os.path.basename(p)[:-5], p) for p in
                    [os.path.join(IFRS, f) for f in os.listdir(IFRS)])
run('미국 IFRS(USD · 관문 켬)', ifrs_files, IFRS_TAGS, 'ifrs-full')

# 갈래별 정확도 — 같은 공시 안에서 직접 DI_P와 대조(미국 us-gaap)
agree = Counter()
for t, path in us_files:
    g = json.load(open(path)).get('facts', {}).get('us-gaap', {})
    per = defaultdict(dict)
    for n, tags in GAAP.items():
        for tag in tags[:1] if n != 'DI_T' else tags:
            for arr in (g.get(tag) or {}).get('units', {}).values():
                for f in arr:
                    if 'start' in f:
                        per[(f['accn'], f['start'], f['end'])].setdefault(n, f['val'])
    for key, r in per.items():
        dp = r.get('DI_P')
        if dp is None or key[2] < '2019-01-01':
            continue
        tol = max(5e5, 0.005 * abs(dp))
        cands = {
            'DI_T-DI_N': (r['DI_T'] - r['DI_N']) if 'DI_T' in r and 'DI_N' in r else None,
            'NIC-CO_P': (r['NI_P'] - r['CO_P']) if 'NI_P' in r and 'CO_P' in r else None,
            'NIC-(CO_T-CO_N)': (r['NI_P'] - (r['CO_T'] - r['CO_N'])) if all(x in r for x in ('NI_P', 'CO_T', 'CO_N')) else None,
            '가정: DI_T(비지배 몫 0)': r.get('DI_T') if 'NCI' in r and r['NCI'] != 0 and 'DI_N' not in r else None,
        }
        for nm, v in cands.items():
            if v is None:
                continue
            agree[nm + ' n'] += 1
            agree[nm + ' 일치'] += abs(v - dp) <= tol
print('갈래 일치(같은 공시 · 2019 뒤 · 직접 DI_P 대비)', dict(agree))
