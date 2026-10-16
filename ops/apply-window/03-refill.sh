#!/bin/bash
# 사용: 03-refill.sh   — 쓰기 세션 안에서($PROD 가드 켬). 미국 재적재 두 실행(계획 v2 결정 3):
#   cur  = --universe=US_TOP1000 (대상 = precheck가 센 열린 편입 수와 같다)
#   cand = --population=candidates (대상 = 「재무 대상 M」 · 후보∩재무 하한 ≤ M ≤ 후보 수)
# 둘 다 11년 · 이른 판 켬 · 관문 켬. 실패는 구성원마다 기대 목록과 대조 — 재시도 가능 실패는 같은 모집단 인자에 --tickers로 곧바로 다시.
# 「과거 편입 --tickers」 실행은 뺀다 — 창 안 과거 편입이 전부 후보 술어에 든다(precheck가 단언한다).
# 끝나면 손 FR 뒤 주식수 판정(클래스 축 → 기저)이 자동으로 돈다(BootstrapRunner의 REDERIVE_SHARE_BASIS_AFTER).
source "$(dirname "$0")/env.sh"
read N_CUR N_CAND N_CAND_FIN < "$A/counts.txt"
YEARS=11
NOTES=$A/notes-$PROD.txt; : > $NOTES
RE_RETRY="$(echo $RETRYABLE | tr ' ' '|')"
check_failures() { # <실행 id> — 기대 밖·재시도 불가 실패면 멈추고, 재시도할 티커를 쉼표로 낸다
  failures_of "$PROD" "$1" > "$A/fail-$PROD-$1.txt"
  local other; other=$(grep -v -x -F -f <(expected_list; echo '#') "$A/fail-$PROD-$1.txt" | grep -vE ":($RE_RETRY)\$" || true)
  [ -z "$other" ] || die "기대 밖·재시도 불가 실패: $(tr '\n' ' ' <<< "$other")"
  grep -E ":($RE_RETRY)\$" "$A/fail-$PROD-$1.txt" | cut -d: -f1 | paste -sd, - || true
}
run() { # <라벨> <기대 target | -> <재시도 모집단 인자 | -> <모집단 인자…>
  local label="$1" expect="$2" rpop="$3"; shift 3
  local P id log="$A/refill-$label-$PROD.log"
  P=$(max_exec_id "$PROD")
  boot "$PROD" --job=financial-refill "$@" --financial-years=$YEARS --insert-earlier-vintages=true $GATE_ARGS > "$log" 2>&1 || true
  gate_line "$log"
  id=$(new_exec "$PROD" "$P" financial-refill "$expect")
  local target ok; target=$(q "$PROD" "select target_count from batch_execution where id=$id")
  ok=$(q "$PROD" "select success_count + failure_count = target_count from batch_execution where id=$id")
  [ "$ok" = t ] || die "$label 성공+실패 ≠ 대상(id=$id)"
  echo "$label id=$id 대상=$target $(q "$PROD" "select note from batch_execution where id=$id")" | tee -a $NOTES
  echo "  실패: $(failures_of "$PROD" "$id" | tr '\n' ' ')"
  local retry; retry=$(check_failures "$id")
  if [ -n "$retry" ]; then
    local n; n=$(echo "$retry" | tr ',' '\n' | grep -c .); P=$(max_exec_id "$PROD")
    # 재시도 모집단: cur은 --tickers만(옛 절차 그대로 · --universe와 함께 쓰는 꼴은 재 본 적이 없다), cand는 후보 모드에 --tickers.
    local rargs=(); [ "$rpop" = - ] || rargs=("$rpop")
    boot "$PROD" --job=financial-refill ${rargs[@]+"${rargs[@]}"} --tickers=$retry --financial-years=$YEARS --insert-earlier-vintages=true $GATE_ARGS > "$A/refill-retry-$PROD-$id.log" 2>&1 || true
    gate_line "$A/refill-retry-$PROD-$id.log"
    local rid; rid=$(new_exec "$PROD" "$P" financial-refill -)
    echo "retry($label) id=$rid 대상=$(q "$PROD" "select target_count from batch_execution where id=$rid") $(q "$PROD" "select note from batch_execution where id=$rid")" | tee -a $NOTES
    # 재시도에서도 기대 실패는 뺀다 — 첫 실행에서 TRANSIENT였던 기대 종목이 재시도에서 신선한 판의 NOT_FOUND로 돌아온다.
    failures_of "$PROD" "$rid" > "$A/fail-$PROD-$rid.txt"
    local left; left=$(grep -v -x -F -f <(expected_list; echo '#') "$A/fail-$PROD-$rid.txt" || true)
    [ -z "$left" ] || die "재시도에도 실패: $(tr '\n' ' ' <<< "$left")"
  fi
  echo "$id" > "$A/exec-$label.txt"
}
run cur "$N_CUR" - --universe=US_TOP1000
run cand - --population=candidates --population=candidates
M=$(q "$PROD" "select target_count from batch_execution where id=$(cat "$A/exec-cand.txt")")
[ "$M" -ge "$N_CAND_FIN" ] && [ "$M" -le "$N_CAND" ] || die "후보 재무 대상 $M이 독립 범위 [$N_CAND_FIN, $N_CAND] 밖이다"
grep -qF "후보 모드 손 FR: 후보 $N_CAND · 재무 대상 $M " "$A/refill-cand-$PROD.log" || die "후보 실행 로그의 후보·재무 대상 수가 precheck·실행 행과 다르다"
# 업종 먼저(company-profile) 실행이 있었다면 FAILED가 아니어야 한다(FAILED면 후보 갈래가 재무로 안 넘어가고 던진다 — 그래도 행을 본다).
K=$(sed -n 's/.*후보 모드 손 FR: .* 업종 먼저 \([0-9]*\).*/\1/p' "$A/refill-cand-$PROD.log" | head -1)
if [ "${K:-0}" -gt 0 ]; then
  s=$(q "$PROD" "select status from batch_execution where id > $(cat "$A/exec-cur.txt") and job_name='company-profile' and triggered_by='MANUAL' order by id desc limit 1")
  case "$s" in SUCCESS|PARTIAL_SUCCESS) ;; *) die "후보 업종 먼저 실행이 ${s:-없음}이다(업종 먼저 $K)";; esac
fi
echo "OK 재적재 — 현 편입 $N_CUR · 후보 재무 대상 $M(하한 $N_CAND_FIN) · 업종 먼저 ${K:-0} · note 합계 $(note_nums $NOTES)"
