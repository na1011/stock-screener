-- 운영 stock_screener SELECT만 (2026-10-07 01:2x)
-- (1) 상태별·잡별 이력과 성공0·실패>0 행
select job_name, status, triggered_by, count(*), sum((success_count=0 and failure_count>0)::int) s0f, sum((success_count=0 and failure_count=0)::int) s0f0 from batch_execution group by 1,2,3 order by 1,2,3;
-- (2) 실패 종류·소스
select failure_kind, source_name, count(*) from batch_execution_failure group by 1,2 order by 3 desc;
-- (3) 무거운 잡 실행별·시장별 실패 (시장 통째 실패 실례 #410·#415)
select e.id, e.job_name, e.status, e.success_count, e.failure_count, s.market_code, count(*) fails, string_agg(distinct f.failure_kind, ',') kinds from batch_execution e join batch_execution_failure f on f.execution_id=e.id join stock s on s.id=f.stock_id where e.job_name in ('financial-refill','full-statement-refill','governance-load','company-profile','corporate-action') group by 1,2,3,4,5,6 order by fails desc limit 20;
-- (4) 무거운 잡의 SCHEDULER 실행 수 (= 노출 기회)
select job_name, count(*) filter (where triggered_by='SCHEDULER') sched, count(*) total from batch_execution where job_name in ('financial-refill','full-statement-refill','governance-load') group by 1;
-- (5) FR 기본 대상의 순서(stock_id) 안 시장 위치
with t as (SELECT DISTINCT m.stock_id, s.market_code FROM universe_membership m JOIN stock s ON s.id = m.stock_id JOIN universe u ON u.code = m.universe_code AND u.is_active WHERE m.effective_to IS NULL AND s.is_active), r as (select *, row_number() over (order by stock_id) rn from t) select market_code<>'US' kr, min(rn), max(rn), count(*) from r group by 1;
