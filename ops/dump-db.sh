#!/bin/bash
#
# DB 전체를 덤프한다. **되돌릴 수 없는 소급 재작성 앞에 두는 안전망이다**(ADR-0049).
#
# 스크립트로 두는 이유는 셋이다.
#
# 1. **파일이 생긴 것과 복원할 수 있는 것이 다르다.** 중간에 죽은 pg_dump도 파일을 남기고
#    크기만 보면 성공으로 보인다. 그래서 `.tmp`에 쓴 뒤 원자적으로 옮기고, 옮긴 뒤에
#    `pg_restore -l`로 TOC를 실제로 읽어 테이블 데이터가 전부 들어 있는지 센다.
#    `guard-rebuild-without-dump.sh`가 "덤프가 있는가"를 파일 존재로 판정하므로,
#    **반쯤 쓰인 파일이 남으면 그 가드가 통과한다** — 원자적 교체가 그것을 막는다.
# 2. **복원 명령을 그 자리에서 알려 준다.** 되돌리는 사람이 덤프 형식(`-Fc`)과 `pg_restore`
#    플래그를 그때 알아내야 하면 알아내지 못한다.
# 3. **오래된 덤프를 지우지 않는다.** 자동 정리는 "무엇이 사라졌는지 나중에도 알기 어렵다"에
#    정면으로 걸린다(CLAUDE.md). 총 크기와 가장 오래된 것만 알리고 판단은 사람에게 남긴다.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "$HERE/lib.sh"

DB="${DB_NAME:-stock_screener}"

PG_DUMP="$(pg_bin pg_dump)" || {
  echo "[dump] pg_dump을 찾지 못했다. postgresql@16이 설치돼 있는지 본다." >&2
  exit 1
}
PG_RESTORE="$(pg_bin pg_restore)" || {
  echo "[dump] pg_restore를 찾지 못했다. 검증할 수 없으므로 덤프하지 않는다." >&2
  exit 1
}
PSQL="$(pg_bin psql)" || {
  echo "[dump] psql을 찾지 못했다. 대조군 테이블 수를 셀 수 없으므로 덤프하지 않는다." >&2
  exit 1
}

mkdir -p "$DUMP_DIR"
chmod 700 "$DUMP_DIR"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
TARGET="$DUMP_DIR/$DB-$STAMP.dump"
TMP="$TARGET.tmp"

# 실패했으면 `.tmp`를 남기지 않는다. 남으면 다음 실행이 그것을 덤프로 셀 수 있다.
cleanup() { [[ -f "$TMP" ]] && rm -f "$TMP"; }
trap cleanup EXIT

# 쓰기 잠금 행은 데이터를 뺀다(ADR-0175 · 잠금 결정 1) — 그 행은 덤프를 뜬 순간의 보유자(pid · 세션)라, 복원한 DB에 옮겨지면
# 이 기계의 다른 프로세스나 끝난 세션을 보유자로 읽는다. 복원 뒤 행 없음 = 비보유다. 표 정의는 그대로 든다.
# 스위치(guard_setting)는 데이터째 든다 — 행이 DB 이름이라 다른 이름으로 복원한 사본에서는 저절로 꺼진다.
LOCK_DATA_EXCLUDED=""
if [[ "$("$PSQL" -d "$DB" -At -c "SELECT to_regclass('public.write_lock') IS NOT NULL")" == "t" ]]; then
  LOCK_DATA_EXCLUDED="--exclude-table-data=public.write_lock"
fi

echo "== 덤프 =="
echo "[dump] $DB → $TARGET"
"$PG_DUMP" -Fc -Z6 ${LOCK_DATA_EXCLUDED:+"$LOCK_DATA_EXCLUDED"} -d "$DB" -f "$TMP"

echo "== 검증 =="
# public 스키마의 실제 테이블 수에서 데이터를 뺀 표를 뺀 것. 덤프의 TABLE DATA 항목 수와 같아야 한다.
expected="$("$PSQL" -d "$DB" -At -c \
  "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")"
if [[ -n "$LOCK_DATA_EXCLUDED" ]]; then
  expected=$((expected - 1))
fi
actual="$("$PG_RESTORE" -l "$TMP" | grep -c 'TABLE DATA' || true)"

if [[ "$actual" -ne "$expected" ]]; then
  echo "[dump] TOC의 테이블 데이터가 ${actual}개인데 public 스키마에는 ${expected}개다." >&2
  echo "[dump] 덤프가 잘렸거나 일부 테이블이 빠졌다 — 이 파일로는 복원이 성립하지 않는다." >&2
  exit 1
fi

