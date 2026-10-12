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

# ── PostgreSQL stale 락 ──────────────────────────────────────────────────────
#
# **postgres는 자기 stale 락을 스스로 못 치울 때가 있다.** `postmaster.pid`의 PID가
# 살아 있으면 "다른 postmaster가 돈다"고 판단해 그 자리에서 FATAL로 끝나는데, 그 판정이
# **PID 재사용을 구분하지 못한다.** 2026-09-26에 실제로 그랬다 — 어제 죽은 postmaster의
# PID 799를 부팅 직후 launchd가 iCloudNotificationAgent에 재할당했고, postgres가 89회
# 연속으로 기동에 실패하는 동안 앱은 Flyway에서 2.1초 만에 죽었다.
#
# 이 파일 머리가 우리 앱·터널에 대해 적은 것과 **같은 판정이다**(ADR-0043) — 살아 있는지는
# 언제나 "그 PID의 명령줄이 우리 것인가"까지 본다. postgres 락 파일만 그 판정 밖에 있었다.
#
# **MUST NOT: 부팅 시각(락 파일 3행)으로 stale을 판정하지 않는다.** 그 대조는 「부팅 뒤에
# 죽은 postmaster」를 원리적으로 못 잡는다 — 2026-09-20이 그 형태였다(부팅 없이 죽어
# 132분 동안 stale 락이 있었다). 게다가 이 기계는 부팅 후 약 70분이면 PID 공간이 한 바퀴
# 돌아(실측 약 24 PID/초) 그 창 안에서도 재사용이 일어난다. **명령줄 대조가 양방향으로
# 면역이고 부팅 시각은 사정거리만 좁힌다.**
PG_DATADIR="/opt/homebrew/var/postgresql@16"

# classify_postmaster_lock <락 파일 경로>
# NONE(파일 없음) · UNREADABLE(형식이 깨짐) · LIVE(진짜 postmaster) · STALE 중 하나를 찍는다.
#
# **판정만 한다 — 아무것도 지우지 않고 pg_isready도 안 본다.** 갈라 둔 이유는 대조군 때문이다.
# 제거까지 한 함수에 넣으면 시험이 DB를 끄지 않고는 판정을 태울 수 없고, 그러면 가장 위험한
# 실패(판정이 「무조건 STALE」로 망가지는 것)를 어떤 대조군으로도 못 잡는다.
classify_postmaster_lock() {
  local lock="$1" pid datadir cmd
  [[ -f "$lock" ]] || { echo "NONE"; return 0; }

  pid="$(sed -n '1p' "$lock" 2>/dev/null || true)"
  # MUST: 산술·비교에 쓰기 전에 숫자인지 본다. 기동 중 락은 8행이 `starting`이고, 잘린
  # 파일은 1행이 비거나 문자열이다. bash 산술은 문자열을 변수명으로 읽어 `set -u`에서
  # 죽으므로, 검사 없이 쓰면 이 함수가 start.sh를 그 자리에서 끝낸다.
  # **그리고 읽을 수 없는 것은 STALE이 아니다** — 판정 못 한 것을 지우는 쪽으로 보내면
  # 살아 있는 DB의 락을 지우고 같은 datadir에 두 번째 인스턴스가 뜬다.
  [[ "$pid" =~ ^[0-9]+$ ]] || { echo "UNREADABLE"; return 0; }

  datadir="$(sed -n '2p' "$lock" 2>/dev/null || true)"
  [[ -n "$datadir" ]] || { echo "UNREADABLE"; return 0; }

  cmd="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  # PID 자체가 없다. postgres도 이 경우는 스스로 치운다.
  [[ -n "$cmd" ]] || { echo "STALE"; return 0; }

  # 살아 있다 — 그것이 **이 datadir의 postmaster인가**가 갈림길이다.
  # postmaster는 argv를 안 바꾼다(자식만 `postgres: checkpointer`처럼 바꾼다). 그래서
  # 이 대조가 선다. 끝 또는 공백까지 보는 이유는 `@16`이 `@16-test`에 걸리지 않게 하려는 것이다.
  if [[ "$cmd" == *"postgres"* ]] && { [[ "$cmd" == *" -D $datadir" ]] || [[ "$cmd" == *" -D $datadir "* ]]; }; then
    echo "LIVE"
  else
    # PID가 재사용됐다. 2026-09-26의 형태이고, **postgres가 스스로 못 고치는 유일한 갈래다.**
    echo "STALE"
  fi
  return 0
}

