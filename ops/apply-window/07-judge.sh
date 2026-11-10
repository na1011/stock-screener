#!/bin/bash
# 사용: 07-judge.sh <운영 후 dir> <운영 전 dir> [<작은 DB 후 dir>]   (dir은 $A 기준 상대 또는 절대 · 02-snapshot.sh가 만든 것)
# 하드 단언(계획 v2 결정 8·12) — 하나라도 서지 않으면 1로 끝난다:
#   ① 국내 불변(재무 해시 · 지표 묶음 — 국내 관문을 같은 창에서 돌렸으면 귀속 재료)   ② ADJ_CLOSE 감소는 귀속이 있는 것뿐
#   ③ 표본 동일성 — 작은 DB(G3)와 운영이 같은 날 같은 코드로 쓴 표본 종목의 재무 행 서명이 같다. 다르면 두 실행 사이 새 공시로
#      귀속해 $A/sample-attributed.tsv(티커<TAB>근거 — 그 종목의 새 접수번호·filingDate)에 적는다. 귀속 없는 차이 1건이면 실패.
#   ④ 펜스 귀속 — 전/후에 출력이 바뀐 전제마다 $A/fence-attribution.tsv(전제<TAB>묶음·근거)에 귀속이 있다.
#   ⑤ 종목 × 지표 귀속 — groups.tsv(종목 · 지표 · 칸 수 · 값 해시)를 전/후 대조해 바뀐 묶음마다 칸 감소 · 값 → 값(칸 같고 해시 다름) · 값 생김을
#      가르고 $A/indicator-changes.tsv(시장<TAB>지표<TAB>티커<TAB>종류<TAB>전 칸<TAB>후 칸)에 전부 쓴다. 각 줄은 $A/indicator-attribution.tsv
#      (시장<TAB>지표<TAB>티커<TAB>근거)에 귀속이 있어야 한다 — (시장, 지표) 한 줄이 종목 하나의 가짜를 덮는 「묶어서 판정」을 도구가 막는다
#      (2026-11-03 미국 관문: 시장 문턱으로는 GME가 코드당 0.1%라 안 보였다). ADJ_CLOSE가 온전한데 한 지표를 80% 넘게 잃은 종목은 「전멸」로 따로 찍는다.
#   ⑥ 미국 불변 — ①의 대칭(미국 재무 해시 · 미국 지표 묶음). 미국 관문을 이 창에서 돌렸으면(US_GATE_RAN=1) 귀속 재료다.
# 프리셋·순위·명부 차이는 귀속 재료로 요약만 낸다(판정이 아니다 — 관문은 값을 바꾸러 도는 것이다).
source "$(dirname "$0")/env.sh"
PA=$(abspath "$1"); PB=$(abspath "$2"); SA=""; [ -z "${3:-}" ] || SA=$(abspath "$3")
for d in "$PA" "$PB" ${SA:+"$SA"}; do for f in preset.tsv rank.tsv groups.tsv fs.tsv stock-market.tsv membership.tsv kr-fs-hash.txt us-fs-hash.txt fences-cmp.tsv adjclose-counts.tsv; do [ -s "$d/$f" ] || die "$d/${f}가 비었거나 없다"; done; done
FAIL=()
# ①은 국내 관문이 이 창에서 안 돌았을 때만 단언이다 — 같은 창에서 국내 관문을 돌렸으면(env.sh의 KR_GATE_RAN) 국내가 바뀌는 것이
#   그 관문의 목적이라 차이를 귀속 재료로만 낸다(2026-11-03 두 관문을 한 창에서 돌렸다 · env.sh의 KR_GATE_RAN).
KR_RAN="${KR_GATE_RAN:-0}"
k1() { if [ "$KR_RAN" = 1 ]; then echo "  (국내 관문을 이 창에서 돌렸다 — $1 차이는 귀속 재료)"; else FAIL+=("$1"); fi; }
echo "===== ① 국내 불변"
cmp -s "$PB/kr-fs-hash.txt" "$PA/kr-fs-hash.txt" && echo "  같음 국내 재무 해시" || k1 "① 국내 재무 해시"
python3 - "$PB/groups.tsv" "$PA/groups.tsv" "$PA/stock-market.tsv" <<'PY' || k1 "① 국내 지표 묶음"
import sys
kr = {l.split('\t')[0] for l in open(sys.argv[3]) if l.split('\t')[1] in ('KOSPI', 'KOSDAQ')}
def load(p): return {tuple(l.split('\t')[:2]): l for l in open(p) if l.split('\t')[0] in kr}
a, b = load(sys.argv[1]), load(sys.argv[2]); d = [k for k in set(a) | set(b) if a.get(k) != b.get(k)]
print('  국내 지표 묶음 차이', len(d), sorted(d)[:5]); sys.exit(1 if d else 0)
PY
echo "===== ② ADJ_CLOSE 감소"
# 줄어든 종목은 $A/adjclose-attributed.tsv(stock_id<TAB>근거 — 02의 adjclose-counts.tsv와 같은 열쇠 — 승인된 봉 삭제 실행 번호 등)에 귀속이 있어야 한다(③ · ④와 같은 규칙).
python3 - "$PB/adjclose-counts.tsv" "$PA/adjclose-counts.tsv" "$A/adjclose-attributed.tsv" <<'PY' || FAIL+=("② ADJ_CLOSE 감소")
import sys, os
def load(p): return {l.split('\t')[0]: int(l.rstrip('\n').split('\t')[1]) for l in open(p)}
a, b = load(sys.argv[1]), load(sys.argv[2]); dec = [(k, a[k], b.get(k, 0)) for k in a if b.get(k, 0) < a[k]]
attr = {}
if os.path.exists(sys.argv[3]):
    for l in open(sys.argv[3]):
        c = l.rstrip('\n').split('\t')
        if len(c) >= 2 and c[1]: attr[c[0]] = c[1]