# 이 phase가 되돌려야 하는 것이 정확히 이 셋이다. 이름으로 확인한다 — 수만 세면 무엇이
# 들어 있는지 알 수 없다.
for table in corporate_action price_daily indicator_value; do
  "$PG_RESTORE" -l "$TMP" | grep -q "TABLE DATA public $table " || {
    echo "[dump] TOC에 ${table}의 데이터가 없다 — 이 덤프로는 P9를 되돌릴 수 없다." >&2
    exit 1
  }
done

# 여기까지 왔으면 쓸 수 있는 덤프다. 이제 이름을 준다.
mv "$TMP" "$TARGET"
trap - EXIT

echo "[dump] OK 테이블 ${actual}개 · $(du -h "$TARGET" | cut -f1)"

echo "== 되돌리는 법 =="
cat <<RESTORE
  # 복원 전에 잠금 보유자를 본다: ops/write-session.sh status --db $DB — 남(bootRun · 다른 세션)이 쥐었으면 멈춘다(ADR-0175).
  # 복원 전에 가드 스위치를 적어 둔다: ops/status.sh 의 「가드」 줄 — 아래 -c 전체 복원은 guard_setting을 덤프 시점 값으로 되돌린다.
  # 복원 뒤 같은 줄을 대조하고, 다르면 사용자 승인으로 맞춘다(운영 스위치는 원시 SQL · 사용자 승인).
  #
  # 무신원 쓰기가 거절되는지는 덤프 시대에 따라 다르다(가드가 켜진 DB):
  #   - 이 덤프(잠금 표가 든 덤프)의 -c 전체 복원: 표를 지우고 다시 만들며 가드·이벤트 트리거는 데이터 뒤에 만들어진다 —
  #     데이터 단계에 가드가 없어 무신원으로도 들어가고, write_lock은 비어서 끝난다(데이터를 안 담았다 · 세션을 다시 begin한다).
  #   - 잠금 표가 없는 옛 덤프의 -c 복원: 가드 객체가 덤프에 없어 남아 있다 — 이벤트 트리거가 새 표에 가드를 붙여 무신원 COPY가
  #     SSWG1로 거절된다 → ops/write-session.sh begin <id> 뒤 exec <id> -- 로 감싼다.
  #   - 아래의 좁힌 되돌리기(TRUNCATE · --data-only): 가드가 그대로라 무신원이면 거절된다 → 세션 exec로 감싼다.
  #
  # 스키마째 되돌린다. 도는 앱을 먼저 내린다(ops/stop.sh --app — 앱 크론이 남았으면 멈춘다 · 넘기는 것은 사람: --force)
  # — 연결이 열려 있으면 -c가 막힌다.
  $PG_RESTORE -d $DB -c --if-exists -j4 "$TARGET"

  # 이 phase만 되돌리려면 데이터만 좁혀 되돌린다 — 앱을 내린 채(두 명령 사이에 쓰면 섞인다).
  # MUST NOT: -c와 -t를 같이 쓰지 않는다. 표 정의만 다시 만들어 PK·FK·인덱스를 잃고, 참조받는 표
  # (corporate_action ← corporate_action_distribution)는 못 지운 채 옛 행으로 남는다(2026-10-06 사본 실측).
  # RESTART IDENTITY를 붙이지 않는다 — 시퀀스가 이어져야 복원 뒤 새 행이 옛 id와 안 부딪는다.
  psql -d $DB -v ON_ERROR_STOP=1 -c "TRUNCATE corporate_action, corporate_action_distribution, price_daily, indicator_value"
  $PG_RESTORE -d $DB --data-only -t corporate_action -t corporate_action_distribution -t price_daily -t indicator_value "$TARGET"

  # 복원 뒤 기동이 FlywayValidateException으로 죽으면 덤프가 못 쓰는 것이 아니다.
  # 덤프를 뜬 뒤에 마이그레이션 파일이 고쳐져 체크섬이 갈린 것이고, 실패 메시지가
  # Applied to database와 Resolved locally를 불일치 전부에 대해 한 번에 찍는다.
  # 그 Resolved locally 값으로 맞춘다 — 재적용이 아니라 값만 고치는 것이다.
  psql -d $DB -c "UPDATE flyway_schema_history SET checksum=<Resolved locally> WHERE version='<N>'"
RESTORE

echo "== 쌓인 덤프 =="
echo "[dump] $DUMP_DIR — 총 $(du -sh "$DUMP_DIR" | cut -f1) / $(find "$DUMP_DIR" -name '*.dump' | wc -l | tr -d ' ')개"
oldest="$(find "$DUMP_DIR" -name '*.dump' -print0 | xargs -0 ls -tr 2>/dev/null | head -1 || true)"
[[ -n "$oldest" ]] && echo "[dump] 가장 오래된 것: $oldest"
echo "[dump] 자동으로 지우지 않는다. 지울 것은 사람이 고른다."
