-- ---------------------------------------------------------------------------
-- 원본 접수일 판의 원천 (재고 「유도 Q4 가시성」 얼림 · ADR-0195 KrEarlierVintages)
-- ---------------------------------------------------------------------------
-- MUST: 관문 마이그레이션이다 — 먼저 여는 관문(시장 무관)이 V70~V70_10 전부를 번호 순서대로 `src/main/resources/db/migration/`으로
-- 한 글자도 안 바꾸고 옮긴다(운영 Flyway out-of-order 꺼짐 — V71이 먼저 들어간 뒤에는 이 파일이 막힌다).
--
-- 원본 접수일 판은 「같은 숫자를 처음 공개한 판」의 값 복사본이다. 복사본은 다시 지어지지 않아 관문 재적재가 원천 판에 채운 칸(중단영업 ·
-- 비표준 지배 NI · 기저 대체)이 빈 채 남았다 — 원천을 가리켜 국내 관문 켬 잡이 매 실행 원천 값으로 다시 맞춘다. 원천이 회수되면 복사본도 간다.

ALTER TABLE financial_statement
    ADD COLUMN copied_from bigint REFERENCES financial_statement (id) ON DELETE CASCADE;

-- 기존 복사본: 국내에서 이른 판을 뒤늦게 넣는 경로는 KrEarlierVintages 하나뿐이다 — 같은 라벨의 더 늦은 판보다 뒤에 만들어진 행이 복사본이고,
-- 원천은 그보다 먼저 만들어진 다음 판이다(2026-11-01 운영 실측: 이 술어가 고르는 2,110행이 전부 10-31 그 잡의 배치 FY 831 · Q4 738 · Q1~Q3 541).
UPDATE financial_statement r
   SET copied_from = (SELECT s.id FROM financial_statement s
                       WHERE s.stock_id = r.stock_id AND s.fiscal_year = r.fiscal_year AND s.fiscal_period = r.fiscal_period
                         AND s.published_at > r.published_at AND s.created_at < r.created_at
                       ORDER BY s.published_at LIMIT 1)
 WHERE r.stock_id IN (SELECT id FROM stock WHERE market_code IN ('KOSPI', 'KOSDAQ'));

CREATE INDEX ix_financial_statement_copied_from ON financial_statement (copied_from) WHERE copied_from IS NOT NULL;

COMMENT ON COLUMN financial_statement.copied_from IS
    '원본 접수일 판(KrEarlierVintages)의 원천 판 id — 국내 관문 켬 잡이 매 실행 원천 값으로 다시 맞춘다. NULL = 소스가 준 판.';
