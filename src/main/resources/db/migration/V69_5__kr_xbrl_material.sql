-- ---------------------------------------------------------------------------
-- 국내 보통주 몫의 원장 재료 — 정기 공시 판마다의 XBRL 재료 사실 · 주식총수 명부 이력(ADR-0192)
-- ---------------------------------------------------------------------------
-- 값을 바꾸지 않는 재료 표다. 판정(financial_common_income의 국내 행)은 국내 관문(`stockscreener.gate.kr`) 뒤에서 이 표를 읽고,
-- 관문 꺼짐에서는 이 표를 읽는 코드가 없다 — 그래서 시험 마이그레이션이 아니라 main에 먼저 놓는다(무해한 선행 배포 · ADR-0192 §규칙).
-- 번호가 V69_5인 이유: 운영 판이 69.4이고 V70~은 관문 시험 마이그레이션이다.
--
-- 쓰기 가드: V66의 이벤트 트리거가 두 표에 가드를 단다(보호 제외 목록에 안 넣는다 — 앱 JVM의 받기 잡(kr-xbrl-collect)만 쓴다).

-- ① 종목별 정기 공시 목록(list.json corp_code · 상세유형 A001 · A002 · A003)의 판 하나 = 한 행, 그 판의 XBRL 재료.
--    판은 한 번 나오면 안 바뀐다 — 재료는 평생 한 번 받는다. 다시 받는 것은 xbrl_status가 FAILED인 행과 reader_version이 지금 판독기와
--    다른 행이다(그때는 디스크 캐시의 원본에서 다시 판독한다 · 외부 0콜).
--    xbrl_status: NULL 아직 안 받음 · OK 재료 있음 · NO_FILE 원장이 원본 파일이 없다고 답함(013 · 014) · FAILED 받기 실패(다음 실행이
--    다시) · UNREADABLE 받았는데 판독 실패(판독기 판이 바뀌면 다시).
--    facts: 판독기를 지난 재료 사실 배열 — [{"s":이름공간 갈래 I/D,"c":개념,"b":기간 시작 또는 null,"e":끝 또는 시점,
--    "k":연결 C/별도 S/축 없음 N,"m":[[축,구성원,구성원 갈래 I/D/E]],"v":값 문자열}].
CREATE TABLE kr_report_xbrl (
    rcept_no         varchar(14)  NOT NULL,
    stock_id         bigint       NOT NULL,
    corp_code        varchar(8)   NOT NULL,
    detail_type      varchar(4)   NOT NULL,
    report_name      varchar(200) NOT NULL,
    rcept_date       date         NOT NULL,
    bsns_year        smallint,
    reprt_code       varchar(5),
    key_reason       text,
    listed_at        timestamptz  NOT NULL DEFAULT now(),
    xbrl_status      varchar(12),
    reader_version   integer,
    facts            jsonb,
    unreadable_values integer,
    failure_kind     varchar(20),
    failure_message  text,
    fetched_at       timestamptz,
    CONSTRAINT pk_kr_report_xbrl PRIMARY KEY (rcept_no, stock_id),
    CONSTRAINT fk_kr_report_xbrl_stock FOREIGN KEY (stock_id) REFERENCES stock (id),
    CONSTRAINT ck_kr_report_xbrl_detail CHECK (detail_type IN ('A001', 'A002', 'A003')),
    CONSTRAINT ck_kr_report_xbrl_key CHECK ((bsns_year IS NULL OR reprt_code IS NULL) = (key_reason IS NOT NULL)),
    CONSTRAINT ck_kr_report_xbrl_status CHECK (xbrl_status IS NULL
        OR xbrl_status IN ('OK', 'NO_FILE', 'FAILED', 'UNREADABLE')),
    CONSTRAINT ck_kr_report_xbrl_shape CHECK (
        (xbrl_status IS NULL AND facts IS NULL AND fetched_at IS NULL)
        OR (xbrl_status = 'OK' AND facts IS NOT NULL AND reader_version IS NOT NULL AND failure_kind IS NULL)
        OR (xbrl_status = 'NO_FILE' AND facts IS NULL AND failure_kind IS NULL)
        OR (xbrl_status IN ('FAILED', 'UNREADABLE') AND facts IS NULL AND failure_kind IS NOT NULL))
);
CREATE INDEX ix_kr_report_xbrl_stock ON kr_report_xbrl (stock_id, bsns_year, reprt_code);
COMMENT ON TABLE kr_report_xbrl IS
    '국내 정기 공시 판(접수번호)마다 XBRL 재료 사실(ADR-0192) — 받기 잡 kr-xbrl-collect만 쓴다. 손으로 고치지 않는다';
COMMENT ON COLUMN kr_report_xbrl.bsns_year IS
    '보고서 이름 「(yyyy.mm)」 → (사업연도, 보고서 코드) — DartReportKey가 정본. 못 읽으면 NULL이고 key_reason이 사유다';
COMMENT ON COLUMN kr_report_xbrl.rcept_date IS
    '목록의 rcept_dt — 재무 행 published_at과 맞대는 날짜 둘(접수번호 앞 8자리 · rcept_dt) 중 하나';

-- ② 주식총수 명부(stockTotqySttus · 사업보고서 11011) 사업연도마다 한 행 — 받은 줄 그대로(라벨은 판별에 안 쓴다 · ADR-0192).
--    status: OK 줄 있음 · NO_DATA 원장이 013(그 해 보고서 없음)을 답함. governance_report_row에 쓰지 않는 이유: 거버넌스 유도가 그 표를
--    읽어 관문 밖에서 값이 바뀐다.
CREATE TABLE kr_share_register (
    stock_id     bigint      NOT NULL,
    bsns_year    smallint    NOT NULL,
    status       varchar(8)  NOT NULL,
    rcept_no     varchar(14),
    record_date  date,
    rows         jsonb,
    fetched_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_kr_share_register PRIMARY KEY (stock_id, bsns_year),
    CONSTRAINT fk_kr_share_register_stock FOREIGN KEY (stock_id) REFERENCES stock (id),
    CONSTRAINT ck_kr_share_register_status CHECK (status IN ('OK', 'NO_DATA')),
    CONSTRAINT ck_kr_share_register_shape CHECK ((status = 'OK') = (rows IS NOT NULL))
);
COMMENT ON TABLE kr_share_register IS
    '국내 주식총수 명부 이력(사업보고서 11011 · ADR-0192) — rows: [{"se":구분,"issued":발행 총수,"treasury":자기주식}] 받은 문자열 그대로. 손으로 고치지 않는다';
