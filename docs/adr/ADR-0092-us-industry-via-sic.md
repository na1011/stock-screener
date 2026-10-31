---
id: ADR-0092
status: 채택됨
date: 2026-08-29
applies_to:
  - "src/main/resources/db/migration/V36__stock_sic_and_industry_keys.sql"
  - "src/main/java/com/stockscreener/pipeline/datasource/IndustryScheme.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/CompanyProfile.java"
  - "src/main/java/com/stockscreener/pipeline/collector/CompanyProfileCollector.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarCompanyProfileAdapter.java"
  - "src/main/java/com/stockscreener/indicator/SectorRelativeCalculator.java"
  - "src/main/java/com/stockscreener/indicator/calculator/AltmanZCalculator.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorUnavailabilityConfig.java"
  - "docs/api-spec/sec-edgar-company-profile.md"
---

# ADR-0092. 미국 업종을 SIC로 받되 체계를 시장에 묶고, 업종 파티션 키에 체계를 굽는다

## 결정

미국 업종을 SEC EDGAR `submissions`의 `sic`으로 받는다. 결정은 넷이다.

1. **`stock.sic_code`를 따로 만든다.** `industry_code`는 KSIC 전용으로 남는다(ADR-0086 승계).
2. **체계를 시장에 묶는다.** `ck_stock_sic_is_us`·`ck_stock_ksic_is_not_us`가 강제한다.
3. **업종 파티션 키에 체계 접두를 굽는다** — `industry_group_key`·`industry_division_key`.
   그래서 **업종 상대 지표의 모집단이 「활성 유니버스 전체 ∩ 같은 *체계의* 같은 업종」**이 된다.
4. **CIK에 붙는 전량을 적재한다**(사용자 결정 2026-08-29). 실적재 **6,266종목 · 31분**.

**ADR-0036 §결정과 ADR-0044 §결정을 좁히는 판단이다** — 그 둘이 업종 모집단을
*"지수를 가리지 않고 업종으로 묶는다"*로 정했는데, 이제 **분류 체계는 가린다.**
선택이 아니라 강제다: KSIC와 SIC 사이에 공표된 매핑이 없다.

## 규칙

- MUST: 업종 판정과 파티션을 `industry_group_key`·`industry_division_key`로 한다.
  `industry_code`·`industry_group`·`industry_division`은 **KSIC 전용 원문**이다.
- MUST: 금융업 판정 집합의 원소에 **체계 접두를 붙인다.** 숫자만 비교하면
  **SIC `65`(부동산)가 KSIC `65`(보험)로 읽힌다** — 그리고 **SIC에는 `66`이 없다**(실측).
  숫자만 옮겼다면 부동산을 자르고 은행(`60`)·보험(`63`)은 그대로 뒀을 것이다.
- MUST NOT: 파티션 키의 **이름**에 `sector`를 쓰지 않는다. ADR-0044 §검증이
  `SectorRelativeCalculator`에 `rg`를 걸고 *"`sector`가 나오면 표시 라벨이 판정에 샜다"*로
  판정하므로, 그 낱말을 쓰면 **정상 상태가 위반으로 신고된다.**
- MUST NOT: 체계를 **어느 컬럼에 썼는가로 유도**하지 않는다. 컬렉터의 UPDATE가 종목당 컬럼
  하나만 쓰고 미국 행의 `industry_code`는 NULL이라, 「한 종목에 한 체계」 제약은 **아무것도
  막지 못한다.** 자릿수로도 못 가른다 — **4자리 KSIC가 54종목 실재한다.**
- MUST NOT: SIC에 zero-padding을 하지 않는다. **소스가 이미 채워서 준다**(실측: `0100`·`0200`).
  넣으면 소스가 형식을 바꾼 날 그것을 덮어 `ck_stock_sic_code`가 잡을 것을 못 잡는다.
- MUST: CIK에 안 붙는 티커를 **예외가 아니라 `Optional.empty()`**로 접는다. 실측 **1,815종목**이
  그 상태이고, 예외로 올리면 매 실행 그만큼이 실패로 쌓여 진짜 장애를 덮는다.
  **`FinancialDataSource` 쪽이 같은 상황을 `NOT_FOUND`로 올리는 것과 다르고 그것이 의도다** —
  저쪽은 「재무를 못 내는 종목」이라는 기록 자체가 값이지만, 이 포트는 빈 결과를 「소스가 이
  종목을 모른다」로 정의한다.
