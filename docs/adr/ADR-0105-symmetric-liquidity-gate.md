---
id: ADR-0105
status: 채택됨
date: 2026-08-31
applies_to:
  - "src/main/java/com/stockscreener/indicator/calculator/TurnoverRatio60Calculator.java"
  - "src/main/java/com/stockscreener/screening/preset/PresetCatalog.java"
---

# ADR-0105. 유동성 게이트를 원화 금액에서 회전율로 갈아끼운다

## 결정

프리셋 셋의 유동성 조건을 `TURNOVER_60 ≥ 100억원/50억원`에서
**`TURNOVER_RATIO_60 ≥ 0.15%`**(60일 평균 회전율)로 갈아끼운다(2026-08-31 사용자 결정).

## 규칙

- **MUST: 두 시장이 같은 식을 쓴다.** 이 조건의 존재 이유가 대칭이다.
- **~~MUST: 두 시장이 같은 임계를 쓴다.~~ 이 절반은 무효다(ADR-0112).** 근거로 든 것이
  §배경의 「0.15%에서 두 시장이 거의 같은 만큼을 자른다」인데, **아무도 자르지 않는 값에서는
  통과율이 언제나 비슷하다** — 실측으로 그 값은 조회 대상 셋 전부에서 증분 탈락이 **0**이다
  (KOSDAQ 10→10 · KOSPI 3→3 · US 51→51). **임계는 미결로 돌아갔고 정하는 것은 실행 시점의
  몫이다.** 이 줄을 지우지 않는 이유는 아래 §배경의 표가 아직 그 논거를 들고 있어서다.
- **MUST NOT: `close × volume`으로 거래대금을 합성해 되돌리지 않는다.** V1 스키마의 MUST NOT이고,
  재보니 미국에서 **계통 +13.6%** 과대다(아래 §배경).
- **MUST NOT: `PriceScaling.adjustedVolume`을 분자로 쓰지 않는다.** 분모(`sharesOf`)와 축척이
  어긋나 분할 이전 구간의 회전율이 사건 배수만큼 커진다.
- **MUST: 크기 축은 `MARKET_CAP`이 든다.** 유동성 조건이 크기를 겸하게 하면 같은 실수가 반복된다.
- **MUST: `TURNOVER_60`을 지우지 않는다.** 국내는 소스가 실측 거래대금을 주므로 그 지표가
  여전히 옳고, 화면·조건 빌더에서 쓸 수 있다. 프리셋이 안 쓸 뿐이다(ADR-0077).

## 검증

```bash
./gradlew test
```

`check-doc-staleness.sh` §5d의 사전등록 표가 `TURNOVER_RATIO_60 ≥ 0.15%`를 **코드 3곳**으로
들고 있어, 임계나 사용처가 갈리면 세션 시작에 잡힌다.

```premise 유동성 게이트가 두 시장에서 같은 만큼을 자르는가
psql -d stock_screener -At -c "SELECT CASE
  WHEN count(*) FILTER (WHERE mkt = 'US') = 0 OR count(*) FILTER (WHERE mkt = 'KR') = 0
    THEN '잴 것이 없었다 — 한쪽 시장의 회전율 표본이 비었다'
  WHEN abs(max(pass) FILTER (WHERE mkt = 'KR') - max(pass) FILTER (WHERE mkt = 'US')) <= 15
    THEN 'OK 국내 ' || round(max(pass) FILTER (WHERE mkt = 'KR'), 1)
         || '% · 미국 ' || round(max(pass) FILTER (WHERE mkt = 'US'), 1) || '%'
  ELSE '통과율이 갈렸다 — 국내 ' || round(max(pass) FILTER (WHERE mkt = 'KR'), 1)
       || '% 대 미국 ' || round(max(pass) FILTER (WHERE mkt = 'US'), 1)
       || '%. 같은 임계가 두 시장에서 다른 것을 자른다'
  END
FROM (
  SELECT mkt, 100.0 * count(*) FILTER (WHERE turn >= 0.15) / count(*) AS pass
  FROM (
    SELECT u.mkt, p.stock_id,
           avg(100.0 * p.volume / NULLIF(COALESCE(p.listed_shares, sh.shares), 0)) AS turn
    FROM (SELECT DISTINCT m.stock_id, CASE WHEN un.market_code = 'US' THEN 'US' ELSE 'KR' END AS mkt
          FROM universe_membership m JOIN universe un ON un.code = m.universe_code
          WHERE m.effective_to IS NULL AND (un.is_active OR un.code = 'US_TOP1000')) u
    JOIN price_daily p ON p.stock_id = u.stock_id
    JOIN stock s ON s.id = p.stock_id
    LEFT JOIN (SELECT stock_id,
                 (array_agg(shares_outstanding ORDER BY published_at DESC)
                  FILTER (WHERE shares_outstanding > 0))[1] AS shares
               FROM financial_statement WHERE is_latest AND fiscal_period <> 'FY'
               GROUP BY stock_id) sh ON sh.stock_id = u.stock_id
    WHERE p.trade_date >= (SELECT max(trade_date) - 120 FROM price_daily)
    GROUP BY 1, 2) r
  GROUP BY mkt) g"
```

