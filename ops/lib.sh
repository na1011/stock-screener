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

# ── 로그 회전 ────────────────────────────────────────────────────────────────
#
# 앱·터널은 셸 `>>`로 받은 fd를 죽을 때까지 붙들고 있다. 그래서 **rename 기반 회전이
# 전부 실패한다** — fd가 경로가 아니라 inode를 따라가므로 새 파일은 0바이트로 남고
# 앱은 사라진 옛 파일에 계속 쓴다(`docs/progress.md`가 `rm`에 대해 실측으로 적은 그대로).
# `newsyslog`가 못 쓰이는 이유도 이것이다 — 그 플래그 집합에 copytruncate가 없다.
#
# 그래서 **언제나 제자리 truncate다.** 성립하는 근거는 fd가 O_APPEND라는 것이고
# (`lsof +fg`가 `W,AP`를 찍는다), O_APPEND는 매 write마다 offset을 EOF로 재배치하므로
# 자른 뒤 다음 쓰기가 0에서 시작한다 — sparse hole이 안 생긴다.
#
# **MUST NOT: 「프로세스가 살아 있으면 truncate, 아니면 mv」로 가르지 않는다.**
# `lsof -t`가 「아무도 안 열었다」와 「판정 실패」를 **둘 다 종료코드 1 + 빈 출력**으로
# 내서 구분할 수 없다(2026-09-11 실측). 갈래가 사는 유일한 값은 한 바이트 미만의 유실을
# 0으로 만드는 것인데, 틀린 쪽으로 가면 링크 수 0인 inode에 계속 써서 **어떤 경로로도
# 안 보이고 디스크도 JVM이 죽을 때까지 안 돌아온다.** 비대칭이 갈래를 없애라고 정한다.
#
# 임계와 보존 세대. **파일마다 다르다** — 하나로 묶으면 성장이 느린 둘은 영원히 안 걷힌다
# (실측: stdout 1.02 MiB/거래일 · tunnel 14.4 KB/일 · stderr 34일간 0).
ROTATE_STDOUT_MAX=$((8 * 1024 * 1024)); ROTATE_STDOUT_KEEP=10
ROTATE_TUNNEL_MAX=$((2 * 1024 * 1024)); ROTATE_TUNNEL_KEEP=5
ROTATE_STDERR_MAX=$((256 * 1024));      ROTATE_STDERR_KEEP=3

# rotate_log <파일> <임계바이트> <보존세대>
# 임계 미만이거나 파일이 없으면 조용히 통과한다. 실패해도 0으로 끝난다 — 호출자가
# `set -e`인 기동 경로라 회전 실패가 기동 실패로 보이면 원인 추적이 통째로 어긋난다.
rotate_log() {
  local f="$1" max="$2" keep="$3" lock size i
  [[ -f "$f" ]] || return 0
  size=$(stat -f%z "$f" 2>/dev/null) || return 0
  [[ "$size" -lt "$max" ]] && return 0

  # `flock`이 이 기계에 없다. mkdir은 원자적이라 이것이 가장 싼 상호배제다.
  # **획득 실패는 에러가 아니라 조용한 skip이다** — 다른 쪽이 이미 하고 있다는 뜻이다.
  #
  # MUST NOT: 해제를 `trap ... RETURN`에 맡기지 않는다. 그 시그널은 bash 전용이라
  # 다른 셸에서 source하면 `undefined signal`로 죽고, **락이 남아 이후 회전이 영구히
  # 조용히 skip된다**(2026-09-11에 실제로 그렇게 12회 중 11회가 넘어갔다). 명시적으로 푼다.
  #
  # 낡은 락도 같은 이유로 닫는다 — 회전 중 강제 종료되면 디렉터리가 남고, 그때부터
  # 이 함수는 아무 말 없이 아무 일도 안 한다. gzip이 9 MiB에 0.04초이므로 5분이면
  # 정상 실행과 겹칠 수 없다.
  lock="$f.rotate.lock"
  if ! mkdir "$lock" 2>/dev/null; then
    if [[ -d "$lock" ]] && [[ -z "$(find "$lock" -maxdepth 0 -mmin -5 2>/dev/null)" ]]; then
      rmdir "$lock" 2>/dev/null || true
      mkdir "$lock" 2>/dev/null || return 0
      echo "[rotate] $(basename "$f")의 낡은 회전 락을 걷었다(5분 초과)." >&2
    else
      return 0
    fi
  fi

  rm -f "$f.$keep.gz"
  for ((i = keep - 1; i >= 1; i--)); do
    [[ -f "$f.$i.gz" ]] && mv "$f.$i.gz" "$f.$((i + 1)).gz"
  done

  # **MUST: `.tmp`에 쓴 뒤 원자적으로 옮긴다**(`ops/stage.sh`가 jar에 대해 하는 것과 같다).
  # 이것이 ① 잘린 .gz가 세대 자리에 들어가는 갈래와 ② gzip 실패(ENOSPC 포함) 시 원본을
  # 비우는 갈래를 **동시에** 닫는다 — `&&`가 끊기면 truncate에 못 간다.
  if gzip -c "$f" > "$f.1.gz.tmp" 2>/dev/null && mv "$f.1.gz.tmp" "$f.1.gz"; then
    : > "$f"
  else
    rm -f "$f.1.gz.tmp"
    echo "[rotate] $(basename "$f") 압축에 실패해 자르지 않았다. 원본은 그대로다." >&2
  fi
  rmdir "$lock" 2>/dev/null || true
  return 0
}