- MUST: 「업종을 아는가」를 **키의 NULL 여부**로 묻는다. `industry_code IS NOT NULL`로 물으면
  SIC를 채운 종목이 통째로 빠진다.
- MUST: 미국 대상은 `--universe=US_LISTED`로만 닿는다(비활성 유니버스). **0건이면 예외로
  끝낸다** — 업종은 재실행 0행이 정상이라 조용한 0건과 구별되지 않는다(ADR-0088 승계).

## 검증

```bash
./gradlew test --tests "*SecEdgar*" --tests "*CompanyProfile*" --tests "*SectorRelative*" \
               --tests "*Swing*" --tests "*ReadApi*" --tests "*BootstrapArgumentGuard*"
```
체계 분기 · 두 CHECK · 혼재 표본에서 파티션이 갈리는지 · AltmanZ 정의역 · `--universe` 허용이
여기서 걸린다.

```bash
./gradlew liveTest --tests "*SecEdgarSubmissionsProbe*"
```
소스가 아직 4자리 SIC를 앞자리 0까지 채워 주는지를 지킨다.

```bash
rg -n 'Set\.of\(' -A8 \
  src/main/java/com/stockscreener/indicator/calculator/AltmanZCalculator.java \
  src/main/java/com/stockscreener/indicator/IndicatorUnavailabilityConfig.java \
  | rg '"[0-9]+"'
```
**아무것도 반환하지 않아야 한다.** 접두 없는 숫자 리터럴이 그 두 집합에 남아 있으면 그 원소는
어느 체계인지 알 수 없고, 그 자리가 두 체계를 섞는다. **낱말이 아니라 리터럴의 모양으로 묻는
이유**는 집합 이름을 grep하면 선언 줄과 메서드 본문이 함께 나와 **정상 상태에서도 출력이
생기기 때문**이다 — 그런 펜스는 침묵보다 나쁘다.

```premise 업종 체계가 시장에 묶여 있다
psql -d stock_screener -At -c "
SELECT CASE
  WHEN (SELECT count(*) FROM stock WHERE coalesce(industry_code, sic_code) IS NOT NULL) = 0
    THEN '잴 것이 없었다 — 업종이 하나도 적재되지 않았다'
  WHEN (SELECT count(*) FROM stock
         WHERE (market_code = 'US') <> (sic_code IS NOT NULL)
           AND coalesce(industry_code, sic_code) IS NOT NULL) > 0
    THEN '시장과 업종 체계가 어긋난 종목이 '
         || (SELECT count(*) FROM stock WHERE (market_code = 'US') <> (sic_code IS NOT NULL)
              AND coalesce(industry_code, sic_code) IS NOT NULL)
         || '건 있다 — 어댑터가 체계를 잘못 선언했고, 그 종목은 다른 나라 업종과 한 모집단이 된다'
  ELSE 'OK 국내 ' || (SELECT count(*) FROM stock WHERE industry_code IS NOT NULL)
       || '종목이 KSIC, 미국 ' || (SELECT count(*) FROM stock WHERE sic_code IS NOT NULL)
       || '종목이 SIC다' END"
```

**항등식이 아니다.** 파티션 키에 접두가 리터럴로 박혀 있어 *"한 키에 두 체계가 섞였는가"*는
언제나 거짓인데, 이 질의는 **어댑터가 잘못 쓰면 실제로 붉어진다.** 두 CHECK가 그 쓰기를
막지만, 제약이 지워지거나 원문 UPDATE가 우회하면 여기서 드러난다.

## 배경

미국 축은 유니버스·시세·재무·기업행위·마켓 캘린더까지 열려 있었고 업종이 남았다. 원자료는
`submissions`에 있었지만 **막고 있던 것은 소스가 아니라 저장 자리**였다 — `ck_stock_industry_code`가
`^[0-9]{3,5}$`라 SIC 4자리가 그대로 통과하고, 통과하면 생성 컬럼이 만들어져 두 체계가 한
파티션에 들어간다. ADR-0086·0089와 마이그레이션 둘이 그 금지를 사본으로 못 박아 두었다.

**착수 전 탐침이 잰 것 중 계획을 실제로 바꾼 것이 하나다.** 첫 표본 23종목의 최소 SIC가
`2080`이라 **앞자리가 0인 코드가 하나도 없었다.** 그 표본으로 「항상 4자리」라 적으면 안 덮은
자리를 덮었다고 말하는 것이 되므로 농업 발행사 넷을 넣어 다시 쟀고, `0100`·`0200`이 그대로 왔다.
**소스가 채워서 준다** — 그래서 어댑터의 zero-padding이 빠졌고 CHECK가 4자리로 고정됐다.

