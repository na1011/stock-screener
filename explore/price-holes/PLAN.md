# 시세 구멍 — 일일 갱신의 재시도가 편입 전환을 못 넘는다 · 계획 v1

작성 2026-10-05 01시(KST, 월). 운영 DB SELECT만 · 소스 15콜(`HoleSourceProbeLiveTest`, 임시·미커밋).

## 1. 실측

### 모집단과 질의
- 시장 달력 = `price_daily`에서 그 시장 봉이 50개 이상인 날짜(5,092 (시장, 날짜)).
- 구멍 = 그 종목의 첫 봉 이후 시장 달력 날짜 중 그 종목 봉이 없는 날.
- 셋을 셌다.
  - (A) 지금 편입(활성 유니버스 · `effective_to IS NULL` · `stock.is_active`) 1,350종목 × 지표 거래일 색인(255일).
  - (B) 보존 창 편입(`IN_RETENTION_WINDOW`와 같은 술어) × 같은 255일, 그날 편입이었는지(`member_that_day`)와 그 뒤에 봉이 있는지로 가름.
  - (C) 지금 편입 × 지표 읽기 창(시장별 최근 1,441 시장일 — 미국 2021-01-06~, 국내 2020-11-13~).
- 질의는 이 문서 끝 「재현」.

### 결과 (2026-10-05 01시 · 10-05 13:30 일일 갱신 전)
(C) 지금 편입의 읽기 창 구멍 19종목 · 2,326칸:
| 갈래 | 종목 | 구멍 | 소스에 물음 | 판정 |
|---|---|---|---|---|
| 재편입(10-02) | AXTI·BB·KLIC·VSH | 09-11~10-01 15일 | AXTI 09-09~09-17 → 09-11·14·15·16 봉 있음 | **가짜** |
| 재편입 | GVA·LAUR 11 · 456160 8 · FTDR·TFX·NAVN·NJR·AGX 5 · 001740·490470 10 | 마지막 봉 뒤 ~10-01 | 001740·490470 09-14~18 → 09-16 봉 있음 | **가짜** |
| 하루 늦은 봉 | TAP.A·WSO.B | 10-01 | 09-29~10-02 → 10-01 거래량 0 평봉 있음 | 15일 창이 다시 받는다(결함 아님 · 아래 범위 밖) |
| 티커 재사용·상장 첫날 | PPLC 1,294 · CAI 894 · DRS 1 | 2021~2026 | 묻지 않음 | 관문 재고 「무거래 평봉(티커 재사용)」에 귀속 — 이 축 밖 |

(B) 보존 창 편입의 **편입일 구멍** 12종목 · 22칸(전부 마지막 봉 뒤 · 그 뒤 봉 없음):
| 종목 | 구멍(편입일) | 소스 | 판정 |
|---|---|---|---|
| 094170·358570·475830(KOSDAQ) · 069620(KOSPI) | 09-23 | 09-22~25 → **09-23 봉 있음(거래량 있음)** | **가짜** — 09-25 편출 · 추석(09-24~26)로 09-23 봉이 09-28에 공개될 때 이미 대상 밖 |
| 010620 · 042670 · 012510(KOSPI) | 마지막 봉 뒤 1~4일 | 마지막 봉까지 거래량 0 평봉 · 그 뒤 봉 없음 | 진짜(정지·폐지) |
| 335890(KOSDAQ) | 12-10·11 | 12-09까지 · 그 뒤 없음 | 진짜 |
| APGE · CRNX | 09-03·04 · 09-01~03 | 09-02·08-31까지 · 그 뒤 없음 | 진짜(피인수) |
| TAP.A·WSO.B | 10-01 | 위와 같음 | 범위 밖 |

(B)의 나머지(편입이 아닌 날 · 그 뒤 봉 없음) 331종목 · 4,895칸은 위 재편입 14종목을 빼면 **편출 뒤 꼬리**다 — 편출 뒤에 그 종목을 읽는 편입일이 없으므로 결함이 아니다(무엇이 읽는가: 없음). 편입이 아닌 날 · 그 뒤 봉 있음은 PPLC 108칸 하나다.
**재편입하면 그 꼬리가 곧 위 「재편입」 갈래가 된다.**

