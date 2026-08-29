# Alpaca — 기업 행위 (분할·병합·배당·분사)

미국 수정계수의 원자료. 이 축의 결정은 ADR-0090에, 미국 시세는
[`alpaca-bars.md`](./alpaca-bars.md)에 있다.

- 어댑터는 `pipeline/datasource/us/AlpacaCorporateActionAdapter`, 포트는 `CorporateActionDataSource`,
  적재는 `CorporateActionJob`이다.
- **HTTP 코드 → `Kind` 매핑의 정본은 `AlpacaResponse`의 javadoc이다.** 문서로 떼어 두면
  갈려도 아무도 모른다(`DartResponse.kindOf`와 같은 취급).
- 원장의 상태·판정 계약은 `CorporateActionLedger`가, 국내 축은
  [`dart-corporate-action.md`](./dart-corporate-action.md)가 답한다.
- 쿼터와 엔드포인트 색인은 `api-sources` skill이 답한다.
- 탐침: `AlpacaCorporateActionProbeLiveTest` — **아래 실측이 그 출력 그대로다.**
- 호출 방법과 함정의 원문은 [`../external/us-market-api-dev-guide.md`](../external/us-market-api-dev-guide.md).

여기는 **이 축의 필드 계약과, 그 문서가 답하지 않았거나 실측에서 달랐던 자리**다.

---

## 엔드포인트

```
GET https://data.alpaca.markets/v1/corporate-actions
```

**Market Data API다** — 시세와 같고 자산 마스터·캘린더(`paper-api.alpaca.markets`)와는 다르다.
구 엔드포인트 `/v2/corporate_actions/announcements`는 deprecated다.

| 파라미터 | 쓰는 값 | 왜 |
|---|---|---|
| `symbols` | 종목 하나 | 종목 단위 실패 격리(ADR-0007)를 지키려면 묶지 않는다. 묶으면 `limit`이 전체 기준이라 **한 종목의 결측과 묶음 전체의 절단이 같은 모양**이 된다 |
| `types` | 명시 | 안 주면 전부 온다. **담지 않을 것을 세려면 일부러 안 좁혀 부르는 실행이 따로 있다** |
| `start`·`end` | **반드시 명시** | 기본값이 둘 다 **당일**이다. 안 주면 `{"corporate_actions":{}}`가 온다(실측) |
| `limit` | `1000`(최대) | 기본 **100**이고 **전체 기준**이다(실측) |
| `sort` | `asc` | |
| `data_quality` | `complete` — **명시** | 기본값이지만 명시한다. 기본에 기대면 소스가 바꾸는 날 조용히 갈린다 |
| `region` | `us` — **명시** | 같은 이유. 기본값이 `us`다 |

---

## 실측 (2026-08-29, 무료 페이퍼 계정)

`./gradlew liveTest --tests "*AlpacaCorporateActionProbe*"`

**무료 계정으로 200이 온다** — 이 엔드포인트에 시세의 15분 규칙 같은 구독 제한이 걸리지 않는다.

| 값 | 실측 |
|---|---|
| 봉투 | `{"corporate_actions": {<타입 복수형>: [...]}, "next_page_token": …}` |
| 파일럿 11종목 × 2023-12-01~2026-08-29 | **80건** — `cash_dividends` 78 · `forward_splits` 1 · `spin_offs` 1 |
| `start`·`end` 없이 같은 요청 | **0건** (`{"corporate_actions":{}}`) |
| `limit` 기본값 | **100** — 2024년 전체 분할·병합을 `limit` 없이 부르면 100건에서 잘리고 `next_page_token`이 있다 |
| `limit=1000` | 같은 조회가 **773건 1페이지** |
| 2024년 미국 **전체** 분할·병합 | **773건** — `forward_splits` **99** · `reverse_splits` **674** |
| `data_quality` `complete` vs `all` | 파일럿 범위에서 **둘 다 80건** |
| 모르는 심볼(`ZZZZNOTREAL,NVDA`) | **HTTP 200**, NVDA 것만 온다 — 에러도 빈 배열도 아니다 |
| 점이 든 심볼·우선주 | `KIM.PRL` **11건** — 표기를 받는다. `BRK.A`·`BRK.B`는 0건이고 그것은 사건이 없는 것이다 |

