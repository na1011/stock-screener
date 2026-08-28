---
id: ADR-0087
status: 채택됨
date: 2026-08-29
applies_to:
  - "src/main/java/com/stockscreener/pipeline/collector/StockRegistrar.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/CollectedMarkets.java"
  - "src/main/java/com/stockscreener/screening/query/ScreeningRepository.java"
---

# ADR-0087. 「전 종목」과 「전체」는 수집하기로 한 시장의 활성 유니버스 편입 종목이다

## 결정

배치의 대상 선정과 스크리닝의 유니버스 미지정 경로를 **두 겹**으로 좁힌다.

1. **활성 유니버스 편입** — `universe.is_active`를 조인한다.
2. **수집하기로 한 시장** — `CollectedMarkets.ALL`(지금 `KOSPI`·`KOSDAQ`)로 거른다.

유니버스에 **편입하는 것**과 데이터를 **모으는 것**을 가른다. 미국은 편입까지만 열려 있다.

## 규칙

- MUST: "편입 중인 활성 종목"의 정의를 `StockRegistrar` **한 곳**에 둔다. 잡마다 조건을 더하면
  뜻이 갈리는데 **어느 쪽도 에러를 내지 않는다.**
- MUST: 새 시장의 수집을 시작할 때 `CollectedMarkets.ALL`에 코드를 더한다. 그 목록이
  **자라는 유일한 자리**다.
- MUST NOT: `CollectedMarkets`를 등록된 어댑터에서 뽑지 않는다. 키가 없는 테스트 환경에서
  집합이 비어 **모든 잡의 대상이 0건**이 되고, 그것은 정상과 구분되지 않는다
  (`MarketIndexCodes`가 같은 이유로 같은 형태다).
- MUST NOT: 유니버스 편입을 `CollectedMarkets`로 막지 않는다. 지나간 시점의 구성은 사후
  복원이 불가능하므로, 데이터를 모으기 전에도 구성은 쌓는다.
- MUST NOT: 스크리닝의 `universeCode == null`을 `stock` 전량으로 되돌리지 않는다.

## 검증

```bash
grep -n "JOIN universe u ON u.code = m.universe_code AND u.is_active" \
  src/main/java/com/stockscreener/pipeline/collector/StockRegistrar.java
```
질의 셋 모두에 나와야 한다(3건).

```bash
./gradlew test --tests "*StockRegistrarLookupTest*" --tests "*ScreeningRepositoryTest*" \
  --tests "*ScreeningApiContractTest*"
```
「비활성 유니버스에만 편입된 종목은 대상이 아니다」와 「수집하기로 하지 않은 시장은 활성
유니버스에 있어도 대상이 아니다」가 이 결정의 실질이다.

```premise 수집 범위 밖 시장이 배치 대상에 없다
psql -d stock_screener -At -c "
SELECT CASE
  WHEN total = 0 THEN '잴 것이 없었다 — 활성 유니버스에 편입된 활성 종목이 하나도 없다'
  WHEN outside = 0 THEN 'OK 대상 ' || total || '종목이 모두 수집 범위 안이다'
  ELSE '수집 범위 밖 종목 ' || outside || '개가 배치 대상에 있다 — 어댑터가 없으면 종목마다 실패한다'
END
FROM (
  SELECT count(*) AS total,
         count(*) FILTER (WHERE s.market_code NOT IN ('KOSPI','KOSDAQ')) AS outside
  FROM stock s
  WHERE s.is_active
    AND EXISTS (SELECT 1 FROM universe_membership m
                JOIN universe u ON u.code = m.universe_code
                WHERE m.stock_id = s.id AND m.effective_to IS NULL AND u.is_active)
) t"
```

**시장 목록을 질의에 박은 것은 사본이다.** 자라는 자리는 `CollectedMarkets.ALL`이고, 거기에
코드를 더하면 이 질의도 함께 고쳐야 한다 — 안 고치면 **의도한 동작에 우는 펜스**가 된다.

## 배경

미국 어댑터를 붙이기 전에는 두 곳 모두 무증상이었다. 운영의 모든 `stock` 행이 활성 유니버스에
편입돼 있고 시장이 둘뿐이라 어느 조건도 아무것도 걸러내지 않았다(착수 전 실측 0건).

미국 유니버스를 채우면서 둘이 동시에 드러났다.

**배치 대상**: `activeUniverseMemberIds()`가 `universe`를 조인하지 않아, 편입 행이 생기면
유니버스를 다시 내려도 종목이 영구히 남았다. 이 질의를 쓰는 파일이 10개다. 그리고
활성 유니버스 조인을 더해도 부족했다 — **적재하려면 그 유니버스를 켜야 하고, 켜는 순간
편입 종목 8,504개가 조인을 통과한다.** 그 상태는 ADR-0010이 §버린 대안에서 기각한
*"미국을 유니버스에 두고 실패를 감수"*이고, `CorporateActionJob`은 루프 앞에서 시장별 소스를
해석하므로 **국내 수집까지 통째로 멈춘다.**

**스크리닝**: `selectStockIds`가 `universeCode == null`이면 활성 종목 전량을 골랐다. 프리셋은
대부분 `universeCode`가 null이고 SCORING은 좁히지 않으므로(ADR-0003), 지표가 하나도 없는
시장의 종목이 0점·전 조건 판정불가로 응답에 실린다. 서버가 전량을 내려보내므로(ADR-0016)
그대로 화면까지 간다.

`PercentileCalculator`와 `StockReader.selectableUniverses`는 처음부터 활성 유니버스를 조인하고
있었다. **없던 쪽이 비대칭이었고, 모집단이 갈린 채 값은 그럴듯했다.**

## 버린 대안

- **등록된 어댑터가 있는 시장으로 거른다** — 테스트에는 키가 없어 국내 어댑터가 등록되지
  않으므로 집합이 비고, 가짜 소스를 안 만든 테스트가 전부 대상 0건이 된다. 게다가 재무·거버넌스
  잡의 대상이 *시세* 소스 유무로 정해지는 것은 축이 어긋난다.
- **잡마다 자기 포트로 좁힌다** — "전 종목"의 정의가 10곳으로 갈린다. 그 javadoc이 이미
  *"정의가 잡마다 갈리면 어느 쪽도 에러를 내지 않는다"*로 막아 둔 것이다.
- **유니버스를 켜지 않고 두는 것만으로 충분하다** — 켜지 않으면 `UniverseRebalanceJob`이
  대상에서 빼므로 **아예 채워지지 않는다.** 구성을 쌓으려면 켜야 하고, 그래서 이 결정이 필요하다.
- **미국 종목을 `stock`에 넣지 않는다** — 유니버스 시점 스냅샷을 포기하는 것이다. 지나간 시점의
  구성은 사후 복원이 불가능하고, 그것을 잃으면 생존편향을 영영 제거할 수 없다.

## 결과

**쉬워지는 것**: 시장을 하나씩 열 수 있다. 유니버스를 먼저 쌓고, 시세가 붙으면 그때
`CollectedMarkets`에 코드를 더한다. 그 사이의 구성 이력이 남는다.

**어려워지는 것**: "전 종목"의 뜻이 두 조건에 걸려 있어, 대상이 비면 **어느 쪽 때문인지**를
따로 봐야 한다. 그래서 위 premise가 그 둘을 갈라 신고한다.

**되돌리려면**: `StockRegistrar`의 질의 셋에서 두 조건을 빼고 `ScreeningRepository`의
`inAnyActiveUniverse`를 지운다. 그러면 미국 종목이 **일일 배치 실패와 프리셋 결과에 동시에**
등장한다.
