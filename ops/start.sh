#!/bin/bash
#
# 앱과 터널을 띄운다.
#
# **자동 기동은 없다.** 이 스크립트를 사람이 실행할 때만 뜨고, 죽으면 죽은 채로 있다.
# 켠 채로 두려면 켜 두는 것이고, 재부팅하면 다시 여기부터다(ADR-0043).
#
#   켜기:  ops/start.sh              앱 + 터널
#          ops/start.sh --app        앱만 (밖에서는 안 보인다)
#          ops/start.sh --tunnel     터널만
#   끄기:  ops/stop.sh
#   상태:  ops/status.sh
#
# 도는 것은 저장소가 아니라 스테이징 사본이다. 코드를 고쳤으면 ops/deploy.sh다(ADR-0034).

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "$HERE/lib.sh"

WANT_APP=1
WANT_TUNNEL=1
case "${1:-}" in
  --app)    WANT_TUNNEL=0 ;;
  --tunnel) WANT_APP=0 ;;
  "")       ;;
  *)        echo "용법: ops/start.sh [--app|--tunnel]" >&2; exit 2 ;;
esac

mkdir -p "$LOG_DIR"

start_app() {
  local pid
  if pid="$(running_pid "$APP_PID_FILE" "$APP_PATTERN")"; then
    echo "[start] 앱은 이미 돌고 있다 (PID $pid)."
    return 0
  fi
  if [[ ! -f "$DEPLOY/run-app.sh" ]]; then
    echo "[start] $DEPLOY/run-app.sh가 없다. ops/stage.sh를 먼저 돌려라." >&2
    return 1
  fi

  # **DB를 먼저 묻는다.** 앱은 DB가 없으면 Flyway에서 **2.1초 만에** 죽는데, 아래 wait_for는
  # 그것을 60초 기다린 뒤에야 "로그를 봐라"로 알린다(2026-09-26 실측). 그 60초는 이미 죽은
  # 프로세스를 기다리는 시간이다. 그리고 원인이 stale 락이면 여기서 고칠 수 있다.
  if ! ensure_postgres_up "start"; then
    echo "[start] DB가 없어 앱을 띄우지 않는다 — 띄워도 Flyway에서 곧바로 죽는다." >&2
    return 1
  fi

  # **회전은 조기 return 뒤에 둔다.** 위 두 갈래는 이미 도는 앱을 만나도 return 0으로
  # 성공을 내므로, 앞에 두면 `ops/start.sh`를 한 번 더 친 것만으로 도는 앱의 로그가 잘린다.
  # `|| true`인 이유는 이 스크립트가 `set -e`이기 때문이다 — 회전 실패가 기동 실패로
  # 보이면 원인을 stderr.log에서 찾게 되고 거기엔 아무것도 없다.
  rotate_log "$LOG_DIR/stdout.log" "$ROTATE_STDOUT_MAX" "$ROTATE_STDOUT_KEEP" || true
  rotate_log "$LOG_DIR/stderr.log" "$ROTATE_STDERR_MAX" "$ROTATE_STDERR_KEEP" || true

  # **쓰기 잠금을 먼저 묻는다**(ADR-0175). 앱은 기동 구간(Flyway·시더·스위퍼)을 잠금 안에서 쓰므로, bootRun·손 세션이 쥐고
  # 있으면 기동에서 「쓰기 잠금 게이트가 기동을 세운다」로 exit 1이 된다 — 여기서 먼저 알리면 60초를 안 기다린다.
  # 앱은 원래 꺼져 있었으므로 상태는 그대로다. 묻지 못했으면(표 없음 · psql 없음) 그대로 띄운다 — 게이트가 다시 본다.
  local holder
  if holder="$(write_lock_held_by_other "${DB_NAME:-stock_screener}")"; then
    echo "[start] 쓰기 잠금을 ${holder}이(가) 쥐고 있다 — 앱은 기동에서 잠금을 쥐므로 지금은 못 뜬다. 그것이 끝난 뒤 다시 띄운다." >&2
    return 1
  fi

  # nohup은 exec으로 넘기고 env도 run-app.sh도 exec으로 java가 되므로, $!가 곧 JVM의 PID다.
  # caffeinate로 감싸지 않는 이유가 그것이다 — 감싸면 PID 파일이 caffeinate를 가리키는데
  # caffeinate는 시그널을 자식에게 넘기지 않아 stop.sh의 kill이 앱에 닿지 않는다.
  #
  # MUST: 쓰기 세션 변수를 걷는다(env -u). 손 세션의 exec 안에서 이 스크립트가 불리면 앱 JVM이 세션에 합류해 세션이 영영 안
  # 만료된다 — 웹 프로파일은 그 변수를 보면 기동을 세우지만(WriteLockGate), 여기서 걷는 것이 먼저다(잠금 v4 반증 L1-6b).
  nohup env -u SS_WRITE_SESSION -u PGAPPNAME bash "$DEPLOY/run-app.sh" >>"$LOG_DIR/stdout.log" 2>>"$LOG_DIR/stderr.log" &
  pid=$!
  echo "$pid" >"$APP_PID_FILE"

  # 절전 방지는 앱이 도는 동안만 건다. -w로 붙였으므로 앱이 끝나면 함께 끝난다.
  # -s는 전원이 연결된 동안만 유휴 절전을 막아, 배터리로 도는 동안에는 평소대로 잠든다.
  nohup /usr/bin/caffeinate -s -w "$pid" >/dev/null 2>&1 &

  echo "[start] 앱 기동 중 (PID $pid) — 20~30초 걸린다."
  if wait_for "$HEALTH_LOCAL" 60; then
    echo "[start] 앱 OK — $(curl -s "$HEALTH_LOCAL")"
  else
    echo "[start] 60초 안에 응답이 없다. $LOG_DIR/stdout.log를 본다 — 기동 실패는 거기 있다." >&2
    # 위 물음과 기동 사이에 누가 잠금을 쥐었으면 게이트가 같은 문구로 exit 1을 냈다 — 그 줄을 그대로 보인다.
    grep -F "쓰기 잠금 게이트가 기동을 세운다" "$LOG_DIR/stdout.log" 2>/dev/null | tail -1 >&2 || true
    return 1
  fi
}

