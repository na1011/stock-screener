#!/bin/bash
# 사용: 06-reconcile.sh   — 쓰기 세션 안에서. 화해 두 판(계획 v2 결정 5 · docs/reload-gate.md 순서 10의 대조군 문장 그대로):
#   한 판 = indicator-reconcile(관문 켬) → 유도(governance-derive · 주식수 기저) → ① indicator-origin-rewrite → ② indicator-derived-rebuild
#   둘째 판의 ①② 「쓴 0 · 지운 0」 · 두 판 모두 실패 0. 실패 0을 함께 보는 이유: ②는 거래일마다 격리되므로 결정적으로 실패하는
#   날짜가 두 번 다 실패하면 쓴·지운 0으로 통과하고, 첫 판에서만 실패한 날짜는 둘째 판이 써서 「쓴 > 0」으로 오경보한다.
# 끝에 VACUUM(ANALYZE) — 화해가 지표 표를 크게 다시 쓴다.
source "$(dirname "$0")/env.sh"
one() { # <판 번호> — 세 행의 note를 내고, ①② 쓴·지운 합을 낸다
  local P log="$A/reconcile-$1-$PROD.log"
  P=$(max_exec_id "$PROD")
  boot "$PROD" --job=indicator-reconcile $GATE_ARGS > "$log" 2>&1 || true
  gate_line "$log"
  local job rows wrote=0
  for job in governance-derive indicator-origin-rewrite indicator-derived-rebuild; do
    rows=$(q "$PROD" "select id, status, target_count, success_count, failure_count, coalesce(note,'') from batch_execution where id > $P and job_name='$job'")
    [ "$(printf '%s' "$rows" | grep -c .)" -eq 1 ] || die "판 $1: $job 새 실행 행이 1이 아니다: $rows"
    IFS=$'\t' read -r id status target success failure note <<< "$rows"
    echo "  판 $1 $job id=$id $status 대상=$target 실패=$failure $note" | tee -a "$A/reconcile-$PROD.txt"
    [ "$status" = SUCCESS ] && [ "$failure" = 0 ] || die "판 $1: ${job}가 $status · 실패 $failure"
    if [ "$job" != governance-derive ]; then
      local w d; w=$(sed -n 's/.*쓴 \([0-9]*\).*/\1/p' <<< "$note"); d=$(sed -n 's/.*지운 \([0-9]*\).*/\1/p' <<< "$note")
      [ -n "$w" ] && [ -n "$d" ] || die "판 $1: $job note에서 쓴·지운을 못 읽었다: $note"
      wrote=$((wrote + w + d))
    fi
  done
  echo "$wrote" > "$A/reconcile-$1-wrote.txt"
}
: > "$A/reconcile-$PROD.txt"
one 1
one 2
w2=$(cat "$A/reconcile-2-wrote.txt")
[ "$w2" = 0 ] || die "둘째 화해가 ①② 합 ${w2}행을 쓰거나 지웠다 — 화해가 수렴하지 않는다(그 행을 연다: $A/reconcile-$PROD.txt)"
PGOPTIONS='-c statement_timeout=0' command psql -d "$PROD" -v ON_ERROR_STOP=1 -c 'VACUUM (ANALYZE) indicator_value, financial_statement' > /dev/null
echo "OK 화해 두 판 — 첫 판 쓴·지운 $(cat "$A/reconcile-1-wrote.txt") · 둘째 판 0 · 실패 0 · VACUUM"
