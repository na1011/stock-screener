-- V51: indicator_value의 커버링 인덱스를 PK에 접는다
--
-- V2가 만든 두 인덱스는 `(trade_date, indicator_code)` 접두를 공유했다.
--   PK            (trade_date, indicator_code, stock_id)
--   idx_iv_screen (trade_date, indicator_code, value) INCLUDE (stock_id)
-- 하나로 접으면 세 읽기 경로(파생층·스크리닝 조건·표시값)를 그대로 커버하면서
-- 인덱스 하나와 그 쓰기 비용이 통째로 사라진다. 근거와 실측은 ADR-0140에 있다.
--
-- MUST: 멱등이다. 운영 DB에는 2026-09-13에 손으로 먼저 적용했으므로 여기서는 아무 일도
-- 하지 않고, 테스트 DB와 새로 만드는 DB에서만 실제로 돈다. 49M행에 ACCESS EXCLUSIVE를
-- 잡는 DDL을 기동 경로에 두면 앱이 그만큼 못 뜬다.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_index i
        JOIN pg_class c ON c.oid = i.indexrelid
        WHERE c.relname = 'indicator_value_pkey'
          AND i.indnatts > i.indnkeyatts            -- INCLUDE 컬럼이 있다
    ) THEN
        ALTER TABLE indicator_value DROP CONSTRAINT indicator_value_pkey;
        ALTER TABLE indicator_value ADD CONSTRAINT indicator_value_pkey
            PRIMARY KEY (trade_date, indicator_code, stock_id) INCLUDE (value);
    END IF;
END $$;

DROP INDEX IF EXISTS idx_iv_screen;

-- MUST: 이 둘을 같은 파일에 둔다. INCLUDE가 붙으면 B-tree 중복 제거가 꺼지고
-- (`bt_metap.allequalimage` t → f), 그것이 이 표가 받는 8,500만 갱신의 버전 churn을
-- 흡수하던 기제다. autovacuum을 자주 돌리는 것이 그 상실의 상쇄 수단이므로 한 변경이다.
-- 기본 scale_factor 0.2는 4,920만 행에서 발동선이 984만이라 긴 백필 한 판이 통째로
-- 지나가도 한 번도 안 돈다(2026-09-13 실측: 91분 동안 0회).
ALTER TABLE indicator_value SET (
    autovacuum_vacuum_scale_factor        = 0.02,
    autovacuum_vacuum_threshold           = 50000,
    autovacuum_analyze_scale_factor       = 0.02,
    autovacuum_vacuum_insert_scale_factor = 0.05
);

COMMENT ON INDEX indicator_value_pkey IS
    'PK이면서 스크리닝의 커버링 인덱스다. INCLUDE(value)가 있어야 파생층(PercentileCalculator·'
    'SectorRelativeCalculator)·조건 판정·표시값이 전부 Index Only Scan으로 끝난다. '
    'MUST NOT: INCLUDE를 떼지 않는다 — 떼면 (trade_date, indicator_code) 슬라이스마다 '
    '1,500건의 무작위 힙 페치가 되살아난다(ADR-0140).';