**모집단에서 뺀 근거**: 활성 유니버스와 `US_TOP1000`만 본다 — `US_LISTED`는 노출 밖이라
게이트가 안 걸린다. **그 목록이 자라는 자리는 `universe` 표의 `is_active`**다.
**허용 폭 15%p**는 지금 격차(0.3%p)보다 훨씬 넓게 잡았다 — 좁게 잡으면 시장 구성이 조금만
바뀌어도 매 세션 울리고, 이 전제가 잡으려는 것은 **다시 갈라지는 것**이지 미세한 차이가 아니다.

## 배경

**같은 임계가 두 시장에서 전혀 다른 것을 잘랐다**(2026-08-31 실측):

| 임계 | 국내 통과율 | 미국 통과율 |
|---|---|---|
| `TURNOVER_60 ≥ 100억원`(버핏·저변동) | **67.7%** — 진짜 게이트 | **98.9%** — 게이트가 아니다 |
| `TURNOVER_60 ≥ 50억원`(린치 고성장) | 85.1% | 99.3% |

게다가 **미국은 그 지표가 원리적으로 0행**이었다 — 소스가 `trade_value`를 안 준다. 그래서
버핏·린치 고성장이 미국에서 **영구 0건**이었고, 그것을 「소스가 없으니 어쩔 수 없다」로 읽은
것이 오판이다. 두 소스가 **다 주는 것**으로 낼 수 있는 축이 있었다.

**`close × volume` 합성은 되돌리지 않는다.** V1 스키마의 MUST NOT이기도 하지만, 재보니
미국에서 **계통 오차**다 — 정규장 분봉 합과 일봉을 견줘 장외 비중이 5거래일 중앙값
**13.62%**이고 45 종목일이 전부 양수이며, 일별 sd 9.47%p가 종목별 평균에서 6.87%p로
**1.38배**밖에 안 줄었다(순수 랜덤이면 2.24배). 국내 대조군은 6.8배 줄어 정반대다.

**임계의 출처는 문헌이다.** 버핏·린치 방법론 문서가 둘 다
*「유동성 | Market data → Average Volume (30D) | **하한 설정** | 거래 불가 종목 유입 차단」*
으로 적는다 — **조건과 목적은 문헌이 주고 수는 명시적으로 구현에 맡긴다.**
ADR-0061 §규칙도 *"절대 임계값은 국내 분포를 재고 정한다"*를 MUST로 둔다.

| 임계 | 국내 통과율 | 미국 통과율 |
|---|---|---|
| 0.10% | 98.9% | 97.1% |
| **0.15%** | **96.3%** | **96.6%** |
| 0.20% | 91.4% | 96.4% |
| 0.30% | 81.7% | 95.6% |

**0.15%에서 두 시장이 거의 같은 만큼을 자른다.** 이것을 「같은 것을 재고 있다」는 증거로 읽은
것이 **오판이다**(ADR-0112) — 그 값은 어느 시장에서도 종목을 자르지 않으므로 통과율이 비슷한
것이 당연하고, 그래서 이 표는 축의 대칭에 대해 아무 말도 하지 않는다.

## 버린 대안

- **금액 게이트를 유지하고 시장별 임계를 둔다**(국내 100억 · 미국 $30M) — 「실제로 거래
  가능한 규모」라는 뜻은 대칭이지만 **미국 거래대금을 재려면 `close × volume`이 필요**하고
  그것이 위 기각에 걸린다. 그리고 $30M은 2021~2024에 통과율 78~87%라 **그때는 게이트였고
  지금은 아니라서** 시점에 따라 뜻이 달라진다.
- **0.30%로 둔다** — 국내에서 81.7%라 ADR-0061의 90% 규칙을 만족하지만 미국은 95.6%다.
  **다시 비대칭이 된다.**
- **회전율을 필수가 아닌 배점으로 낮춘다** — ADR-0061 §규칙의 「통과율 90% 초과는 필수
  게이트에 두지 않는다」를 형식적으로 지키지만, 그 규칙의 사유는
  *"게이트 개수는 안전장치의 개수로 읽힌다"*이고 예로 든 것은 **품질 조건**(발생액)이다.
  **~~유동성 하한은 96%에서도 자기가 말하는 일을 한다~~ — 재보니 거짓이다**(ADR-0112).
  leave-one-out으로 재면 세 조회 대상 전부에서 **증분 탈락이 0**이다. 그때는 그 수단이
  없었다.

## 결과

**쉬워지는 것**: 유동성 조건이 두 시장에서 같은 뜻으로 걸린다. 미국에서 버핏·린치 고성장이
이 조건 때문에 0건이던 상태가 풀린다. `LOWVOL_QUALITY`의 배점 1점도 미국이 얻을 수 있다.

**어려워지는 것**

- **국내 기준으로는 푸는 쪽이다.** 국내 탈락이 **32.3% → 3.7%**로 줄었다.
  `phase-criteria.md`의 사전등록 표에 그 방향을 적었다. 그 32%는 「거래 불가」가 아니라
  **대형주 선호**였고 그 축은 `MARKET_CAP`이 이미 든다 — 그래서 중복이 빠진 것이지만,
  **결과적으로 통과 종목이 는다.**
- **ADR-0061의 90% 규칙과 형식적으로 어긋난다.** 위 §버린 대안에 사유를 적었다.
- **회전율은 규모에 중립이라** 대형주와 소형주를 같은 잣대로 본다. 「10억 주문을 슬리피지
  없이 체결할 수 있는가」는 이 지표가 답하지 않는다 — 그 물음이 필요해지면 별도 축이다.
