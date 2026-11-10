-- ---------------------------------------------------------------------------
-- 국내 실적 발표 공시 본문의 해석 결과(잠정실적 I002 · 손익구조 변동 I001 · 탐색 explore/kr-blanks/PROVISIONAL-PLAN-1110.md)
-- ---------------------------------------------------------------------------
-- MUST: 관문 마이그레이션이다 — 시험 전용 자리에 두고, 국내 잠정 관문 직전에 src/main/resources/db/migration/으로 한 글자도 안 바꾸고
-- 옮긴다(운영 Flyway는 out-of-order가 꺼져 있다). 운영 코드는 stockscreener.gate.kr-provisional이 켜졌을 때만 이 표를 읽고 쓴다.
--
-- 값은 문서 단위 그대로다(소수 자릿수 보존 — 반올림 폭이 자체 검사의 허용 오차다). 원 = 값 × unit_won.
-- 못 읽은 서식도 행이 있다(status = 사유) — 실적 발표 판정은 release_period = 'OTHER'로 읽힌 줄만 발표에서 뺀다.

CREATE TABLE kr_provisional_release (
    rcept_no                   char(14)    NOT NULL,
    stock_id                   bigint      NOT NULL REFERENCES stock (id),
    form                       varchar(4)  NOT NULL CHECK (form IN ('I001', 'I002')),
    report_name                text        NOT NULL,
    filed_on                   date        NOT NULL,
    status                     varchar(40) NOT NULL,
    consolidated               boolean,
    period_start               date,
    period_end                 date,
    cumulative_start           date,
    cumulative_end             date,
    release_period             varchar(8) CHECK (release_period IS NULL OR release_period IN ('QUARTER', 'YEAR', 'OTHER')),
    unit_won                   bigint,
    revenue                    numeric,
    operating_income           numeric,
    profit_before_tax          numeric,
    net_income                 numeric,
    net_income_controlling     numeric,
    cum_revenue                numeric,
    cum_operating_income       numeric,
    cum_profit_before_tax      numeric,
    cum_net_income             numeric,
    cum_net_income_controlling numeric,
    parser_version             int         NOT NULL,
    parsed_at                  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (rcept_no, stock_id),
    CONSTRAINT ck_kr_provisional_release_ok CHECK (status <> 'OK' OR (period_start IS NOT NULL AND period_end IS NOT NULL
        AND cumulative_start IS NOT NULL AND cumulative_end IS NOT NULL AND unit_won IS NOT NULL AND consolidated IS NOT NULL))
);

CREATE INDEX ix_kr_provisional_release_stock ON kr_provisional_release (stock_id, period_end);

COMMENT ON TABLE kr_provisional_release IS
    '국내 실적 발표 공시 본문(DART 웹 뷰어) 해석 — 원문은 파일 캐시(stockscreener.dart-prov.cache-dir). 값은 문서 단위 · 당해(3개월 또는 결산기간)와 누계';