### 앞선 재편입은 왜 구멍이 없나 — 손 전체 재적재가 가렸다
09-18·09-25 재편입 중 공백이 21일 넘는 10종목의 공백 봉은 전부 **09-10 00:4x(국내) · 09-11 10:3x(미국) · 09-17 01:16** 한 시각에 쓰였다(`price_daily.created_at`) — 손으로 돌린 전체 시세 재적재다.
재편입은 리밸런싱마다 2~32건이고 공백 21일 초과가 0~15건이다(OBSERVED). **관문 원칙(2026-10-04)으로 임시 전체 재적재가 멈추면 이 구멍이 리밸런싱마다 쌓인다** — 10-02가 그 첫 회다.

### 뿌리 한 줄
`DailyUpdateJob.run`의 대상이 `activeUniverseMemberIds()`(지금 편입)이고 창이 `priceWindowStart(target) = target − 15일` 하나다 — **재시도 창이 「그 날짜에 편입이었던 종목」이 아니라 「지금 편입」에 걸리고, 지금 편입이라도 15일 앞의 구멍은 영영 다시 안 묻는다.**
- 재편입: 편출 동안 안 받은 봉 중 `target − 15일` 앞이 남는다. 재편입 첫 실행이 그 앞을 안 묻는다.
- 편출 직전: 마지막 편입일 봉이 공개 지연(연휴) 중 편출되면, 공개된 뒤의 실행이 그 종목을 대상에서 뺀다.
- ADR-0042 §버린 대안이 「개별 종목만 비는 결손은 별도의 갭 메우기 잡이 할 일」로 미뤘고 그 잡은 이력 0행(`PRICE_HISTORY`)만 맡는 꼬리로만 생겼다.
  기각 근거 「창 폭이 예측 불가능」은 **전역 `max(trade_date)` 가변 창**에 붙은 것이다 — 이 계획은 구멍이 있는 종목만 넓히므로(지금 19종목 · 나머지 1,331종목은 바이트까지 같은 창) 그 근거가 안 선다.
- 감시가 못 본 이유: `MembershipGap.PRICE_HISTORY`는 「첫 봉 > 편입일」(재편입은 옛 봉이 있어 거짓) · `PRICE_COVERAGE`는 비율 하한 0.25(255일 중 15일 결손 = 94%).

### 0콜 재현
이 문서 끝 「재현」의 SQL 둘 — (C) 19종목·(B) 편입일 구멍 12종목이 나오면 재현이다.

### 무엇이 틀리나 (소비자)
- 재편입 종목: 10-02 이후 편입일의 지표가 **봉이 빠진 계열**로 계산된다 — 봉 수로 세는 창(이동평균·RSI·변동성·52주 수익률의 기준 봉)이 그만큼 과거로 밀린다. 오늘 13:30이 09-17 뒤를 채운 뒤에도 **AXTI·BB·KLIC·VSH 4거래일 · 001740·490470 1거래일**이 남는다(계산 — 13:30 뒤 다시 잰다).
- 편출 직전 4종목: 09-23에 **지표가 없다** — 그날 화면·백분위 모집단에서 빠져 있다(과거 날짜 결과가 틀림).
- 셀 수는 재지 않았다(재편입 종목의 어느 지표가 몇 칸 바뀌는지) — 드라이런이 잰다.

## 2. 계획 v1 — 일일 갱신이 종목별 구멍까지 다시 묻는다

### 대상 (`DailyUpdateJob.run`)
지금 편입 ∪ **창 [`priceWindowStart(target)`, target] 안에 편입일이 있고 그 편입일에 봉이 없는 종목**(편출 직전 갈래).
- 앞 갈래 술어: 활성 유니버스 편입 행 중 `effective_from <= d AND (effective_to IS NULL OR effective_to > d)`인 시장일 d(창 안) 중 봉이 없는 날이 있다.
- 진짜 부재(APGE·CRNX 같은 폐지)는 창 15일 동안만 다시 묻고 창을 벗어나면 대상에서 빠진다 — 재시도의 상한이 곧 기존 창이다.

### 종목별 시작일
- 지금 편입: `from_s = min(priceWindowStart(target), 첫 구멍)` — 첫 구멍 = [max(첫 봉, 시장 읽기 창 하한), `priceWindowStart(target)`) 안 시장일 중 봉이 없는 가장 이른 날.
  읽기 창 하한 = 시장 달력의 최근 `IndicatorRegistry.readWindowBars()` 시장일(지금 1,441).
