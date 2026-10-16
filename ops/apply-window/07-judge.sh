#!/bin/bash
# 사용: 07-judge.sh <운영 후 dir> <운영 전 dir> [<작은 DB 후 dir>]   (dir은 $A 기준 상대 또는 절대 · 02-snapshot.sh가 만든 것)
# 하드 단언(계획 v2 결정 8·12) — 하나라도 서지 않으면 1로 끝난다:
#   ① 국내 불변(재무 해시 · 지표 묶음)   ② ADJ_CLOSE 감소 0
#   ③ 표본 동일성 — 작은 DB(G3)와 운영이 같은 날 같은 코드로 쓴 표본 종목의 재무 행 서명이 같다. 다르면 두 실행 사이 새 공시로
#      귀속해 $A/sample-attributed.tsv(티커<TAB>근거 — 그 종목의 새 접수번호·filingDate)에 적는다. 귀속 없는 차이 1건이면 실패.
#   ④ 펜스 귀속 — 전/후에 출력이 바뀐 전제마다 $A/fence-attribution.tsv(전제<TAB>묶음·근거)에 귀속이 있다.
# 프리셋·순위·명부 차이는 귀속 재료로 요약만 낸다(판정이 아니다 — 관문은 값을 바꾸러 도는 것이다).
source "$(dirname "$0")/env.sh"
PA=$(abspath "$1"); PB=$(abspath "$2"); SA=""; [ -z "${3:-}" ] || SA=$(abspath "$3")
for d in "$PA" "$PB" ${SA:+"$SA"}; do for f in preset.tsv rank.tsv groups.tsv fs.tsv stock-market.tsv membership.tsv kr-fs-hash.txt fences-cmp.tsv adjclose-counts.tsv; do [ -s "$d/$f" ] || die "$d/${f}가 비었거나 없다"; done; done
FAIL=()
echo "===== ① 국내 불변"
cmp -s "$PB/kr-fs-hash.txt" "$PA/kr-fs-hash.txt" && echo "  같음 국내 재무 해시" || FAIL+=("① 국내 재무 해시")
python3 - "$PB/groups.tsv" "$PA/groups.tsv" "$PA/stock-market.tsv" <<'PY' || FAIL+=("① 국내 지표 묶음")
import sys
kr = {l.split('\t')[0] for l in open(sys.argv[3]) if l.split('\t')[1] in ('KOSPI', 'KOSDAQ')}
def load(p): return {tuple(l.split('\t')[:2]): l for l in open(p) if l.split('\t')[0] in kr}
a, b = load(sys.argv[1]), load(sys.argv[2]); d = [k for k in set(a) | set(b) if a.get(k) != b.get(k)]
print('  국내 지표 묶음 차이', len(d), sorted(d)[:5]); sys.exit(1 if d else 0)
PY
echo "===== ② ADJ_CLOSE 감소"
python3 - "$PB/adjclose-counts.tsv" "$PA/adjclose-counts.tsv" <<'PY' || FAIL+=("② ADJ_CLOSE 감소")
import sys
def load(p): return {l.split('\t')[0]: int(l.rstrip('\n').split('\t')[1]) for l in open(p)}
a, b = load(sys.argv[1]), load(sys.argv[2]); dec = [(k, a[k], b.get(k, 0)) for k in a if b.get(k, 0) < a[k]]
print('  줄어든 종목', len(dec), dec[:10]); sys.exit(1 if dec else 0)
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
echo "===== 귀속 재료(판정 아님)"
for f in preset.tsv rank.tsv membership.tsv; do echo "  $f 차이 $(diff "$PB/$f" "$PA/$f" | grep -c '^[<>]' || true)줄"; done
echo "===== 판정"; if [ ${#FAIL[@]} -eq 0 ]; then echo "OK 전 항목 통과"; else printf '  실패: %s\n' "${FAIL[@]}"; exit 1; fi
