#!/bin/bash
#
# ops/ 스크립트가 공유하는 경로와 프로세스 판정. 단독 실행용이 아니다 — source 해서 쓴다.
#
# **PID 파일만으로는 살아 있는지 판정할 수 없다.** PID는 재사용되므로 남은 PID 파일이
# 무관한 프로세스를 가리킬 수 있고, 그것을 그대로 죽이면 엉뚱한 것이 죽는다. launchd가
# 하던 일을 스크립트로 가져오면서 이 판정도 함께 넘어왔다 — 그래서 살아 있는지는 언제나
# "그 PID의 명령줄이 우리 것인가"까지 확인한다(ADR-0043).

DEPLOY="$HOME/Library/Application Support/stock-screener"
LOG_DIR="$HOME/Library/Logs/stock-screener"

# DB 덤프. **저장소 밖이다**(ADR-0034). 저장소는 개발 트리라 `gradlew clean`·`mv`·`git add`의
# 사정거리 안이고, 1GB짜리 덤프가 작업 트리에 있으면 커밋 사고 반경에 들어온다. 배포 산출물과
# 같은 700 트리에 두면 `.env`와 같은 보호를 받는다.
DUMP_DIR="$DEPLOY/dumps"

APP_PID_FILE="$DEPLOY/app.pid"
TUNNEL_PID_FILE="$DEPLOY/tunnel.pid"
TUNNEL_TOKEN_FILE="$DEPLOY/tunnel-token"

# ps의 명령줄에 이것이 있어야 우리 프로세스로 인정한다.
APP_PATTERN="app.jar"
TUNNEL_PATTERN="cloudflared tunnel run"

HEALTH_LOCAL="http://localhost:8080/actuator/health"
HEALTH_PUBLIC="https://screener.na1011.net/actuator/health"

# running_pid <pid_file> <명령줄에 있어야 하는 문자열>
# 살아 있으면 PID를 출력하고 0을 반환한다. 아니면 아무것도 출력하지 않고 1.
running_pid() {
  local pid_file="$1" pattern="$2" pid
  [[ -f "$pid_file" ]] || return 1
  pid="$(cat "$pid_file" 2>/dev/null || true)"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  ps -p "$pid" -o command= 2>/dev/null | grep -qF -- "$pattern" || return 1
  echo "$pid"
}

# wait_for <url> <최대 초>
wait_for() {
  local url="$1" limit="$2" i
  for ((i = 0; i < limit; i++)); do
    if curl -sf --max-time 2 "$url" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  return 1
}

# cloudflared 실행 파일. Homebrew 경로가 /etc/paths에 없는 환경이 있어 직접 찾는다.
cloudflared_bin() {
  command -v cloudflared 2>/dev/null || { [[ -x /opt/homebrew/bin/cloudflared ]] && echo /opt/homebrew/bin/cloudflared; }
}

# pg_dump·pg_restore 실행 파일. cloudflared와 같은 이유로 직접 찾는다 — Homebrew의
# postgresql@16은 keg-only라 `/opt/homebrew/bin`의 심볼릭 링크가 없으면 Cellar까지 내려가야 한다.
pg_bin() {
  local name="$1"
  command -v "$name" 2>/dev/null && return 0
  local candidate
  for candidate in "/opt/homebrew/bin/$name" "/opt/homebrew/opt/postgresql@16/bin/$name"; do
    [[ -x "$candidate" ]] && { echo "$candidate"; return 0; }
  done
  return 1
}
