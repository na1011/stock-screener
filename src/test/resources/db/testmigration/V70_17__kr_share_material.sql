-- ---------------------------------------------------------------------------
-- 국내 정기보고서마다 주식수 재료 셋(계획 K3-b · K3-d · 탐색 KR-BLANKS-PLAN-1106.md)
-- ---------------------------------------------------------------------------
-- MUST: 관문 마이그레이션이다 — V70_14 · V70_15 · V70_16과 함께 국내 관문 직전에 주 경로로 한 글자도 안 바꾸고 옮긴다. 받기 · 읽기 모두 관문 켬
-- 갈래에만 있다(꺼짐 코드는 이 표를 안 읽고 안 쓴다).
--
-- 그 보고서(사업연도 · 보고서 코드)의 주식총수 표(stockTotqySttus — 분기 · 반기만 · 사업보고서 명부는 kr_share_register) · 증자(감자) 현황
-- (irdsSttus — 일자 · 형태 · 종류 · 수량 · 매 보고서가 이력 전부) · 자기주식 취득 · 처분(tesstkAcqsDspsSttus — 기초 · 취득 · 처분 · 소각 · 기말 ·
-- 그해 누계). 원장은 그 보고서 코드의 가장 늦은 판(정정 포함)을 주고 rcept_no가 그 판이다 — 그 판 접수일이 행 공시일보다 늦으면 그날부터 보인다
-- (ADR-0207). status: OK 줄 있음 · NO_DATA 원장이 013을 답함(그 보고서 판이 그 「없음」 뒤 날짜로 목록에 오르면 다시 묻는다).
-- rows: 받은 줄을 문자열 그대로(필드 이름은 원장 것 · corp_code · corp_name · corp_cls만 뺀다). 손으로 고치지 않는다.

CREATE TABLE kr_share_material (
    stock_id    bigint      NOT NULL,
    bsns_year   smallint    NOT NULL,
    reprt_code  varchar(5)  NOT NULL,
    endpoint    varchar(24) NOT NULL,
    status      varchar(8)  NOT NULL,
    rcept_no    varchar(14),
    rows        jsonb,
    fetched_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_kr_share_material PRIMARY KEY (stock_id, bsns_year, reprt_code, endpoint),
    CONSTRAINT fk_kr_share_material_stock FOREIGN KEY (stock_id) REFERENCES stock (id),
    CONSTRAINT ck_kr_share_material_endpoint CHECK (endpoint IN ('stockTotqySttus', 'irdsSttus', 'tesstkAcqsDspsSttus')),
    CONSTRAINT ck_kr_share_material_status CHECK (status IN ('OK', 'NO_DATA')),
    CONSTRAINT ck_kr_share_material_shape CHECK ((status = 'OK') = (rows IS NOT NULL))
);
