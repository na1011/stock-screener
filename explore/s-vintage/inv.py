"""스윕 TSV 위의 불변식 검사기. DB도 외부 호출도 없다.

python3 inv.py base.tsv [members.txt]        → 요약
python3 inv.py base.tsv members.txt --diff new.tsv → 두 스윕의 불변식·커버리지·칸 변화 대조

불변식
  B  대차대조표: 자산 − 부채 − 자본 ≥ −1.5M (ADR-0158 펜스와 같은 술어)
  G  매출총이익: |매출 − 원가 − 총이익| ≤ 1% · |매출| (셋 다 있을 때)
  Q  Q4 시점 정합: Q4 판 공시일 t에 알던 FY 판 − 그때 알던 Q1~Q3 = Q4 (컬럼별, 1%)
  L  최신 Q4 대 「FY 최신 − 최신 Q1~Q3」(1%) — 124칸 지표의 사본
  C  커버리지: 최신 판 컬럼별 값 칸 수
"""
import csv, sys, collections

MONEY = ["revenue", "costOfSales", "grossProfit", "operatingIncome", "netIncome", "netIncomeControlling",
         "totalAssets", "totalLiabilities", "totalEquity", "totalEquityControlling", "currentAssets",
         "currentLiabilities", "profitBeforeTax", "operatingCashFlow", "costsAndExpenses", "operatingExpenses",
         "benefitsLossesAndExpenses", "interestAndDividendIncome", "noninterestIncome", "interestExpense",
         "noninterestExpense", "incomeTaxExpense", "inventories", "capex", "cashAndEquivalents",
         "retainedEarnings", "sharesOutstanding", "eps"]
FLOWS = ["revenue", "costOfSales", "grossProfit", "operatingIncome", "netIncome", "netIncomeControlling"]
LFLOWS = ["revenue", "operatingIncome", "netIncome", "grossProfit"]


def num(x):
    if x is None or x == "" or x == "null":
        return None
    try:
        return float(x)
    except ValueError:
        return None


def load(path, members=None):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f, delimiter="\t"):
            if members and r["ticker"] not in members:
                continue
            for c in MONEY:
                if c in r:
                    r[c] = num(r[c])
            rows.append(r)
    return rows


def latest(rows):
    """(ticker, fy, fp) → 최신 판."""
    out = {}
    for r in rows:
        k = (r["ticker"], r["fy"], r["fp"])
        if k not in out or r["pub"] >= out[k]["pub"]:
            out[k] = r
    return out


def by_label(rows):
    d = collections.defaultdict(list)
    for r in rows:
        d[(r["ticker"], r["fy"], r["fp"])].append(r)
    for v in d.values():
        v.sort(key=lambda r: r["pub"])
    return d


def asof(vs, t):
    best = None
    for r in vs:
        if r["pub"] <= t:
            best = r
    return best


def close(a, b, rel=0.01, floor=1.0):
    return abs(a - b) <= rel * max(abs(a), abs(b), floor)


def check(rows, since="0000"):
    lat = latest(rows)
    lab = by_label(rows)
    out = {"B": set(), "G": set(), "Q": set(), "L": set()}
    cov = collections.Counter()
    for k, r in lat.items():
        if r["fy"] < since:
            continue
        for c in MONEY:
            if r.get(c) is not None:
                cov[c] += 1
        a, l, e = r.get("totalAssets"), r.get("totalLiabilities"), r.get("totalEquity")
        if a is not None and l is not None and e is not None and a - l - e < -1.5e6:
            out["B"].add((k, "balance"))
        rv, cs, gp = r.get("revenue"), r.get("costOfSales"), r.get("grossProfit")
        if rv is not None and cs is not None and gp is not None and not close(rv - cs, gp, floor=1e6):
            out["G"].add((k, "gross"))
    # Q4 시점 정합 · 최신 정합
    for (t, fy, fp), vs in lab.items():
        if fp != "Q4" or fy < since:
            continue
        fyv = lab.get((t, fy, "FY"))
        qs = [lab.get((t, fy, q)) for q in ("Q1", "Q2", "Q3")]
        if not fyv or any(q is None for q in qs):
            continue
        for q4 in vs:
            tt = q4["pub"]
            F = asof(fyv, tt)
            Q = [asof(q, tt) for q in qs]
            if F is None or any(x is None for x in Q):
                continue
            for c in FLOWS:
                if q4.get(c) is None or F.get(c) is None or any(x.get(c) is None for x in Q):
                    continue
                r = F[c] - sum(x[c] for x in Q)
                if not close(q4[c], r, floor=1e6):
                    out["Q"].add(((t, fy, "Q4"), c, tt))
        q4 = vs[-1]
        F = fyv[-1]
        Q = [q[-1] for q in qs]
        if q4["pub"] < "2024-09-26":
            continue
        for c in LFLOWS:
            if q4.get(c) is None or F.get(c) is None or any(x.get(c) is None for x in Q):
                continue
            r = F[c] - sum(x[c] for x in Q)
            if not close(q4[c], r, floor=1):
                out["L"].add(((t, fy, "Q4"), c))
    return out, cov


def main():
    path = sys.argv[1]
    members = None
    if len(sys.argv) > 2 and not sys.argv[2].startswith("--"):
        members = set(l.split("|")[0].strip() for l in open(sys.argv[2]))
    rows = load(path, members)
    inv, cov = check(rows)
    print(f"rows {len(rows)}  stocks {len({r['ticker'] for r in rows})}")
    for k, v in inv.items():
        print(f"  {k}: {len(v)} cells · {len({x[0][0] for x in v})} stocks")
    if "--diff" in sys.argv:
        other = load(sys.argv[sys.argv.index("--diff") + 1], members)
        inv2, cov2 = check(other)
        print("== after")
        for k, v in inv2.items():
            new = v - inv[k]
            gone = inv[k] - v
            print(f"  {k}: {len(v)} (new {len(new)} · fixed {len(gone)})")
        print("== coverage (latest vintages, FY>=2015) — before → after")
        for c in MONEY:
            if cov[c] != cov2[c]:
                print(f"  {c:28} {cov[c]:7} → {cov2[c]:7}  ({cov2[c] - cov[c]:+d})")
        la, lb = latest(rows), latest(other)
        changed = collections.Counter()
        for k in la.keys() & lb.keys():
            for c in MONEY:
                x, y = la[k].get(c), lb[k].get(c)
                if (x is None) != (y is None) or (x is not None and not close(x, y, rel=1e-9, floor=0.5)):
                    changed[c] += 1
        print("== latest-vintage cells changed by column:", dict(changed))
        print("== labels only in before:", len(la.keys() - lb.keys()), " only in after:", len(lb.keys() - la.keys()))
        print("== vintages before → after:", len(rows), "→", len(other))


if __name__ == "__main__":
    main()
