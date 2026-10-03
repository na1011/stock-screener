"""원장 현상 카탈로그 — 판정을 담지 않은 검출기로 companyfacts 캐시 전수를 센다. DB·외부 호출 없음.

python3 catalog.py <factcache 디렉터리> <members.txt> <출력 디렉터리> [--tickers A,B]

검출기 이름은 현상만 말한다(「거짓양성」·「재작성」 같은 판정을 담지 않는다). 컬럼값은 저장 개념의 정의
(SecEdgarColumn의 후보 목록과 LARGEST 선택)로 **공시마다** 고른다 — 판 조립(이어받기)은 흉내 내지 않는다.
출력: summary.tsv(검출기 × 편입 × 칸·종목 수) · events-<검출기>.tsv(전 사건 — 열어 볼 표본은 여기서 뽑는다).
"""
import collections
import datetime as dt
import json
import os
import random
import sys

COLUMNS = {
    "revenue": (["Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax", "SalesRevenueNet",
                 "SalesRevenueGoodsNet"], ["RevenueFromContractWithCustomerIncludingAssessedTax"], True),
    "costOfSales": (["CostOfGoodsAndServicesSold", "CostOfRevenue", "CostOfGoodsSold", "CostOfSales"], [], True),
    "grossProfit": (["GrossProfit"], [], False),
    "operatingIncome": (["OperatingIncomeLoss"], [], False),
    "netIncome": (["NetIncomeLoss"], [], False),
    "totalAssets": (["Assets"], [], False),
    "totalLiabilities": (["Liabilities"], [], False),
    "totalEquity": (["StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest",
                     "StockholdersEquity"], [], False),
}
INSTANT = {"totalAssets", "totalLiabilities", "totalEquity"}
INCOME = ["revenue", "costOfSales", "grossProfit", "operatingIncome", "netIncome"]
ANNUAL_FORMS = {"10-K", "10-K/A", "20-F", "20-F/A", "40-F", "40-F/A", "10-KT", "10-KT/A"}
FROM = dt.date(2015, 1, 1)


def d(s):
    return dt.date.fromisoformat(s)


def kind_of(start, end):
    if start is None:
        return "I"
    n = (end - start).days
    if 80 <= n <= 100:
        return "Q"
    if 170 <= n <= 190:
        return "H"
    if 260 <= n <= 285:
        return "N"
    if 350 <= n <= 380:
        return "F"
    return "?"


def rel(a, b):
    m = max(abs(a), abs(b))
    return 0.0 if m == 0 else abs(a - b) / m


def load(path):
    """(col, start, end) → [(filed, accn, form, tag, val)] — 공시마다 저장 개념 규칙으로 고른 값 하나."""
    facts = json.load(open(path))["facts"].get("us-gaap", {})
    per = collections.defaultdict(dict)   # (col, start, end, accn) → {tag: (val, filed, form)}
    for col, (cands, fallback, largest) in COLUMNS.items():
        for tag in cands + fallback:
            node = facts.get(tag)
            if not node:
                continue
            for f in node.get("units", {}).get("USD", []):
                end = d(f["end"])
                if end < FROM:
                    continue
                start = d(f["start"]) if "start" in f else None
                if (col in INSTANT) != (start is None):
                    continue
                per[(col, start, end, f["accn"])][tag] = (f["val"], d(f["filed"]), f.get("form", ""))
    out = collections.defaultdict(list)
    for (col, start, end, accn), tags in per.items():
        cands, fallback, largest = COLUMNS[col]
        pick = None
        if largest:
            comp = [(t, tags[t]) for t in cands if t in tags]
            if comp:
                pick = max(comp, key=lambda x: x[1][0])
            else:
                fb = [(t, tags[t]) for t in fallback if t in tags]
                pick = fb[0] if fb else None
        else:
            for t in cands:
                if t in tags:
                    pick = (t, tags[t])
                    break
        if pick:
            tag, (val, filed, form) = pick
            out[(col, start, end)].append((filed, accn, form, tag, val))
    for v in out.values():
        v.sort()
    return out


