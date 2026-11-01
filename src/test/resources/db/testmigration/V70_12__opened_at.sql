-- ---------------------------------------------------------------------------
-- 공시의 공개 시각 (재고 「공개 시각이 공시일 칸과 갈린다」 · 설계 v3)
-- ---------------------------------------------------------------------------
-- MUST: 관문 마이그레이션이다 — 먼저 여는 관문(시장 무관)이 V70~V70_12 전부를 번호 순서대로 `src/main/resources/db/migration/`으로
-- 한 글자도 안 바꾸고 옮긴다(운영 Flyway out-of-order 꺼짐 — V71이 먼저 들어간 뒤에는 이 파일이 막힌다).
--
-- 재무 · 거버넌스는 공개 시각 뒤 처음 마감하는 세션의 봉부터 보인다(ReportingBasis.visibleFrom). 보이는 날은 저장하지 않는다 — 판독기가
-- 이 원자료와 세션 달력으로 매번 짓는다(달력이 뒤에 채워져도 낡지 않는다). 저장 공시일(published_at)은 판 열쇠 · 주식수 앵커로 그대로다.

-- 미국: 그 판에 값을 낸 공시들의 EDGAR 접수 시각 중 가장 늦은 것(submissions acceptanceDateTime). 그중 하나라도 모르면 NULL —
-- NULL은 「모른다」라 공시일 다음 날부터 보인다(늦는 쪽).
ALTER TABLE financial_statement
    ADD COLUMN accepted_at timestamptz;

COMMENT ON COLUMN financial_statement.accepted_at IS
    '그 판에 값을 낸 공시들의 공개 시각 중 가장 늦은 것(미국 EDGAR 접수 시각) — 하나라도 모르면 NULL(공시일 다음 날부터 보인다).';

-- 국내: DART 웹 「오늘의 공시」(dsac001)가 공시마다 싣는 시각 — OpenAPI(list.json)에는 시각이 없다. 게시 시각으로 보인다(00계열에서
-- 접수번호 순과 뒤집힌 줄이 일괄신고서 · 주요사항보고서에만 있다). 분 단위라 opened_at은 그 분의 끝(59초)으로 둔다 — 마감 분에 걸린
-- 공시를 「마감 전」으로 읽지 않는다.
CREATE TABLE kr_filing_time (
    rcept_no     varchar(14) PRIMARY KEY,
    corp_code    varchar(8),
    corp_name    text        NOT NULL,
    report_name  text        NOT NULL,
    listed_on    date        NOT NULL,
    opened_at    timestamptz NOT NULL,
    fetched_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_kr_filing_time_corp ON kr_filing_time (corp_code, listed_on);

COMMENT ON TABLE kr_filing_time IS
    'DART 「오늘의 공시」 한 줄 — 공시마다 공개 시각. listed_on = 그 줄이 실린 날짜 페이지. 접수번호로 못 잇는 재무 판은 회사 · 보고서명 · 날짜로 잇는다.';