bad = [d for d in dec if d[0] not in attr]
print('  줄어든 종목', len(dec), dec[:10], '· 귀속 없음', bad[:10]); sys.exit(1 if bad else 0)
PY
if [ -n "$SA" ]; then
  echo "===== ③ 표본 동일성(작은 DB 후 = 운영 후 · 표본 종목)"
  python3 - "$SA/fs.tsv" "$PA/fs.tsv" "$PA/stock-market.tsv" "$A/sample-attributed.tsv" <<'PY' || FAIL+=("③ 표본 동일성")
import sys, os
# 서명 = (종목, 연도, 분기, 공시일, 판 번호, 최신 표지, 값 해시) — 행 id·created_at은 DB마다 다르므로 뺀다.
def load(p, ids=None):
    out = {}
    for l in open(p):
        c = l.rstrip('\n').split('\t')
        if ids is None or c[1] in ids: out.setdefault(c[1], set()).add((c[4], c[5], c[6], c[7], c[8], c[18]))
    return out
small = load(sys.argv[1]); prod = load(sys.argv[2], set(small))
tick = {l.split('\t')[0]: l.rstrip('\n').split('\t')[2] for l in open(sys.argv[3])}
attr = {}
if os.path.exists(sys.argv[4]):
    for l in open(sys.argv[4]):
        c = l.rstrip('\n').split('\t')
        if len(c) >= 2 and c[1]: attr[c[0]] = c[1]
diff = sorted(tick.get(s, s) for s in small if small[s] != prod.get(s, set()))
bad = [t for t in diff if t not in attr]
print(f'  표본 {len(small)}종목 · 다름 {len(diff)} · 귀속됨 {len(diff) - len(bad)} · 귀속 없음 {bad}')
sys.exit(1 if bad else 0)
PY
fi
echo "===== ④ 펜스 귀속"
CH=$(diff "$PB/fences-cmp.tsv" "$PA/fences-cmp.tsv" | grep '^[<>]' | cut -c3- | cut -f1 | sort -u || true)
n=0
while IFS= read -r p; do
  [ -n "$p" ] || continue; n=$((n+1))
  awk -F'\t' -v k="$p" '$1==k && $2!="" {f=1} END {exit !f}' "$A/fence-attribution.tsv" 2>/dev/null || FAIL+=("④ 귀속 없는 펜스: $p")
