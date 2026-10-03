"""뿌리별 노출 크기 — 지금 main 어댑터 출력(기준선 스윕)에서 지표 창에 보이는, 소비자 조회 범위 안의 틀린(혼합) 칸을 센다.

노출: 그 판이 W = [2025-09-26, 2026-10-01]의 어느 날짜 d에 라벨별로 보이는 판(pub ≤ d < 다음 pub)이고,
      FY는 fy ≥ 2020 · 분기는 period_end ≥ 2023-07-01(TTM + 1년 전 TTM).
"""
import collections, csv, datetime as dt, sys

S = sys.argv[1]
W0, W1 = dt.date(2025, 9, 26), dt.date(2026, 10, 1)
MEM = dict(l.strip().split("|") for l in open(f"{S}/members.txt"))
COLMAP = {"revenue": "revenue", "costOfSales": "costOfSales", "grossProfit": "grossProfit",
          "operatingIncome": "operatingIncome", "netIncome": "netIncomeControlling",
          "totalAssets": "totalAssets", "totalLiabilities": "totalLiabilities", "totalEquity": "totalEquity"}


def d(s):
    return dt.date.fromisoformat(s) if s else None


def num(x):
    return float(x) if x not in ("", None) else None


def close(a, b, r=0.01):
    return a is not None and b is not None and abs(a - b) <= r * max(abs(a), abs(b), 1.0)


lab = collections.defaultdict(list)
for r in csv.DictReader(open(f"{S}/base.tsv"), delimiter="\t"):
    if r["ticker"] in MEM:
        lab[(r["ticker"], r["fy"], r["fp"])].append(r)
for v in lab.values():
    v.sort(key=lambda r: r["pub"])
by_ticker = collections.defaultdict(list)
for k, v in lab.items():
    by_ticker[k[0]].append((k, v))


def relevant(k, v):
    if k[2] == "FY":
        return int(k[1]) >= 2020
    return d(v[-1]["end"]) >= dt.date(2023, 7, 1)


def visible_spans(v):
    """판마다 W 안에서 보이는 구간."""
    out = []
    for i, r in enumerate(v):
        a = max(d(r["pub"]), W0)
        b = min(d(v[i + 1]["pub"]) - dt.timedelta(days=1), W1) if i + 1 < len(v) else W1
        if a <= b:
            out.append((r, a, b))
    return out


def labels_for(t, s, e, instant):
    out = []
    for k, v in by_ticker[t]:
        rs, re_ = d(v[-1]["start"]), d(v[-1]["end"])
        if instant:
            if re_ == e:
                out.append((k, v))
        elif abs((rs - s).days) <= 7 and abs((re_ - e).days) <= 7:
            out.append((k, v))
    return out


def events(name):
    try:
        for l in open(f"{S}/cat3/events-{name}.tsv"):
            p = l.rstrip("\n").split("\t")
            yield dict(t=p[0], g=p[1], col=p[2], s=d(p[3]), e=d(p[4]), k=p[5], filed=d(p[6]), accn=p[7],
                       detail=p[8], extra=p[9] if len(p) > 9 else "")
    except FileNotFoundError:
        return


res = {}


def tally(name, cells):
    st = {c[0] for c in cells}
    cur = {c for c in cells if MEM.get(c[0]) == "current"}
    res[name] = (len(cells), len(st), len(cur), len({c[0] for c in cur}))


# A. 다른 후보 태그로 실린 재작성을 못 읽음(K4): F2 이후 W에서 보이는 저장값이 옛값 그대로
cells = set()
for ev in events("K4_tag_switch_value_change"):
    col = COLMAP.get(ev["col"])
    if not col:
        continue
    old, new = (x.split(":")[1] for x in ev["detail"].split(">"))
    old, new = float(old), float(new)
    for k, v in labels_for(ev["t"], ev["s"], ev["e"], ev["k"] == "I"):
        if not relevant(k, v):
            continue
        for r, a, b in visible_spans(v):
            if b >= ev["filed"] and close(num(r[col]), old) and not close(num(r[col]), new):
                cells.add((ev["t"], k[1], k[2], col))
