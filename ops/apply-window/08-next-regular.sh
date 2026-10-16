#!/bin/bash
# 사용: 08-next-regular.sh <표본 파일>   — 작은 DB 드라이런 전용(G4 · 옛 q4v/08-gate.sh의 대체 · docs/reload-gate.md 「후보 ⑧」).
# 관문 뒤 켜진 jar의 첫 정기 경로를 한 판씩 태워 관문이 쓴 행을 그 경로가 되돌리지 않는지 본다:
#   ① 현 편입 — 정기 FR과 같은 인자(--universe=US_TOP1000 · 연수 기본 · 이른 판 끔 · 관문 켬)
#   ② 후보 표본 — 후보 소비와 같은 인자(--population=candidates --tickers=<표본의 후보> · 기본 창 · 이른 판 끔 · 관문 켬)
# 둘 다 note 신규·정정·채움·교정·이른판 0이고 로그에 「유도 판을 회수한다」·「정정공시를 반영한다」가 없어야 한다.
# 0이 아니면 그 행을 열어 「창 의존(기존 결함 — LNT 2018·2019 Q4)」인지 관문 묶음이 만든 것인지 가른다.
source "$(dirname "$0")/env.sh"
[ "$PROD" != "$LIVE" ] || die "운영에서 돌지 않는다 — 켠 jar의 첫 정기 실행이 운영에서의 이 판정이다(09-flip.md)"
SAMPLE="$1"; [ -s "$SAMPLE" ] || die "표본 파일이 없다"
CAND="select s.ticker from stock s where s.market_code='US' and s.is_active and not exists (select 1 from universe_membership am join universe au on au.code=am.universe_code and au.is_active where am.stock_id=s.id and am.effective_to is null)"
CT=$(q "$PROD" "select string_agg(ticker, ',' order by ticker) from ($CAND) c")
[ -n "$CT" ] || die "작은 DB에 후보 표본이 없다"
FAIL=()
one() { # <라벨> <인자…>
  local label="$1"; shift; local P id log="$A/next-$label-$PROD.log"
  P=$(max_exec_id "$PROD")
  boot "$PROD" --job=financial-refill "$@" $GATE_ARGS > "$log" 2>&1 || true
  gate_line "$log"; id=$(new_exec "$PROD" "$P" financial-refill -)
  local note; note=$(q "$PROD" "select note from batch_execution where id=$id"); echo "$note" > "$A/next-$label.note"
  local nums; nums=$(note_nums "$A/next-$label.note")
  local ev; ev=$(grep -cE '유도 판을 회수한다|정정공시를 반영한다' "$log" || true)
  echo "  $label id=$id $nums · 회수·정정 사건 $ev"
  [ "$nums" = "신규=0 정정=0 채움=0 교정=0 이른판=0" ] && [ "$ev" = 0 ] || FAIL+=("$label")
  grep -E '유도 판을 회수한다|정정공시를 반영한다' "$log" | sed -E 's/.*(유도 판을 회수한다|정정공시를 반영한다)\. //' | head -10 || true
}
one members --universe=US_TOP1000
one candidates --population=candidates --tickers=$CT   # runForCandidateConsumption과 같은 인자(연수 기본 · 이른 판 끔) — 소비 표지 갈고리만 없다
# ③ 실제 14:16 후보 소비 잡 한 판(A9) — 소비할 새 공시가 없으면 대상 0이 정상이다. 행 상태와 note를 귀속 재료로 낸다.
P=$(max_exec_id "$PROD")
boot "$PROD" --job=candidate-consumption $GATE_ARGS > "$A/next-consumption-$PROD.log" 2>&1 || true
gate_line "$A/next-consumption-$PROD.log"
rows=$(q "$PROD" "select job_name, status, target_count, failure_count, coalesce(note,'') from batch_execution where id > $P order by id")
echo "$rows" | sed 's/^/  소비 /'
! grep -qE $'\t(FAILED|RUNNING)\t' <<< "$rows" || FAIL+=("candidate-consumption 실행 행")
if [ ${#FAIL[@]} -eq 0 ]; then echo "OK 다음 정기 경로가 관문이 쓴 행을 안 건드린다"; else die "다음 정기 경로가 움직였다: ${FAIL[*]} — 행을 열어 귀속한다"; fi