done <<< "$CH"
echo "  바뀐 전제 $n"
echo "===== ⑤ 종목 × 지표 귀속"
python3 - "$PB/groups.tsv" "$PA/groups.tsv" "$PA/stock-market.tsv" "$PB/stock-market.tsv" "$A/indicator-changes.tsv" "$A/indicator-attribution.tsv" <<'PY' || FAIL+=("⑤ 귀속 없는 종목 × 지표")
import sys, os, collections
def groups(p):
    out = {}
    for l in open(p):
        c = l.rstrip('\n').split('\t')
        out[(c[0], c[1])] = (int(c[2]), c[3])
    return out
before, after = groups(sys.argv[1]), groups(sys.argv[2])
meta = {}
for p in (sys.argv[4], sys.argv[3]):
    for l in open(p):
        c = l.rstrip('\n').split('\t')
        meta[c[0]] = (c[1], c[2])
def market(sid): return 'KR' if meta.get(sid, ('?',))[0] in ('KOSPI', 'KOSDAQ') else meta.get(sid, ('?',))[0]
changes = []
for k in sorted(set(before) | set(after)):
    b, a = before.get(k, (0, '')), after.get(k, (0, ''))
    if b == a:
        continue
    kind = '칸 감소' if a[0] < b[0] else '값 생김' if a[0] > b[0] else '값 → 값'
    changes.append((market(k[0]), k[1], meta.get(k[0], ('?', k[0]))[1], kind, b[0], a[0], k[0]))
with open(sys.argv[5], 'w') as f:
    for c in changes:
        f.write('\t'.join(map(str, c[:6])) + '\n')
attr = set()
if os.path.exists(sys.argv[6]):
    for l in open(sys.argv[6]):
        c = l.rstrip('\n').split('\t')
        if len(c) >= 4 and c[3].strip():
            attr.add((c[0], c[1], c[2]))
adj = {k[0]: v[0] for k, v in before.items() if k[1] == 'ADJ_CLOSE'}
adj_after = {k[0]: v[0] for k, v in after.items() if k[1] == 'ADJ_CLOSE'}
wiped = [c for c in changes if c[1] != 'ADJ_CLOSE' and c[4] > 0 and c[5] < 0.2 * c[4] and adj_after.get(c[6], 0) >= adj.get(c[6], 0) > 0]
by = collections.Counter((c[0], c[3]) for c in changes)
print('  바뀐 묶음', len(changes), dict(by), '→', sys.argv[5])
print('  전멸(ADJ_CLOSE 온전 · 한 지표 80% 넘게 잃음)', len(wiped), [(c[0], c[2], c[1], c[4], c[5]) for c in wiped[:10]])
bad = [c for c in changes if (c[0], c[1], c[2]) not in attr]
print('  귀속 없음', len(bad), [(c[0], c[2], c[1], c[3]) for c in bad[:10]])
sys.exit(1 if bad else 0)
PY
echo "===== ⑥ 미국 불변"
US_RAN="${US_GATE_RAN:-0}"
k6() { if [ "$US_RAN" = 1 ]; then echo "  (미국 관문을 이 창에서 돌렸다 — $1 차이는 귀속 재료)"; else FAIL+=("$1"); fi; }
cmp -s "$PB/us-fs-hash.txt" "$PA/us-fs-hash.txt" && echo "  같음 미국 재무 해시" || k6 "⑥ 미국 재무 해시"
python3 - "$PB/groups.tsv" "$PA/groups.tsv" "$PA/stock-market.tsv" <<'PY' || k6 "⑥ 미국 지표 묶음"
import sys
us = {l.split('\t')[0] for l in open(sys.argv[3]) if l.split('\t')[1] == 'US'}
def load(p): return {tuple(l.split('\t')[:2]): l for l in open(p) if l.split('\t')[0] in us}
a, b = load(sys.argv[1]), load(sys.argv[2]); d = [k for k in set(a) | set(b) if a.get(k) != b.get(k)]
print('  미국 지표 묶음 차이', len(d), sorted(d)[:5]); sys.exit(1 if d else 0)
PY
echo "===== 귀속 재료(판정 아님)"
for f in preset.tsv rank.tsv membership.tsv; do echo "  $f 차이 $(diff "$PB/$f" "$PA/$f" | grep -c '^[<>]' || true)줄"; done
echo "===== 판정"; if [ ${#FAIL[@]} -eq 0 ]; then echo "OK 전 항목 통과"; else printf '  실패: %s\n' "${FAIL[@]}"; exit 1; fi
