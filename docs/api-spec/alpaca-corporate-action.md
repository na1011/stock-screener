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
  어댑터가 실제 소스에 통하는지는 `AlpacaCorporateActionLiveTest`가 지킨다.
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

### 사건은 **그때의 심볼** 아래 있다 — 이름이 바뀐 종목은 옛 심볼로 물어야 온다 (2026-09-17 실측)

`symbols=현재 심볼`로 물으면 이름 변경 **뒤**의 사건과 `name_changes` 레코드만 온다. 그 앞의
분할·병합은 **옛 심볼로 물어야** 온다. 전 창(2019-09 ~) 실측:

| 현재 | 현재 심볼로 물으면 | 옛 심볼로 물으면 |
|---|---|---|
| `FLG` | `name_changes` old `NYCB` → new `FLG` · process 2024-10-28 | `NYCB`: `reverse_splits` 3 → 1 · ex **2024-07-12** |
| `DFTX` | `name_changes` old `MNMD` → new `DFTX` · process 2026-01-15 | `MNMD`: `reverse_splits` 15 → 1 · ex **2022-08-29** |
| `QXO` | `name_changes` SSNT → QXO 2024-06-06 · **병합 8 → 1도 같은 날 QXO로 온다** | `SSNT`: 분할 0건 |
| `SIRI` · `HUT` | 병합이 현재 심볼로 온다(CUSIP만 바뀐 신설 지주회사 · `name_changes` 없음) | `LSXMA`·`LSXMK`: `stock_mergers`만 |

```json
// name_changes — 옛·새 심볼 응답 양쪽에 **같은 id로** 온다
{"id":"458870b2-…", "old_symbol":"NYCB", "new_symbol":"FLG",
 "old_cusip":"649445400", "new_cusip":"649445400", "process_date":"2024-10-28"}
```

- **옛 심볼은 재사용된다**(이 DB의 `VG`: Vonage → Venture Global). 옛 심볼로 받은 사건은 **그 심볼이
  우리 회사의 것이던 시절**([그 심볼로 바뀐 날, 다음 심볼로 바뀐 날])과 **CUSIP 계보**(분할의
  `new_cusip` → `old_cusip`을 거슬러 잇는다)로 자른다. `AlpacaCorporateActionAdapter`의 `Era`가 그 자리다.
- **같은 레코드가 두 응답에 온다** — 사건·제외 타입 수를 `id`로 한 번만 센다.
- **`UHAL` 2022-11-10 비의결주식 배분(가격 ×1/9.87)은 어느 심볼(`UHAL`·`UHAL.B`)로도 안 온다.**
  `UHALB`로는 안 물었고 SEC에도 안 물었다. 미국은 휴리스틱이 없어(`listed_shares` NULL) 원장 행을 만들
  경로가 없다.

### 끝 사건 — 피인수 · 가치 없음 제거 (2026-10-17 실측 · ADR-0180 끝 판정)

`symbols` 없이 `types=cash_merger,stock_merger,stock_and_cash_merger,worthless_removal`로 시장 전체를 묻는다(2025-09-01~2026-10-16 ·
이름 변경 포함 2쪽). 효력일은 합병이 `effective_date` · 가치 없음 제거가 `process_date`다(그 타입에 효력일 필드가 없다).

- **피인수 실체는 `/v2/assets` 목록에서 사라진다** — 창 안 SIP 거래가 있는 피인수·가치 없음 제거 661 중 588이 목록에 없다.
  단건 `/v2/assets/{acquiree_cusip}`로는 `inactive`로 온다(661/661). **그런데 단건은 그 실체의 사실이 아니다** — 기호가 오늘 다른
  실체에 있는 49건은 49/49 오늘 주인의 이름·거래소를 준다(`PINC` → 「PGIM Securitized Income ETF · BATS」).
- **사건 ≠ 폐지.** 기호에 우리 행이 있는 사건 21 중 17은 사건 뒤에도 같은 실체가 거래된다(CUSIP만 바뀐 재조직 — `AZN` `TRI` `QGEN`
  `IRTC`). 끝은 봉으로 확인한다: 효력일 뒤 거래 봉(`n`>0) 0 · 마지막 거래는 효력일 전 거래일(31/35) 또는 당일(4/35 · 장 뒤 효력).
- **한 실체에 사건이 여럿일 수 있다** — 합병 뒤 가치 없음 제거(`FOLD` · `CSGS` · `SLNO`) · 91개 기호가 2건 이상.
- **목록과 원장이 어긋나는 기호가 있다** — `ARQQW` 가치 없음 제거 09-21인데 10-17 목록은 `active`(`tradable=false`).

### 신원 사건 — 등록인 교체의 날짜 박힌 증거 (2026-10-17 실측 · ADR-0180)

