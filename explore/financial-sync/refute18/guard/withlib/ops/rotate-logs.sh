#!/bin/bash
#
# 앱·터널 로그를 임계 초과분만 걷는다. **도는 중에도 안전하다** — 제자리 truncate라
# fd가 살아 있고, 세대는 `.1.gz`부터 압축본으로 남는다.
#
# 이 스크립트가 따로 있는 이유는 **터널 때문**이다. 회전은 `ops/start.sh`가 기동 직전에도
# 부르는데, 앱은 거의 매일 재기동되는 반면 터널은 그렇지 않다(기간 34일에 10회). 기동
# 시점에만 걸면 `tunnel.log`가 오래 안 걷힌다.
#
# **MUST NOT: 이력을 가볍게 여기지 않는다.** `ADR-0125`가 `stdout.log`를 진단의 정본으로
# 지목하고, 거기 든 사건 중 일부는 DB에 사본이 없다(2026-09-05 리밸런싱 거부가 그렇다 —
# `batch_execution`에 PARTIAL_SUCCESS로만 남고 「어느 유니버스가 왜」는 로그에만 있다).
# 그래서 회전은 지우는 것이 아니라 **압축해 세대로 미는 것**이고, 지난 이력은
# `zgrep -A 20 '<문구>' ~/Library/Logs/stock-screener/stdout.log.*.gz`로 뒤진다.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "$HERE/lib.sh"

force=0
case "${1:-}" in
  "")        ;;
  --force)   force=1 ;;
  *)         echo "용법: ops/rotate-logs.sh [--force]" >&2; exit 2 ;;
esac

if [[ ! -d "$LOG_DIR" ]]; then
  # MUST: 중괄호를 씌운다 — 한글 조사가 변수명에 붙어 `set -u`가 죽인다(status.sh와 같은 자리).
  echo "[rotate] ${LOG_DIR}이 없다 — 아직 앱을 띄운 적이 없다. 할 일이 없다."
  exit 0
fi

# --force는 임계를 1바이트로 낮춰 무조건 돌게 한다.
#
# **MUST: 확인을 받고 돈다.** `guard-destructive.sh`는 `tool_input.command`만 읽으므로
# 이 파일 안의 `rm`·`: >`를 심사하지 못하고, settings.json의 soft_deny는 범위가
# "ops/ 의 배포·설치 스크립트"라 이 스크립트를 안 덮는다. 확인이 없으면 에이전트가
# 자발적으로 --force를 쳐서 이력을 세대 밖으로 밀어낼 수 있고 아무 가드도 안 운다.
if [[ "$force" -eq 1 ]]; then
  echo "[rotate] --force: 임계를 무시하고 세 로그를 전부 회전한다."
  echo "         지금 크기 — $(cd "$LOG_DIR" && ls -lh stdout.log stderr.log tunnel.log 2>/dev/null | awk '{printf "%s %s  ", $9, $5}')"
  echo "         보존 세대를 넘긴 것은 사라진다. 계속할까? [y/N] "
  read -r ans
  case "$ans" in
    y|Y) ;;
    *) echo "[rotate] 아무것도 하지 않았다."; exit 0 ;;
  esac
fi

# 사람이 읽는 크기. `numfmt`는 GNU coreutils라 이 기계에 없다(실측) — 직접 만든다.
human() {
  local b="$1"
  awk -v b="$b" 'BEGIN{
    if (b >= 1048576)   printf "%.1fM", b/1048576;
    else if (b >= 1024) printf "%.0fK", b/1024;
    else                printf "%dB", b }'
}

rotate_one() {
  local name="$1" max="$2" keep="$3" before after
  local f="$LOG_DIR/$name"
  [[ -f "$f" ]] || { printf '  %-12s 없다\n' "$name"; return 0; }
  before=$(stat -f%z "$f")
  [[ "$force" -eq 1 ]] && max=1
  rotate_log "$f" "$max" "$keep" || true
  after=$(stat -f%z "$f")
  if [[ "$before" != "$after" ]]; then
    printf '  %-12s %8s → %s  (세대 %d개)\n' "$name" \
      "$(human "$before")" \
      "$(human "$after")" \
      "$(find "$LOG_DIR" -maxdepth 1 -name "$name.*.gz" | wc -l | tr -d ' ')"
  else
    printf '  %-12s %8s  임계(%s) 미만 — 그대로 둔다\n' "$name" \
      "$(human "$before")" \
      "$(human "$max")"
  fi
}

echo "== 로그 회전 =="
rotate_one stdout.log "$ROTATE_STDOUT_MAX" "$ROTATE_STDOUT_KEEP"
rotate_one stderr.log "$ROTATE_STDERR_MAX" "$ROTATE_STDERR_KEEP"
rotate_one tunnel.log "$ROTATE_TUNNEL_MAX" "$ROTATE_TUNNEL_KEEP"

echo
echo "[rotate] 세대 총량 $(du -sh "$LOG_DIR" 2>/dev/null | cut -f1) · 지난 이력은 zgrep으로 뒤진다:"
echo "         zgrep -A 20 '<문구>' $LOG_DIR/stdout.log.*.gz"
