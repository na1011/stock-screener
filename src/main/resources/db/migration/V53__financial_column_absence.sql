-- 소스가 그 회사에 대해 그 금액 항목을 안 내는가, 아니면 우리가 못 담았는가.
--
-- 결측이 전부 같은 얼굴이라 화면이 업종이라는 프록시로밖에 말하지 못한다. 실측(2026-09-14 ·
-- US_TOP1000)에서 매출총이익률 결측 380 중 금융 업종으로 설명되는 것이 143(37.6%)뿐이고,
-- 그 설명력은 이 저장소의 가장 약한 기존 사유 규칙(PSR 64%)보다도 낮다.
--
-- 그 사실을 적재가 이미 알고 버리고 있었다 — SecEdgarFacts가 컬럼마다 후보 태그를 훑어
-- 팩트 맵을 만드는데, 그것이 비면 「우리 후보로는 그 컬럼이 안 온다」는 뜻이다.
--
-- MUST NOT: 행이 없는 것을 「소스에 없다」로 읽지 않는다. 행 없음은 「모른다」다 —
-- 그 종목을 아직 이 축으로 안 훑었거나, 그 소스가 이 축을 안 보는 것이다(지금 DART가 그렇다).
CREATE TABLE financial_column_absence (
    stock_id      bigint       NOT NULL REFERENCES stock(id),
    column_name   varchar(40)  NOT NULL,
    state         varchar(16)  NOT NULL,
    source_name   varchar(40)  NOT NULL,
    observed_at   date         NOT NULL,
    PRIMARY KEY (stock_id, column_name),
    CONSTRAINT ck_fca_state CHECK (state IN ('ABSENT', 'FILTERED'))
);

COMMENT ON TABLE financial_column_absence IS
  '금액 컬럼 하나를 그 종목에서 한 팩트도 못 얻었다는 관측. 행 없음은 「모른다」이지 「없다」가 아니다.';
COMMENT ON COLUMN financial_column_absence.state IS
  'ABSENT=소스에 후보 태그가 이름으로도 없다(화면 사유가 된다) · FILTERED=태그는 있는데 단위·폼·필드 필터가 다 걸렀다(우리 쪽 한계라 화면에 안 낸다).';
COMMENT ON COLUMN financial_column_absence.observed_at IS
  '이 판정을 낸 적재의 기준일. 판정은 그때의 후보 목록에 달려 있으므로, 후보를 바꾼 뒤에는 다시 적재해야 뜻이 맞는다.';
