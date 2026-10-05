SET statement_timeout='60s';
-- FSR 실행 창 안에 created_at이 찍힌 행 = FSR 자신이 만든 행(#648 창에 동시 실행 0 — 둘째 질의)
with w as (select id, started_at, finished_at from batch_execution where id in (648,656,663,664,706))
select w.id, count(distinct f.stock_id) stocks, count(*) rows_, string_agg(distinct f.fiscal_period, ',') periods
from w join financial_statement f on f.created_at between w.started_at and w.finished_at group by w.id order by w.id;
select id, job_name, started_at, finished_at from batch_execution
where (started_at, finished_at) overlaps (timestamptz '2026-09-21 23:19:06+09', timestamptz '2026-09-21 23:36:22+09') order by id;
