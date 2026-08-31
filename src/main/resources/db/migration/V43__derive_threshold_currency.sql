-- 이미 저장된 조건의 통화 축을 채운다. 프리셋은 시더가 코드에서 덮으므로 대상이 아니고,
-- **사용자가 만든 화면**이 대상이다 — 시더는 PRESET만 갱신하므로 사본은 옛 조건을 든 채
-- 남는다(ADR-0072 §규칙 · P27 축①: "프리셋 조건을 복사해 만든 사용자 화면이 원본보다
-- 느슨해지지 않는다").
--
-- 술어를 지표 코드로 박지 않고 데이터로 쓴다 — indicator.value_type이 정본이다.
-- 0 임계는 뺀다: 부호 조건(OCF_SUM_5Y > 0)은 시장 중립이고, 그 근거를 P27 축①이 인정한다.
--
-- 비활성 화면은 대상이 아니다. 선언에서 사라진 프리셋은 시더가 is_active만 내리고 조건은
-- 안 건드리므로 **아무도 영원히 안 채운다** — 그것까지 세면 검증 전제가 매 세션 발화한다.
UPDATE screen_condition sc
SET threshold_currency = 'KRW'
FROM indicator i, condition_group g, screen s
WHERE i.code = sc.indicator_code
  AND g.id = sc.group_id
  AND s.id = g.screen_id
  AND s.screen_type = 'CUSTOM'
  AND s.is_active
  AND sc.threshold_currency IS NULL
  AND sc.operand_type = 'LITERAL'
  AND i.value_type IN ('CURRENCY', 'PRICE')
  AND (sc.literal_value <> 0 OR coalesce(sc.literal_value_2, 0) <> 0);
