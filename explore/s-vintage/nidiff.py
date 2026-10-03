"""S_NIFIX 변형 대 기준선 — 바뀐 칸 · 판 집합 · Writer 층 도달 · 노출 · Q4 항등식."""
import collections, csv, datetime as dt, sys

S = sys.argv[1]
base_p, var_p = sys.argv[2], sys.argv[3]
mem = dict(l.strip().split("|") for l in open(f"{S}/members.txt"))
days = [l.strip() for l in open(f"{S}/us_days.txt")]
W0, W1 = days[0], days[-1]


def load(p):
    with open(p) as f:
        r = csv.DictReader(f, delimiter="\t")
        cols = r.fieldnames
        return cols, {(x["ticker"], x["fy"], x["fp"], x["pub"]): x for x in r if x["ticker"] in mem}


cols, B = load(base_p)
_, V = load(var_p)
money = cols[cols.index("revenue"):]
num = lambda x: float(x) if x else None

print("판 키: 기준선에만", len(B.keys() - V.keys()), "· 변형에만", len(V.keys() - B.keys()))
chg = collections.Counter()
cls = collections.Counter()
ex = collections.defaultdict(list)
for k in B.keys() & V.keys():
    b, v = B[k], V[k]
    for c in money:
        if b[c] != v[c]:
            chg[c] += 1
    if b["netIncomeControlling"] != v["netIncomeControlling"]:
        x, y = num(b["netIncomeControlling"]), num(v["netIncomeControlling"])
        kind = "null→값" if x is None else ("값→null" if y is None else "값→값")
        writer = ("파생행(덮고 지움)" if v["derived"] == "true"
                  else ("소스행 값→null(DB 옛값 남음)" if kind == "값→null" else "소스행 덮음"))
        fp = "Q4" if k[2] == "Q4" else ("FY" if k[2] == "FY" else "Q1-3")
        flip = x is not None and y is not None and (x > 0) != (y > 0)
        cls[(fp, kind, writer, "부호반전" if flip else "")] += 1
        if len(ex[(fp, kind)]) < 4:
            ex[(fp, kind)].append((k, x, y, b["netIncome"]))
print("바뀐 칸(컬럼별):", dict(chg))
for k in sorted(cls):
    print("  ", k, cls[k])
for k, v in ex.items():
    print("  예", k, v)


def labels(D):
    lab = collections.defaultdict(list)
    for k, r in D.items():
        lab[k[:3]].append(r)
    for v in lab.values():
        v.sort(key=lambda r: r["pub"])
    return lab


def at(v, day):
    best = None
    for r in v:
        if r["pub"] <= day:
            best = r
    return best


def q4_check(D, name):
    lab = labels(D)
    wrong, empty = set(), set()
    for (t, fy, fp), v in lab.items():
        if fp != "Q4" or v[-1]["end"] < "2020-07-01":
            continue
        parts = [lab.get((t, fy, q)) for q in ("FY", "Q1", "Q2", "Q3")]
        if any(p is None for p in parts):
            continue
        for day in (W0, W1):
            q4 = at(v, day)
            rows = [at(p, day) for p in parts]
            if q4 is None or any(r is None for r in rows):
                continue
            vals = [num(r["netIncomeControlling"]) for r in rows]
            if None in vals:
                continue
            q = num(q4["netIncomeControlling"])
            res = vals[0] - sum(vals[1:])
            if q is None:
                empty.add((t, fy))
            elif abs(q - res) > max(0.01 * max(abs(q), abs(res)), 1e6):
                wrong.add((t, fy))
    print(f"{name}: Q4 지배주주 ≠ FY − ΣQ {len(wrong)}/{len({x[0] for x in wrong})} · 빈 칸 {len(empty)}/{len({x[0] for x in empty})}")
    return wrong


wb = q4_check(B, "기준선")
wv = q4_check(V, "변형")
left = sorted(wv)[:12]
print("변형에 남은 예:", left)
