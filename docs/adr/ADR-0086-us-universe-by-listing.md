---
id: ADR-0086
status: 채택됨
date: 2026-08-29
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/**"
  - "src/main/resources/db/migration/V34__us_listed_universe.sql"
  - "docs/api-spec/alpaca-universe.md"
---

# ADR-0086. 미국 유니버스를 거래소 상장으로 정의하고, 데이터가 붙기 전까지 닫아 둔다

## 결정

미국 유니버스를 **지수 편입이 아니라 NYSE·NASDAQ 상장**으로 정의한다. 시세는 Alpaca, 재무는
SEC EDGAR로 확정한다. 유니버스 코드는 `US_LISTED` 하나이고 `market_code`는 `US` 하나다.
**구성은 지금 쌓되 `is_active = false`로 둔다** — 수집 어댑터가 시세도 재무도 아직 없다.

> spec §1·§3·§7·§9·§10·§11의 전제를 뒤집는 판단이다. **사용자가 2026-08-29에 승인했다.**

## 규칙

- MUST: `status=active`를 명시한다. 기본값이 "모든 상태 포함"이라 빠뜨리면 상장폐지 종목이
  섞인다(실측 +2,163).
- MUST NOT: `tradable`로 거르지 않는다. 데이터 조회 전용 자산이 그 값으로 오고(실측 34건)
  우리는 거래를 하지 않는다. 거르는 것은 `status`다.
- MUST NOT: 적재 단계에서 ETF·REIT·우선주·워런트를 거르지 않는다. **적재는 넓게, 노출은 좁게.**
  응답에 증권 유형 필드가 없고 이름 문자열의 커버리지를 재본 적이 없어, 지금 거르면 관측하지
  못한 표기 위에 판정을 세우는 것이다.
- MUST: 부분·빈 목록을 스냅샷으로 만들지 않는다(ADR-0008). 하한은 **실측의 절반**이고
  측정 일자를 상수 옆에 적는다 — 추정치에서 뽑은 상수는 소스가 반쯤 준 날 울지 않는다.
- MUST NOT: 미국 업종코드를 `stock.industry_code`에 넣지 않는다. 그 CHECK가 3~5자리를
  허용해 **SEC의 SIC 4자리가 그대로 통과하고**, 통과하면 생성 컬럼이 만들어져 금융업
  판정(`industry_division` 64~66)과 업종 백분위가 **KSIC 종목과 SIC 종목을 한 그룹으로 묶는다.**
  아무 에러도 안 나고 값이 그럴듯하다.
- MUST: `universe.code = 'SP500'` 행과 `sp500.csv` 시드, `SeededUniverseDataSource`의 담당
  코드를 유지한다(ADR-0010에서 승계). 비활성으로 내렸을 뿐이며, **지우면
  `universe_membership`의 FK와 테스트 픽스처가 함께 끊긴다.**
- MUST: 시세 데이터를 제3자에게 제공하지 않는다. 아래 §라이선스가 그 조건을 정의한다.

## 검증

```bash
./gradlew test --tests "*Alpaca*"
```
봉투 판별·하한·중복 심볼·인증 헤더가 여기서 걸린다.

```bash
./gradlew liveTest --tests "*Alpaca*"
```
키가 있으면 소스가 아직 그대로인지를 지킨다(실행당 1콜). 없으면 스킵된다.

```bash
grep -rnE 'alpaca|Alpaca|APCA' \
  src/main/java/com/stockscreener/pipeline/{collector,batch,scheduler} \
  | grep -vE ':[0-9]+: *(\*|//|/\*)'
```
아무것도 반환하지 않아야 한다 — 벤더 이름이 상위 계층에 새지 않는다(ADR-0006).

```premise 미국 유니버스가 켜졌으면 수집 어댑터가 함께 있는가
psql -d stock_screener -At -c "
SELECT CASE
  WHEN u.code IS NULL THEN '잴 것이 없었다 — US_LISTED 행이 없다(V34 미적용)'
  WHEN NOT u.is_active THEN 'OK US_LISTED는 비활성이다 (구성 ' || u.members || '종목을 쌓아 두었다)'
  ELSE 'US_LISTED가 켜졌다 — CollectedMarkets에 US를 더했는지, 시세·재무 어댑터가 있는지 확인하라'
END
FROM (SELECT code, is_active,
             (SELECT count(*) FROM universe_membership m
               WHERE m.universe_code = 'US_LISTED' AND m.effective_to IS NULL) AS members
        FROM universe WHERE code = 'US_LISTED') u
RIGHT JOIN (SELECT 1) x ON true"
```

**켜는 것 자체가 결함은 아니다.** 이 전제가 묻는 것은 *"켤 때 함께 해야 할 것을 했는가"*이며,
그 짝은 ADR-0087의 `CollectedMarkets`다.

## 배경

spec §10은 미국을 v1.1로 미루며 근거를 **"무료로 접근 가능한 시세 소스가 확인되지 않았다"**로
적었고, §11을 열어 셋 중 하나를 고르게 했다 — ① 대기 ② 유료 도입 ③ 영구 제외.

그 전제가 두 번 바뀌었다.

**시세**: `docs/external/us-market-api-dev-guide.md`가 Alpaca 무료 계정으로 SIP 과거 데이터와
수정주가를 실측 합격시켰다(2026-08-19). 15분 규칙 아래 `end`가 15분 이상 지나면 구독 없이
SIP 전체를 받는다 — 공식 문서에서도 같은 서술을 확인했다.

**유니버스**: 지수 편입 이력을 무료로 구할 수 없어 리밸런싱 배치의 전제가 성립하지 않았다.
그래서 기준을 거래소 상장으로 바꾼다. 한국(지수 기준)과 달라지는 것은 **의도된 비대칭**이고,
양국의 데이터 가용성이 다르므로 억지로 맞추지 않는다.

**규모는 추정과 달랐다.** 기획 추정 ~5,600에 대해 실측은 **8,504종목**(NYSE 2,920 +
NASDAQ 5,584), 응답 3.88MB다. 그리고 이름 최대 229자가 `stock.name varchar(200)`을 넘겨
V34에서 400으로 넓혔다 — 긴 것은 회사명이 아니라 증권의 조건문이라 자르면 서로 다른 우선주가
같은 이름이 된다.

### 라이선스 — 조건이 붙은 결정이다

Alpaca가 편입한 **NYSE Agreement for Market Data Display Services**(2013-03-04판) 원문:

- §5 PERMITTED USE — *"Subscriber shall not furnish Market Data to any other person or entity."*
- §11 PERMITTED USE (Nonprofessional Subscriber) — *"he or she shall receive Market Data solely
  for his or her personal, non-business use."*

spec §10이 다른 소스를 기각한 사유가 이것이고, Alpaca에도 똑같이 걸린다.

**사용자가 고른 문장(2026-08-29):** *"현재 관리자 계정 외에 등록된 계정도 전부 내 부계정들임.
본인 단독 사용 중이므로 라이센스에 문제 없음. 또한 본 프로젝트는 인가된 계정이 없으면 사이트에
접근이 막히는 폐쇄 사이트이기 때문에 계약위반 사항도 아님."*

**그에 대한 해석 — 갈라 적는다.** 근거로 채택하는 것은 **앞의 하나**다: 허용목록의 계정이 전부
본인 것이면 실질이 본인 단독 사용이고, 그것이 §11의 *personal, non-business use* 안이다.
**뒤의 「폐쇄 사이트라 위반이 아니다」는 근거로 쓰지 않는다** — 지금 지워지는 기획서가 그 논거를
명시적으로 기각해 두었다: *"인증·접근제어는 이 제약을 해소하지 못한다. 허용목록으로 걸러진
지인 3명도 '다른 개인'에 해당한다. 인증은 누구에게 제공되는지를 통제할 뿐 제공 여부를 바꾸지
못한다."*

**둘을 가르는 이유는 판정이 어디로 가는가가 다르기 때문이다.** 「폐쇄 사이트인가」로 가면
타인을 추가해도 아무것도 안 막지만, **「전부 본인 계정인가」로 가면 그 순간 제약이 되살아난다.**
그래서 spec §2에 그 조건을 문장으로 적었다.

**적용 범위는 시세에 한정된다.** SEC EDGAR는 정부 공개 데이터라 무관하다.

## 버린 대안

- **S&P500 편입 기준 유지** — 구성종목을 무료로 얻을 경로가 없다. `/v2/assets`는 지수 편입을
  주지 않고 `sp500.csv`는 21줄 표본이다. 이 경로를 고르면 유니버스 갱신 배치가 성립하지 않는다.
- **`SP500` 코드를 재사용해 거래소 유니버스로 바꾼다** — 화면에 "S&P 500"이라 적히는데 내용은
  NYSE+NASDAQ 전체가 된다. 이미 쌓인 편입 이력이 **어느 정의였는지 사후에 구분할 수 없다.**
- **`market`에 `NYSE`·`NASDAQ`를 추가한다** — `UniverseRebalanceJob.rebalanceOne`이 유니버스
  하나에 시장 코드 하나를 넘기므로 유니버스도 둘로 갈라야 하고, 그러면 **백분위 모집단이
  상장 장소로 갈린다**(ADR-0036). 두 거래소는 통화·시간대가 같아 행을 늘려 얻는 것도 없다.
- **미국을 유니버스에 두고 실패를 감수** — ADR-0010이 §버린 대안에서 기각한 것이고
  **그 근거는 아직 유효하다**: *"종목이 매일 실패로 기록되어 부분 실패 알림이 상시화되고,
  그러면 진짜 장애가 묻힌다."* 규모는 그 ADR이 적은 500이 아니라 8,504다.
  **이번에 그 기각을 넘어선 것은 ADR-0087이 수집 대상을 선언된 시장으로 좁혔기 때문**이고,
  그 장치가 없으면 이 결정도 성립하지 않는다.
- **적재 단계에서 유형을 거른다** — 이름 접미의 커버리지를 재본 적이 없다. 잘못 분류된 ETF는
  사업회사로 스크리닝에 올라오고 어떤 에러도 내지 않는다. 분포만 세어 문서에 남겼다.
- **`cusip`을 EDGAR 매핑의 보조 키로 쓴다** — 실측 8,504건 전부 비어 있다. 개발가이드의
  응답 예시에는 있으나 무료 페이퍼 계정에는 오지 않는다.

## 결과

**쉬워지는 것**: 유니버스가 소스에서 자동 반영된다. 상장·폐지가 목록에 그대로 나타나고
편입 이력 데이터를 따로 살 필요가 없다.

**어려워지는 것**

- **ADR-0016의 `MAX_ROWS = 2000`이 흔들린다.** 그 결정은 §배경이 *"유니버스 350종목"*을 근거로
  삼고 재검토 신호를 *"운영에서 `truncated: true`가 관측되는 날"*로 박아 두었다. 미국을 노출하면
  **그 신호가 상시 발화한다.**
- **티커 리네임과 재사용이 실제 문제가 된다.** `StockRegistrar.resolveOne`은 기존 행을 찾으면
  이름을 갱신하지 않고, `uq_stock_market_ticker`는 풀려난 티커를 새 회사와 같은 행으로 합친다
  (FB→META가 FB를 풀어 준 사례). `delisted_on`을 읽는 코드도 아직 없다.
- **이름이 티커와 같은 종목이 소스에 실재한다** — `RH`·`VTEX` 둘. `StockRegistrar`의 폴백이
  만드는 행과 모양이 같아, 결손 판정을 **수가 아니라 집합**으로 해야 한다.

**되돌리려면**: `datasource/us/`를 지우고 V34를 되돌린다. 다만 `universe_membership`에 쌓인
구성은 **되돌리는 순간 영구히 사라진다** — 지나간 시점의 유니버스는 사후 복원이 불가능하다.
