-- 기업 행위 사슬 대상(= StockRegistrar.windowMemberIds 술어) 시장별 · 지금 편입 대 과거 편입
with w as (
SELECT DISTINCT m.stock_id, s.market_code,
       bool_or(m.effective_to is null) over (partition by m.stock_id) cur
FROM universe_membership m
JOIN stock s ON s.id = m.stock_id
JOIN universe u ON u.code = m.universe_code AND u.is_active
WHERE s.is_active
  AND (m.effective_to IS NULL
       OR (m.effective_from <= (SELECT max(d.trade_date) FROM indicator_trade_date d WHERE d.market_code = s.market_code)
           AND m.effective_to > (SELECT min(d.trade_date) FROM indicator_trade_date d WHERE d.market_code = s.market_code))))
select case when market_code in ('KOSPI','KOSDAQ') then 'KR' else market_code end mkt, count(distinct stock_id) n,
       count(distinct stock_id) filter (where cur) cur_n
from w group by 1 order by 1;