**그 측정이 접두의 필요성도 실증했다.** SIC에 `01`·`02`가 실재하고 KSIC 소분류에도 `011`이
있어 **앞 2자리가 둘 다 `01`**이다. 가정이 아니라 겹치는 값이다.

### 실측 — 6,689콜이 답한 것 (2026-08-29)

| 무엇 | 값 |
|---|---|
| 적재 | **6,266 / 8,504종목 · 31분 · 실패 0** |
| 못 받은 2,238의 사정 | **CIK 매핑에 없음 1,815 · `sic`이 빈 값으로 옴 423** |
| 자릿수 | **4자리 6,266 / 6,266.** 다른 자릿수 0 |
| 결산월 | 6,070 (`fiscalYearEnd` 파싱 실패 0) |
| 그룹 수 | 소분류 **257**(표본 ≥5는 172) · 중분류 **71**(표본 ≥5는 63) |
| **표본 충족** | 소분류로 **6,073** · 중분류 폴백 **179** · **판정불가 14** |
| 업종 자체가 없음 | **2,238**(26.3%) |

**국내 대조군은 350 중 43(12%)이 판정불가**였고 커버리지는 350/350이었다. 미국은 반대다 —
**표본은 넉넉한데(판정불가 14) 커버리지가 얇다(2,238 결손).** 결손의 대부분은 ETF·펀드·우선주로
**업종을 원리적으로 못 받는 자산**이다. **「전량 적재」를 「전 종목 커버」로 읽으면 안 된다.**

### 금융업 후보에 무엇이 들었는가

| 중분류 | 종목 | SIC |
|---|---|---|
| `SIC:60` | 382 | 6021·6022·6029·6035·6036·6099 (은행) |
| `SIC:61` | 210 | 6111~6199 (비예금 신용) |
| `SIC:62` | 140 | 6200·6211·6221·6282 (증권·투자자문) |
| `SIC:63` | 133 | 6311~6399 (보험사) |
| `SIC:64` | 33 | 6411 (보험 대리·중개) |
| **`SIC:65`** | **75** | **6500~6552 — 부동산이다** |
| **`SIC:67`** | **965** | 6770(SPAC)·6792·6794·6795(로열티·특허)·6798(REIT)·6799 |

**`SIC:66`은 한 종목도 없다** — SIC에 그 번호가 배정되어 있지 않다.

**`SIC:67`을 종목명까지 열어 보면 대부분이 제외가 옳은 자리다**(P25 ③이 요구하는 대조):
**6770이 722종목으로 전부 SPAC**(`… Acquisition Corp.`와 그 유닛·워런트)이고 영업이 없으며,
**6798이 214종목으로 REIT**라 유동/비유동을 원리적으로 안 가른다. 나머지는
6794(12·특허, Acacia Research) · 6792(10·로열티신탁, LandBridge) · 6795(6·Royal Gold·Mesabi Trust) ·
6799(1)로 **합쳐 29종목**이다 — **함께 잘리는 것 중 사업회사일 수 있는 자리는 그 29다.**
965가 아니라 29이며, 그것이 이 넓은 집합의 실제 대가다.

## 버린 대안

- **`industry_code`에 SIC를 넣고 `industry_scheme` 컬럼을 더한다** — ADR-0086이 이름으로 기각한
  방향이고, 생성 컬럼 둘의 뜻이 행마다 달라져 ADR-0039의 「뜻이 다른 값을 같은 이름으로
  부르지 않는다」에 걸린다.
- **「한 종목에 한 체계」 CHECK만 건다** — **아무것도 막지 못한다.** 컬렉터가 컬럼 하나만 쓰므로
  두 컬럼이 동시에 차는 일이 없고, 자릿수로도 못 가른다(4자리 KSIC 54종목).
  이 안을 처음에 방어선이라 적었다가 반증 라운드가 잡았다.
- **`market_code`로 파티션을 가른다** — 더 싸 보이고 `StockAttributes`가 이미 `market_code`를
  읽는다. 기각 근거는 **`market_code`가 `KOSPI`·`KOSDAQ` 둘로 갈려 있다**는 것이다 —
  그러면 국내 업종 모집단이 지수별로 쪼개져 ADR-0036 §결정을 정면으로 깬다.
