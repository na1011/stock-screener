-- 종목 상세의 분류 구획에 기본으로 보일 지표인가(ADR-0058).
--
-- 지금까지 지표의 노출을 가르는 축은 is_active 하나뿐이었고, 그것은 조건 빌더·결과 표·
-- 종목 상세 셋에 동시에 걸린다. 그래서 "상세 화면에서만 감춘다"를 표현할 방법이 없었고,
-- 감추려면 폐기하는 수밖에 없는데 폐기하면 조건으로도 못 쓴다.
--
-- 기본값이 true인 것은 defaultColumn과 반대다. 대부분의 지표는 보여야 하므로 기본을
-- false로 두면 거의 모든 계산기에 true를 적게 된다.
ALTER TABLE indicator ADD COLUMN detail_default boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN indicator.detail_default IS
    '종목 상세의 분류 구획에 기본으로 보일 것인지. false여도 폐기가 아니다 — 조건 빌더와 '
    '「내 조건」 구획에는 그대로 나온다. 정본은 계산 클래스의 @IndicatorMeta(detailDefault)이고 '
    '이 컬럼은 그 사본이다.';
