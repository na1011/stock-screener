-- q03 민감도: 손 전량 FR 날짜 D별 다음 FR 두 번(26일 뒤 첫 평일 14:15 · 요일 배정 무시 근사) — 작년 철 공시일 +1년 모형
with cur as (select m.stock_id, s.market_code from universe_membership m join stock s on s.id=m.stock_id
             where m.universe_code in ('US_TOP1000','KOSPI_TOP200','KOSDAQ_TOP150') and m.effective_to is null),
f as (select distinct case when c.market_code='US' then 'US' else 'KR' end mk, fs.stock_id, (fs.published_at + interval '1 year')::date pub
      from financial_statement fs join cur c using(stock_id)
      where fs.published_at between '2025-10-01' and '2025-12-15' and fs.revision_no=0),
sc(name, ds) as (values ('A 손FR없음(10-30)', array['2026-10-30','2026-11-25','2026-12-21']::date[]),
                        ('D=10-06', array['2026-11-02','2026-11-30','2026-12-28']::date[]),
                        ('D=10-13', array['2026-11-09','2026-12-07','2027-01-04']::date[]),
                        ('D=10-15', array['2026-11-10','2026-12-07','2027-01-04']::date[]))
select sc.name, f.mk, sum((select min(d) from unnest(sc.ds) d where d>=f.pub) - f.pub) lag_days
from f cross join sc group by 1,2 order by 2,1;