### 봉투 — **온 타입의 키만 있다**

```json
{"corporate_actions": {"cash_dividends": [...], "forward_splits": [...], "spin_offs": [...]},
 "next_page_token": null}
```

**없는 타입은 키 자체가 없다.** 빈 배열이 아니다 — `path("reverse_splits")`가 빈 노드를 내므로
없는 것과 0건이 파서에서 같아진다.

### 타입별 필드 — **심볼 필드의 이름이 타입마다 다르다**

```json
// forward_splits — NVDA 2024-06-10 10:1
{"symbol":"NVDA", "cusip":"67066G104", "ex_date":"2024-06-10",
 "old_rate":1, "new_rate":10,
 "record_date":"2024-06-07", "payable_date":"2024-06-10",
 "due_bill_redemption_date":"2024-06-10", "process_date":"2024-06-10",
 "id":"50199fac-0af8-43ef-9846-eaf64c6d322d"}

// reverse_splits — ATRA 2024-06-20 25:1
{"symbol":"ATRA", "old_cusip":"046513107", "new_cusip":"046513206",
 "ex_date":"2024-06-20", "old_rate":25, "new_rate":1, …}

// spin_offs — T가 WBD를 분사 (ex_date 2022-04-11, process_date 2025-07-18)
{"source_symbol":"T", "new_symbol":"WBD",
 "source_cusip":"00206R102", "new_cusip":"934423104",
 "source_rate":1, "new_rate":0.24192,
 "ex_date":"2022-04-11", "record_date":"2022-04-05", "payable_date":"2022-04-08",
 "due_bill_redemption_date":"2022-04-12", "process_date":"2025-07-18"}

// cash_dividends
{"symbol":"AAPL", "cusip":"037833100", "ex_date":"2024-02-09", "rate":0.24,
 "record_date":"2024-02-12", "payable_date":"2024-02-15", "process_date":"2024-02-15",
 "special":false, "foreign":false}
```

**분할·병합·배당은 `symbol` 하나인데 분사는 `source_symbol`(발행사)과 `new_symbol`(분사된 회사)
둘이다.** 뒤쪽을 집으면 **묻지도 않은 종목이 응답에 있는 것처럼 보인다** — 실제로 그렇게
오독했다. 가격이 조정되는 쪽은 `source_symbol`이다.

### 비율의 방향 — `new_rate / old_rate`

`CorporateAction.ratio`의 계약이 *"1주가 몇 주가 되었는가"*이고, 그것이 `new_rate / old_rate`다.

| 사건 | `old_rate` | `new_rate` | ratio | 우리 종류 |
|---|---|---|---|---|
| NVDA 2024-06-10 10:1 분할 | 1 | 10 | **10** | `SPLIT` |
| ATRA 2024-06-20 25:1 병합 | 25 | 1 | **0.04** | `REVERSE_SPLIT` |

**이 방향이 어댑터의 유일한 무증상 버그 자리다.** 뒤집으면 과거 시세가 배수의 제곱만큼 틀리고
값은 그럴듯하다(ADR-0049).

### `ex_date`가 우리 봉의 불연속과 같은 날인가 — **NVDA에서 참**

저장된 미국 봉 2,772행에서 `PriceDiscontinuity` 임계(1.30/0.70)를 넘는 불연속은 NVDA 2024-06-10
하나이고, 소스의 `ex_date`가 **정확히 그 날**이다. `AdjustmentRecalculator`는
`trade_date < 효력일`인 봉을 나누므로 **하루만 밀려도 이미 분할된 봉이 한 번 더 나뉜다.**

### 페이징 — 정렬이 **심볼 우선**이다

파일럿 11종목 80건을 `limit=2`로 받으면 **40페이지**다. 페이지별 첫 사건의 심볼이
`AAPL ×6 → F ×6 → KIM.PRL ×6 → META → MSFT → NVDA → T` 순이고, 각 심볼 안에서 `process_date`가
오름차순이다.

