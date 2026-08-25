package com.stockscreener.pipeline.datasource;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 기업 행위(액면분할·병합·무상증자) 공시 소스. 벤더를 모른다.
 *
 * <p>{@link PriceDataSource}에 얹지 않은 이유는 <b>국내에서 그 값을 주는 곳이 DART이고
 * DART는 시세 소스가 아니기</b> 때문이다. 시세 어댑터가 DART를 부르면 ADR-0006의 국소화가
 * 깨진다. 반대로 {@link PriceDataSource#fetchCorporateActions}는 <b>지우지 않는다</b> —
 * v1.1의 미국 소스는 시세와 기업 행위를 함께 준다(ADR-0049 §버린 대안).
 *
 * <p>MUST: 구현체는 벤더 예외를 {@link DataSourceException}으로 감싼다.
 */
public interface CorporateActionDataSource {

    Set<String> supportedMarketCodes();

    /** 실패 기록과 로그에 남는 소스 이름. */
    String sourceName();

    /**
     * {@code [from, to]} 구간에 <b>효력일</b>이 드는 기업 행위.
     *
     * <p>MUST: 구현체가 {@code [from, to]}를 {@link CorporateAction#effectiveDate} 기준으로
     * 거른다. 국내 소스는 한 번의 호출이 5년 이력을 주므로, 거르지 않으면 창 밖 사건이
     * <b>매 실행 다시 올라온다</b>(ADR-0049).
     *
     * <p>IMPORTANT — <b>소스가 최대 넉 달 늦을 수 있다.</b> 국내는 이 값이 정기보고서에만
     * 실리므로, 사건은 그것을 담는 보고서가 제출되기 전까지 <b>원리적으로 보이지 않는다</b>
     * (api-spec dart-corporate-action.md의 「증자(감자) 현황」 절). 그래서 <b>좁은 최근 창으로 부르면
     * 구조적으로 0건</b>이고 그것은
     * 실패가 아니다. 호출자의 창은 그 지연보다 넓어야 하며, 그 사이의 사건은
     * {@code AdjustmentRecalculator.detectCandidates}가 후보로 올린다.
     *
     * <p>MUST: <b>시세를 먼저 저장한 뒤 부른다.</b> 구현이 우리가 이미 저장한 시계열
     * ({@code StoredPriceSeries})을 근거로 일자와 비율을 유도하므로, 순서가 반대면 최근 봉을
     * 못 봐 유도가 <b>조용히 실패한다</b>(로그 한 줄만 남는다).
     *
     * <p>빈 목록은 "그 창에 사건이 없다"이지 실패가 아니다.
     */
    List<CorporateAction> fetchCorporateActions(SecurityRef security, LocalDate from, LocalDate to)
            throws DataSourceException;
}
