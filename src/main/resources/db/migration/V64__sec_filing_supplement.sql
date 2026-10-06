-- companyfacts가 빠뜨린 공시를 XBRL 인스턴스로 보충한 결과 — 접수번호마다 한 행(ADR-0173).
--
-- 공시는 한 번 나오면 안 바뀌므로 평생 한 번 받는다. 그래서 매달 FR이 같은 인스턴스를 다시 받지 않고(실패 표면도 그만큼
-- 준다), 보충에 실패한 공시를 다음 실행이 알아본다(status = FAILED → 회수에서 그 공시일의 행을 지키고, 나중에 성공하면
-- 그 공시일의 판을 이른 판이어도 넣는다).
-- 다시 받는 것은 FAILED 중 failure_kind가 UNREADABLE(받은 파일을 해석 못 함)이 아닌 것과, reader_version이 지금과 다른(낮든 높든) 행이다
-- (둘 다 그 공시가 아직 보충 대상일 때만 — 대상이 아니면 아무도 안 읽는다).
-- reader_version은 저장되는 것을 정하는 코드 전체(받기 · 읽기 · 고르기 · 신원 · 분류 · 저장 형태 — 파일 단위)의 판이다 —
-- 한 토큰이라도 바꾸면 올린다(ReaderVersionPinTest가 소스와 출력을 판마다 박아 묻는다).
--
-- MUST: facts는 「고르기 전」의 원 팩트다(같은 개념·단위·기간의 중복을 정밀도째 · XbrlInstanceReader.RawFact) — 저장분은
-- 읽을 때마다 지금의 고르기를 탄다. 그래도 고르기를 바꾸면 판을 올리고 판이 다른 행은 다시 받는다(고르기 충돌로 실패한 행은
-- 원 팩트가 없다).
-- MUST NOT: 이 표의 행을 손으로 고치지 않는다 — 상태는 종목 쓰기와 같은 트랜잭션에서만 바뀐다.
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
    -- 보충이 이 행을 대상으로 마지막에 본 시각. FR 실행 기록의 미해결은 이 실행이 본 행만 센다 — 표 전체를 세면 기본 FR이 안
    -- 도는 과거 편입의 행이 섞여 관문 사이에 0으로 못 내려온다. 관문은 표 전체를 따로 본다.
    last_seen_at        timestamptz  NOT NULL DEFAULT now(),
    -- 판이 지금과 다른 저장분을 합친 시각(다시 받지 못했거나 공시 목록을 못 받은 실행). 미해결은 실패와 이것뿐이다 —
    -- 「판이 다르다」만으로 세면 안 쓰이는 행(COVERED · companyfacts가 덮은 공시 · 과거 편입)이 판을 올린 뒤 영영 남는다.
    -- 저장(OK·NO_XBRL·FAILED)과 COVERED 전이가 지운다.
    stale_used_at       timestamptz,
    -- 공동 등록 공시는 한 접수번호를 여러 CIK가 낸다. 읽는 팩트가 CIK마다 다르므로(남의 CIK 컨텍스트는 건너뛴다) 키에 CIK를 넣는다.
    CONSTRAINT pk_sec_filing_supplement PRIMARY KEY (accession, cik),
    -- COVERED: companyfacts가 나중에 그 공시를 실었다(실패였든 받았든) — 판은 이제 companyfacts가 낸다. 지우지 않고 남겨 매
    -- 실행 그 제출일의 판을 이른 판이어도 넣게 연다(한 번만 열면 그 실행이 APPEND_ONLY거나 같은 날 다른 공시가 실패할 때 열림이
    -- 사라지고, 받았던 공시는 실패였던 공시와 다른 판 집합에 닿는다).
    -- NO_XBRL: 원장이 XBRL이 있다고 했는데 공시 폴더에 XBRL 산출물(인스턴스 · 스키마 · 링크베이스)이 하나도 없다 — 원장의
    -- 사실이라 실패가 아니고(판 보호 없음 · 관문의 미해결에 안 셈) 팩트는 빈 배열이다(2026-10-06: 6-K 2017 · ESLT 2025-06).
    CONSTRAINT ck_sec_filing_supplement_status CHECK (status IN ('OK', 'NO_XBRL', 'FAILED', 'COVERED')),
    CONSTRAINT ck_sec_filing_supplement_shape CHECK (
        (status IN ('OK', 'NO_XBRL') AND facts IS NOT NULL AND failure_kind IS NULL)
        OR (status = 'FAILED' AND facts IS NULL AND failure_kind IS NOT NULL)
        OR (status = 'COVERED' AND facts IS NULL AND failure_kind IS NULL))
);
CREATE INDEX ix_sec_filing_supplement_cik ON sec_filing_supplement (cik, status);
COMMENT ON TABLE sec_filing_supplement IS
    'companyfacts가 빠뜨린 정기 공시를 XBRL 인스턴스로 보충한 결과(ADR-0173). (접수번호, CIK)마다 평생 한 행. FAILED는 그 공시일의 판을 회수에서 지키는 표지다';
