---
id: ADR-0165
status: 채택됨
date: 2026-11-03
applies_to:
  - "src/main/java/**/pipeline/datasource/us/SecEdgarFacts.java"
---

# ADR-0165. 미국 유도 4분기 지배주주 순이익은 지배주주끼리 뺀다

## 결정

소스가 4분기 지배주주 순이익을 직접 주지도 않고 연결 총액도 안 주면, 유도 값은 `FY 지배주주 − Σ(Q1~Q3 지배주주)`다(`SecEdgarFacts.controllingIncomeQ4`의 마지막 갈래 · S0). 연결 순이익(`net_income`)을 빼서 얻은 값을 지배주주 칸에 두지 않는다. 미국 관문 설정 뒤에 쌓았고 2026-11-03 관문에서 켰다.

## 규칙

- MUST: 4분기 지배주주 칸은 FY · 분기와 같은 기저(지배주주)로 유도한다 — ADR-0154 §규칙. 네 분기를 더하는 지표(`EPS_TTM` 등)가 한 시계열 안에서 다른 기저를 더하지 않게 한다.
- MUST NOT: 「FY는 비지배가 있는데 어떤 분기는 지배 = 연결」일 때 연결 뺄셈으로 되돌리는 가드를 두지 않는다. 그 가드는 칸 하나의 음수(ARES 2024 Q4 −60.5)를 막지만 소비자 TTM에서 더 틀린다(아래 §버린 대안). 고칠 자리는 그 분기의 폴백이다.
- 소스 4분기가 연결만 실은 갈래(셋째)는 이 결정이 안 바꾼다 — 소스 대 잔차를 고르는 4분기 정책이고, 관문 켬 P3a가 따로 다룬다(`docs/reload-gate.md` [S] Q4 세대).

## 검증

```premise S0 앵커 종목이 보존 창에 EPS_TTM을 갖는다
# 앵커 일곱(ACGL CCL GATX KDP MKTX SEIC TER)은 옛 갈래(지배주주 = 연결 뺄셈 · 연결이 게이트에서 null이면 null)에서 보존 창 내내 EPS_TTM이
# 없었다(2026-10-01). 모집단은 그중 지금 미국 편입 — 편출된 종목은 값을 안 내는 것이 정상이다. 술어는 결함의 꼴(창 내내 0일) 그대로다 —
# 최신일로 재면 실적 발표가 10-Q 적재 전까지 재무 지표를 막는 정상 동작(ADR-0195)에 운다(2026-11-03 ACGL · GATX · MKTX · TER).
psql -d stock_screener -At -c "WITH d AS (SELECT min(trade_date) lo, max(trade_date) d FROM indicator_trade_date WHERE market_code = 'US'),
a AS (SELECT s.id, s.ticker FROM stock s WHERE s.market_code = 'US' AND s.ticker IN ('ACGL','CCL','GATX','KDP','MKTX','SEIC','TER')
        AND EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id = s.id AND m.universe_code = 'US_TOP1000' AND m.effective_to IS NULL)),
miss AS (SELECT a.ticker FROM a, d WHERE NOT EXISTS (SELECT 1 FROM indicator_value v WHERE v.stock_id = a.id AND v.indicator_code = 'EPS_TTM' AND v.trade_date BETWEEN d.lo AND d.d))
SELECT CASE WHEN (SELECT d FROM d) IS NULL THEN '잴 것이 없었다 — 미국 지표일이 없다'
  WHEN (SELECT count(*) FROM a) = 0 THEN '잴 것이 없었다 — 앵커 일곱이 지금 편입에 없다'
  WHEN (SELECT count(*) FROM miss) = 0 THEN 'OK 앵커 ' || (SELECT count(*) FROM a) || '종목 전부 보존 창(' || (SELECT lo FROM d) || '~' || (SELECT d FROM d) || ')에 EPS_TTM이 있다'
  ELSE (SELECT string_agg(ticker, ' ' ORDER BY ticker) FROM miss) || '가 보존 창 내내 EPS_TTM이 없다 — 4분기 지배주주 유도가 연결 뺄셈으로 되돌아갔는지 controllingIncomeQ4 마지막 갈래를 본다' END"
```

## 배경

ADR-0159로 `net_income`이 연결 총액이 되면서, 옛 마지막 갈래 `fy.netIncome() − Σq.netIncome()`은 4분기 지배주주 칸에 연결 값을 넣거나(비지배가 있는 회사) 연결이 게이트에서 null이면 null을 냈다. 미국 편입 · 4분기 끝 2020-07 이후에서 틀린 라벨 1,325(421종목) · 빈 칸 197(95종목)이었고(2026-10-04), 앵커 일곱은 `EPS_TTM`이 창 내내 없었다. 관문 뒤(2026-11-03) 앵커 일곱은 창 255일 중 224~249일에 `EPS_TTM`을 갖는다.

## 버린 대안

- **기저 혼합 가드**(FY에 비지배가 있는데 어떤 분기가 지배 = 연결이면 연결 뺄셈으로 되돌림) — 사후 진실(그 해가 다 정리된 뒤의 공시) 기준 틀림 셀-일: 창 5,282 대 4,133 · 1년 전 TTM 4,934 대 3,146 · 5년 전 TTM 7,818 대 6,929(2026-10-04 · 반증 여섯 · 브랜치 `explore/s-vintage/S0-PLAN.md`). 가드가 막는 음수 한 칸보다 TTM 오차가 크다.
- **소스 4분기 「연결만」 갈래를 잔차로 바꾸기** — 맞던 소스값 14칸을 잃었다(ACGL · CNX · STRL · WTM · JLL · MTCH). 4분기 정책 결정이라 이 결정에 안 넣었다.

## 결과

- 쉬워지는 것: 네 분기 합이 FY 지배주주와 같아져 `EPS_TTM` · `PER`의 기저가 한 계열이다.
- 어려워지는 것: ARES · APA · AR · CNH · IVZ는 해를 가로지르는 TTM 오차가 커진다 — 뿌리는 각각 분기 폴백 · 세대 · 이어받기 · 우선주 혼합이고 그 분기에서 고친다.
- 되돌리려면: `controllingIncomeQ4`의 마지막 갈래와 전 종목 재적재 · 백필(ADR-0145).
