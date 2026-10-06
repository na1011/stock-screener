#!/bin/bash
# 2부 v4 계획 밖 반증 — 운영 DB SELECT만 (0콜)
Q() { psql -d stock_screener -v ON_ERROR_STOP=1 -At -F $'\t' -c "$1"; }
echo "## q01 FINANCIAL 실패 행의 해결 — FR·FSR이 한 단계(FINANCIAL)를 공유" > q01_financial_resolution.out
Q "select e.job_name, e.triggered_by, count(*) rows, sum(case when f.resolved then 1 else 0 end) resolved from batch_execution_failure f join batch_execution e on e.id=f.execution_id where f.failed_phase='FINANCIAL' group by 1,2 order by 1,2" >> q01_financial_resolution.out
echo "## FSR 실패 2,002행의 시장(FSR은 미국에서 성공한 적이 없다 → FR/initial-load 성공이 해결로 찍음)" >> q01_financial_resolution.out
Q "select s.market_code, count(*), sum(case when f.resolved then 1 else 0 end) from batch_execution_failure f join batch_execution e on e.id=f.execution_id join stock s on s.id=f.stock_id where e.job_name='full-statement-refill' group by 1" >> q01_financial_resolution.out
echo "## q02 미국 재편입(앞 구간 종료 뒤 92일 안) 중 빈 구간에 공시(published_at)가 있던 것 — 월별" > q02_reentry_gap_filings.out
Q "with m as (select stock_id, effective_from, effective_to, lag(effective_to) over (partition by stock_id order by effective_from) prev_to from universe_membership where universe_code='US_TOP1000'), r as (select * from m where prev_to is not null and effective_from - prev_to <= 92) select date_trunc('month', r.effective_from)::date mon, count(*) reentries, count(*) filter (where exists (select 1 from financial_statement f where f.stock_id=r.stock_id and f.published_at >= r.prev_to and f.published_at < r.effective_from)) with_gap_filing from r where r.effective_from between '2025-07-01' and '2026-10-05' group by 1 order by 1" >> q02_reentry_gap_filings.out
echo "## 최근 주별 미국 편입(재편입 포함)" >> q02_reentry_gap_filings.out
Q "with m as (select stock_id, effective_from, lag(effective_to) over (partition by stock_id order by effective_from) prev_to from universe_membership where universe_code='US_TOP1000') select effective_from, count(*) entries, count(prev_to) reentries, min(effective_from-prev_to), max(effective_from-prev_to) from m where effective_from >= '2026-09-01' group by 1 order by 1" >> q02_reentry_gap_filings.out
echo "## 09-26 기준점 뒤 편출된 미국 종목(해석 B면 모집단에 영구히 남는 몫)" >> q02_reentry_gap_filings.out
Q "select count(distinct stock_id) from universe_membership where universe_code='US_TOP1000' and effective_to > '2026-09-26'" >> q02_reentry_gap_filings.out
echo "## q04 선행 실행(초기 기준점 재료)" > q04_baseline_runs.out
Q "select id, job_name, status, target_count, success_count, failure_count, started_at from batch_execution where id in (645,646,647,648,655,656,657,658,659,663,664,683) order by id" >> q04_baseline_runs.out
echo "## 국내 재무 행 공시일 09-15~10-06" >> q04_baseline_runs.out
Q "select f.published_at, s.market_code, count(distinct f.stock_id) from financial_statement f join stock s on s.id=f.stock_id where s.market_code in ('KOSPI','KOSDAQ') and f.published_at between '2026-09-15' and '2026-10-06' group by 1,2 order by 1" >> q04_baseline_runs.out
