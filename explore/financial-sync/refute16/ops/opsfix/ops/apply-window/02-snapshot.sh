#!/bin/bash
# 사용: 02-snapshot.sh <라벨> <db>   — 전부 읽기. 산출물은 $A/<라벨>-<db>/
source "$(dirname "$0")/env.sh"
L="$1"; DB="$2"; OUT="$A/$L-$DB"; mkdir -p "$OUT"
LATEST=$(cut -d= -f2 $A/latest.txt); [ -n "$LATEST" ] || die "LATEST가 비었다"
D=2025-09-01,2025-09-26,2025-10-15,2025-10-31,2025-11-06,2025-11-28,2025-12-12,2026-01-16,2026-02-20,2026-03-16,2026-04-03,2026-05-15,2026-07-10,2026-07-24,2026-09-07,2026-09-08,2026-09-09,2026-09-10,2026-09-11,2026-09-14,$LATEST
t0=$(date +%s)
(cd $REPO && BACKTEST_DB_URL="jdbc:postgresql://localhost:5432/$DB?readOnlyMode=always" ./gradlew backtestProbe --tests '*PresetResultSnapshotTest*' -Dsweep.dates=$D -Dsweep.out=$OUT/preset.tsv -PskipFrontend --rerun > $OUT/preset.log 2>&1) || die "프리셋 스냅숏 실패"
[ -s $OUT/preset.tsv ] && [ "$(stat -f %m $OUT/preset.tsv)" -ge "$t0" ] || die "프리셋 TSV가 이번 실행에서 안 쓰였다"
grep -qF "localhost:5432/$DB?readOnlyMode=always" $OUT/preset.log || die "프리셋 스냅숏이 $DB를 읽었다는 로그가 없다"
(cd $REPO && RANK_SNAPSHOT_DB=$DB RANK_SNAPSHOT_OUT=$OUT/rank.tsv ./gradlew test --tests '*RankShareCountSnapshotTest*' -PskipFrontend --rerun -i > $OUT/rank.log 2>&1) || die "순위 스냅숏 실패"
grep -q "RANK_SNAPSHOT db=$DB " $OUT/rank.log && [ "$(stat -f %m $OUT/rank.tsv)" -ge "$t0" ] || die "순위 스냅숏이 안 돌았다"
export_q() { PGOPTIONS='-c statement_timeout=0' command psql -d $DB -v ON_ERROR_STOP=1 -At -c "\\copy ($1) to '$2'" > /dev/null || die "추출 실패: $2"; }
export_q "SELECT stock_id, indicator_code, count(*), sum(hashtext(trade_date::text || '|' || value::text)::bigint) FROM indicator_value GROUP BY 1,2" $OUT/groups.tsv
export_q "SELECT f.id, f.stock_id, s.ticker, (s.market_code IN ('KOSPI','KOSDAQ')) kr, f.fiscal_year, f.fiscal_period, f.published_at, f.revision_no, f.is_latest, f.revenue, f.operating_income, f.net_income, f.operating_cash_flow, f.total_assets, f.total_liabilities, f.total_equity, f.shares_outstanding, f.created_at, md5((to_jsonb(f) - 'id' - 'created_at' - 'revision_no' - 'is_latest')::text) FROM financial_statement f JOIN stock s ON s.id=f.stock_id" $OUT/fs.tsv
export_q "SELECT id, market_code FROM stock" $OUT/stock-market.tsv
export_q "select stock_id, count(*) from indicator_value where indicator_code='ADJ_CLOSE' group by 1" $OUT/adjclose-counts.tsv
export_q "select trade_date, indicator_code, count(*) from indicator_value where indicator_code like '%\_PCTL' and indicator_code not like '%\_SECTOR\_PCTL' group by 1,2" $OUT/pctl-counts.tsv
export_q "select trade_date, indicator_code, count(*) from indicator_value where indicator_code not like '%\_PCTL' and indicator_code not like '%\_SECTOR\_%' group by 1,2" $OUT/base-counts.tsv
q $DB "select md5(string_agg((f.*)::text, '|' order by f.id)) from financial_statement f join stock s on s.id=f.stock_id where s.market_code in ('KOSPI','KOSDAQ')" > $OUT/kr-fs-hash.txt
PGOPTIONS='-c statement_timeout=120s' command psql -d $DB -v ON_ERROR_STOP=1 -At -F $'\t' -f $SCRIPTS/a2.sql > $OUT/a2.txt || die "a2 실패"
[ "$(grep -c . $OUT/a2.txt)" -eq 2 ] || die "a2 결과가 2줄이 아니다"
python3 $SCRIPTS/premises.py $REPO $DB $OUT/fences.txt
for f in groups.tsv fs.tsv stock-market.tsv adjclose-counts.tsv pctl-counts.tsv base-counts.tsv kr-fs-hash.txt fences.txt; do [ -s $OUT/$f ] || die "$f가 비었다"; done
echo "OK $OUT"
