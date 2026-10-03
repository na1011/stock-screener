"""사례 카드 — 판정하지 않는다. 판정 재료만 보인다(FINDINGS 2a절 프로토콜).

python3 card.py sample.tsv base.tsv v7.tsv origin-base.tsv origin-v7.tsv <factcache> <출력 디렉터리> [--known 목록.tsv]

카드마다 두 쪽(기준선 · v7)의 「그 판 공시일에 보이는 행」을 X/Y로 무작위 배정해 보이고, 그 기간(Q4면 그 회계연도의
FY·분기·누계 창까지)의 원장 팩트를 공시일 순으로 보인다 — 판 공시일 이하와 사후(그 뒤)를 갈라서.
키(X/Y가 어느 쪽인지 · 출처 추적 · DB 행 · 노출 구간)는 keys.tsv에 따로 쓴다. 판정을 적은 뒤에 연다.
"""
import collections
import csv
import datetime as dt
import json
import os
import random
import subprocess
import sys

INCOME_TAGS = ["Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax", "SalesRevenueNet",
               "SalesRevenueGoodsNet", "RevenueFromContractWithCustomerIncludingAssessedTax",
               "CostOfGoodsAndServicesSold", "CostOfRevenue", "CostOfGoodsSold", "CostOfSales",
               "GrossProfit", "OperatingIncomeLoss", "OperatingExpenses", "CostsAndExpenses",
               "BenefitsLossesAndExpenses", "NetIncomeLoss", "ProfitLoss"]
BALANCE_TAGS = ["Assets", "Liabilities", "LiabilitiesAndStockholdersEquity",
                "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest", "StockholdersEquity",
                "MinorityInterest", "RedeemableNoncontrollingInterestEquityCarryingAmount",
                "TemporaryEquityCarryingAmountAttributableToParent"]
INCOME_COLS = ["revenue", "costOfSales", "grossProfit", "operatingIncome", "operatingExpenses",
               "costsAndExpenses", "benefitsLossesAndExpenses", "netIncome"]
BALANCE_COLS = ["totalAssets", "totalLiabilities", "totalEquity", "totalEquityControlling"]
DB_COLS = {"revenue": "revenue", "costOfSales": "cost_of_sales", "grossProfit": "gross_profit",
           "operatingIncome": "operating_income", "operatingExpenses": "operating_expenses",
           "costsAndExpenses": "costs_and_expenses", "benefitsLossesAndExpenses": "benefits_losses_and_expenses",
           "netIncome": "net_income", "totalAssets": "total_assets", "totalLiabilities": "total_liabilities",
           "totalEquity": "total_equity", "totalEquityControlling": "total_equity_controlling"}
WINDOW = (dt.date(2025, 9, 26), dt.date(2026, 10, 1))


def d(s):
    return dt.date.fromisoformat(s) if s else None


def m(x):
    if x in (None, ""):
        return "·"
    return f"{float(x) / 1e6:,.1f}"


def tsv(path):
    with open(path) as f:
        return list(csv.DictReader(f, delimiter="\t"))


def by_label(rows):
    out = collections.defaultdict(list)
    for r in rows:
        out[(r["ticker"], r["fy"], r["fp"])].append(r)
    for v in out.values():
        v.sort(key=lambda r: r["pub"])
    return out


def visible(vs, pub):
    best = None
    for r in vs:
        if r["pub"] <= pub:
            best = r
    return best


def next_pub(vs, pub):
    for r in vs:
        if r["pub"] > pub:
            return r["pub"]
    return None


def exposure(vs, row):
    """그 판이 미국 지표 창 안에서 「라벨별 보이는 판」인 구간."""
    if row is None:
        return "없음"
    start = max(d(row["pub"]), WINDOW[0])
    nxt = next_pub(vs, row["pub"])
    end = min(d(nxt) - dt.timedelta(days=1), WINDOW[1]) if nxt else WINDOW[1]
    return f"{start}~{end}" if start <= end else "노출 없음"


def origin_index(path):
    idx = {}
    for line in open(path):
        p = line.rstrip("\n").split("\t")
        if p[4] in ("ROW", "Q4", "Q4FB"):
            idx[(p[0], p[1], p[2], p[3])] = "\t".join(p[4:])[:600]
    return idx


def ledger(cache, ticker, windows, tags):
    facts = json.load(open(os.path.join(cache, ticker + ".json")))["facts"].get("us-gaap", {})
    out = []
    for tag in tags:
        for f in facts.get(tag, {}).get("units", {}).get("USD", []):
            s, e = d(f.get("start")), d(f["end"])
            for (ws, we) in windows:
                if ws is None and s is None and e == we:
                    out.append((f["filed"], f["form"], f["accn"], tag, None, e, f["val"]))
                elif ws is not None and s is not None and abs((s - ws).days) <= 7 and abs((e - we).days) <= 7:
                    out.append((f["filed"], f["form"], f["accn"], tag, s, e, f["val"]))
    return sorted(set(out))


def db_row(ticker, fy, fp, pub):
    cols = ",".join(DB_COLS.values())
    q = (f"SELECT {cols}, is_latest FROM financial_statement fs JOIN stock s ON s.id=fs.stock_id "
         f"WHERE s.ticker='{ticker}' AND s.market_code='US' AND fs.fiscal_year={int(fy)} "
         f"AND fs.fiscal_period='{fp}' AND fs.published_at='{pub}'")
    r = subprocess.run(["psql", "-d", "stock_screener", "-At", "-F", "\t", "-c", q],
                       capture_output=True, text=True, check=True)
    return r.stdout.strip()


