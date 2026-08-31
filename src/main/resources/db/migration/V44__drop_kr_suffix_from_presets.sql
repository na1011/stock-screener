-- 활성 프리셋 여덟의 코드에서 _KR 접미를 뗀다(2026-08-31 사용자 결정).
--
-- 접미가 뜻하던 것이 사라졌다. ADR-0106이 임계에 시장 축을 달아 **같은 프리셋이 두 시장에서
-- 돈다** — BUFFETT_QUALITY_KR이 미국에서 41종목을 낸다. 이름이 사실이 아니게 됐고,
-- ADR-0012 §배경의 IMPORTANT(이름에 다른 내용을 담지 않는다)가 그대로 걸린다.
--
-- MUST: 제자리 UPDATE다. 카탈로그만 고치면 시더가 새 행을 만들고 옛 행을 비활성으로 내리는데,
-- watchlist_item이 screen_id를 참조하고 WatchlistAlertRunner가 비활성 화면을 log.info만 남기고
-- 건너뛴다 — 알림이 조용히 멈춘다. 실측으로 지금 VALUE_KR을 가리키는 워치리스트가 1건이다.
--
-- MUST: 이 마이그레이션과 PresetCatalog를 같은 배포에 묶는다. 마이그레이션만 먼저 나가면
-- 옛 빌드가 VALUE_KR을 새로 만들고 VALUE를 비활성으로 내린다.
--
-- 충돌이 없는 근거는 「부분 유니크」가 아니라(uq_screen_code의 조건은 code IS NOT NULL뿐이다)
-- **접미 없는 코드가 아직 하나도 없다**는 것이다(실측 0건).
--
-- 비활성 넷(QVG_KR·SWING_MIDTERM_KR·SWING_TREND_KR·ICHIMOKU_SP_KR)은 안 바꾼다. 폐기된
-- 프리셋의 기록이고, ADR-0074의 premise가 SWING_TREND_KR을 이름으로 확인한다.
UPDATE screen SET code = 'BUFFETT_QUALITY',   updated_at = now() WHERE code = 'BUFFETT_QUALITY_KR';
UPDATE screen SET code = 'VALUE',             updated_at = now() WHERE code = 'VALUE_KR';
UPDATE screen SET code = 'LYNCH_FAST_GROWER', updated_at = now() WHERE code = 'LYNCH_FAST_GROWER_KR';
UPDATE screen SET code = 'LYNCH_STALWART',    updated_at = now() WHERE code = 'LYNCH_STALWART_KR';
UPDATE screen SET code = 'MOMENTUM',          updated_at = now() WHERE code = 'MOMENTUM_KR';
UPDATE screen SET code = 'STAGE2_BREAKOUT',   updated_at = now() WHERE code = 'STAGE2_BREAKOUT_KR';
UPDATE screen SET code = 'PULLBACK_UPTREND',  updated_at = now() WHERE code = 'PULLBACK_UPTREND_KR';
UPDATE screen SET code = 'LOWVOL_QUALITY',    updated_at = now() WHERE code = 'LOWVOL_QUALITY_KR';
