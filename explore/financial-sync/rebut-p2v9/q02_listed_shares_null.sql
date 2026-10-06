-- 봉의 listed_shares NULL 비율(시장별 · 최근 400일) — NULL이면 MARKET_CAP 등 넷이 재무(sharesOf 폴백)를 읽는다
select s.market_code, count(*) bars, count(*) filter (where p.listed_shares is null or p.listed_shares <= 0) null_bars,
       count(distinct p.stock_id) filter (where p.listed_shares is null or p.listed_shares <= 0) null_stocks,
       max(p.trade_date) filter (where p.listed_shares is null or p.listed_shares <= 0) last_null
from price_daily p join stock s on s.id = p.stock_id
where p.trade_date > current_date - 400
group by 1 order by 1;