def main():
    a = sys.argv
    sample_path, base_path, v7_path, ob_path, ov_path, cache, out = a[1:8]
    os.makedirs(out, exist_ok=True)
    base, v7 = by_label(tsv(base_path)), by_label(tsv(v7_path))
    ob, ov = origin_index(ob_path), origin_index(ov_path)
    units = tsv(sample_path)
    if "--known" in a:
        units += tsv(a[a.index("--known") + 1])
    rng = random.Random(20261004)
    keys = open(os.path.join(out, "keys.tsv"), "w")
    keys.write("card\tX\tY\tticker\tfy\tfp\tpub\tcolumn\tcause\texposure_base\texposure_v7\t"
               "origin_base\torigin_v7\tdb_row\n")
    for n, u in enumerate(units, 1):
        t, fy, fp, pub, col = u["ticker"], u["fy"], u["fp"], u["pub"], u["column"]
        label = (t, fy, fp)
        rb, rv = visible(base.get(label, []), pub), visible(v7.get(label, []), pub)
        x_is_base = rng.random() < 0.5
        X, Y = (rb, rv) if x_is_base else (rv, rb)
        ref = rb or rv
        start, end = d(ref["start"]), d(ref["end"])
        balance = col in BALANCE_COLS
        cols = BALANCE_COLS if balance else INCOME_COLS
        if col == "*":
            cols = INCOME_COLS + BALANCE_COLS
        windows = [(None, end)] if balance else [(start, end)]
        if col == "*":
            windows = [(start, end), (None, end)]
        if fp in ("Q1", "Q2", "Q3") and not balance:
            # 분기는 그 회계연도의 누계 창도 보인다 — 재작성이 누계 비교열에만 드러나는 일이 있다(BDX)
            fyrows = base.get((t, fy, "FY"), []) or v7.get((t, fy, "FY"), [])
            if fyrows:
                windows.append((d(fyrows[-1]["start"]), end))
        if fp == "Q4" or (not balance and fp == "FY"):
            # Q4·FY는 그 회계연도의 FY 창과 분기·누계 창까지 보인다
            fyrows = base.get((t, fy, "FY"), []) or v7.get((t, fy, "FY"), [])
            if fyrows:
                fs, fe = d(fyrows[-1]["start"]), d(fyrows[-1]["end"])
                windows.append((fs, fe))
                for q in ("Q1", "Q2", "Q3"):
                    qr = base.get((t, fy, q), []) or v7.get((t, fy, q), [])
                    if qr:
                        windows.append((d(qr[-1]["start"]), d(qr[-1]["end"])))
                        windows.append((fs, d(qr[-1]["end"])))
        tags = (BALANCE_TAGS if balance else INCOME_TAGS) if col != "*" else INCOME_TAGS + BALANCE_TAGS
        facts = ledger(cache, t, sorted(set(windows), key=lambda w: (w[1], w[0] or w[1])), tags)
        lines = [f"# 카드 {n:03d} — {t} {fy}/{fp} 창 {start}~{end} · 판 공시일 {pub} · 대상 컬럼 {col}",
                 f"# 층: {u.get('cause', 'known')} · {u.get('rowtype', '')} · {u.get('group', '')}", "",
                 "## 두 쪽이 그 판 공시일에 보이는 행(백만 달러)", "| 컬럼 | X | Y |", "|---|---|---|"]
        lines.append(f"| (판 공시일) | {X['pub'] if X else '없음'} | {Y['pub'] if Y else '없음'} |")
        for c in cols:
            mark = " ◀" if c == col else ""
            lines.append(f"| {c}{mark} | {m(X[c]) if X else '—'} | {m(Y[c]) if Y else '—'} |")
        for side, r in (("X", X), ("Y", Y)):
            if r and not balance:
                rv_, cs, gp = (float(r[k]) if r[k] else None for k in ("revenue", "costOfSales", "grossProfit"))
                if None not in (rv_, cs, gp):
                    lines.append(f"- {side} 매출 − 원가 − 총이익 = {(rv_ - cs - gp) / 1e6:,.1f}")
            if r and (balance or col == "*"):
                aa, ll, ee = (float(r[k]) if r[k] else None for k in ("totalAssets", "totalLiabilities", "totalEquity"))
                if None not in (aa, ll, ee):
                    lines.append(f"- {side} 자산 − 부채 − 자본 = {(aa - ll - ee) / 1e6:,.1f}")
        for title, cond in (("원장 — 판 공시일 이하", lambda f: f[0] <= pub), ("원장 — 사후(판정 근거 아님)", lambda f: f[0] > pub)):
            lines += ["", f"## {title}", "| 공시일 | 폼 | accn | 태그 | 창 | 값 |", "|---|---|---|---|---|---|"]
            for f in facts:
                if cond(f):
                    w = f"{f[4]}~{f[5]}" if f[4] else f"@{f[5]}"
                    lines.append(f"| {f[0]} | {f[1]} | {f[2][-9:]} | {f[3]} | {w} | {f[6] / 1e6:,.1f} |")
        with open(os.path.join(out, f"card-{n:03d}.md"), "w") as w:
            w.write("\n".join(lines) + "\n")
        keys.write("\t".join([f"{n:03d}", "base" if x_is_base else "v7", "v7" if x_is_base else "base",
                              t, fy, fp, pub, col, u.get("cause", "known"),
                              exposure(base.get(label, []), rb), exposure(v7.get(label, []), rv),
                              ob.get((t, fy, fp, rb["pub"] if rb else ""), "").replace("\t", " | "),
                              ov.get((t, fy, fp, rv["pub"] if rv else ""), "").replace("\t", " | "),
                              db_row(t, fy, fp, pub).replace("\t", ",")]) + "\n")
    keys.close()
    print("cards", len(units), "→", out)


if __name__ == "__main__":
    main()