`types=name_change,reorganization,stock_merger`로 좁혀 여러 기호를 한 콜에 묻는다(REAX·XRPN·AAPL → 1·1·1건 · HTTP 200).
끝의 증거로 쓰는 모양은 셋이다.

- `name_changes` 중 **CUSIP이 바뀐 것** — `XRPN→XRPN`(`G0R38G104→299946103` · 10-12) · `COLA→SAIQ` 10-02 · `IPEX→GOW` 09-28 ·
  `JFB→XTND` 09-04 · `NHIC→NWCL` 09-22. 날짜는 `process_date`다(효력일 필드가 없다). 같은 증권의 기호만 바뀐 것(`HLX→HOS` · CUSIP
  `42330P107` 그대로)은 등록인이 끝났다는 증거가 아니다.
- `reorganizations` — `symbol`이 대상이다. `REAX` 08-25(`75585H206` → `stock_movements` `776105108` 1:10).
- `stock_mergers` 중 그 기호가 **인수 쪽**(`acquirer_symbol`)인 것 — `REAX` 08-25 인수 `RMAX`. 피인수 쪽은 그 증권이 사라진 것이라 다른 갈래다.
- **기호 그대로의 CUSIP 개명은 `old_symbol` = `new_symbol`이다** — 집합으로 담을 때 같은 원소 둘에 던지는 구현이 있다(`Set.of`).

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

### 비율을 가격이 뒷받침하지 않는 사건이 섞인다 — 전수 실측 (2026-08-31)

**1,000종목 × 7년의 원장 110건을 적용한 뒤 전수로 쟀다.** 효력일 직전·직후 종가의 배수가
이론 배수(`1 / ratio`)를 얼마나 뒷받침하는지 본 값이다.

| 지지도 | 건수 |
|---|---|
| 0.7 ~ 1.35 (가격이 비율을 뒷받침) | **104 / 107** |
| 벗어남 | **3** |

| 심볼 | 소스 비율 | 관측 배수 | 이론 배수 | 지지도 |
|---|---|---|---|---|
| `HON` | 0.500 (병합) | **0.981** | 2.000 | 0.491 |
| `QXO` | 0.125 (병합) | 13.489 | 8.000 | 1.686 |
| `DRS` | 1.451 (분할) | **0.980** | 0.689 | 1.423 |

**`HON`·`DRS`는 가격이 아예 안 움직였다 — 그런데 둘의 이유가 다르다.** `HON` 2026-06-29는 **진짜 병합 1:2와 HONA
분사가 같은 날**이라 ×2와 분사 가치비 ×0.5085가 상쇄된 것이다(재무 주식수 633,653,119 → 316,940,010 · Alpaca
`adjustment=all`도 ×2 × 0.5241을 함께 적용한다 · 2026-10-05 실측). 같은 부류가 더 있다 — `UTX` 2020-04-03 `reverse_splits`
(`new_rate` 0.5)는 병합이 아니라 Carrier·Otis 분사 + Raytheon 합병이다(SEC 8-K 2020-04-08 Item 2.01 ·
`CARR.WI`·`OTIS.WI`가 그날 정식 심볼로 바뀐다 · 종가 86.01 → 49.93). **소스에 사건이 아예 없는 것도 있다** —
`UHAL` 2022-11 Series N 무의결권주 배당은 `UHAL`·`UHAL.B` 어느 심볼로도 레코드가 없고 SEC 8-K 2022-10-24
(items 3.02·8.01)가 준다. 주식수만 바뀌는 사건(합병 신주·분할설립)을 소스가
분할·병합 타입으로 싣는 것으로 보인다. **그 비율을 홀로 계수로 적용하면 우리가 없던 불연속을
만든다** — `DRS`의 2022-11-23 이전이 31% 낮아진다. `HON`은 병합만 적용하면 2배가 되고, 같은 날 분사와 한 행으로
합치면(ADR-0168 · 주식수 배수 0.5 · 가격 배수 0.983) 맞는다.

**IMPORTANT — 원장의 신뢰 규칙이 이것을 가정한다.** `CorporateActionLedger.record`가
*"공시는 `APPROVED`, 휴리스틱은 `OBSERVED`"*이고 그 javadoc이 *"공시가 보증하는 것은 「그날
사건이 있었다」이지 「비율이 이것이다」가 아니다"*라고 적어 두었는데, **그 판단은 타입이
`UNKNOWN`일 때만 적용된다.** 타입과 비율이 함께 온 사건은 가격 대조 없이 `APPROVED`가 된다.
국내는 그 가정이 참이었다(공시 62건 전부 적용, ADR-0090의 premise가 초록). **미국에서는 3건이
아니다.**

