-- 2025 같은 철 평일마다 「전 평일 공시」 종목 수(그날 활성 유니버스 편입 · 국내·미국) — 단계 대상 0인 날(= v6이 말하는 G 적합일)이 몇 날인가.
-- XBRL·재무 행을 만든 공시만 센다(하한 — 6-K 등 대상은 더 많다).
with act as (select code from universe where is_active),
d as (select g::date d from generate_series(date '2025-10-16', date '2025-11-28', interval '1 day') g where extract(isodow from g)<=5),
pw as (select d.d, (select max(x) from (select d.d - i x from generate_series(1,4) i) z where extract(isodow from x)<=5) prev from d),
f as (select fs.published_at::date pd, s.market_code, fs.stock_id from financial_statement fs join stock s on s.id=fs.stock_id
      where fs.published_at between '2025-10-10' and '2025-11-28' group by 1,2,3)
select to_char(pw.d,'MM-DD Dy') dd,
  count(distinct f.stock_id) filter (where f.market_code='US') us,
  count(distinct f.stock_id) filter (where f.market_code<>'US') kr
from pw left join f on f.pd = pw.prev and exists (select 1 from universe_membership m where m.stock_id=f.stock_id and m.universe_code in (select code from act) and m.effective_from<=f.pd and (m.effective_to is null or m.effective_to>f.pd))
group by pw.d order by pw.d;
