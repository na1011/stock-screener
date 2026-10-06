#!/bin/bash
# 사용: 07-judge.sh <운영 후 dir> <S1 후 dir> <S0 후 dir> <운영 전 dir>   (dir은 $A 기준 상대 또는 절대)
source "$(dirname "$0")/env.sh"
PA=$(abspath "$1"); S1A=$(abspath "$2"); S0A=$(abspath "$3"); PB=$(abspath "$4")
for d in "$PA" "$S1A" "$S0A" "$PB"; do for f in preset.tsv rank.tsv groups.tsv fs.tsv stock-market.tsv a2.txt kr-fs-hash.txt fences.txt adjclose-counts.tsv pctl-counts.tsv base-counts.tsv; do [ -s "$d/$f" ] || die "$d/$f가 비었거나 없다"; done; done
FAIL=()
# 프로세스 치환은 한 번만 읽히므로(cmp 뒤 diff가 Bad file descriptor) 임시 파일로 받는다.
same() { # <라벨> <파일1> <파일2>
  local f1=$(mktemp) f2=$(mktemp); cat "$2" > "$f1"; cat "$3" > "$f2"
  if cmp -s "$f1" "$f2"; then echo "  같음 $1"; else
    echo "  다름 $1 ($(diff "$f1" "$f2" | grep -c '^[<>]' || true)줄)"; diff "$f1" "$f2" | head -8 || true; FAIL+=("① $1"); fi
  rm -f "$f1" "$f2"; }
