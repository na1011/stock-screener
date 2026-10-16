#!/bin/bash
# 사용: 00-precheck.sh   — 읽기만 한다. $PROD(기본 운영 · 드라이런은 GATE_PROD=<작은 DB>)에 대해 관문을 시작해도 되는지 묻고 대상 수·날짜를 파일로 남긴다.
source "$(dirname "$0")/env.sh"
(cd "$REPO" && git rev-parse HEAD) > "$A/head.txt"
echo "PROD=$PROD HEAD=$(cat "$A/head.txt") 산출물=$A"
[ -z "$(cd "$REPO" && git status --porcelain --untracked-files=no)" ] || die "작업 트리에 추적 파일 변경이 있다"
r=$(q "$PROD" "select count(*) from batch_execution where status='RUNNING'"); [ "$r" = 0 ] || die "RUNNING $r"
# 적재 JVM 판정은 훅·ops/crons-done.sh와 같은 함수다. 판정 불가(2)도 멈춘다.
. "$REPO/.claude/hooks/lib/load-process.sh"; lp=0; stray_load_pids >/dev/null || lp=$?
[ "$lp" = 1 ] || die "bootRun이 떠 있거나 판정하지 못했다(stray_load_pids rc=$lp)"
# 그 주 토요일 리밸런싱이 끝났는가 — 요일과 무관하게 본다(토요일 발화가 빠진 주가 있다: 10-03 · 계획 v2 결정 10).
SAT=$(q "$PROD" "select case when now() < s then s - interval '7 days' else s end from (select (current_date - ((extract(isodow from current_date)::int - 6 + 7) % 7))::timestamp + interval '20 hours' as s) x")
RB=$(q "$PROD" "select status from batch_execution where job_name='universe-rebalance' and started_at >= '$SAT' order by id desc limit 1")
[ "$RB" = SUCCESS ] || die "$SAT 뒤 universe-rebalance가 SUCCESS가 아니다(${RB:-행 없음}) — 손 리밸런싱이 먼저다"
# 마이그레이션 기준은 지금 도는 jar의 커밋이다(배포가 적는 app.jar.commit). 관문 날 V70은 배포(설정 꺼짐)로 먼저 들어간다(계획 결정 2).
JARC=$(sed -n 's/^commit=//p' "$HOME/Library/Application Support/stock-screener/app.jar.commit" 2>/dev/null)
[ -n "$JARC" ] && [ "$JARC" != unknown ] || die "app.jar.commit이 커밋을 가리키지 않는다 — 운영 jar를 모른다"
(cd "$REPO" && git cat-file -e "$JARC^{commit}") 2>/dev/null || die "운영 jar 커밋($JARC)을 저장소가 모른다"
[ -z "$(cd "$REPO" && git diff --stat "$JARC"..HEAD -- src/main/resources/db)" ] || die "운영 jar($JARC) 뒤 마이그레이션 변화가 있다"
# 대상 — 잡의 술어를 복제한다(StockRegistrar.usRankingCandidateTickers · 현 편입은 US_TOP1000 열린 편입).
CUR="select s.id from stock s where s.market_code='US' and s.is_active and exists (select 1 from universe_membership m where m.stock_id=s.id and m.universe_code='US_TOP1000' and m.effective_to is null)"
CAND="select s.id from stock s where s.market_code='US' and s.is_active and not exists (select 1 from universe_membership am join universe au on au.code=am.universe_code and au.is_active where am.stock_id=s.id and am.effective_to is null)"
N_CUR=$(q "$PROD" "select count(*) from ($CUR) c")
N_CAND=$(q "$PROD" "select count(*) from ($CAND) c")
# 후보 재무 대상의 독립 하한 — 재무 행이 있는 후보(대상 = 재무 행 ∪ SEC 매핑이므로 그 이상이다 · 계획 v2 결정 3).
N_CAND_FIN=$(q "$PROD" "select count(*) from ($CAND) c where exists (select 1 from financial_statement f where f.stock_id=c.id)")
[ "$N_CUR" -gt 0 ] && [ "$N_CAND_FIN" -gt 0 ] || die "대상이 비었다(현 편입 $N_CUR · 후보∩재무 $N_CAND_FIN)"
# 창 안 과거 편입이 전부 후보 술어에 드는가 — 비활성이면 두 실행 어디에도 안 들어 재적재에서 빠진다.
WIN="(select min(trade_date) from indicator_trade_date where market_code='US')"
N_PAST_OUT=$(q "$PROD" "select count(distinct m.stock_id) from universe_membership m join universe u on u.code=m.universe_code and u.is_active join stock s on s.id=m.stock_id where s.market_code='US' and m.effective_to > $WIN and m.stock_id not in ($CUR) and m.stock_id not in ($CAND)")
[ "$N_PAST_OUT" = 0 ] || die "창 안 과거 편입 중 두 실행 어디에도 안 드는 종목 $N_PAST_OUT(비활성?)"
# a2(판 번호 연속) 미국 행의 종목이 전부 재적재 대상인가 — 관문 뒤 a2 미국 0은 이른 판 켬 실행의 재번호가 닫는다(계획 v2 결정 4③).
PGOPTIONS='-c statement_timeout=120s' command psql -d "$PROD" -v ON_ERROR_STOP=1 -At -F $'\t' -f "$SCRIPTS/a2.sql" > "$A/a2-before.txt" || die "a2 실패"
A2_OUT=$(q "$PROD" "with f as (select f.stock_id, row_number() over (partition by f.stock_id, f.fiscal_year, f.fiscal_period order by f.published_at, f.revision_no, f.id) - 1 as e, f.revision_no from financial_statement f join stock s on s.id=f.stock_id where s.market_code='US') select string_agg(distinct s.ticker, ',') from f join stock s on s.id=f.stock_id where f.revision_no <> f.e and f.stock_id not in ($CUR) and f.stock_id not in ($CAND)")
[ -z "$A2_OUT" ] || die "a2 미국 행의 종목 중 재적재 대상 밖: $A2_OUT"
# 미국 워치리스트 알림 항목 — 0보다 크면 플립 전에 켬 기저로 last_satisfied를 맞추는 길이 먼저다(계획 v2 결정 9).
N_ALERT=$(q "$PROD" "select count(*) from watchlist_item w join stock s on s.id=w.stock_id where s.market_code='US' and w.screen_id is not null")
LATEST=$(q "$PROD" "select max(trade_date) from indicator_trade_date where market_code='US'"); [ -n "$LATEST" ] || die "LATEST가 비었다"
echo "LATEST=$LATEST" > "$A/latest.txt"
# 프리셋 스냅숏 날짜 — 그날 창에서 규칙으로(고정 날짜는 창이 밀리면 밖으로 나간다 · 계획 결정 8):
# 창 첫날 · 둘째 거래일 · 달마다 첫 리밸런싱 기준일(두 평면) · 분기 공시 몰림(2/15·5/10·8/10·11/10 뒤 첫 거래일) · LATEST.
q "$PROD" "with w as (select trade_date d from indicator_trade_date where market_code='US'),
  firsts as (select d from w order by d limit 2),
  reb as (select min(m.effective_from) d from universe_membership m where m.universe_code='US_TOP1000' and m.effective_from >= (select min(d) from w) group by date_trunc('month', m.effective_from)),
  season as (select (select min(d) from w where d >= make_date(y, mo, dd)) d
             from generate_series(extract(year from (select min(d) from w))::int, extract(year from (select max(d) from w))::int) y,
                  (values (2,15),(5,10),(8,10),(11,10)) v(mo, dd))
  select string_agg(distinct d::text, ',' order by d::text) from (select d from firsts union select d from reb union select d from season union select max(d) from w) x where d is not null" > "$A/dates.txt"
[ -s "$A/dates.txt" ] || die "프리셋 날짜가 비었다"
echo "$N_CUR $N_CAND $N_CAND_FIN" > "$A/counts.txt"
echo "토요일 리밸런싱($SAT 뒤) SUCCESS · 현 편입=$N_CUR · 후보=$N_CAND · 후보∩재무(재무 대상 하한)=$N_CAND_FIN · 창 안 과거 편입 누락 0"
echo "a2 전: $(tr '\n' ' ' < "$A/a2-before.txt") · 미국 알림 항목=$N_ALERT · LATEST=$LATEST"
echo "프리셋 날짜 $(tr ',' '\n' < "$A/dates.txt" | grep -c .)개: $(cat "$A/dates.txt")"
echo OK
