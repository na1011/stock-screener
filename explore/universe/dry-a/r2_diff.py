"""RA0·RA2 — r0_diff에 숨은 상장 갈래(라)를 더한 판. 경계(다)는 숨은 상장 진입 수만큼만 귀속한다(A2 v3 반증: 경계 밀림 수 = 경계 위 숨은 진입 수)."""
import re, sys, collections
LINE = re.compile(r"재생 대조\. US_TOP1000 (\S+) 관측=(\d+) 복원=(\d+) 재현율=(.+?) 놓침=\[(.*?)\] 더든것=\[(.*?)\]")
SWITCH = re.compile(r"CIK 대표가 시총 최대와 갈렸다: cik=(\d+) 대표=(\S+)\(중복 제거 전 시총 (\d+)위 · 거래대금 (\d+)\) 시총 최대=(\S+)\((\d+)위 · 거래대금 (\d+)\)")
HYBRID = re.compile(r"혼성 문구에만 걸림 \[(.*?)\]")
BOUNDARY = re.compile(r"경계 구간\((\d+)~(\d+)위 · (\d+)위까지 편입\): \[(.*?)\]")
HIDDEN = re.compile(r"숨은 상장: 풀=\d+ 마지막 세션=\S+ 받음=\[(.*?)\] 마지막 세션 봉 없음")
BUILT = re.compile(r"미국 시총 상위 유니버스를 만들었다\. US_TOP1000 기준일=(\S+)")

def split(s): return [t.strip() for t in s.split(",") if t.strip()]

def parse(path):
    recalls, per_date = {}, collections.defaultdict(lambda: {"switch": [], "hybrid": [], "boundary": []})
    cur, pending, pending_hidden = None, [], []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = SWITCH.search(line)
        if m: pending.append(m.groups())
        m = HIDDEN.search(line)
        if m: pending_hidden = [x.split("(")[0].strip() for x in split(m.group(1))]
        m = BUILT.search(line)
        if m:
            cur = m.group(1)
            per_date[cur]["switch"].extend(pending); pending = []
            per_date[cur]["hidden"] = pending_hidden; pending_hidden = []
        m = HYBRID.search(line)
        if m and cur: per_date[cur]["hybrid"] = split(m.group(1))
        m = BOUNDARY.search(line)
        if m and cur:
            per_date[cur]["boundary"] = [x.split("=")[0] for x in split(m.group(4))]
            per_date[cur]["boundary_from"] = int(m.group(1))
        m = LINE.search(line)
        if m:
            d, obs, rest, rec, missed, extra = m.groups()
            recalls[d] = dict(obs=int(obs), rest=int(rest), rec=rec, missed=set(split(missed)), extra=set(split(extra)))
    return recalls, per_date

import json
CIK = {}
for row in json.load(open(sys.argv[3]))["data"]:
    CIK[row[2]] = row[0]
def cik(t): return CIK.get(t.replace(".", "-"))
a, _ = parse(sys.argv[1])
c, cmeta = parse(sys.argv[2])
total_unattributed = 0
for d in sorted(set(a) | set(c)):
    A, C = a[d], c[d]
    added = (A["missed"] - C["missed"]) | (C["extra"] - A["extra"])
    removed = (C["missed"] - A["missed"]) | (A["extra"] - C["extra"])
    meta = cmeta.get(d, {"switch": [], "hybrid": [], "boundary": []})
    sw_new = {s[1] for s in meta["switch"]}; sw_old = {s[4] for s in meta["switch"]}
    hybrid = set(meta["hybrid"])
    hybrid_ciks = {cik(h) for h in hybrid if cik(h)}
    boundary = set(meta.get("boundary", []))
    hidden = set(meta.get("hidden", []))
    att_added, att_removed, un = [], [], []
    hidden_in = 0
    for t in sorted(added):
        why = "라(숨은 상장)" if t in hidden else ("나(대표)" if t in sw_new else ("가(혼성의 보통주)" if cik(t) in hybrid_ciks else None))
        if why == "라(숨은 상장)": hidden_in += 1
        (att_added if why else un).append(f"{t}:{why}" if why else t)
    pushed = 0
    for t in sorted(removed):
        why = "가(혼성)" if t in hybrid else ("나(옛 대표)" if t in sw_old else None)
        if why is None and t in boundary and pushed < hidden_in:
            why = "다(경계 — 숨은 상장이 밀어냄)"; pushed += 1
        (att_removed if why else un).append(f"{t}:{why}" if why else t)
    total_unattributed += len(un)
    print(f"{d} 관측={A['obs']} a재현율={A['rec']} c재현율={C['rec']} | c에만 +{len(added)} · a에만 -{len(removed)} · 미귀속 {len(un)}")
    if added or removed:
        print(f"   + {att_added}\n   - {att_removed}")
    if un: print(f"   미귀속: {un}")
print("미귀속 합", total_unattributed)
