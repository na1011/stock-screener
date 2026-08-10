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

  # nohup은 exec으로 넘기고 run-app.sh도 exec으로 java가 되므로, $!가 곧 JVM의 PID다.
  # caffeinate로 감싸지 않는 이유가 그것이다 — 감싸면 PID 파일이 caffeinate를 가리키는데
  # caffeinate는 시그널을 자식에게 넘기지 않아 stop.sh의 kill이 앱에 닿지 않는다.
  nohup bash "$DEPLOY/run-app.sh" >>"$LOG_DIR/stdout.log" 2>>"$LOG_DIR/stderr.log" &
  pid=$!
  echo "$pid" >"$APP_PID_FILE"

  # 절전 방지는 앱이 도는 동안만 건다. -w로 붙였으므로 앱이 끝나면 함께 끝난다.
  # -s는 전원이 연결된 동안만 유휴 절전을 막아, 배터리로 도는 동안에는 평소대로 잠든다.
  nohup /usr/bin/caffeinate -s -w "$pid" >/dev/null 2>&1 &

  echo "[start] 앱 기동 중 (PID $pid) — 20~30초 걸린다."
  if wait_for "$HEALTH_LOCAL" 60; then
    echo "[start] 앱 OK — $(curl -s "$HEALTH_LOCAL")"
  else
    echo "[start] 60초 안에 응답이 없다. $LOG_DIR/stderr.log를 본다." >&2
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
    echo "[start] $TUNNEL_TOKEN_FILE이 없다. .env에 CLOUDFLARE_TUNNEL_TOKEN을 넣고 ops/stage.sh를 돌려라." >&2
    return 1
  fi

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
