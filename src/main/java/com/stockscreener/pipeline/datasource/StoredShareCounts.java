package com.stockscreener.pipeline.datasource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * <b>우리가 이미 저장한</b> 재무에서 뗀 종목별 주식수. 외부 소스가 아니다.
 *
 * <p>IMPORTANT — 이 패키지의 대부분은 <b>벤더</b> 포트지만 이것은 {@link StoredPriceSeries}와
 * 같은 성질이다. ADR-0006의 규칙들(소스가 계산해 준 값을 받지 않는다, 벤더 예외를 감싼다 …)은
 * 여기 걸리지 <b>않는다.</b> 그래도 여기 두는 이유는 {@link UniverseDataSource} 구현이 이것을
 * 필요로 하고, {@link SecurityRef}의 javadoc이 <i>"어댑터가 JPA 영속성 컨텍스트에 닿을 이유가
 * 없고 어댑터 테스트가 DB 없이 성립해야 한다"</i>고 못박고 있기 때문이다. 어댑터에
 * {@code JdbcTemplate}을 직접 주입하면 그 둘이 함께 깨진다.
 *
 * <h2>왜 유니버스 어댑터가 우리 DB를 봐야 하는가</h2>
 * <p>미국 시총은 <b>소스 하나로 값이 나오지 않는다.</b> 시세 소스가 상장주식수를 주지 않고
 * (ADR-0013 · {@code price_daily.listed_shares}가 미국 전 행 NULL) 재무 소스는 종가를 주지
 * 않는다. 국내는 전종목 시세 응답 하나에 {@code mrktTotAmt}가 함께 와서 이 포트가 필요 없었다.
 */
public interface StoredShareCounts {

    /**
     * 그 시장에서 <b>주식수를 낼 수 있는</b> 종목 전부.
     *
     * <p>기준은 <b>기(期)마다 하나</b>다 — {@code asOf}까지 공표된 것 중 {@code period_end}가
     * 가장 늦은 행을 고르고, 같은 기가 여럿이면 그 시점의 최신 판을 고른다.
     * <b>{@code max()}로 뽑으면 안 된다</b>: AAPL은 {@code is_latest} 행이 38개고 그중 주식수
     * 최댓값이 옛 기의 17,001,802,000인데 최신기는 14,594,180,000이라 시총이 <b>약 1.165배</b>
     * 크게 나온다(2026-08-31 실측).
     *
     * <p><b>MUST: 기를 고르는 첫 키는 {@code period_end}다.</b> 「가장 늦게 공시된 것」으로 바꾸면
     * 뒤늦게 공시된 옛 기가 이겨 {@code NCRA}가 <b>7.16배</b>가 된다(실측 6종목).
     *
     * @param marketCode 시장 코드
     * @param oldestPeriodEnd 이 날짜보다 이른 기의 값은 낡은 것으로 보고 <b>내지 않는다.</b>
     *                        경계가 없으면 십수 년 전 주식수가 그날 종가에 곱해진다 —
     *                        {@code companyconcept}에서 {@code V}가 2010-01-27,
     *                        {@code BRK.A/B}가 2011-04-29로 멈춰 있던 것과 같은 병이다
     *                        ({@code docs/api-spec/sec-edgar-financial.md})
     * @param asOf <b>그 시점에 공표돼 있던 것만</b> 낸다(ADR-0135). 공시일이 이보다 뒤인 판은
     *             안 고르고, 기업행위 배수도 이 날짜까지만 곱한다. 라이브는 오늘을 준다
     * @return 티커 오름차순. <b>통화가 어긋난 종목도 함께 낸다</b> — 아래 계약을 보라
     */
    List<ShareCount> activeByMarket(String marketCode, LocalDate oldestPeriodEnd, LocalDate asOf);

    /**
     * 종목 하나의 주식수와, 그것을 쓸 수 있는지에 대한 판정.
     *
     * <p><b>MUST: {@code currencyMismatched}가 참이면 호출자가 그 종목을 뺀다.</b> 포트가
     * 미리 걸러 내지 않는 이유는 <b>어느 축이 무엇을 잘랐는지를 호출자가 셀 수 있어야</b>
     * 하기 때문이다(`docs/phase-criteria.md` P26 축①). 합계만 남으면 오분류와 정상 배제가
     * 구분되지 않는다.
     *
     * <p><b>MUST: 그 판정의 술어를 {@code IndicatorDataReader.CURRENCY_GATE}와 같게 유지한다</b>
     * (ADR-0094 §규칙). 그쪽이 {@code is_latest}를 <b>안 보고</b> 그 종목의 <b>어느 행이든</b>
     * 불일치면 막으므로 여기도 그렇게 한다. 최신 기의 행만 보면 <b>134종목이 새어 들어오고</b>
     * (2026-08-31 실측), 그 종목은 유니버스에는 있는데 지표 계층이 재무를 한 행도 안 줘
     * <b>재무 지표가 전부 판정불가</b>가 된다.
     *
     * @param sicCode 미국은 SIC, 국내는 언제나 {@code null}이다(ADR-0092 — 체계가 시장에 묶여
     *                있고 {@code industry_code}는 KSIC 전용이다). 없을 수 있다
     * @param publishedAt <b>어느 판을 골랐는지</b>를 호출자가 셀 수 있게 함께 낸다. 복원 대조가
     *                    「빠진 것」만이 아니라 「왜 빠졌는지」를 내려면 이 값이 필요하다
     * @param name 저장된 종목 이름({@code stock.name}). 시세 소스 목록이 그 종목을 빠뜨렸을 때 이름 축을
     *             태울 재료다(ADR-0171) — 소스에서 온 이름이 있으면 그쪽이 먼저다
     */
    record ShareCount(String ticker, BigDecimal shares, String sicCode,
                      LocalDate periodEnd, LocalDate publishedAt, boolean currencyMismatched,
                      String name) {
    }
}
