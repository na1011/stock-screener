-- 08-31 뒤 OBSERVED로 새로 든 편입(지금 열린 행) 중 「편입일 뒤 재무 행이 한 번도 새로 안 써진」 종목 — 시장별.
-- 재무 쓰기 시각은 financial_statement의 created_at·updated_at 최대(있는 컬럼만).
SELECT column_name FROM information_schema.columns WHERE table_name='financial_statement' AND column_name ~ '(created|updated|fetched|loaded)';
