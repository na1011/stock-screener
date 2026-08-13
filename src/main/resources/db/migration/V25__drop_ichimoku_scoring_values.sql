-- 일목 점수화 파생 13개의 값을 지운다(ADR-0046 개정).
--
-- indicator 행은 남긴다 — indicator_value와 screen_condition이 FK로 붙어 있고,
-- IndicatorCatalogSynchronizer.deactivateMissing()이 is_active = false로 내리면
-- 조건 빌더·결과 표·종목 상세 셋에서 함께 사라진다.
--
-- 값을 지우는 이유는 자리 때문이 아니다. IndicatorRetentionPolicy.purge()가 코드를
-- 가리지 않으므로 방치해도 180거래일 뒤 소멸하지만, 그동안 batch-load skill이 진단
-- 수단으로 지정한 "지표 코드별 일자 수"가 죽은 코드를 살아 있는 것처럼 보고한다.
-- 착수 시점 실측으로 54만 행이고 전체의 14.6%다.
DELETE FROM indicator_value
WHERE indicator_code IN (
    'ICHI_TIER', 'ICHI_SIGNAL', 'ICHI_PILLAR', 'ICHI_CONFIDENCE', 'ICHI_CONCLUSION',
    'ICHI_BULL', 'ICHI_BEAR', 'ICHI_EXT_SIGNAL', 'ICHI_STAGE', 'ICHI_LEAF',
    'ICHI_FRESHNESS', 'ICHI_T3YEOK', 'ICHI_KAIZEN_MATCH'
);