def detect(ticker, data, emit):
    # 공시(accn) → 공시일·폼, 그리고 FY 창.
    filings = {}
    fy_windows = set()
    for (col, s, e), rows in data.items():
        for filed, accn, form, tag, val in rows:
            filings[accn] = (filed, form)
            if s is not None and kind_of(s, e) == "F":
                fy_windows.add((s, e))

    # K1 같은 태그 재게재 1% 초과 · K1a 그중 절대차 100만 미만 · K4 태그 전환 + 값 변화 / 값 같음
    for (col, s, e), rows in data.items():
        k = kind_of(s, e) if s else "I"
        for (f1, a1, fm1, t1, v1), (f2, a2, fm2, t2, v2) in zip(rows, rows[1:]):
            if a1 == a2:
                continue
            r = rel(v1, v2)
            if t1 == t2 and r > 0.01:
                emit("K1_same_tag_gt1pct", ticker, col, s, e, k, f2, a2, f"{v1}>{v2}", f"{r:.4f}")
                if abs(v1 - v2) < 1e6:
                    emit("K1a_same_tag_gt1pct_abs_lt1M", ticker, col, s, e, k, f2, a2, f"{v1}>{v2}", f"{r:.4f}")
            if t1 != t2:
                name = "K4_tag_switch_value_change" if r > 0.01 else "K4b_tag_switch_same_value"
                emit(name, ticker, col, s, e, k, f2, a2, f"{t1}:{v1}>{t2}:{v2}", f"{r:.4f}")

    # K2 표(손익) 안에서 한 컬럼만 1% 초과 변화 — 그 공시가 다시 실은 컬럼 둘 이상 중 하나만 바뀜
    windows = {(s, e) for (col, s, e) in data if s is not None and col in INCOME}
    for (s, e) in windows:
        accns = sorted({(r[0], r[1]) for col in INCOME for r in data.get((col, s, e), [])})
        for filed, accn in accns:
            changed, same = [], []
            for col in INCOME:
                rows = data.get((col, s, e), [])
                mine = [r for r in rows if r[1] == accn]
                prev = [r for r in rows if r[0] < filed]
                if not mine or not prev:
                    continue
                if mine[0][3] != prev[-1][3]:
                    continue   # 태그가 바뀐 것은 K4가 센다
                (changed if rel(mine[0][4], prev[-1][4]) > 0.01 else same).append(col)
            if len(changed) == 1 and same:
                emit("K2_one_income_column_gt1pct", ticker, changed[0], s, e, kind_of(s, e), filed, accn,
                     "same=" + ",".join(same), "")

    # K3 FY 공시 뒤 누계·분기 비교열이 FY 공시 시점까지의 마지막 게재값과 1% 초과 다름
    fy_filed = {}
    for (col, s, e), rows in data.items():
        if s is not None and kind_of(s, e) == "F":
            for filed, accn, form, tag, val in rows:
                if form in ANNUAL_FORMS:
                    fy_filed[(s, e)] = min(fy_filed.get((s, e), filed), filed)
    for (col, s, e), rows in data.items():
        if s is None or col not in INCOME or kind_of(s, e) not in ("Q", "H", "N"):
            continue
        fy = [w for w in fy_windows if abs((w[0] - s).days) <= 7 and w[1] >= e and w in fy_filed]
        if not fy or e == fy[0][1]:
            continue
        t = fy_filed[fy[0]]
        before = [r for r in rows if r[0] <= t]
        after = [r for r in rows if r[0] > t]
        if before and after and before[-1][3] == after[0][3] and rel(before[-1][4], after[0][4]) > 0.01:
            emit("K3_comparative_after_FY_differs", ticker, col, s, e, kind_of(s, e), after[0][0], after[0][1],
                 f"{before[-1][4]}>{after[0][4]}", f"FY10K={t}")

    # K5 같은 공시 안 매출 − 원가 − 총이익 항등식(셋 다 실은 창) — 성립/불성립
    by_filing = collections.defaultdict(dict)
    for col in ("revenue", "costOfSales", "grossProfit"):
        for (c, s, e), rows in data.items():
            if c != col:
                continue
            for filed, accn, form, tag, val in rows:
                by_filing[(accn, s, e)][col] = (val, filed)
    coreported, holds = 0, 0
    for (accn, s, e), cols in by_filing.items():
        if len(cols) == 3:
            R, C, G = cols["revenue"][0], cols["costOfSales"][0], cols["grossProfit"][0]
            ok = abs(R - C - G) <= max(0.01 * abs(R), 1e6)
            coreported += 1
            holds += ok
            emit("K5_identity_in_filing_" + ("holds" if ok else "fails"), ticker, "gross", s, e,
                 kind_of(s, e), cols["revenue"][1], accn, f"{R}-{C}-{G}={R - C - G}", "")
    # 회사 단위 — 원가와 총이익을 둘 다 실은 적이 있는 회사만(둘 중 하나만 내는 회사는 항등식이 적용 대상 밖)
    has_cost = any(c == "costOfSales" for (c, s, e) in data)
    has_gp = any(c == "grossProfit" for (c, s, e) in data)
    if has_cost and has_gp:
        status = "never_coreported" if coreported == 0 else ("never_holds" if holds == 0
                 else ("always_holds" if holds == coreported else "mixed"))
        emit("K5c_company_" + status, ticker, "gross", None, "", "", "", "", f"{holds}/{coreported}", "")

    # K6 같은 컬럼·같은 태그, 시작이나 끝이 1~7일 다른 창이 1% 초과 다름
    by_col = collections.defaultdict(list)
    for (col, s, e), rows in data.items():
        if s is not None:
            by_col[col].append((s, e, rows[-1]))
    for col, ws in by_col.items():
        ws.sort()
        for i, (s1, e1, r1) in enumerate(ws):
            for s2, e2, r2 in ws[i + 1:]:
                if (s2 - s1).days > 7:
                    break
                if (s1, e1) == (s2, e2) or abs((e2 - e1).days) > 7 or kind_of(s1, e1) != kind_of(s2, e2):
                    continue
                if r1[3] == r2[3] and rel(r1[4], r2[4]) > 0.01:
                    emit("K6_window_off_1to7d_gt1pct", ticker, col, s1, e1, kind_of(s1, e1), r2[0], r2[1],
                         f"{s2}~{e2}:{r1[4]}>{r2[4]}", "")

    # K7 총이익 값 = 같은 창 다른 공시의 원가 값(0.1%) 이면서 같은 창 다른 공시 총이익과 1% 초과 다름
    for (col, s, e), rows in data.items():
        if col != "grossProfit":
            continue
        costs = data.get(("costOfSales", s, e), [])
        for filed, accn, form, tag, val in rows:
            others = [r for r in rows if r[1] != accn]
            if any(c[1] != accn and val != 0 and rel(val, c[4]) <= 0.001 for c in costs) \
                    and others and all(rel(val, o[4]) > 0.01 for o in others):
                emit("K7_gp_equals_other_filing_cost", ticker, col, s, e, kind_of(s, e), filed, accn, f"{val}", "")

    # K8 같은 공시 안 3개월 창(FY 끝과 같은 끝) 값 = 그 공시의 FY 값(0.5%)
    for (col, s, e), rows in data.items():
        if s is None or kind_of(s, e) != "Q":
            continue
        fyw = [w for w in fy_windows if w[1] == e]
        if not fyw:
            continue
        fyrows = {r[1]: r[4] for r in data.get((col, fyw[0][0], fyw[0][1]), [])}
        for filed, accn, form, tag, val in rows:
            fv = fyrows.get(accn)
            if fv and val != 0 and rel(val, fv) <= 0.005:
                emit("K8_q4_context_equals_FY", ticker, col, s, e, "Q", filed, accn, f"{val}={fv}", "")

    # K9 자본만 재게재 — 그 시점 잔액을 다시 실은 공시가 자산을 안 싣고 자본이 1% 초과 다름
    for (col, s, e), rows in data.items():
        if col != "totalEquity":
            continue
        assets = {r[1] for r in data.get(("totalAssets", None, e), [])}
        for (f1, a1, fm1, t1, v1), (f2, a2, fm2, t2, v2) in zip(rows, rows[1:]):
            if a2 not in assets and t1 == t2 and rel(v1, v2) > 0.01:
                emit("K9_equity_only_restated", ticker, col, None, e, "I", f2, a2, f"{v1}>{v2}", "")

    # K10 Q4 3개월 창(FY 끝과 같은 끝) — 끝 + 300일 뒤 공시가 다시 실었는가
    for (col, s, e), rows in data.items():
        if s is None or col != "revenue" or kind_of(s, e) != "Q" or not any(w[1] == e for w in fy_windows):
            continue
        late = any(r[0] > e + dt.timedelta(days=300) for r in rows)
        emit("K10_q4_rereported_" + ("yes" if late else "no"), ticker, col, s, e, "Q", rows[0][0], rows[0][1],
             "", str(e.year))