start_tunnel() {
  local pid bin
  if pid="$(running_pid "$TUNNEL_PID_FILE" "$TUNNEL_PATTERN")"; then
    echo "[start] 터널은 이미 돌고 있다 (PID $pid)."
    return 0
  fi
  # 데몬으로 등록된 것이 아직 남아 있으면 같은 터널이 둘이 된다. PID 파일 밖의 것도 본다.
  if pgrep -f "$TUNNEL_PATTERN" >/dev/null 2>&1; then
    echo "[start] cloudflared가 이미 돌고 있다(이 스크립트가 띄운 것이 아니다)." >&2
    echo "        부팅 시 뜨는 데몬이 남아 있는지 본다: sudo cloudflared service uninstall" >&2
    return 1
  fi
  bin="$(cloudflared_bin)"
  if [[ -z "$bin" ]]; then
    echo "[start] cloudflared를 찾을 수 없다: brew install cloudflared" >&2
    return 1
  fi
  if [[ ! -f "$TUNNEL_TOKEN_FILE" ]]; then
    echo "[start] ${TUNNEL_TOKEN_FILE}이 없다. .env에 CLOUDFLARE_TUNNEL_TOKEN을 넣고 ops/stage.sh를 돌려라." >&2
    return 1
  fi

  # 앱과 같은 이유로 조기 return 뒤다. 터널은 재기동이 드물어(기간 내 10회) 기동 시점
  # 회전만으로는 오래 안 걷히므로 `ops/rotate-logs.sh`가 따로 있다.
  rotate_log "$LOG_DIR/tunnel.log" "$ROTATE_TUNNEL_MAX" "$ROTATE_TUNNEL_KEEP" || true

  # --token이 아니라 --token-file이다. 인자로 넘기면 ps에 토큰이 그대로 뜬다.
  nohup "$bin" tunnel run --token-file "$TUNNEL_TOKEN_FILE" >>"$LOG_DIR/tunnel.log" 2>&1 &
  pid=$!
  echo "$pid" >"$TUNNEL_PID_FILE"
  echo "[start] 터널 기동 (PID $pid)."
}

if [[ $WANT_APP -eq 1 ]]; then start_app; fi
if [[ $WANT_TUNNEL -eq 1 ]]; then start_tunnel; fi

# 앱과 터널이 둘 다 있어야 밖에서 보인다. 로컬만 보면 터널이 죽어도 초록이다(ADR-0033).
if [[ $WANT_APP -eq 1 && $WANT_TUNNEL -eq 1 ]]; then
  echo -n "[start] 외부: "
  if wait_for "$HEALTH_PUBLIC" 30; then
    curl -s "$HEALTH_PUBLIC"; echo
  else
    echo "응답 없음 — $LOG_DIR/tunnel.log를 본다." >&2
    exit 1
  fi
fi