- 편출 직전 갈래: `priceWindowStart(target)` 그대로.
- 구멍이 없는 종목은 지금과 같은 창이다 — **지금 1,331종목의 콜이 바이트까지 같다**(드라이런 판정 ①).

### 왜 일일 갱신 안인가
같은 실행의 지표 단계가 채운 봉으로 바로 계산한다. 따로 잡을 두면 13:30 지표가 구멍 난 계열로 한 번 나가고 14:20 ①이 되돌린다 — 한 시간짜리 틀린 값을 만들 이유가 없다.

### 그대로 두는 것
- `PriceWriter` upsert는 기존 행의 `adj_factor`를 보존한다(`adj_close = EXCLUDED.close × price_daily.adj_factor`). 새로 채운 구멍 봉은 계수 1로 들어가고, 14:00 사슬의 `adjustment-rebuild`(지금 편입 전원 · 원장에서 전 구간 재구축)가 같은 날 바로잡는다 — 그 한 시간은 `MembershipGapFiller` javadoc이 이미 적은 성질과 같다. 편출 직전 갈래는 재구축 대상 밖이지만 편출 뒤 원장을 안 묻는 것은 모든 과거 편입과 같다.
- 지수 수집(`marketIndexJob.collectInto(from, to)`)·`alertIfNothingArrived`·`PipelineSchedulerTest`의 창 검사는 `priceWindowStart`를 그대로 쓴다.
- 감시 술어(`PRICE_HISTORY`·`PRICE_COVERAGE`)는 안 바꾼다 — 구멍을 고치는 것이 이 계획이고, 남는 구멍(진짜 부재)을 상시 붉히는 축을 새로 만들지 않는다.

### 설정 뒤에 두는가 — 안 둔다(내 판단 · 반증에 묻는다)
`CLAUDE.md`의 설정 규칙이 막는 것은 **값을 바꾸는 어댑터·계산기 코드**다. 이 변경은 매핑·계산을 안 바꾸고 **소스가 이미 준 봉 중 안 받은 날을 받는 수집 범위**다 — 화해(ADR-0166)와 같이 「데이터를 원장에 맞추는 유지」다.
관문에 묶으면 관문까지 재편입마다 구멍이 쌓이고, 관문의 전체 재적재가 그것을 가린다(위 「손 전체 재적재가 가렸다」와 같은 모양).

### 대가 (재보지 않은 것 포함)
- 콜: 편출 직전 갈래 지금 4종목 · 재편입 갈래는 종목당 1콜 그대로(범위만 넓음). 진짜 부재(PPLC·CAI·DRS)는 매일 2021년부터 다시 묻는다 — 국내는 기간조회 1콜, 미국은 페이지 1장인지 **재지 않았다**.
- 구멍 질의 비용: 위 (C) 질의가 1.0초(시장 달력 0.2초 포함) — 13:30 실행에 그만큼 는다.
- 편출 직전 갈래의 지표 단계: 그 종목의 원본층을 다시 쓴다 — 14:20 ①이 창 편입 전원으로 같은 일을 하므로 새 동작이 아니다.

### 바뀌는 것 · 시험
- `StockRegistrar`(또는 `PriceCalendar`)에 「종목별 첫 구멍」과 「창 안 편입일 구멍 종목」 질의 하나씩 — 술어는 위 재현 SQL과 같은 정의.
- `DailyUpdateJob.run`·`retryFailures`(재시도는 실패 종목만 — 시작일 규칙은 같이 탄다).
- 시험(실 DB): ① 재편입 종목이 첫 구멍부터 받는다 ② 구멍 없는 종목은 `priceWindowStart`에서 받는다(가짜 수집기가 받은 `from`을 셈) ③ 창 안 편입일 구멍이 있는 편출 종목이 대상에 든다 ④ 창 밖 편출·구멍 없는 편출은 안 든다 ⑤ 첫 봉 앞은 구멍이 아니다(신규 상장) ⑥ 읽기 창 하한 앞 구멍은 안 묻는다.
  대조군은 원래 결함으로 만든다 — 시험 ①·③이 지금 main 코드에서 붉는지 먼저 본다.
