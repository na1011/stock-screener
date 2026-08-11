-- V19: 보험·금융서비스 종목에 남아 있는 Altman Z 행을 지운다
--
-- `AltmanZCalculator`가 KSIC 중분류 65·66을 명시적으로 제외하게 됐지만(ADR-0044),
-- **계산기를 고치는 것만으로는 이미 저장된 행이 사라지지 않는다.** IndicatorRunner는 값을
-- 못 내면 건너뛸 뿐이고 IndicatorValueWriter는 UPSERT만 한다. 그래서 옛 값이 그대로 남아
-- 필수 조건 `ALTMAN_Z >= 2.6`을 계속 통과한다 — 에러도 경고도 없다.
--
-- 지우는 대상은 **파생 지표**다. 원자료는 건드리지 않으며 필요하면 백필로 되살아난다
-- (ADR-0011의 비대칭). 실측 대상은 카카오페이 1종목 752행이다.
--
-- 재발은 ADR-0044의 premise가 세션 시작에 잡는다 — 종목의 업종이 65·66으로 바뀌면
-- 그때 다시 이 상태가 되고, 그 사실을 사람이 알아야 한다.

DELETE FROM indicator_value iv
USING stock s
WHERE s.id = iv.stock_id
  AND iv.indicator_code = 'ALTMAN_Z'
  AND s.industry_division IN ('65', '66');
