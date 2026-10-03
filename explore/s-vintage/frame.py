"""v7이 바꾼 칸의 표본 틀 — 원인 × 행 종류 × 편입 층과 층 크기, 그리고 시드 고정 표본.

python3 frame.py base.tsv v7.tsv origin-base.tsv origin-v7.tsv members.txt <출력 디렉터리> [--per 4]

원인(S_ORIGIN 추적으로 가른다 — 갈래를 다시 추론하지 않는다):
  fired        그 판의 v7 행에서 규칙이 값을 바꿨다(추적의 diffs가 비지 않음)
  propagated   그 판에선 규칙이 안 켜졌는데 앞 판의 차이를 이어받았다
  q4_inputs    Q4 판의 입력(FY 판 · 분기 판 셋 · 소스 Q4)이 두 쪽에서 다르다
  q4_values    Q4 입력은 같은데 입력 행의 값이 달라 잔차가 움직였다
  vintage_only 한쪽에만 있는 판(판 집합 변화) — 칸이 아니라 판 단위
"""
import collections
import csv
import os
import random
import sys


def rows_of(path):
    with open(path) as f:
        reader = csv.DictReader(f, delimiter="\t")
        cols = reader.fieldnames
        return cols, {(r["ticker"], r["fy"], r["fp"], r["pub"]): r for r in reader}


def origins_of(path):
    row, q4 = {}, {}
    for line in open(path):
        p = line.rstrip("\n").split("\t")
        t, fy, fp, pub, kind = p[:5]
        if kind == "ROW":
            row[(t, fy, fp, pub)] = {"accn": p[5], "revB": p[6], "revV": p[7], "branches": p[8], "diffs": p[9]}
        elif fp == "Q4" and kind in ("Q4", "Q4FB"):
            q4[(t, fy, "Q4", pub)] = (p[5], p[6], p[7])
    return row, q4


def main():
    a = sys.argv
    base_path, v7_path, ob_path, ov_path, members_path, out = a[1:7]
    per = int(a[a.index("--per") + 1]) if "--per" in a else 4
    members = dict(l.strip().split("|") for l in open(members_path))
    cols, base = rows_of(base_path)
    _, v7 = rows_of(v7_path)
    money = cols[cols.index("revenue"):]
    _, ob_q4 = origins_of(ob_path)
    ov_row, ov_q4 = origins_of(ov_path)
    os.makedirs(out, exist_ok=True)

    frame = []
    for key in base.keys() & v7.keys():
        t = key[0]
        if t not in members:
            continue
        b, v = base[key], v7[key]
        changed = [c for c in money if b[c] != v[c]]
        if not changed:
            continue
        fp = key[2]
        rowtype = "FY" if fp == "FY" else ("Q4" if fp == "Q4" else "Q1-3")
        if fp == "Q4":
            cause = "q4_inputs" if ob_q4.get(key) != ov_q4.get(key) else "q4_values"
            detail = ""
        else:
            o = ov_row.get(key)
            cause = "fired" if o and o["diffs"] else "propagated"
            detail = o["branches"] if o else ""
        for c in changed:
            frame.append((t, members[t], key[1], fp, key[3], rowtype, cause, detail, c, b[c], v[c]))
    for side, here, there in (("base_only", base, v7), ("v7_only", v7, base)):
        for key in here.keys() - there.keys():
            t = key[0]
            if t not in members:
                continue
            fp = key[2]
            rowtype = "FY" if fp == "FY" else ("Q4" if fp == "Q4" else "Q1-3")
            frame.append((t, members[t], key[1], fp, key[3], rowtype, "vintage_only", side, "*", "", ""))

    with open(os.path.join(out, "frame.tsv"), "w") as w:
        w.write("ticker\tgroup\tfy\tfp\tpub\trowtype\tcause\tdetail\tcolumn\tbase\tv7\n")
        for r in sorted(frame):
            w.write("\t".join(r) + "\n")

    strata = collections.defaultdict(list)
    for r in frame:
        strata[(r[6], r[5], r[1])].append(r)
    by_col = collections.Counter((r[6], r[8]) for r in frame)
    rng = random.Random(20261004)
    sample = []
    with open(os.path.join(out, "strata.tsv"), "w") as w:
        w.write("cause\trowtype\tgroup\tcells\tstocks\tsampled\n")
        for k in sorted(strata):
            cells = sorted(strata[k])
            rng.shuffle(cells)
            seen, picked = set(), []
            for r in cells:
                if r[0] in seen:
                    continue
                seen.add(r[0])
                picked.append(r)
                if len(picked) == per:
                    break
            sample += picked
            w.write(f"{k[0]}\t{k[1]}\t{k[2]}\t{len(cells)}\t{len({r[0] for r in cells})}\t{len(picked)}\n")
    with open(os.path.join(out, "sample.tsv"), "w") as w:
        w.write("ticker\tgroup\tfy\tfp\tpub\trowtype\tcause\tdetail\tcolumn\tbase\tv7\n")
        for r in sample:
            w.write("\t".join(r) + "\n")
    print(open(os.path.join(out, "strata.tsv")).read())
    print("cells by cause x column:")
    for (cause, c), n in sorted(by_col.items()):
        print(f"  {cause:13} {c:28} {n}")
    print("sample", len(sample))


if __name__ == "__main__":
    main()