**셋을 판정했다(2026-08-31).** `HON`·`DRS`는 **`REJECTED`** — 가격이 그 비율로 안 움직였으므로
계수로 적용할 사건이 아니다. **`HON`의 판정은 틀렸다**(같은 날 분사를 안 봤다 — 위). 그 결과 06-29~07-22의 17봉
동안 시총·PER이 약 2배였다(공시일부터의 기업행위 배수가 1이었다). 합친 `SPIN_OFF`로 다시 판정한다(승인 대기). **`UNSUPPORTED`가 아닌 이유**는 그것이 `findUnadjusted`에 잡혀
**가격에 영향도 없는 사건 때문에 지표를 억제**하기 때문이다(`CorporateActionStatus` javadoc이
두 뜻을 가른다). `QXO`는 **`APPLIED`를 유지**했다 — 주식수가 2024-03-31 `5,315,581`에서
2024-06-30 `409,430,195`로 뛴 리캡이라 `5,315,581 / 8 = 664,448`에 신주가 얹힌 값과 맞고,
관측 13.489배 중 8배가 병합이며 남는 1.686배가 그날의 실제 등락이다.

**MUST: 지지도를 재기 전에 같은 날(±7일) 분사가 있는지 본다** — 있으면 기대 배수는 `1/ratio × v`다. 빼고 재면
진짜 병합을 거절한다(`HON`).

**MUST: 가격만으로는 「효력일이 밀린 것」과 「그날의 실제 등락」을 못 가른다.** 그래서
ADR-0090의 premise는 **`note`가 없는 행만** 묻는다 — 확인한 사실을 `judge --ca-note`로 남기면
빠진다. 그러지 않으면 `QXO` 한 건 때문에 그 펜스가 영구히 붉고, **상시 붉은 펜스는 무시된다.**

### `old_rate`/`new_rate`가 「선언된 주식 교환」과 「가격계수」를 가른다 — 전수 실측 (2026-09-23)

**`forward_splits`·`reverse_splits`는 파생된 비율이 아니라 선언된 두 rate를 준다.** 그 둘이
주식수를 바꾸는 사건과 **분사·역합병의 가격계수**를 가른다 — 후자가 같은 배열에 실려 온다.

**미국 130종목 153사건**에서 `new/old`를 약분한 **작은 쪽**의 분포:

| 작은 쪽 | 건수 | 정체 |
|---|---|---|
| 1 | **123** | N-for-1 · 1-for-N |
| 2 | **24** | 3-for-2 |
| *3 ~ 96* | **0** | **빈 구간** |
| 97 · 189 · 200 · 1000 · 1000 · 1103 | 6 | `QGEN`(합성 자사주) · `DD`(Corteva) · `FTV`(Vontier) · `DELL`(VMware) · `TT`+`IR`(같은 Reverse Morris Trust 양쪽) |

- **MUST: 3-for-2가 두 표기로 온다** — `2→3`(19건)과 `1→1.5`(5건). 「양쪽이 정수인가」로 재면
  정당한 분할 다섯이 죽는다. **정확한 유리수로 약분한 뒤 재야 한다.**
- **MUST NOT: 분사를 `spin_offs` 교차대조로 가르려 하지 않는다.** `DELL` 2021-11-02의 VMware
  분사는 **`spin_offs`에 없고** `forward_splits`에 `old_rate=1000 · new_rate=1973`으로만 온다
  (2026-09-23 탐침. **대조군 `T`→`WBD`는 `spin_offs`에 뜬다** — 탐침이 안 닿은 것이 아니다).
- **MUST NOT: 파생 `ratio`의 유리수 근사로 가르려 하지 않는다** — `IR`의 `0.8824`는 `15/17`이라
  작은 유리수 시험을 통과한다. **선언된 rate라야 갈린다.**
- **가격 지지도로도 안 갈린다** — `DELL`의 지지도는 **0.9662**로 밴드 한가운데다(가격 사건으로는
  정당하고 **주식수 사건만 아니다**). 위 §「비율을 가격이 뒷받침하지 않는 사건」과 **다른 축이다.**

이 판정을 쓰는 곳은 ADR-0162다.

### `spin_offs` — 가격 배수 재료 (2026-10-05 실측 · 쓰는 곳 ADR-0168)

**질의**: 보존 창 편입 1,208종목의 현재 심볼(이름 체인 없음)로 `types=spin_off` · process_date ≥ 2019-01-01을 물었다.

**결과**: 76행이 왔다. 워런트·에스크로를 빼면 **실제 분사 53건**이다.