- **벌크로 받는다** — 실측 `submissions.zip` **1.56GB**, DERA 분기 zip **0.06~0.09GB**로 종목당
  1콜(**2.6GB**)보다 작다. 그럼에도 안 쓴 이유는 크기가 아니다: zip·TSV 파서가 새로 필요하고,
  DERA는 **그 분기에 공시한 발행사만** 덮어 커버리지가 「우리 유니버스」와 다른 축으로 갈린다.
  31분이 그 비용보다 싸다. **크기가 뒤집히면 다시 볼 자리다.**
- **Alpaca에서 받는다** — 실측으로 없다(`cusip` 전건 공백, `class`는 `us_equity` 단일).
- **`sicDescription`을 `stock.sector`에 채운다** — 안 채운다. 그 컬럼은 쓰기 경로가 없어 양 시장
  전량 NULL이고, **한 시장만 채우면 「왜 국내는 비었나」가 이 phase가 답할 것이 아닌 새 질문**이
  된다. 관측한 값은 api-spec에 남겼다.
## 결과

**Altman 제외 집합을 KR과 같은 방법으로 좁혔다(2026-10-31).** 버린 대안이던 「좁힌다」의 기각 근거(미국 `indicator_value` 0행)가
무너져 다시 열었다 — 현 편입 최신 FY에서 유동자산을 낸 종목을 중분류마다 열었다: `60` 43 중 0 · `61` 9 · `62` 8 · `63` 7 · `64` 5는
값이 나는 쪽이 금융 중개(증거금·수탁 자금·보험부채)라 남기고, `65` 4(부동산 서비스)·`67` 6(특허·로열티·토지)은 전부 사업회사라
걷었다. REIT·부동산 보유 임대는 유동/비유동을 안 갈라 걷어도 값이 안 난다. 판정불가 사유 집합(`IndicatorUnavailabilityConfig`)은
「재료가 오지 않는 업종」이라 그대로 둔다(KR 64와 같은 꼴).


**쉬워지는 것**: 미국 업종이 종목당 1콜로 들어오고, 두 체계가 **원리적으로** 한 모집단이 될 수
없다. 어댑터는 포트 구현 하나이고 **레지스트리·컬렉터 배선을 한 줄도 안 고쳤다.**
ADR-0086의 premise에서 비어 있는 축이 **거버넌스 하나로 줄었다.**

**어려워지는 것**

- **미국 업종 값은 아직 한 행도 안 나온다.** 모집단이 활성 유니버스인데 `US_LISTED`가 비활성이다.
  이 결정이 여는 것은 **켜질 때 섞이지 않는 것**이지 값이 아니다.
- **금융업 제외가 미국 6,266 중 1,938(31%)을 덮는다.** 절반이 `SIC:67`인데 그 안을 열어 보면
  **936이 SPAC·REIT**라 제외가 옳고, **값을 낼 수 있었을 자리는 29종목**이다(§실측).
  **그 29가 실제로 값을 냈을지는 미국 지표가 0행이라 재지 못했다.**
- **커버리지가 얇다.** 2,238종목이 업종을 원리적으로 못 받는다.
- **`company-profile`의 대상이 시장을 넘었다.** `US_LISTED`를 켜는 날 그 잡의 기본 대상이
  **2,238종목**(실제 콜 **423**)이 되고, `AdminJobLauncher`의 「전량이어도 350콜」이 그때
  거짓이 된다. **6,689이 아닌 이유는 이 결정이 이미 6,266에 `sic_code`를 채웠기 때문**이고,
  남은 2,238 중 1,815는 CIK에 안 붙어 **영원히 결손으로 남아 매 실행 다시 대상이 된다**
  (2026-08-30 실측).
- **사본이 하나 더 생겼다** — 「NYSE·NASDAQ」처럼, 금융업 집합이 계산기와 판정불가 설정 둘에 있다.
  KR에서 일부러 다르게 둔 두 집합이 **미국에서는 같아졌고**, 갈리는 날 둘 다 고쳐야 한다.

**되돌리려면**: `SecEdgarCompanyProfileAdapter`와 `IndustryScheme`을 지우고 V36을 되돌린다.
`industry_*_key`로 옮긴 자리는 `industry_group`·`industry_division`으로 되돌리면 그대로
동작한다 — **국내 값이 안 바뀌는 것은 실측했다**(두 키가 국내에서 같은 분할을 만들고,
같은 날짜 재백필이 0행으로 수렴했다). 적재된 `sic_code`는 시장으로 지운다.