tally("A 태그 전환 재작성 미반영(K4)", cells)

# C. 문맥 오태깅(K11 · K8): W에서 보이는 저장값이 오태깅 값
cells = set()
for name in ("K11_value_equals_other_window_in_same_filing", "K8_q4_context_equals_FY"):
    for ev in events(name):
        col = COLMAP.get(ev["col"])
        if not col:
            continue
        bad = float(ev["detail"].split(">")[1]) if ">" in ev["detail"] else float(ev["detail"].split("=")[0])
        for k, v in labels_for(ev["t"], ev["s"], ev["e"], False):
            if not relevant(k, v):
                continue
            for r, a, b in visible_spans(v):
                if b >= ev["filed"] and close(num(r[col]), bad, 0.001):
                    cells.add((ev["t"], k[1], k[2], col))
tally("C 문맥 오태깅 값 저장(K11·K8)", cells)

# D. 누계로만 드러난 재작성(K3, 누계 창 H·N): 그 공시일에 분기 판이 안 생긴 분기가 옛값으로 남음.
#    함의값 = 새 누계 − 같은 공시가 실은 나머지 분기(그 공시일 판) — 저장값과 1% 넘게 다를 때만 센다.
cells = set()
undetermined = set()
for ev in events("K3_comparative_after_FY_differs"):
    if ev["k"] not in ("H", "N"):
        continue
    col = COLMAP.get(ev["col"])
    new_ytd = float(ev["detail"].split(">")[1])
    quarters = sorted([(k, v) for k, v in by_ticker[ev["t"]] if k[2] in ("Q1", "Q2", "Q3")
                       and d(v[-1]["start"]) >= ev["s"] - dt.timedelta(days=7)
                       and d(v[-1]["end"]) <= ev["e"] + dt.timedelta(days=7)], key=lambda x: x[1][-1]["end"])
    fresh = {k: next((r for r in v if r["pub"] == str(ev["filed"])), None) for k, v in quarters}
    stale = [(k, v) for k, v in quarters if fresh[k] is None]
    if not stale:
        continue
    rest = sum(num(fresh[k][col]) or 0 for k, v in quarters if fresh[k] is not None)
    implied = new_ytd - rest
    # 판 공시일(F) 시점에 보이는 옛 분기 값의 합
    stored = 0.0
    ok = True
    for k, v in stale:
        prev = [r for r in v if r["pub"] < str(ev["filed"])]
        if not prev or num(prev[-1][col]) is None:
            ok = False
            break
        stored += num(prev[-1][col])
    if not ok:
        continue
    if close(stored, implied):
        continue
    for k, v in stale:
        if relevant(k, v) and any(b >= ev["filed"] for r, a, b in visible_spans(v)):
            (cells if len(stale) == 1 else undetermined).add((ev["t"], k[1], k[2], col))
tally("D 누계로만 드러난 재작성 — 분기 하나로 귀속(K3)", cells)
tally("D' 같은 — 둘 이상 분기에 걸쳐 귀속 불가", undetermined)

# E. 잔액 혼합(자산 − 부채 − 자본 < −1.5M) — W에서 보이는 판
cells = set()
for k, v in lab.items():
    if not relevant(k, v):
        continue
    for r, a, b in visible_spans(v):
        A, L, E = num(r["totalAssets"]), num(r["totalLiabilities"]), num(r["totalEquity"])
        if None not in (A, L, E) and A - L - E < -1.5e6:
            cells.add((k[0], k[1], k[2], "balance"))
tally("E 대차대조표 불성립(B)", cells)