echo "===== ① 운영 후 = S1 후 (끼어듦·비결정성·명령)"
same preset.tsv "$PA/preset.tsv" "$S1A/preset.tsv"
same rank.tsv "$PA/rank.tsv" "$S1A/rank.tsv"
same a2 "$PA/a2.txt" "$S1A/a2.txt"
same 국내재무해시 "$PA/kr-fs-hash.txt" "$S1A/kr-fs-hash.txt"
T=$(mktemp -d)
sort "$PA/groups.tsv" > $T/gp; sort "$S1A/groups.tsv" > $T/gs; same groups $T/gp $T/gs
cut -f2,5-9,19 "$PA/fs.tsv" | sort > $T/fp; cut -f2,5-9,19 "$S1A/fs.tsv" | sort > $T/fs1; same 재무행 $T/fp $T/fs1
grep -v '^(rc' "$PA/fences.txt" > $T/cp; grep -v '^(rc' "$S1A/fences.txt" > $T/cs; same 펜스 $T/cp $T/cs
# note는 실행별 분배가 아니라 합계로 본다 — 일시 실패 종목이 달라 본실행/재시도로 나뉘는 몫이 갈린다.
sumnote() { python3 -c "
import re,sys,collections
t=collections.Counter()
for l in open(sys.argv[1]):
    for k,v in re.findall(r'(신규|정정|채움|교정|이른판)=([0-9]+)', l): t[k]+=int(v)
print(' '.join(f'{k}={t[k]}' for k in ('신규','정정','채움','교정','이른판')))" "$1"; }
sumnote "$A/notes-$PROD-true-11.txt" > $T/np; sumnote "$A/notes-$S1-true-11.txt" > $T/ns
echo "  note 합계 운영: $(cat $T/np) / S1: $(cat $T/ns)"
same refill-note합계 $T/np $T/ns
echo "===== ② 순위 재료"
FLOOR=$(q $PROD "select min(effective_from) from universe_membership where universe_code='US_TOP1000' and origin='OBSERVED'"); [ -n "$FLOOR" ] || die "관측 하한이 비었다"
n=$(diff <(awk -F'\t' -v f="$FLOOR" 'NR>1 && $1<f' "$PB/rank.tsv") <(awk -F'\t' -v f="$FLOOR" 'NR>1 && $1<f' "$PA/rank.tsv") | grep -c '^[<>]' || true)
echo "  하한($FLOOR) 앞 운영 전 대 후 차이 ${n}줄 (Q4V 밖 원인도 든다 — 새 라벨 적재가 순위 재료를 바꾼다. 0이 아니면 ADR-0135 규칙으로 복원 재실행을 판단한다)"
[ "$n" -eq 0 ] || echo "    → 바뀐 종목: $(diff <(awk -F'\t' -v f="$FLOOR" 'NR>1 && $1<f' "$PB/rank.tsv") <(awk -F'\t' -v f="$FLOOR" 'NR>1 && $1<f' "$PA/rank.tsv") | grep '^[<>]' | cut -f2 | sort -u | tr '\n' ' ')"
dd=$(diff <(tail -n +2 "$S0A/rank.tsv") <(tail -n +2 "$PA/rank.tsv") | grep '^[<>]' || true)
echo "  전 격자 S0 후 대 운영 후 차이 $(printf '%s' "$dd" | grep -c . || true)줄 — 종목: $(printf '%s' "$dd" | cut -f2 | sort -u | tr '\n' ' ')"
[ -z "$(printf '%s' "$dd" | cut -f2 | sort -u | grep -v -x DCO || true)" ] || FAIL+=("② S0 대비 순위 재료(DCO 외)")
echo "===== ③ 운영 후 대 S0 후 (구간 규칙)"
python3 - "$PA/groups.tsv" "$S0A/groups.tsv" "$A/diff-groups-judge.tsv" <<'PY'
import sys
def load(p): return {tuple(l.split('\t')[:2]): tuple(l.rstrip('\n').split('\t')[2:]) for l in open(p)}
a, b = load(sys.argv[1]), load(sys.argv[2]); d = sorted(k for k in set(a) | set(b) if a.get(k) != b.get(k))
open(sys.argv[3], 'w').write(''.join(f'{s}\t{c}\n' for s, c in d)); print('  차이 묶음', len(d))
PY
for pair in "$PROD:$PA" "$S0:$S0A"; do db=${pair%%:*}; dir=${pair#*:}
  PGOPTIONS='-c statement_timeout=0' command psql -d "$db" -v ON_ERROR_STOP=1 -q -c "CREATE TEMP TABLE dg(stock_id bigint, indicator_code varchar(40));" -c "\copy dg from '$A/diff-groups-judge.tsv'" -c "\copy (SELECT v.stock_id, v.indicator_code, v.trade_date, v.value FROM indicator_value v JOIN dg USING (stock_id, indicator_code) ORDER BY v.stock_id, v.indicator_code COLLATE \"C\", v.trade_date) to '$dir/iv.tsv'" || die "$db 차이 행 추출 실패"; done
q $PROD "select distinct p.trade_date from price_daily p join stock s on s.id=p.stock_id where s.ticker='AAPL' and s.market_code='US' order by 1" > $A/us-trade-dates.txt
EXP=$(grep -oE '이른판=[0-9]+' "$A/notes-$PROD-true-11.txt" | cut -d= -f2 | paste -sd+ - | bc)
python3 $SCRIPTS/r3nk.py "$PA" "$S0A" $A/us-trade-dates.txt $A/diff-groups-judge.tsv "$EXP" || FAIL+=("③ 구간 규칙")
echo "===== ④ 국내 불변 (운영 전 대 후)"
same 국내재무해시-전후 "$PB/kr-fs-hash.txt" "$PA/kr-fs-hash.txt"
python3 - "$PB/groups.tsv" "$PA/groups.tsv" "$PA/stock-market.tsv" <<'PY' || FAIL+=("④ 국내 지표")
import sys
kr = {l.split('\t')[0] for l in open(sys.argv[3]) if l.rstrip('\n').split('\t')[1] in ('KOSPI', 'KOSDAQ')}
def load(p): return {tuple(l.split('\t')[:2]): l for l in open(p) if l.split('\t')[0] in kr}
a, b = load(sys.argv[1]), load(sys.argv[2]); d = [k for k in set(a) | set(b) if a.get(k) != b.get(k)]
print('  국내 지표 묶음 차이', len(d), d[:5]); sys.exit(1 if d else 0)
PY
echo "===== ⑤ 지워진 채 남은 것"
python3 - "$PB" "$PA" <<'PY' || FAIL+=("⑤ ADJ_CLOSE 감소")
import sys
B, A = sys.argv[1], sys.argv[2]
def load(p, k): return {tuple(l.rstrip('\n').split('\t')[:k]): int(l.rstrip('\n').split('\t')[k]) for l in open(p)}
a, b = load(f'{B}/adjclose-counts.tsv', 1), load(f'{A}/adjclose-counts.tsv', 1)
dec = [(k[0], a[k], b.get(k, 0)) for k in a if b.get(k, 0) < a[k]]
print('  ADJ_CLOSE 줄어든 종목', len(dec), dec[:10])
pa, pb = load(f'{B}/pctl-counts.tsv', 2), load(f'{A}/pctl-counts.tsv', 2)
ba, bb = load(f'{B}/base-counts.tsv', 2), load(f'{A}/base-counts.tsv', 2)
mism = []
for (td, code), n in pa.items():
    dp = pb.get((td, code), 0) - n
    base = code[:-len('_PCTL')]; db_ = bb.get((td, base), 0) - ba.get((td, base), 0)
    if dp < 0 and dp < db_: mism.append((td, code, dp, db_))
print('  백분위 감소가 원본 감소보다 큰 (날짜,코드)', len(mism), sorted(mism)[:8])
sys.exit(1 if dec else 0)
PY
echo "===== 판정"; if [ ${#FAIL[@]} -eq 0 ]; then echo "OK 전 항목 통과"; else printf '  실패: %s\n' "${FAIL[@]}"; exit 1; fi
