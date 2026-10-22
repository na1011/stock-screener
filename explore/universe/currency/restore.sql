-- 10-19 GAP_FILL(#860) 판 회수가 지운 비USD 판을 10-18 덤프 원행에서 되살린다(CURRENCY-RETRACTION-PLAN-1022.md §6 · 2026-10-22 사용자 승인).
--
-- psql -X -v ON_ERROR_STOP=1 -v rows=<restore284.tsv 절대경로> -v finish=COMMIT|ROLLBACK -f restore.sql
-- 운영은 ops/write-session.sh exec 안에서만 돈다(ADR-0175 쓰기 가드).
--
-- 한 트랜잭션: 사전 단언 → 삭제가 만든 이른 USD 판 3행 지우기 → 라벨 번호 비키기 → 행 넣기(원래 id) → 번호 다시 매기기 →
-- 최신 하나 → 사후 단언. 번호·최신 규칙은 FinancialWriter.renumberLabel · retractStaleVintages와 같다
-- ((published_at, revision_no, id) 순 0..n · 최신 = published_at DESC, revision_no DESC 첫 행).

\set ON_ERROR_STOP 1
BEGIN;

CREATE TEMP TABLE r (LIKE financial_statement) ON COMMIT DROP;
\set copycmd '\\copy r FROM ' :'rows'
:copycmd

-- 같은 라벨의 비USD 최신이 지워져 「정정」으로 들어온 이른 USD 판(안 지워졌다면 Writer가 이른 판으로 건너뛰었다).
CREATE TEMP TABLE artifact (id bigint, stock_id bigint, fiscal_year int, fiscal_period text, published_at date) ON COMMIT DROP;
INSERT INTO artifact VALUES
    (231905, 457, 2023, 'FY', '2025-03-31'),
    (232332, 1999, 2023, 'FY', '2025-03-03'),
    (233181, 5366, 2019, 'FY', '2019-11-13');

DO $$
BEGIN
    IF (SELECT count(*) FROM r) <> 284 THEN
        RAISE EXCEPTION '재료 행 수가 284가 아니다: %', (SELECT count(*) FROM r);
    END IF;
    IF EXISTS (SELECT 1 FROM r WHERE currency_code = 'USD') THEN
        RAISE EXCEPTION '재료에 USD 행이 있다';
    END IF;
    IF (SELECT count(DISTINCT stock_id) FROM r) <> 60 THEN
        RAISE EXCEPTION '재료 종목 수가 60이 아니다';
    END IF;
    IF EXISTS (SELECT 1 FROM financial_statement f JOIN r USING (id)) THEN
        RAISE EXCEPTION 'id가 이미 쓰이고 있다';
    END IF;
    IF EXISTS (SELECT 1 FROM financial_statement f JOIN r
               ON (f.stock_id, f.fiscal_year, f.fiscal_period, f.published_at)
                = (r.stock_id, r.fiscal_year, r.fiscal_period, r.published_at)) THEN
        RAISE EXCEPTION '같은 (라벨, 공시일) 행이 이미 있다';
    END IF;
    IF (SELECT count(*) FROM financial_statement f JOIN artifact a
        ON (f.id, f.stock_id, f.fiscal_year, f.fiscal_period, f.published_at)
         = (a.id, a.stock_id, a.fiscal_year, a.fiscal_period, a.published_at)
        WHERE f.currency_code = 'USD') <> 3 THEN
        RAISE EXCEPTION '지울 이른 USD 판 3행이 그 모양으로 없다';
    END IF;
    IF EXISTS (SELECT 1 FROM artifact a
               WHERE NOT EXISTS (SELECT 1 FROM r WHERE (r.stock_id, r.fiscal_year, r.fiscal_period)
                                                     = (a.stock_id, a.fiscal_year, a.fiscal_period)
                                                 AND r.published_at > a.published_at)) THEN
        RAISE EXCEPTION '지울 판보다 늦은 되살릴 판이 같은 라벨에 없다';
    END IF;
END $$;

CREATE TEMP TABLE lab ON COMMIT DROP AS
    SELECT DISTINCT stock_id, fiscal_year, fiscal_period FROM r;

DELETE FROM financial_statement f USING artifact a WHERE f.id = a.id;

-- 지금 행을 비킨다(+1000) · 최신 표지를 내린다 — 유니크 인덱스 둘이 문장마다 걸리기 때문이다.
UPDATE financial_statement f SET revision_no = f.revision_no + 1000, is_latest = false
FROM lab WHERE (f.stock_id, f.fiscal_year, f.fiscal_period) = (lab.stock_id, lab.fiscal_year, lab.fiscal_period);

UPDATE r SET revision_no = revision_no + 2000, is_latest = false;
INSERT INTO financial_statement OVERRIDING SYSTEM VALUE SELECT * FROM r;

UPDATE financial_statement f SET revision_no = x.expected
FROM (SELECT f2.id, row_number() OVER (PARTITION BY f2.stock_id, f2.fiscal_year, f2.fiscal_period
                                       ORDER BY f2.published_at, f2.revision_no, f2.id) - 1 AS expected
      FROM financial_statement f2 JOIN lab
        ON (f2.stock_id, f2.fiscal_year, f2.fiscal_period) = (lab.stock_id, lab.fiscal_year, lab.fiscal_period)) x
WHERE f.id = x.id;

UPDATE financial_statement f SET is_latest = true
WHERE f.id IN (SELECT DISTINCT ON (f2.stock_id, f2.fiscal_year, f2.fiscal_period) f2.id
               FROM financial_statement f2 JOIN lab
                 ON (f2.stock_id, f2.fiscal_year, f2.fiscal_period) = (lab.stock_id, lab.fiscal_year, lab.fiscal_period)
               ORDER BY f2.stock_id, f2.fiscal_year, f2.fiscal_period, f2.published_at DESC, f2.revision_no DESC);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM financial_statement f JOIN lab
               ON (f.stock_id, f.fiscal_year, f.fiscal_period) = (lab.stock_id, lab.fiscal_year, lab.fiscal_period)
               GROUP BY f.stock_id, f.fiscal_year, f.fiscal_period
               HAVING count(*) FILTER (WHERE f.is_latest) <> 1
                   OR max(f.revision_no) <> count(*) - 1
                   OR min(f.revision_no) <> 0) THEN
        RAISE EXCEPTION '라벨의 최신 표지나 번호가 어긋났다';
    END IF;
    IF (SELECT count(*) FROM financial_statement f JOIN r USING (id)) <> 284 THEN
        RAISE EXCEPTION '되살린 행이 284가 아니다';
    END IF;
    IF EXISTS (SELECT 1 FROM financial_statement f JOIN artifact a USING (id)) THEN
        RAISE EXCEPTION '이른 USD 판이 남았다';
    END IF;
    IF (SELECT count(DISTINCT f.stock_id) FROM financial_statement f JOIN stock s ON s.id = f.stock_id
        JOIN market m ON m.code = s.market_code
        WHERE f.stock_id IN (SELECT stock_id FROM lab) AND f.currency_code <> m.currency_code) <> 60 THEN
        RAISE EXCEPTION '60종목이 통화 불일치로 돌아가지 않았다';
    END IF;
END $$;

SELECT count(*) AS restored_rows, count(DISTINCT stock_id) AS stocks FROM financial_statement WHERE id IN (SELECT id FROM r);
SELECT count(*) AS labels_touched FROM lab;

:finish;