# ensure_postgres_up <라벨>
# DB가 뜰 때까지 손쓴다. 앱을 띄우기 **전에** 부른다 — 안 그러면 Flyway가 2초 만에 죽는데
# start.sh는 60초를 기다린 뒤 "로그를 봐라"만 남긴다.
# 고칠 수 있으면 고치고, 아니면 **무엇이 막고 있는지 이름을 대고** 1을 반환한다.
ensure_postgres_up() {
  local label="$1" isready rc verdict lock backup db_url i
  # MUST: `-d`를 준다. 없으면 libpq가 **사용자명을 DB 이름으로** 써서 서버가 매 호출마다
  # `FATAL: database "na1011" does not exist`를 로그에 남긴다(2026-09-26 대조군: 준 호출은
  # 안 늘고, 안 준 호출은 1건 는다). 종료코드는 `-d` 유무로 안 바뀌므로 판정은 그대로다 —
  # 없는 DB를 줘도 rc=0이고 서버가 무응답일 때만 rc=2다. 바뀌는 것은 **로그 오염**뿐이고,
  # 그 노이즈가 진짜 FATAL과 섞여 사고 진단을 방해한다.
  local db="${DB_NAME:-stock_screener}"

  # 사정거리 — 로컬 postgres에만 선다. DB_URL이 다른 호스트면 락 파일은 그 기계에 있고
  # 여기서 보는 것은 무관한 파일이다. 없으면 application.yaml의 기본값(localhost:5432)이다.
  db_url="$(grep -E '^DB_URL=' "$DEPLOY/.env" 2>/dev/null | head -1 | cut -d= -f2- || true)"
  if [[ -n "$db_url" && "$db_url" != *"//localhost:"* && "$db_url" != *"//127.0.0.1:"* ]]; then
    echo "[$label] DB_URL이 로컬이 아니다 — postmaster 락 판정을 건너뛴다."
    return 0
  fi

  # MUST: pg_bin으로 찾는다. postgresql@16은 keg-only라 **깨끗한 PATH에 pg_isready가 없다.**
  isready="$(pg_bin pg_isready)" || {
    echo "[$label] pg_isready가 없어 DB 상태를 확인하지 못했다 — 앱 기동을 그대로 시도한다." >&2
    return 0
  }

  # 종료코드를 가른다. 0 수락 · 1 거부(기동·복구 중) · 2 무응답 · 3 시도 안 함.
  # **1과 2를 뭉치면 정상 복구를 사고로 신고한다** — 크래시 뒤 redo는 크기에 따라 분 단위가 된다.
  "$isready" -h localhost -p 5432 -d "$db" -q && return 0
  rc=$?
  if [[ $rc -eq 1 ]]; then
    echo "[$label] DB가 기동·복구 중이다 — 최대 120초 기다린다."
    for ((i = 0; i < 120; i++)); do
      "$isready" -h localhost -p 5432 -d "$db" -q && { echo "[$label] DB OK (${i}초)."; return 0; }
      sleep 1
    done
    echo "[$label] DB가 120초 안에 복구를 끝내지 못했다. /opt/homebrew/var/log/postgresql@16.log를 본다." >&2
    return 1
  fi

  # 여기부터는 응답이 없다. 락 파일이 막고 있는지 본다.
  lock="$PG_DATADIR/postmaster.pid"
  verdict="$(classify_postmaster_lock "$lock")"
  case "$verdict" in
    NONE)
      echo "[$label] DB가 응답하지 않는데 postmaster 락은 없다 — stale 락이 원인이 아니다." >&2
      echo "        켜려면: brew services start postgresql@16" >&2
      return 1
      ;;
    LIVE|UNREADABLE)
      # **판정한 것만 지운다.** LIVE는 진짜 postmaster이고, UNREADABLE은 판정 자체가 안 된 것이다.
      echo "[$label] DB가 응답하지 않지만 postmaster 락을 지울 근거가 없다 (판정: $verdict)." >&2
      echo "        락: $lock" >&2
      return 1
      ;;
  esac

  # STALE이다. 옮겨 둔다 — 지우지 않는 이유는 무엇이 남아 있었는지가 사후에 유일한 증거라서다.
  backup="$lock.stale-$(date '+%Y%m%d-%H%M%S')"
  echo "[$label] stale한 postmaster 락을 걷는다 (PID $(sed -n '1p' "$lock")는 postgres가 아니거나 없다)."
  mv "$lock" "$backup" || { echo "[$label] 락을 옮기지 못했다: $lock" >&2; return 1; }
  echo "        옮긴 자리: $backup"

  # **MUST NOT: 여기서 `brew services start`를 부르지 않는다.** KeepAlive=true라 launchd가
  # 10초마다 스스로 재시도하고(사고 당일 89회가 그 증거다) 락이 걷히면 그 다음 시도가 뜬다.
  # 게다가 이미 bootstrap된 라벨에 다시 bootstrap하면 `Bootstrap failed: 5: Input/output
  # error`로 brew가 비0으로 죽어, `set -e`인 start.sh가 여기서 끝난다.
  for ((i = 0; i < 40; i++)); do
    "$isready" -h localhost -p 5432 -d "$db" -q && { echo "[$label] DB OK (${i}초)."; return 0; }
    sleep 1
  done
  echo "[$label] 락을 걷었으나 DB가 40초 안에 뜨지 않았다 — 자동 재시도가 꺼져 있을 수 있다." >&2
  echo "        켜려면: brew services start postgresql@16" >&2
  return 1
}

