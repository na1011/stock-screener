-- 분배 표의 자식 심볼·CUSIP 폭을 넓힌다. 같은 날 여러 증권을 나눠 준 분사는 자식을 쉼표로 이어 한 행에 싣는데
-- (원장이 한 날에 한 행이다 — ADR-0168), OPEN 2025-11-18 워런트 세 종의 CUSIP 셋(29자)이 varchar(16)을 넘어
-- 그 종목의 기업행위 수집이 롤백됐다(분사 드라이런 2026-10-05 · 사본 실행 720).
ALTER TABLE corporate_action_distribution ALTER COLUMN new_symbol TYPE varchar(128);
ALTER TABLE corporate_action_distribution ALTER COLUMN new_cusip TYPE varchar(128);