# F. 매출 재작성 공시가 안 실은 영업 줄을 옛 기준에서 이어받음(출처 추적 · 기준선)
op = ("COST_OF_SALES", "GROSS_PROFIT", "OPERATING_INCOME", "OPERATING_EXPENSES", "COSTS_AND_EXPENSES")
inh = {}
for line in open(f"{S}/origin-base.tsv"):
    p = line.rstrip("\n").split("\t")
    if p[4] != "ROW" or p[0] not in MEM or not p[6] or not p[7]:
        continue
    rb, rv = float(p[6]), float(p[7])
    if not close(rb, rv):
        orig = dict(x.split("=", 1) for x in p[10].split(";") if "=" in x)
        cols = [c for c in op if orig.get(c, "").startswith("^")]
        if cols:
            inh[(p[0], p[1], p[2], p[3])] = cols
cells = set()
for (t, fy, fp, pub), cols in inh.items():
    v = lab.get((t, fy, fp))
    if not v or not relevant((t, fy, fp), v):
        continue
    for r, a, b in visible_spans(v):
        if r["pub"] >= pub:
            for c in cols:
                cells.add((t, fy, fp, c))
            break
tally("F 매출 재작성 뒤 영업 줄 옛 기준 이어받음", cells)

# G. 영업이익 null(최신 판 · FY ≥ 2023 · 현 편입) — [S] 원래 결손
g = {k[0] for k, v in lab.items() if k[2] == "FY" and int(k[1]) >= 2023 and MEM.get(k[0]) == "current"
     and v[-1]["operatingIncome"] == ""}
res["G 저장 영업이익 null(FY≥2023 최신 판 · 소비자 유도 전)"] = (len(g), len(g), len(g), len(g))

print("뿌리\t노출 칸\t종목\t현 편입 칸\t현 편입 종목")
for k, (n, s, nc, sc) in res.items():
    print(f"{k}\t{n}\t{s}\t{nc}\t{sc}")

# H. Q4 — W의 양 끝 날짜에 보이는 Q4가 같은 날 보이는 FY − (Q1+Q2+Q3)과 다름(1% · 100만 하한)
#    H1 지배주주 순이익(그중 Q4 지배주주 = Q4 연결 총액인 것) · H2 나머지 손익 컬럼
def at(v, day):
    best = None
    for r in v:
        if d(r["pub"]) <= day:
            best = r
    return best

def mclose(a, b):
    return abs(a - b) <= max(0.01 * max(abs(a), abs(b)), 1e6)

h1, h1eq, h2 = set(), set(), collections.defaultdict(set)
for (t, fy, fp), v in lab.items():
    if fp != "Q4" or not relevant((t, fy, fp), v):
        continue
    parts = [lab.get((t, fy, q)) for q in ("FY", "Q1", "Q2", "Q3")]
    if any(p is None for p in parts):
        continue
    for day in (W0, W1):
        q4 = at(v, day)
        rows = [at(p, day) for p in parts]
        if q4 is None or any(r is None for r in rows):
            continue
        for col in ("revenue", "costOfSales", "grossProfit", "operatingIncome", "netIncomeControlling"):
            vals = [num(r[col]) for r in rows]
            q = num(q4[col])
            if q is None or None in vals or mclose(q, vals[0] - sum(vals[1:])):
                continue
            if col == "netIncomeControlling":
                h1.add((t, fy))
                if num(q4["netIncome"]) == q:
                    h1eq.add((t, fy))
            else:
                h2[col].add((t, fy))

def line(name, cells):
    st = {c[0] for c in cells}
    cur = {c for c in cells if MEM.get(c[0]) == "current"}
    print(f"{name}\t{len(cells)}\t{len(st)}\t{len(cur)}\t{len({c[0] for c in cur})}")

line("H1 Q4 지배주주 순이익 ≠ FY − ΣQ", h1)
line("H1' 그중 Q4 지배주주 = Q4 연결 총액", h1eq)
for col, cells in sorted(h2.items()):
    line(f"H2 Q4 {col} ≠ FY − ΣQ", cells)
line("H2 합(라벨 × 컬럼)", {(t, fy, c) for c, cells in h2.items() for (t, fy) in cells})