**개발가이드는 정렬 기준을 `process_date`라고 적었지만 실측은 심볼 우선이다** — 시세
(`/v2/stocks/bars`)와 같은 모양이고, 따라서 **중간에 멈추면 뒤쪽 심볼이 통째로 빈다.**

---

## 함정

- **`start`/`end`가 `process_date` 기준인데 포트는 `ex_date` 기준 필터를 요구한다.**
  그리고 **둘이 3년 넘게 벌어진 사건이 실재한다** — T→WBD 분사는 `ex_date` 2022-04-11인데
  `process_date`가 **2025-07-18**이다. 공급자가 *"기업행위의 생성 시점을 보장하지 않는다"*고
  공식 경고한 것이 이 모양이다. **`ex_date` 기준으로 창을 좁게 잡으면 못 받는다** —
  넓게 부르고 `ex_date`로 거른다.
- **없는 타입은 키가 없다.** `{"corporate_actions":{}}`가 「그 기간에 사건이 없다」이고,
  파라미터를 빠뜨렸을 때와 **같은 모양**이다. `start`/`end`를 안 주면 정확히 그것이 온다.
- **모르는 심볼은 200 응답에서 조용히 빠진다.** 시세와 같다 — 이 소스는 「사건이 없다」와
  「종목을 모른다」를 갈라 주지 않는다.
- **분사의 심볼 필드는 `symbol`이 아니다.** `source_symbol`을 안 보면 발행사의 사건이
  분사된 회사 쪽으로 간다.
- **비율이 정확히 안 떨어지는 경우가 있다.** 3:1 병합이면 `1/3`이고, 스케일 없는
  `BigDecimal.divide`는 **`ArithmeticException`**을 던진다. 그 RuntimeException은 종목 실패가
  아니라 **배치 전체를 죽인다**(`failUnexpectedly` 뒤 되던져진다). `numeric(18,10)`에 맞춰
  스케일을 지정해 나눈다. **`ShareRatioSnap`은 쓰지 않는다** — 그것은 *관측된* 배수를 유리수에
  붙이는 도구이고, 여기서는 소스가 유리수를 그대로 준다.
- **원장의 근접중복 창이 미국에서 퇴화한다.** `CorporateActionLedger.findNearDuplicate`는 두
  날짜 사이의 **`price_daily` 행 수**로 5거래일을 세는데, 저장된 미국 봉이 2024년뿐이라 그 밖
  구간에서는 어떤 두 날짜든 **0거래일**이다. 방어는 45달력일 하나뿐이고, 걸리면 먼저 든 공시 행이
  `APPROVED`라 **알림 없이 `log.info`로 끝난다.** 조회 창을 저장된 봉 구간 근처로 두는 것이
  지금의 완화책이다.

---

## 이 소스가 답하지 못하는 것

**「없다」와 「아직 안 물었다」를 가르기 위해 적는다.**

- **상장주식수.** 이 엔드포인트도 시세도 주지 않는다. 그래서 국내가 비율·효력일의 정본으로 쓰는
  `price_daily.listed_shares` 대조가 **미국에는 없다** — `CorporateActionReader`의
  `sameDayShareRatio`도 「효력일 이후 주식수 점프」도 미국에서는 비어 있다. 사람이 판정할 때
  볼 증거가 **가격 배수 하나**다.
- **`ex_date`가 실제 매매 기준일과 같은지.** NVDA 한 건에서 참인 것을 확인했다. **전량에서는
  안 쟀다.**
- **분사·합병의 기준가 조정 비율.** `new_rate`(0.24192)는 배정 비율이지 가격 조정 계수가 아니다.
  그것을 계수로 쓰면 틀린다.
- **`region=non_us`·`cusips`·`ids` 파라미터의 동작.** 안 썼고 안 쟀다.
- **소스의 이력 시작 시점.** 2022년 사건이 오는 것은 봤다(T→WBD). 그 앞은 확인하지 않았다.