- 문서: ADR-0042 §버린 대안 「가변 창」 줄에 이 경로를 잇는다 · `DailyUpdateJob` javadoc · `batch-load` skill의 15일 창 서술 · `MembershipGap.PRICE_HISTORY` javadoc의 「재편입은 이 축에 안 걸린다」.

## 3. 드라이런 (판정 규칙은 대조 전에 이 절에 커밋한다)
사본 둘(같은 덤프) · a = main · b = 이 계획 · 같은 대상일로 `daily-update` 한 판씩(알림 변수 비움 · `boot()`).
- ① 구멍 없는 종목: a·b의 `price_daily`가 (종목, 날짜)마다 같다(소스가 두 실행 사이에 바꾼 봉은 양쪽 원시값으로 귀속).
- ② b에만 있는 봉 = 위 「가짜」 갈래의 구멍 날짜 ∪ 오늘 새로 공개된 날 — 그 밖 0.
- ③ 진짜 부재 갈래: a·b 같음(소스가 안 줌).
- ④ b 실행 뒤 재현 SQL (C)·(B)에서 가짜 갈래 0 · 진짜 부재만 남는다.
- ⑤ 지표: 재편입 종목의 원본층이 a·b에서 다르고 그 밖 종목은 같다 — 다른 칸 수를 적는다(사전 기대치 없음 · 0이면 의심).
- ⑥ 소요: b − a(구멍 질의 + 넓은 범위).

## 재현
```sql
-- (C) 지금 편입 × 읽기 창
CREATE TEMP TABLE mcal AS SELECT s.market_code, p.trade_date FROM price_daily p JOIN stock s ON s.id=p.stock_id GROUP BY 1,2 HAVING count(*) >= 50;
CREATE TEMP TABLE floor_ AS SELECT market_code, min(trade_date) fl FROM (SELECT market_code, trade_date, row_number() OVER (PARTITION BY market_code ORDER BY trade_date DESC) rn FROM mcal WHERE trade_date <= '2026-10-01') x WHERE rn <= 1441 GROUP BY 1;
CREATE TEMP TABLE cur AS SELECT DISTINCT s.id, s.market_code, s.ticker FROM universe_membership m JOIN stock s ON s.id=m.stock_id JOIN universe u ON u.code=m.universe_code AND u.is_active WHERE m.effective_to IS NULL AND s.is_active;
SELECT c.market_code, c.ticker, count(*), min(k.trade_date), max(k.trade_date)
FROM cur c JOIN floor_ f USING (market_code) JOIN LATERAL (SELECT min(trade_date) fb FROM price_daily p WHERE p.stock_id=c.id) b ON true
JOIN mcal k ON k.market_code=c.market_code AND k.trade_date >= greatest(f.fl, b.fb) AND k.trade_date <= '2026-10-01'
WHERE NOT EXISTS (SELECT 1 FROM price_daily p WHERE p.stock_id=c.id AND p.trade_date=k.trade_date) GROUP BY 1,2 ORDER BY 3 DESC;

-- (B) 보존 창 편입 × 지표 거래일 색인 · 그날 편입이었던 구멍
WITH win AS (SELECT min(trade_date) lo, max(trade_date) hi FROM indicator_trade_date),
mem AS (SELECT DISTINCT s.id, s.market_code, s.ticker FROM universe_membership m JOIN stock s ON s.id=m.stock_id JOIN universe u ON u.code=m.universe_code AND u.is_active, win
        WHERE m.effective_to IS NULL OR (m.effective_from <= win.hi AND m.effective_to > win.lo)),
fb AS (SELECT mem.*, (SELECT min(trade_date) FROM price_daily p WHERE p.stock_id=mem.id) first_bar FROM mem)
SELECT fb.market_code, fb.ticker, c.trade_date FROM fb JOIN indicator_trade_date c ON c.market_code=fb.market_code
WHERE c.trade_date >= fb.first_bar AND NOT EXISTS (SELECT 1 FROM price_daily p WHERE p.stock_id=fb.id AND p.trade_date=c.trade_date)
  AND EXISTS (SELECT 1 FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
              WHERE m.stock_id=fb.id AND m.effective_from<=c.trade_date AND (m.effective_to IS NULL OR m.effective_to>c.trade_date))
ORDER BY 1,2,3;
```
