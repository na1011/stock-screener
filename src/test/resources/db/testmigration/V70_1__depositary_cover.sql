-- ---------------------------------------------------------------------------
-- 예탁증권 판 비율 (미국 관문 · ADR-0183 · 탐색 `explore/universe/ADS-B2-PLAN-1022.md`)
-- ---------------------------------------------------------------------------
-- MUST: 이 파일은 관문 마이그레이션이다 — V70과 같이 관문 직전에 `src/main/resources/db/migration/`으로 내용을 한 글자도 안 바꾸고
-- 옮긴다. V70을 고치지 않고 따로 둔 이유: V70의 체크섬이 이미 적용된 공유 시험 DB에 박혀 있어 고치면 무관한 배포 빌드까지 선다.
-- 운영 코드는 `stockscreener.gate.us`가 켜졌을 때만 이 표를 부른다.
--
-- 쓰기 가드: V66의 이벤트 트리거가 두 표에 가드를 단다(앱 JVM 안에서만 쓴다).

-- ① 예탁증권 줄 발행사의 2019 뒤 정기 공시 표지(상장 판정 표지 · ClassFactReader.readListingCover) — 공시는 안 바뀌므로 평생 한 번
--    받는다. 클래스 축 표(sec_filing_class_fact)와 따로 두는 이유: 그 표에 행이 생기면 그 공시가 클래스 판정 대상이 되어 판정 종류가
--    바뀐다(B v1 반증).
CREATE TABLE sec_depositary_cover (
    cik             bigint       NOT NULL,
    accession       varchar(20)  NOT NULL,
    form            varchar(10)  NOT NULL,
    filed           date         NOT NULL,
    reader_version  integer      NOT NULL,
    status          varchar(8)   NOT NULL,
    facts           jsonb,
    failure_kind    varchar(20),
    failure_message text,
    fetched_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_sec_depositary_cover PRIMARY KEY (cik, accession),
    CONSTRAINT ck_sec_depositary_cover_status CHECK (status IN ('OK', 'NO_XBRL', 'FAILED'))
);
COMMENT ON TABLE sec_depositary_cover IS
    '예탁증권 줄 발행사의 정기 공시 표지(기호 · 증권 이름 · 비율 태그 · 표지 주식수) — 판마다 비율의 재료. 손으로 고치지 않는다';

-- ② 공시마다 정한 k(1 ADS = k 보통주). 정한 값은 다시 안 짓는다 — 매일 다시 지으면 뒤에 온 표지·사건이 지난 판을 소급해 뒤집는다
--    (B v2 반증). 미정(k IS NULL)인 행만 다시 정한다(숨겨져 있던 판을 채울 뿐 보이던 값을 안 바꾼다).
CREATE TABLE sec_depositary_ratio (
    stock_id    bigint       NOT NULL,
    accession   varchar(20)  NOT NULL,
    filed       date         NOT NULL,
    k           numeric(24,10),
    how         varchar(16)  NOT NULL,
    decided_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_sec_depositary_ratio PRIMARY KEY (stock_id, accession),
    CONSTRAINT fk_sec_depositary_ratio_stock FOREIGN KEY (stock_id) REFERENCES stock (id),
    CONSTRAINT ck_sec_depositary_ratio_k CHECK (k IS NULL OR k > 0),
    CONSTRAINT ck_sec_depositary_ratio_how CHECK (how IN ('OWN', 'NOT_DEPOSITARY', 'CARRY_FORWARD', 'CARRY_BACK', 'ADS_UNIT',
                                                         'UNRESOLVED'))
);
COMMENT ON TABLE sec_depositary_ratio IS
    '공시마다 정한 예탁 비율 k와 근거 — 정한 값은 불변 · 미정만 다시 정한다(ADR-0183). 손으로 고치지 않는다';
