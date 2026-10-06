-- 미국 공시일(published_at)이 연방 공휴일(EDGAR 휴무)·성금요일(NYSE 휴장 · EDGAR 개장)에 있는가 + 앱 달력(NYSE 세션)
select d::date, to_char(d,'Dy'), (select count(*) from financial_statement f join stock s on s.id=f.stock_id where s.market_code='US' and f.published_at=d::date) us_rows,
       exists(select 1 from market_calendar where exchange_code='NYSE' and session_date=d::date) nyse_session
from (values ('2023-10-09'::date),('2023-11-10'),('2024-10-14'),('2024-11-11'),('2025-10-13'),('2025-11-11'),('2025-01-09'),('2023-04-07'),('2024-03-29'),('2025-04-18'),('2025-10-14'),('2025-11-10'),('2025-11-12'),('2024-11-12')) v(d) order by 1;
-- market_calendar 범위: select exchange_code,min(session_date),max(session_date),count(*) from market_calendar group by 1  → NYSE·NASDAQ 2016-01-04~2026-08-28
