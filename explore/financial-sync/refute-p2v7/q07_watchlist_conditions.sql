-- 알림 걸린 워치리스트 항목(전부 1)의 화면 조건 — 백분위(_PCTL)는 시장 모집단 전체를 읽으므로 그 종목에 미소비 공시가 없어도 동료의 낡은 값이 판정에 든다.
select w.id item, s.ticker, s.market_code, c.indicator_code, c.operator, c.operand_type, c.operand_indicator_code, c.literal_value
from watchlist_item w join stock s on s.id=w.stock_id
join condition_group g on g.screen_id=w.screen_id join screen_condition c on c.group_id=g.id
order by w.id, c.sort_order;
