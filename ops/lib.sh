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

# warn_running_batches <라벨>
# 도는 배치가 있으면 알린다. **끄기 전에** 부른다 — 끄고 나면 사람이 되돌릴 수 없다.
#
# 왜 있는가: 종료로 끊긴 배치는 알림을 보내지 않는데(ADR-0078), 그날의 백분위·업종 상대값·
# 워치리스트 통지는 다음 실행이 다시 만들지 않는다. PercentileCalculator·SectorRelativeCalculator가
# 하루치만 받고 DailyUpdateJob이 asOf 하나로만 부르기 때문이다. ADR-0042의 15일 창은 **시세만**
# 메운다. 그래서 여기서 안 알리면 그날 통지가 빠진 것을 알 경로가 하나도 없다.
#
# MUST NOT: 여기서 멈추지 않는다. 경고만 하고 진행한다 — 판단은 사람이 한다(ADR-0043).
# MUST NOT: 조용히 넘어가지 않는다. 못 물었으면 **못 물었다고 찍는다.** "없음"과 구분되지
#   않으면 이 함수는 있으나 마나다.
# MUST: `if`로 쓴다. `[[ ... ]] && return`은 조건이 거짓일 때 AND 리스트가 1을 반환해
#   set -e에 걸린다 — 그러면 이 함수가 stop.sh를 kill 앞에서 죽인다.
warn_running_batches() {
  local label="$1"
  # MUST: 여기서 만든다. lib.sh는 DB도 DB_NAME도 정의하지 않으며, dump-db.sh의 그것은
  # 그 스크립트의 지역 변수다. 그대로 참조하면 set -u에서 unbound variable로 죽는다.
  local db="${DB_NAME:-stock_screener}"
  local psql rows

  # MUST: local과 대입을 가른다. `local x="$(...)"`는 local의 종료코드 0이 명령 치환의
  # 실패를 삼켜, 뒤따르는 ||가 영원히 안 탄다. dump-db.sh가 local 없이 쓰는 이유가 그것이다.
  psql="$(pg_bin psql)" || {
    echo "[$label] psql이 없어 도는 배치를 확인하지 못했다 (DB $db)." >&2
    return 0
  }

  # -w: 비밀번호를 물으면 프롬프트 없이 실패한다. 물으면 stop.sh가 **영영 멈춘다.**
  # PGCONNECT_TIMEOUT: DB가 안 뜬 상태에서 기본 대기에 걸리지 않게 한다.
  rows="$(PGCONNECT_TIMEOUT=3 "$psql" -w -d "$db" -tAF'|' -c "
      SELECT job_name, triggered_by,
             to_char(started_at, 'MM-DD HH24:MI'),
             date_trunc('second', now() - started_at)
        FROM batch_execution
       WHERE status = 'RUNNING'
       ORDER BY started_at" 2>/dev/null)" || {
    echo "[$label] DB($db)를 읽지 못해 도는 배치를 확인하지 못했다." >&2
    return 0
  }

  if [[ -z "$rows" ]]; then
    return 0
  fi

  echo "[$label] 도는 배치가 있다 (DB $db) — 지금 끄면 중단된다:" >&2
  while IFS='|' read -r job trigger started elapsed; do
    if [[ -z "$job" ]]; then
      continue
    fi
    if [[ "$trigger" == "MANUAL" ]]; then
      # 적재 JVM(bootstrap 프로파일)이 만든 행이다. BatchExecutionSweeper는 MANUAL을
      # **다음 적재 실행 때만** 닫으므로, 죽은 적재가 남긴 행이 영구히 남아 매번 보인다.
      # 그래서 갈라 표시한다 — 도는 gradle 프로세스가 없으면 그것은 유령이다.
      echo "         $job [$trigger · $started 시작 · $elapsed 경과] — 적재 JVM의 것. 도는 적재가 없으면 유령이다." >&2
    else
      echo "         $job [$trigger · $started 시작 · $elapsed 경과]" >&2
    fi
  done <<< "$rows"
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
