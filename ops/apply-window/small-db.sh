#!/bin/bash
# 사용: small-db.sh <새 DB 이름> <표본 파일>   — 표본 파일은 한 줄에 「시장<TAB>티커」(# 줄은 주석 · 셋째 칸부터 고른 근거).
# 운영의 스키마 + 표본 종목의 행만 옮긴 작은 DB를 만든다(CLAUDE.md 「드라이런이란 무엇인가」 ④ · 계획 v2 결정 6). 운영은 읽기만 한다.
#   - stock_id가 있는 표 → 표본 종목 행만 · stock → 표본 행만
#   - stock_id가 없는 자식이 stock_id가 있는 부모를 FK로 부르면 → 표본 부모 행을 부르는 자식 행만(pg_constraint에서 실행 때 읽는다)
#   - 나머지 → 통째(전부 7.6MB 이하 · 2026-10-16 실측)
#   - 시퀀스는 운영 값으로 맞춘다(안 맞추면 새 행이 옮긴 id와 부딪는다) · 그 DB 이름의 guard_setting 행을 넣는다(쓰기 세션 경로를 그대로 태운다)
#   - post-data(제약·색인·트리거)를 마지막에 — 고아 행이 있으면 여기서 실패해 드러난다
# 끝에 표마다 「작은 DB 행 수 = 운영의 같은 술어 행 수」를 단언한다(G1).
# MUST: 이름은 stock_screener_gate_로 시작한다 — 판정이 끝나면 지울 재료라는 표지다. 이미 있으면 멈춘다(덮어쓰지 않는다).
set -euo pipefail
source "$(dirname "$0")/env.sh"
DB="$1"; SAMPLE="$2"
case "$DB" in stock_screener_gate_*) ;; *) die "작은 DB 이름은 stock_screener_gate_로 시작한다: $DB";; esac
[ -s "$SAMPLE" ] || die "표본 파일이 없다: $SAMPLE"
PGDUMP="$(pg_bin pg_dump)" || die "pg_dump를 못 찾았다"
RO=(env PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=0')
live() { "${RO[@]}" psql -d "$LIVE" -v ON_ERROR_STOP=1 -At -F $'\t' -c "$1"; }
small() { PGOPTIONS='-c statement_timeout=0' psql -d "$DB" -v ON_ERROR_STOP=1 -q -At -F $'\t' "$@"; }
[ -z "$(psql -d postgres -At -c "select 1 from pg_database where datname='$DB'")" ] || die "${DB}가 이미 있다"

# 표본 → stock id. 표본 줄마다 정확히 한 행이어야 한다(없는 티커를 조용히 빼면 표본이 근거 없이 줄어든다).
PAIRS=$(grep -v '^#' "$SAMPLE" | awk -F'\t' 'NF>=2 {printf "%s(%s,%s)", (n++?",":""), "'\''"$1"'\''", "'\''"$2"'\''"}')
[ -n "$PAIRS" ] || die "표본이 비었다"
N_SAMPLE=$(grep -v '^#' "$SAMPLE" | awk -F'\t' 'NF>=2' | wc -l | tr -d ' ')
IDS=$(live "select string_agg(s.id::text, ',' order by s.id) from stock s join (values $PAIRS) v(m,t) on s.market_code=v.m and s.ticker=v.t")
N_FOUND=$(echo "$IDS" | tr ',' '\n' | grep -c . || true)
[ "$N_FOUND" -eq "$N_SAMPLE" ] || die "표본 ${N_SAMPLE}줄 중 ${N_FOUND}종목만 stock에 있다 — 없는 줄: $(live "select string_agg(v.m||':'||v.t, ' ') from (values $PAIRS) v(m,t) where not exists (select 1 from stock s where s.market_code=v.m and s.ticker=v.t)")"
echo "$IDS" > "$A/small-$DB-ids.txt"
ARR="'{$IDS}'::bigint[]"

createdb "$DB"
"$PGDUMP" -d "$LIVE" --schema-only --section=pre-data | small -f - > /dev/null

# 표마다 옮길 술어. 자식 표의 FK 짝은 운영 카탈로그에서 읽는다(계획 v2 §0 — 지금은 corporate_action_distribution → corporate_action 하나).
has_stock() { [ "$(live "select count(*) from information_schema.columns where table_schema='public' and table_name='$1' and column_name='stock_id'")" = 1 ]; }
where_of() { local t="$1" fk
  if [ "$t" = stock ]; then echo "id = any($ARR)"; return; fi
  if has_stock "$t"; then echo "stock_id = any($ARR)"; return; fi
  fk=$(live "select a.attname || ' ' || c.confrelid::regclass::text || ' ' || fa.attname
             from pg_constraint c
             join pg_attribute a on a.attrelid=c.conrelid and a.attnum=c.conkey[1]
             join pg_attribute fa on fa.attrelid=c.confrelid and fa.attnum=c.confkey[1]
             where c.contype='f' and c.conrelid='public.$t'::regclass and array_length(c.conkey,1)=1
               and exists (select 1 from information_schema.columns k where k.table_schema='public'
                             and k.table_name=c.confrelid::regclass::text and k.column_name='stock_id')")
  if [ -n "$fk" ]; then
    [ "$(echo "$fk" | grep -c .)" -eq 1 ] || die "${t}가 stock_id 표를 둘 이상 부른다 — 술어를 손으로 정한다: $fk"
    set -- $fk; echo "$1 in (select $3 from $2 where stock_id = any($ARR))"; return
  fi
  echo "true"; }
TABLES=$(live "select relname from pg_class where relnamespace='public'::regnamespace and relkind='r' order by relname")
# 운영 앱이 떠 있어도 표 사이에 고아가 안 생기게 전부 한 스냅숏에서 받는다(REPEATABLE READ 읽기 전용 트랜잭션 하나).
D="$A/small-$DB"; mkdir -p "$D"; SQL="$D/export.sql"
{ echo 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;'
  for t in $TABLES; do
    w=$(where_of "$t")
    # 생성 컬럼은 COPY FROM이 거부한다 — 열 목록에서 뺀다(stock의 업종 키 넷).
    cols=$(live "select string_agg(quote_ident(attname), ',' order by attnum) from pg_attribute where attrelid='public.$t'::regclass and attnum>0 and not attisdropped and attgenerated=''")
    echo "$cols" > "$D/$t.cols"; echo "$w" > "$D/$t.where"
    echo "\\copy (select $cols from public.$t where $w) to '$D/$t.copy'"
    echo "select '$t', count(*) from public.$t where $w;"
  done
  echo 'COMMIT;'; } > "$SQL"
"${RO[@]}" psql -d "$LIVE" -v ON_ERROR_STOP=1 -q -At -F $'\t' -f "$SQL" > "$D/live-counts.tsv" || die "운영 스냅숏 받기 실패"
: > "$A/small-$DB-counts.tsv"
while IFS=$'\t' read -r t n_live; do
  cols=$(cat "$D/$t.cols")
  small -c "\\copy public.$t ($cols) from '$D/$t.copy'" || die "$t 싣기 실패"
  n_small=$(small -c "select count(*) from public.$t")
  printf '%s\t%s\t%s\t%s\n' "$t" "$n_live" "$n_small" "$(cat "$D/$t.where")" >> "$A/small-$DB-counts.tsv"
  [ "$n_live" = "$n_small" ] || die "$t 행 수 운영 $n_live ≠ 작은 DB $n_small"
done < "$D/live-counts.tsv"
[ "$(grep -c . "$D/live-counts.tsv")" -eq "$(echo "$TABLES" | wc -w | tr -d ' ')" ] || die "받은 표 수가 표 목록과 다르다"
# 시퀀스를 운영 값으로.
live "select format('select setval(%L, %s, %s);', schemaname||'.'||sequencename, coalesce(last_value, 1), case when last_value is null then 'false' else 'true' end) from pg_sequences where schemaname='public'" \
  | small -f - > /dev/null
"$PGDUMP" -d "$LIVE" --schema-only --section=post-data | small -f - > /dev/null || die "post-data 실패 — 고아 행이나 중복이 있다"
small -c "insert into guard_setting(db_name, note) values ('$DB', '관문 실행기 드라이런 작은 DB — 판정 뒤 지운다')"
small -c 'VACUUM (ANALYZE)'
rm -rf "$D"   # 옮긴 파일은 실은 뒤 필요 없다(드라이런 재료)
echo "OK $DB — 표본 ${N_SAMPLE}종목 · 표 $(echo "$TABLES" | wc -w | tr -d ' ')개 · 행 수 $A/small-$DB-counts.tsv"
