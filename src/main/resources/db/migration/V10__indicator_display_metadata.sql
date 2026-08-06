-- V10: 지표 카탈로그에 화면이 필요로 하는 두 속성을 추가
--
-- Phase 4의 조건 빌더와 결과 표가 `indicator` 테이블을 평범한 쿼리로 읽는다는 전제 위에 있다
-- (`IndicatorMeta` 주석 — "API와 조건 빌더 UI는 평범한 쿼리로 읽는다"). 그런데 그 전제를
-- 실제로 확인해보니 두 가지가 테이블에 없어 화면을 만들 수 없다.


-- ---------------------------------------------------------------------------
-- default_column — 결과 표에 기본으로 보일 컬럼인가
--
-- @IndicatorMeta에 defaultColumn 속성이 이미 선언되어 있고 PER·PBR·ROE·DEBT_RATIO 네 개가
-- true를 달고 있는데, IndicatorCatalogSynchronizer의 INSERT 목록에 없어 어디로도 흘러가지
-- 않았다. 선언은 있고 소비자가 없는 상태였다.
--
-- 지표가 23종이고 계속 늘어나므로 결과 표가 전부를 보여줄 수는 없다. 어느 것을 기본으로
-- 보일지는 지표를 만든 사람이 계산 코드 옆에서 정하는 것이 맞다 — 화면 코드에 목록을 두면
-- 지표 추가 비용이 다시 2파일이 된다.
-- ---------------------------------------------------------------------------
ALTER TABLE indicator ADD COLUMN default_column boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN indicator.default_column IS
    '결과 표에 기본 노출할 컬럼인지. 정본은 계산 클래스의 @IndicatorMeta(defaultColumn)이고 이 컬럼은 그 사본이다.';


-- ---------------------------------------------------------------------------
-- source_indicator_code — 이 백분위의 원본 지표
--
-- 백분위는 예외 없이 오름차순이지만 "상위 20%"의 부등호는 원본 지표의 유불리에 따라 뒤집힌다.
-- PER은 낮은 쪽이 좋으므로 PER_PCTL <= 0.2이고, ROE는 높은 쪽이 좋으므로 ROE_PCTL >= 0.8이다.
-- 이 판단을 하려면 백분위 행에서 원본의 higher_is_better에 도달할 수 있어야 한다.
--
-- 코드에서 '_PCTL' 접미사를 문자열로 떼어내는 방법을 쓰지 않는 이유는, 그 순간 코드 명명 규칙이
-- 조회 로직의 암묵적 계약이 되기 때문이다. 접미사를 바꾸면 화면이 조용히 방향을 잃는다.
--
-- 부수적으로 Phase 6의 "이 지표를 비활성화하면 어떤 화면이 깨지는가"가 백분위까지 확장된다 —
-- PER을 내리면 PER_PCTL도 함께 봐야 한다는 것이 데이터로 드러난다.
-- ---------------------------------------------------------------------------
ALTER TABLE indicator ADD COLUMN source_indicator_code varchar(40) REFERENCES indicator(code);

COMMENT ON COLUMN indicator.source_indicator_code IS
    '백분위 지표의 원본 지표 코드. 백분위가 아닌 지표는 NULL이다. 부등호 방향 판단이 여기 의존한다.';

CREATE INDEX idx_indicator_source ON indicator (source_indicator_code)
    WHERE source_indicator_code IS NOT NULL;
