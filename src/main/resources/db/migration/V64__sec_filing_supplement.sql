-- companyfacts가 빠뜨린 공시를 XBRL 인스턴스로 보충한 결과 — 접수번호마다 한 행(ADR-0173).
--
-- 공시는 한 번 나오면 안 바뀌므로 평생 한 번 받는다. 그래서 매달 FR이 같은 인스턴스를 다시 받지 않고(실패 표면도 그만큼
-- 준다), 보충에 실패한 공시를 다음 실행이 알아본다(status = FAILED → 회수에서 그 공시일의 행을 지키고, 나중에 성공하면
-- 그 공시일의 판을 이른 판이어도 넣는다).
-- 다시 받는 것은 FAILED 중 failure_kind가 UNREADABLE(받은 파일을 해석 못 함)이 아닌 것과, reader_version이 지금보다 낮은 행이다.
--
-- MUST: facts는 「고르기 전」의 원 팩트다(같은 개념·단위·기간의 중복을 정밀도째 · XbrlInstanceReader.RawFact). 고르기를
-- 고쳐도 다시 받지 않는다. 무엇을 싣는지(읽기 규칙)를 바꾸면 reader_version을 올리고, 판이 다른 행은 다시 읽는다.
-- MUST NOT: 이 표의 행을 손으로 고치지 않는다 — 상태(OK·FAILED)는 종목 쓰기와 같은 트랜잭션에서만 바뀐다.
CREATE TABLE sec_filing_supplement (
    accession           varchar(20)  NOT NULL,
    cik                 bigint       NOT NULL,
    form                varchar(10)  NOT NULL,
    filed               date         NOT NULL,
    reader_version      integer      NOT NULL,
    status              varchar(8)   NOT NULL,
    file_name           text,
    file_size           bigint,
    document_type       varchar(20),
    fiscal_year_focus   varchar(8),
    fiscal_period_focus varchar(8),
    us_gaap_facts       integer,
    facts               jsonb,
    failure_kind        varchar(20),
    failure_message     text,
    fetched_at          timestamptz  NOT NULL DEFAULT now(),
    -- 공동 등록 공시는 한 접수번호를 여러 CIK가 낸다. 읽는 팩트가 CIK마다 다르므로(남의 CIK 컨텍스트는 건너뛴다) 키에 CIK를 넣는다.
    CONSTRAINT pk_sec_filing_supplement PRIMARY KEY (accession, cik),
    -- NO_XBRL: 원장이 XBRL이 있다고 했는데 공시 폴더에 XBRL 산출물(인스턴스 · 스키마 · 링크베이스)이 하나도 없다 — 원장의
    -- 사실이라 실패가 아니고(판 보호 없음 · 관문의 미해결에 안 셈) 팩트는 빈 배열이다(2026-10-06: 6-K 2017 · ESLT 2025-06).
    CONSTRAINT ck_sec_filing_supplement_status CHECK (status IN ('OK', 'NO_XBRL', 'FAILED')),
    CONSTRAINT ck_sec_filing_supplement_shape CHECK (
        (status IN ('OK', 'NO_XBRL') AND facts IS NOT NULL AND failure_kind IS NULL)
        OR (status = 'FAILED' AND facts IS NULL AND failure_kind IS NOT NULL))
);
CREATE INDEX ix_sec_filing_supplement_cik ON sec_filing_supplement (cik, status);
COMMENT ON TABLE sec_filing_supplement IS
    'companyfacts가 빠뜨린 정기 공시를 XBRL 인스턴스로 보충한 결과(ADR-0173). (접수번호, CIK)마다 평생 한 행. FAILED는 그 공시일의 판을 회수에서 지키는 표지다';