def main():
    cache, members_path, outdir = sys.argv[1], sys.argv[2], sys.argv[3]
    only = None
    if "--tickers" in sys.argv:
        only = set(sys.argv[sys.argv.index("--tickers") + 1].split(","))
    members = {}
    for line in open(members_path):
        t, k = line.strip().split("|")
        members[t] = k
    os.makedirs(outdir, exist_ok=True)
    files = {}
    counts = collections.defaultdict(lambda: [0, set()])
    scanned = collections.Counter()

    def emit(name, ticker, col, s, e, k, filed, accn, detail, extra):
        group = members.get(ticker, "other")
        if name not in files:
            files[name] = open(os.path.join(outdir, f"events-{name}.tsv"), "w")
        files[name].write("\t".join(str(x) for x in (ticker, group, col, s or "", e, k, filed, accn, detail, extra)) + "\n")
        c = counts[(name, group)]
        c[0] += 1
        c[1].add(ticker)

    for t in sorted(members):
        if only and t not in only:
            continue
        p = os.path.join(cache, t + ".json")
        if not os.path.exists(p):
            scanned["missing_" + members[t]] += 1
            continue
        scanned[members[t]] += 1
        detect(t, load(p), emit)
    for f in files.values():
        f.close()
    with open(os.path.join(outdir, "summary.tsv"), "w") as w:
        w.write("detector\tgroup\tevents\tstocks\n")
        for (name, group), (n, ts) in sorted(counts.items()):
            w.write(f"{name}\t{group}\t{n}\t{len(ts)}\n")
    print("scanned", dict(scanned))
    print(open(os.path.join(outdir, "summary.tsv")).read())


if __name__ == "__main__":
    random.seed(20261004)
    main()
