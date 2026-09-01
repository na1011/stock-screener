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
- **MUST: 임계가 옳은지를 통과율로 판정하지 않는다.** 이 문서가 처음 든 근거가 통과율의
  대칭이었고 ADR-0112가 그것을 무너뜨렸다 — **아무도 자르지 않는 값에서는 통과율이 언제나
  비슷하다.** 판정은 **무엇을 자르는가**로 한다(§배경·§검증). 두 시장이 같은 수를 쓰는지는
  그 판정의 결과이지 전제가 아니다.
- **MUST NOT: `close × volume`으로 거래대금을 합성해 되돌리지 않는다.** V1 스키마의 MUST NOT이고,
  재보니 미국에서 **계통 +13.6%** 과대다(아래 §배경). **§검증이 그 값을 쓰는 것은 예외가 아니다** —
  거기서 재는 것은 절대 금액이 아니라 **두 집합의 비**이고, 저장하지 않는다.
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

```premise 유동성 하한이 두 시장에서 얇은 꼬리를 자르는가
psql -d stock_screener -At -c "WITH thr AS ( SELECT max(c.literal_value) v FROM screen_condition c JOIN condition_group g ON g.id = c.group_id JOIN screen s ON s.id = g.screen_id WHERE s.is_active AND s.screen_type = 'PRESET' AND c.is_required AND c.indicator_code = 'TURNOVER_RATIO_60'), d AS (SELECT market_code mc, max(trade_date) t FROM indicator_trade_date GROUP BY 1), mem AS (SELECT DISTINCT s.id, s.market_code mc, mk.currency_code ccy FROM stock s JOIN market mk ON mk.code = s.market_code JOIN universe_membership m ON m.stock_id = s.id AND m.effective_to IS NULL JOIN universe u ON u.code = m.universe_code AND u.is_active), adv AS MATERIALIZED ( SELECT p.stock_id, avg(COALESCE(p.trade_value, p.close * p.volume)) v FROM price_daily p JOIN mem ON mem.id = p.stock_id JOIN d ON d.mc = mem.mc WHERE p.trade_date > d.t - 90 GROUP BY 1), turn AS (SELECT iv.stock_id, iv.value tr FROM indicator_value iv JOIN mem ON mem.id = iv.stock_id JOIN d ON d.mc = mem.mc AND d.t = iv.trade_date WHERE iv.indicator_code = 'TURNOVER_RATIO_60'), g AS (SELECT m.ccy, count(*) FILTER (WHERE t.tr < (SELECT v FROM thr)) cut, percentile_cont(0.5) WITHIN GROUP (ORDER BY adv.v) FILTER (WHERE t.tr < (SELECT v FROM thr)) cut_adv, percentile_cont(0.5) WITHIN GROUP (ORDER BY adv.v) FILTER (WHERE t.tr >= (SELECT v FROM thr)) kept_adv FROM turn t JOIN mem m ON m.id = t.stock_id JOIN adv ON adv.stock_id = t.stock_id GROUP BY 1) SELECT CASE WHEN (SELECT v FROM thr) IS NULL THEN '잴 것이 없었다 — 활성 프리셋에 유동성 필수 게이트가 없다' WHEN (SELECT count(*) FROM g WHERE cut > 0) = 0 THEN '하한이 어느 시장에서도 한 종목도 안 자른다 — 「거래 불가 유입 차단」이 성립하지 않는다' WHEN (SELECT count(*) FROM g WHERE cut > 0 AND cut_adv * 3 >= kept_adv) = 0 THEN 'OK 하한이 두 시장에서 얇은 꼬리를 자른다 — ' || (SELECT string_agg(ccy || ' ' || cut || '종목 중앙값 ' || round((cut_adv / 1e6)::numeric, 1) || ' 대 ' || round((kept_adv / 1e6)::numeric, 1) || ' (백만 ' || ccy || ')', ' · ' ORDER BY ccy) FROM g WHERE cut > 0) ELSE '하한이 얇은 꼬리를 안 자른다: ' || (SELECT string_agg(ccy || ' 잘린 중앙값 ' || round((cut_adv / 1e6)::numeric, 1) || ' 대 남은 중앙값 ' || round((kept_adv / 1e6)::numeric, 1) || ' (백만 ' || ccy || ')', ', ' ORDER BY ccy) FROM g WHERE cut > 0 AND cut_adv * 3 >= kept_adv) || ' — 임계가 거래 가능성 하한이 아니라 선별자가 됐다(ADR-0112·ADR-0066)' END"
```

**재는 것이 「얼마나 자르는가」에서 「무엇을 자르는가」로 바뀌었다.** 앞의 술어는 두 시장의
통과율 차를 봤는데, **아무도 안 자르는 값에서는 그 차가 언제나 작다**(ADR-0112). 그래서 그
전제는 임계가 하한으로 서 있든 무너져 있든 초록이었다 — **실패할 수 없는 펜스**였다.

문헌이 준 목적이 「거래 불가 종목 유입 차단」이므로 판정도 그 목적으로 한다: **잘리는 집합의
일평균 체결금액 중앙값이 남는 집합보다 한 자릿수 낮은가.** 실측 배수가 국내 8.8배 · 미국
37배이고 **경보선은 3배**다 — 좁게 잡으면 구성이 조금만 바뀌어도 울고, 이 전제가 잡으려는 것은
**하한이 선별자로 변한 것**이지 미세한 이동이 아니다.

**모집단을 통화로 묶는다.** 임계 축이 통화라 손잡이가 그 단위에만 있고(ADR-0106 §결정 1),
조회 대상으로 가르면 `KOSDAQ_TOP150`처럼 꼬리가 없는 대상에서 「잘린 것이 0」이 되어
**고칠 수 없는 것을 신고한다.**

**미국은 `close × volume`이라 계통 +12.5% 과대다**(ADR-0112 실측). 그래도 쓰는 이유는 이 술어가
재는 것이 **절대 금액이 아니라 두 집합의 비**이고, 계통 편의는 분자와 분모에 함께 걸리기 때문이다.
**MUST NOT: 이 값을 지표로 저장하지 않는다** — §규칙의 MUST NOT이 그대로 걸린다.

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

**임계가 옳은지는 통과율이 아니라 「무엇을 자르는가」가 답한다.** 처음에는 두 시장의 통과율이
0.15%에서 거의 같다는 것을 근거로 삼았는데, **아무도 안 자르는 값에서는 통과율이 언제나
비슷하므로** 그 논증은 성립하지 않았다(ADR-0112). 잣대를 갈아끼우고 다시 쟀다.

**0.15%가 잘라 내는 집합의 일평균 체결금액**(2026-09-01 · 직전 90거래일 평균 · 미국은
`close × volume`이라 계통 +12.5% 과대이나 순위에는 영향이 작다):

| | 자르는 집합 | | | 남는 집합 |
|---|---|---|---|---|
| | n | 중앙값 | 최댓값 | 중앙값 |
| 국내(활성 편입 349) | 13 | **24.6억원** | 890억원 | 241억원 |
| 미국(`US_TOP1000`) | 14 | **$5.9M** | $111M | $220M |

**두 시장에서 같은 것을 자른다 — 하루에 몇 백만 달러어치가 거래되는 꼬리다**(국내 24.6억원이
약 $1.8M로 같은 자릿수). 문헌이 준 목적이 「거래 불가 종목 유입 차단」이므로, **이 표가 그
목적에 대한 판정이고 통과율은 아니다.**

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
