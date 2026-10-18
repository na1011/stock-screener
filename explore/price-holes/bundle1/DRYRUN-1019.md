# 묶음 1 드라이런 — 기대값(실행 전 커밋)

재료: 작은 DB `stock_screener_gate_b1dr`(`ops/apply-window/small-db.sh` · 표본 36 = 경계 14 · 머리 평봉 8 · 원장 11 · 대조 AAPL · TEVA · WSO.B · 표 37 운영 행 수 대조 통과) ·
jar = main `85b5f980` 이후 작업 트리 · 쓰기는 `ops/write-session.sh exec b1dr --db stock_screener_gate_b1dr` · 알림 변수 비움. 판정 뒤 DB를 지운다.

| # | 명령 | 기대 |
|---|---|---|
| 1 | `price-boundary`(계획만) | 대상 23행 = 경계 14(봉 10,829 · 운영 SELECT와 행마다 같음) + 머리 평봉 9(SN 983 · MBLY 788 · DJT 518 · OWL 324 · MIR 134 · ACT 88 · LNZA 160 · SMCI 92 · **WSO.B 4**(2019-09-03~06 · 첫 실거래 09-09 앞 — 성질 규칙)) · 경계 앞 원장 BAM 2 · 거절 0 · 쓴 행 0 |
| 1b | `price-boundary --apply=true` → 다시 계획만 | CSV 줄 = 10,829 + 3,091 · 지운 뒤 대상 0 · BAM 원장 2행 REJECTED · AAPL · TEVA 봉 수 불변 |
| 2 | `adjustment-rebuild --tickers=<23>` → `indicator-reconcile --tickers=<23>` | `RETURN_52W` CAI 2026-04-01 · FLY 2025-10-01 · PPLC 2026-10-01이 경계 뒤 봉만으로(252봉 미만이면 빈칸) · `TRADING_DAYS_750` SN · MBLY = 경계 뒤 봉 수(750 포화 전) · AAPL · TEVA 원본층 차이 0 |
| 3 | `symbol-era-seed`(계획만 → apply) → `initial-load --tickers=UHAL.B,SPHR --price-years=7 --skip-financial=true --reload-existing=true` | 대상 = 표본 안의 UHAL.B ← UHALB · SPHR ← MSGE(그 밖 표본 행 0) · UHAL.B 봉 +26(2022-11-10~12-16) · SPHR 봉 +약 758(~2023-04-20 · 값 = 옛 MSGE asof 04-20) |
| 4 | `initial-load --tickers=CAI,VG,SN,CR --price-years=7 --skip-financial=true --reload-existing=true` | 경계 앞 · 머리 평봉 재등장 0 · 로그에 「관측 구간 밖 N봉」 > 0(소스가 그 봉을 지금도 준다 — 대조군) |
| 5 | `corporate-action --tickers=CBSH,SCCO,RJF,WOLF,UHAL,SPHR,IR,CORZ,HUT,SIRI,BAM` → `adjustment-rebuild` 같은 표 | CBSH 6 · SCCO 10 BONUS_ISSUE APPLIED · RJF OBSERVED(note 「가격 반증 — 관측 ×1.0085」) · WOLF OBSERVED(자식 가치 못 잼) · UHAL SPIN_OFF 가치 비율 있음 · SPHR SPIN_OFF 가치 비율 있음 · CORZ OBSERVED(「체결 없는 채움」) · HUT · SIRI 원장 무변화 · BAM 경계 앞 사건 새로 0 · 재구축 뒤 CBSH 2024-12-03 수정 종가 비 ≈ 1/1.05 · UHAL 2022-11-10 수정 종가 연속 |
| 6 | 감시(`PriceIntegrityAlerter.evaluate`를 그 DB에서) | 교정 전: 간격 4(CAI · FLY · NIQ · PPLC) · 외딴 평봉 = 경계 행 + 면제 · 상쇄 배수 UHAL · CRC → 교정 · 보충 뒤 0 |
| 7 | `price-boundary --apply=true` 두 번째 | 지운 0(멱등) |