| 사실 | 실측 |
|---|---|
| `process_date` | 최근 20건 중 18건이 ex일과 같다 — KST 14:00(미국 개장 전)에는 그날 행이 아직 없다 |
| **when-issued 봉은 `SYM.WI`로 온다** | RXO.WI · FDXF.WI · Q.WI · MBGL.WI · RAL.WI(`SYMWI`·`SYM WI`는 빈 응답 · 400) · HONA는 ex 전 거래가 본 심볼에 있다 · ex 전날 자식 가격 44/53 |
| **소스의 `adjustment=spin-off` 계수 = 1 − rate × 자식(ex 전날 when-issued) ÷ 모체(ex 전날)** | DD(Q.WI 95.0) · HON×2 · FDX(FDXF.WI 160.37) · SPGI(MBGL.WI 22.05) · BDX에서 정확히 재현된다 |
| 소스 계수가 어긋나는 사건 | XPO 3.5% · **FTV 0.6697**(자기 rate 1/3과 RAL.WI 52.98로는 0.7534) |
| 소스가 아직 안 붙인 사건 | **CTVA 2026-10-01**(1.0000 · VYLR에 ex 전날 봉 없음) |
| 분배 워런트 | ex 며칠 뒤에야 거래된다 — GME.WS +3거래일 · OPENW·OPENL·OPENZ +4 |
| 자식 봉이 아예 없음 | SNRBY · THNPF(sip·iex · `asof` 유무 모두 `{}` · 대조군 Q는 받았다) |
| 가격과 안 맞는 날짜 | MRP(LEN) — 첫 봉 02-07 대 소스 ex 01-21 |

- **본 심볼의 ex 전날 봉이 다른 증권일 수 있다** — BN → BAM은 모체 자신이고, FNF → FG 2022는 옛 FG, LGND → OABI는 SPAC 시절 봉이다.
- **자기 분배 레코드가 있다** — `source_symbol = new_symbol`(CR 2023-04-04)이고 티커 재사용 겸 재조직이다.

### `cash_dividends`만 따로 잰다 — 배당 축의 착수 판단 (2026-09-01 실측)

탐침은 `AlpacaDividendProbeLiveTest`다. **표본을 실제 조회 대상에서 뽑았다** — `US_TOP1000` 편입을
티커 오름차순으로 20에 하나씩 **50종목**, 창은 `2018-01-01 ~ 오늘`. 파일럿 11종목으로 재면
대형 배당주에 치우쳐 지연 분포가 낙관적으로 나온다.

| 값 | 실측 |
|---|---|
| 사건 | **889건 · 1페이지**(`limit=1000`) |
| 배당이 온 종목 | **28 / 50** — 나머지 22는 창 전체에서 0건이다 |
| 연도별 | 2018~2025가 93~110건으로 고르고 2026이 70건(진행 중) |
| **`process_date − ex_date`** | 최소 **0** · p50 **15** · p90 **28** · p99 **36** · **최대 50일** |
| 음수(효력일보다 먼저 처리) | **0건** |
| 365일 초과 | **0건** — 분사에서 관측된 3년 지연(T→WBD)은 **배당에는 없다** |
| `foreign` | **60건**(6.7%) |
| `special` | **2건** |
| 필드 결손(`ex_date`·`process_date`·`rate`) | **0** |
| 역년 경계 ±3일 | 17건 |

**`foreign`은 발행사가 외국이라는 뜻이고 `rate`는 그대로 USD다.** 플래그가 붙은 셋(`CB`·`FTAI`·
`WTW`, 각 4건 = 분기배당 전량)의 2025년 합계를 우리 종가로 나누면 **1.13% · 0.66% · 0.82%**이고,
플래그가 없는 대조(`PM` 3.01% · `T` 4.29%)와 같은 범위다. **통화가 섞이지 않으므로 합계에 넣는다.**
`special`도 넣는다 — 국내 `DPS_COMMON`이 사업보고서 배당표의 주당 현금배당금이라 특별배당이 그
표에 실리면 함께 들어간다.

### 함정 — 연간 재무 공시일이 배당 처리 지연을 **안 덮는다**

**「그 해에 배당이 없었다」를 말하려면 그 해를 끝까지 봤어야 한다.** 그런데 조회 창은
`process_date` 기준이고 위 실측의 최대 지연이 **50일**이다. 미국 연간 재무의 공시 지연은
기말 대비 **최소 18일**(US_TOP1000 실측 · 중앙 53일)이므로, **공시일에 서 있는 것만으로는
그 회계연도의 배당을 다 봤다고 말할 수 없다.**

→ **완결 판정은 공시일이 아니라 수집 시점으로 한다.** `period_end + 여유일 <= 수집일`일 때만
그 회계연도의 합(0 포함)을 사실로 낸다. 여유일은 위 최대 50일에 마진을 얹어 정한다.
**MUST: 이 수를 다시 잴 때 표본을 함께 적는다** — 50종목·8년이고, 넓히면 꼬리가 길어질 수 있다.

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
