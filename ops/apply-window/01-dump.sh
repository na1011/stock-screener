#!/bin/bash
# 사용: 01-dump.sh   — 되돌리기용 덤프 한 판. 운영은 ops/dump-db.sh(검증·복원 명령까지 · 운영 덤프 폴더),
# 작은 DB는 산출물 폴더에 pg_dump로 직접 — 드라이런 덤프가 운영 되돌리기 덤프 폴더에 섞이지 않게(그 폴더는 자동으로 안 지운다).
source "$(dirname "$0")/env.sh"
if [ "$PROD" = "$LIVE" ]; then
  out=$(DB_NAME="$PROD" "$REPO/ops/dump-db.sh" 2>&1) || { echo "$out"; die "덤프 실패"; }
  echo "$out" | tail -20
  path=$(echo "$out" | sed -n "s/^\[dump\] $PROD → //p" | head -1)
else
  path="$A/dump-$PROD.dump"; [ ! -e "$path" ] || die "${path}가 이미 있다"
  PGOPTIONS='-c statement_timeout=0' "$(pg_bin pg_dump)" -Fc -d "$PROD" -f "$path" || die "작은 DB 덤프 실패"
fi
[ -s "$path" ] || die "덤프 경로를 못 읽었다: $path"
echo "$path" > "$A/dump-path.txt"; echo "OK $path"