# ── 쓰기 잠금(ADR-0175) ─────────────────────────────────────────────────────────
#
# 사망 술어는 pid + 프로세스 시작 초뿐이다 — Java(ProcessHandle 초 절사)와 셸(LC_ALL=C ps -o lstart)이 같은 값을 낸다
# (20/20 실측 · 잠금 v3). MUST NOT: 부팅 시각을 넣지 않는다 — 같은 부팅 안에서도 움직여 산 보유자를 죽었다고 판정했다.

# proc_start_epoch <pid> — 그 프로세스의 시작 epoch 초. 없으면 아무것도 안 찍고 1.
proc_start_epoch() {
  local pid="$1" lstart
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  lstart="$(LC_ALL=C ps -o lstart= -p "$pid" 2>/dev/null)" || return 1
  # ps가 뒤에 공백을 붙인다 — 그대로 넘기면 date가 경고를 stderr에 찍는다.
  lstart="${lstart%"${lstart##*[![:space:]]}"}"
  [[ -n "$lstart" ]] || return 1
  LC_ALL=C date -j -f "%a %b %d %T %Y" "$lstart" +%s 2>/dev/null
}

# proc_alive <pid> <시작초> — 그 신원의 프로세스가 살아 있으면 0.
proc_alive() {
  local pid="$1" start="$2" actual
  actual="$(proc_start_epoch "$pid")" || return 1
  [[ "$actual" == "$start" ]]
}

# lock_member_alive <kind:pid:시작초> — 세션 구성원(jvm:… · exec:…)이 살아 있으면 0. 꼴이 틀리면 산 것으로 친다(빼앗지 않는 쪽).
lock_member_alive() {
  local member="$1" kind pid start
  IFS=':' read -r kind pid start <<< "$member"
  if [[ -z "$kind" || ! "$pid" =~ ^[0-9]+$ || ! "$start" =~ ^[0-9]+$ ]]; then
    return 0
  fi
  proc_alive "$pid" "$start"
}

# write_lock_holder <db> — 잠금 보유자를 한 줄로 찍는다(`ops/status.sh` · `ops/start.sh` · `ops/deploy.sh`).
# 출력: 「없음」 · 「WEB pid 123 시작 … 목적 …」 · 「세션 abc …」 · 표가 없으면 「표 없음」. psql이 실패하면 1.
write_lock_holder() {
  local db="$1" psql row
  psql="$(pg_bin psql)" || return 1
  row="$(PGCONNECT_TIMEOUT=3 PGAPPNAME=ss:ops "$psql" -w -X -d "$db" -tAF'|' -c "
      SELECT CASE WHEN to_regclass('public.write_lock') IS NULL THEN 'ABSENT' ELSE 'PRESENT' END" 2>/dev/null)" || return 1
  if [[ "$row" == "ABSENT" ]]; then
    echo "표 없음"
    return 0
  fi
  row="$(PGCONNECT_TIMEOUT=3 PGAPPNAME=ss:ops "$psql" -w -X -d "$db" -tAF'|' -c "
      SELECT coalesce(holder_kind, ''), coalesce(holder_pid::text, ''), coalesce(holder_start::text, ''),
             coalesce(session_id, ''), coalesce(purpose, ''), coalesce(to_char(acquired_at, 'MM-DD HH24:MI:SS'), ''),
             coalesce(array_to_string(members, ','), ''), coalesce(waiter_purpose, '')
        FROM (SELECT 1) one LEFT JOIN write_lock l ON l.db_name = current_database()" 2>/dev/null)" || return 1
  local kind pid start session purpose since members waiter
  IFS='|' read -r kind pid start session purpose since members waiter <<< "$row"
  if [[ -z "$kind" ]]; then
    echo "없음${waiter:+ (대기: $waiter)}"
  elif [[ "$kind" == "SESSION" ]]; then
    echo "손 세션 「${session}」 (${since}부터 · 구성원 ${members:-없음})${waiter:+ (대기: $waiter)}"
  else
    local state="산"
    proc_alive "$pid" "$start" || state="죽은"
    echo "$kind pid $pid(${state} 프로세스) 「${purpose}」 (${since}부터)${waiter:+ (대기: $waiter)}"
  fi
}

# write_guard_switch <db> — 쓰기 가드 스위치를 한 줄로(ops/status.sh). 「켜짐(전부)」 · 「켜짐(범위 <접두>)」 · 「꺼짐」 ·
# 「표 없음(V66 전)」. psql이 실패하면 1. MUST: 표 존재는 따로 묻는다(같은 문장이면 V66 전 DB에서 파싱 단계에 죽는다).
write_guard_switch() {
  local db="$1" psql row
  psql="$(pg_bin psql)" || return 1
  row="$(PGCONNECT_TIMEOUT=3 PGAPPNAME=ss:ops "$psql" -w -X -d "$db" -tA -c "
      SELECT to_regclass('public.guard_setting') IS NOT NULL" 2>/dev/null)" || return 1
  if [[ "$row" != "t" ]]; then
    echo "표 없음(V66 전)"
    return 0
  fi
  row="$(PGCONNECT_TIMEOUT=3 PGAPPNAME=ss:ops "$psql" -w -X -d "$db" -tA -c "
      SELECT coalesce((SELECT CASE WHEN identity_prefix IS NULL THEN '켜짐(전부)' ELSE '켜짐(범위 ' || identity_prefix || ')' END
                         FROM guard_setting WHERE db_name = current_database()), '꺼짐')" 2>/dev/null)" || return 1
  echo "$row"
}

# write_lock_held_by_other <db> <내 pid> — 잠금을 「다른」 산 보유자가 쥐고 있으면 그 설명을 찍고 0. 비었거나 죽었거나 내 것이면
# (표가 없는 V66 전 DB 포함) 1. **물어보지 못했으면 2**(psql 없음 · 접속 실패 · 질의 실패) — MUST: 호출자는 2를 「안 쥠」으로
# 읽지 않는다. 쓰기 경로(start · deploy)는 멈추고 표시 경로는 「판정하지 못했다」로 찍는다.
# 세션은 산 구성원이 있거나 마지막 활동에서 60분이 안 지났으면 산 것으로 본다(Java WriteLock.SESSION_TTL과 같은 술어).
write_lock_held_by_other() {
  local db="$1" mine="${2:-}" psql row
  psql="$(pg_bin psql)" || return 2
  row="$(PGCONNECT_TIMEOUT=3 PGAPPNAME=ss:ops "$psql" -w -X -d "$db" -tAF'|' -c "
      SELECT CASE WHEN to_regclass('public.write_lock') IS NULL THEN 'ABSENT' END" 2>/dev/null)" || return 2
  [[ "$row" == "ABSENT" ]] && return 1
  row="$(PGCONNECT_TIMEOUT=3 PGAPPNAME=ss:ops "$psql" -w -X -d "$db" -tAF'|' -c "
      SELECT coalesce(holder_kind, ''), coalesce(holder_pid::text, ''), coalesce(holder_start::text, ''),
             coalesce(session_id, ''), coalesce(array_to_string(members, ','), ''),
             ${WRITE_SESSION_IDLE_SECONDS_SQL}
        FROM (SELECT 1) one LEFT JOIN write_lock l ON l.db_name = current_database()" 2>/dev/null)" || return 2
  local kind pid start session members idle member
  IFS='|' read -r kind pid start session members idle <<< "$row"
  [[ -z "$kind" ]] && return 1
  if [[ "$kind" == "SESSION" ]]; then
    local alive=0
    IFS=',' read -ra list <<< "$members"
    for member in "${list[@]:-}"; do
      [[ -n "$member" ]] && lock_member_alive "$member" && alive=1
    done
    if [[ $alive -eq 1 ]] || ! write_session_idle_expired "$idle"; then
      echo "손 세션 「${session}」(구성원 ${members:-없음} · 유휴 $((idle / 60))분)"
      return 0
    fi
    return 1
  fi
  [[ -n "$mine" && "$pid" == "$mine" ]] && return 1
  if proc_alive "$pid" "$start"; then
    echo "$kind pid $pid"
    return 0
  fi
  return 1
}

# write_lock_held_by_jvm <db> <pid> — 그 JVM(pid + 지금의 lstart 시작 초)이 잠금을 쥐고 있으면 목적을 찍고 0. 아니면(비었음 ·
# 남의 것 · 표 없음 · 그 pid가 없음) 1. psql이 실패하면 2 — 호출자는 이것을 「안 쥠」으로 읽지 않는다.
# 앱을 내리기 전에 묻는다(ops/stop.sh · ops/deploy.sh): 앱이 쥐었다는 것은 크론·관리자 잡·판정 PUT이 지금 쓰는 중이라는 뜻이다.
write_lock_held_by_jvm() {
  local db="$1" pid="$2" start psql row kind holder holder_start purpose
  start="$(proc_start_epoch "$pid")" || return 1
  psql="$(pg_bin psql)" || return 2
  row="$(PGCONNECT_TIMEOUT=3 PGAPPNAME=ss:ops "$psql" -w -X -d "$db" -tAF'|' -c "
      SELECT CASE WHEN to_regclass('public.write_lock') IS NULL THEN 'ABSENT' END" 2>/dev/null)" || return 2
  [[ "$row" == "ABSENT" ]] && return 1
  row="$(PGCONNECT_TIMEOUT=3 PGAPPNAME=ss:ops "$psql" -w -X -d "$db" -tAF'|' -c "
      SELECT coalesce(holder_kind, ''), coalesce(holder_pid::text, ''), coalesce(holder_start::text, ''), coalesce(purpose, '')
        FROM (SELECT 1) one LEFT JOIN write_lock l ON l.db_name = current_database()" 2>/dev/null)" || return 2
  IFS='|' read -r kind holder holder_start purpose <<< "$row"
  if [[ -n "$kind" && "$kind" != "SESSION" && "$holder" == "$pid" && "$holder_start" == "$start" ]]; then
    echo "${kind} pid ${pid} 「${purpose}」"
    return 0
  fi
  return 1
}

# 세션이 구성원 없이 버티는 시간(분). MUST: Java WriteLock.SESSION_TTL과 같다(WriteSessionScriptTest가 대조한다).
WRITE_SESSION_TTL_MINUTES=60

# 세션의 유휴 초 — write_lock 행을 읽는 SELECT의 한 칸으로 넣는다. MUST: 만료 경계는 Java(WriteLock.sessionExpired: 마지막 활동 +
# TTL ≤ 지금이면 만료)와 같아야 한다 — 분으로 반올림하면 59분 30초를 60분으로 읽어 Java가 산 세션을 셸이 만료로 본다. 초를 내림하고
# 「유휴 초 ≥ TTL × 60」로 비교하면 같은 경계다. 활동 시각이 둘 다 없으면 Java처럼 만료로 친다.
WRITE_SESSION_IDLE_SECONDS_SQL="coalesce(floor(extract(epoch FROM now() - coalesce(last_activity_at, acquired_at)))::bigint::text, '999999999')"

# write_session_idle_expired <유휴 초> — TTL을 넘겼으면 0.
write_session_idle_expired() {
  [[ "$1" -ge $((WRITE_SESSION_TTL_MINUTES * 60)) ]]
}
