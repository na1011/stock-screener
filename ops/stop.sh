#!/bin/bash
#
# 앱과 터널을 끈다. **끄면 꺼진 채로 있다** — 되살리는 것은 아무것도 없다(ADR-0043).
#
#   ops/stop.sh              앱 + 터널
#   ops/stop.sh --app        앱만
#   ops/stop.sh --tunnel     터널만
#
# MUST NOT: pkill -f java를 쓰지 않는다. 이 저장소와 무관한 JVM까지 함께 죽는다.

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
  *)        echo "용법: ops/stop.sh [--app|--tunnel]" >&2; exit 2 ;;
esac

# stop_one <이름> <pid_file> <명령줄 패턴> <종료 대기 초>
stop_one() {
  local name="$1" pid_file="$2" pattern="$3" limit="$4" pid i
  if ! pid="$(running_pid "$pid_file" "$pattern")"; then
    # PID 파일이 남아 있는데 그 프로세스가 우리 것이 아니면 지운다. 그대로 두면
    # 다음 stop이 재사용된 PID를 죽인다.
    [[ -f "$pid_file" ]] && rm -f "$pid_file"
    # MUST: 한글이 바로 뒤에 붙는 자리는 ${} 로 감싼다. bash가 "$name은"을 name은이라는
    # 변수명으로 읽어 set -u에서 죽는다 — 실제로 이 줄에서 그랬다.
    echo "[stop] ${name}은 돌고 있지 않다."
    return 0
  fi

  kill "$pid"
  for ((i = 0; i < limit; i++)); do
    if ! ps -p "$pid" >/dev/null 2>&1; then
      rm -f "$pid_file"
      echo "[stop] $name 종료 (PID $pid)."
      return 0
    fi
    sleep 1
  done

  # 여기서 kill -9를 자동으로 쏘지 않는다. 앱이 종료 훅에서 멈춰 있는 상태일 수 있고,
  # 그것을 조용히 강제 종료하면 왜 안 죽었는지가 영영 남지 않는다.
  echo "[stop] ${name}이 ${limit}초 안에 종료되지 않았다 (PID ${pid})." >&2
  echo "       원인은 $LOG_DIR/stderr.log에 있다. 강제로 끄려면: kill -9 $pid" >&2
  return 1
}

rc=0
if [[ $WANT_APP -eq 1 ]]; then
  stop_one "앱" "$APP_PID_FILE" "$APP_PATTERN" 30 || rc=1
fi
if [[ $WANT_TUNNEL -eq 1 ]]; then
  stop_one "터널" "$TUNNEL_PID_FILE" "$TUNNEL_PATTERN" 10 || rc=1
  # 우리가 안 띄웠어도 도는 cloudflared가 있으면 사이트는 아직 밖에서 보인다.
  # "터널은 돌고 있지 않다"만 찍고 끝내면 껐다고 믿게 된다.
  if ! [[ -f "$TUNNEL_PID_FILE" ]] && pgrep -f "$TUNNEL_PATTERN" >/dev/null 2>&1; then
    echo "[stop] cloudflared가 아직 돈다 — 이 스크립트가 띄운 것이 아니라 끄지 않았다 (PID $(pgrep -f "$TUNNEL_PATTERN" | tr '\n' ' '))." >&2
    echo "       부팅 데몬이면: sudo cloudflared service uninstall" >&2
  fi
fi

if [[ $WANT_APP -eq 1 && $rc -eq 0 ]]; then
  # 포트가 풀렸는지까지 본다. 프로세스가 사라져도 다른 무언가가 8080을 잡고 있으면
  # 다음 start가 "떴다"고 오판한다.
  if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "[stop] :8080을 아직 무언가 잡고 있다:" >&2
    lsof -nP -iTCP:8080 -sTCP:LISTEN >&2
    rc=1
  fi
fi

exit $rc
