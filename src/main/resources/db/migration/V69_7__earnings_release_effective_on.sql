-- ---------------------------------------------------------------------------
-- 실적 발표가 「어느 봉부터 공개돼 있던 것인가」(ADR-0195 · 2026-10-31)
-- ---------------------------------------------------------------------------
-- 재무 지표는 그 봉에서 재무 기준보다 새로운 실적 발표가 이미 공개돼 있으면 계산하지 않는다. 그 「공개」의 날짜가 effective_on이다.
--   미국: 8-K 접수 시각(accepted_at, submissions의 acceptanceDateTime)이 미국 동부 16:00(정규장 마감) 전이면 그 날, 뒤면 다음 날.
--         RMD 8-K는 2026-10-29 16:05 ET 접수라 10-29 종가는 발표 전 가격이다 — 그 봉의 재무 지표는 옛 분기가 맞다.
--         접수 시각을 아직 모르는 줄은 filed_on이다(보수적으로 하루 일찍 막는다).
--   국내: 공시검색이 접수 시각을 주지 않는다 — filed_on(접수일)이다. 장 마감 뒤 접수면 그 봉 하나를 일찍 막는다.
ALTER TABLE earnings_release ADD COLUMN accepted_at timestamptz;
ALTER TABLE earnings_release ADD COLUMN effective_on date;
UPDATE earnings_release SET effective_on = filed_on;
ALTER TABLE earnings_release ALTER COLUMN effective_on SET NOT NULL;
CREATE INDEX ix_earnings_release_effective ON earnings_release (stock_id, effective_on);
