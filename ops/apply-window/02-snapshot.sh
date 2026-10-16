#!/bin/bash
# 사용: 02-snapshot.sh <라벨> <db> <off|on>   — 전부 읽기. 산출물은 $A/<라벨>-<db>/
#   off|on — 순위 재료를 어느 판독기로 읽나. 관문 전은 off(그때 순위가 읽은 것), 관문 뒤는 on(켠 jar의 순위가 읽을 것)이다.
# 판정이 아니라 귀속 재료다(계획 v2 결정 8) — 하드 단언은 07-judge.sh가 한다.
source "$(dirname "$0")/env.sh"
L="$1"; DB="$2"; AX="$3"; OUT="$A/$L-$DB"; mkdir -p "$OUT"
case "$AX" in off) CA=false;; on) CA=true;; *) die "셋째 인자는 off|on";; esac
D=$(cat "$A/dates.txt"); [ -n "$D" ] || die "프리셋 날짜가 비었다 — 00-precheck.sh부터"
t0=$(date +%s)
(cd "$REPO" && BACKTEST_DB_URL="jdbc:postgresql://localhost:5432/$DB?readOnlyMode=always" ./gradlew backtestProbe --tests '*PresetResultSnapshotTest*' -Dsweep.dates=$D -Dsweep.out=$OUT/preset.tsv -PskipFrontend --rerun > $OUT/preset.log 2>&1) || die "프리셋 스냅숏 실패"
[ -s $OUT/preset.tsv ] && [ "$(stat -f %m $OUT/preset.tsv)" -ge "$t0" ] || die "프리셋 TSV가 이번 실행에서 안 쓰였다"
grep -qF "localhost:5432/$DB?readOnlyMode=always" $OUT/preset.log || die "프리셋 스냅숏이 $DB를 읽었다는 로그가 없다"
(cd "$REPO" && RANK_SNAPSHOT_DB=$DB RANK_SNAPSHOT_OUT=$OUT/rank.tsv RANK_SNAPSHOT_CLASS_AXIS=$CA ./gradlew test --tests '*RankShareCountSnapshotTest*' -PskipFrontend --rerun -i > $OUT/rank.log 2>&1) || die "순위 스냅숏 실패"
grep -q "RANK_SNAPSHOT db=$DB 클래스축=$CA " $OUT/rank.log && [ "$(stat -f %m $OUT/rank.tsv)" -ge "$t0" ] || die "순위 스냅숏이 그 판독기로 안 돌았다"
export_q() { PGOPTIONS='-c statement_timeout=0 -c default_transaction_read_only=on' command psql -d $DB -v ON_ERROR_STOP=1 -At -c "\\copy ($1) to '$2'" > /dev/null || die "추출 실패: $2"; }
export_q "SELECT stock_id, indicator_code, count(*), sum(hashtext(trade_date::text || '|' || value::text)::bigint) FROM indicator_value GROUP BY 1,2" $OUT/groups.tsv
export_q "SELECT f.id, f.stock_id, s.ticker, (s.market_code IN ('KOSPI','KOSDAQ')) kr, f.fiscal_year, f.fiscal_period, f.published_at, f.revision_no, f.is_latest, f.revenue, f.operating_income, f.net_income, f.operating_cash_flow, f.total_assets, f.total_liabilities, f.total_equity, f.shares_outstanding, f.created_at, md5((to_jsonb(f) - 'id' - 'created_at' - 'revision_no' - 'is_latest')::text) FROM financial_statement f JOIN stock s ON s.id=f.stock_id" $OUT/fs.tsv
export_q "SELECT id, market_code, ticker, sic_code FROM stock" $OUT/stock-market.tsv
export_q "SELECT m.universe_code, s.ticker, m.effective_from, m.effective_to, m.origin FROM universe_membership m JOIN stock s ON s.id=m.stock_id ORDER BY 1,2,3" $OUT/membership.tsv
export_q "select stock_id, count(*) from indicator_value where indicator_code='ADJ_CLOSE' group by 1" $OUT/adjclose-counts.tsv
export_q "select trade_date, indicator_code, count(*) from indicator_value where indicator_code like '%\_PCTL' and indicator_code not like '%\_SECTOR\_PCTL' group by 1,2" $OUT/pctl-counts.tsv
export_q "select trade_date, indicator_code, count(*) from indicator_value where indicator_code not like '%\_PCTL' and indicator_code not like '%\_SECTOR\_%' group by 1,2" $OUT/base-counts.tsv
q $DB "select md5(string_agg((f.*)::text, '|' order by f.id)) from financial_statement f join stock s on s.id=f.stock_id where s.market_code in ('KOSPI','KOSDAQ')" > $OUT/kr-fs-hash.txt
PGOPTIONS='-c statement_timeout=120s -c default_transaction_read_only=on' command psql -d $DB -v ON_ERROR_STOP=1 -At -F $'\t' -f $SCRIPTS/a2.sql > $OUT/a2.txt || die "a2 실패"
[ "$(grep -c . $OUT/a2.txt)" -eq 2 ] || die "a2 결과가 2줄이 아니다"
# 펜스 — 세션 훅의 붙잡는 갈래로 전제 전부를 그 DB에서 돈다(러너를 새로 만들지 않는다 · 참·발견·무판정을 전제마다 적는다).
(cd "$REPO" && PREMISE_CAPTURE=$OUT/fences.tsv PREMISE_DB=$DB PREMISE_STATEMENT_TIMEOUT=120 bash .claude/hooks/check-premises.sh </dev/null > $OUT/fences.log 2>&1) || true
[ -s $OUT/fences.tsv ] || die "펜스 캡처가 비었다 — $OUT/fences.log"
cut -f1,3- $OUT/fences.tsv | sort > $OUT/fences-cmp.tsv   # 초 열을 뺀 대조용
for f in groups.tsv fs.tsv stock-market.tsv membership.tsv adjclose-counts.tsv pctl-counts.tsv base-counts.tsv kr-fs-hash.txt fences-cmp.tsv; do [ -s $OUT/$f ] || die "$f가 비었다"; done
echo "OK $OUT · 펜스 $(grep -c . $OUT/fences.tsv)개(무판정 $(cut -f3 $OUT/fences.tsv | grep -c '^\(\[DB 무관\] \)\?<' || true))"
