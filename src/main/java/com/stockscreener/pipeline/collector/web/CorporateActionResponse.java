package com.stockscreener.pipeline.collector.web;

import com.stockscreener.pipeline.collector.CorporateActionReader;
import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.collector.CorporateActionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 판정 대기열의 한 줄.
 *
 * <p>{@code sourceForm}이 여기 있어야 하는 이유가 있다 — {@code type}이 {@code UNKNOWN}인데
 * 이 값이 있으면 <b>"우리가 이름은 아는데 계수를 못 낸다"</b>이고, 없으면 "가격이 뛴 것밖에
 * 모른다"다. 사람이 할 일이 전혀 다르다. 감추면 로그에만 있던 낱말이 DB에만 있게 될 뿐이다.
 *
 * <p>{@code appliedAt}은 {@code status}와 잇지 않는다(V21). {@code APPROVED}인데 이 값이 있으면
 * <b>"한때 계수에 들어갔다가 사람이 되돌렸다"</b>는 뜻이고, 그 사실은 재구축을 다시 돌려야
 * 한다는 신호다.
 *
 * @param observedRatio     효력일에 관측된 가격 배수. {@code ratio}와 같은 방향이다.
 * @param sameDayShareRatio 효력일 당일 상장주식수 배수. <b>1이면 무상증자류 권리락이다.</b>
 * @param priceRatio        가격 배수. 분사만 채운다 — 사람이 분사를 판정할 때 {@code ratio}(주식수 배수)와 갈라 본다.
 */
public record CorporateActionResponse(
        long id, String ticker, String stockName,
        LocalDate effectiveDate, LocalDate listingDate, LocalDate sourceDate,
        CorporateAction.Type type, BigDecimal ratio,
        CorporateAction.DetectionSource detectionSource, CorporateActionStatus status,
        String sourceForm, String note, Instant appliedAt, Instant updatedAt,
        BigDecimal prevClose, BigDecimal close, BigDecimal observedRatio,
        BigDecimal sameDayShareRatio, BigDecimal sameDaySnappedRatio,
        Boolean sameDaySnapMatchesPrice, BigDecimal priceRatio) {

    public static CorporateActionResponse of(CorporateActionReader.Row row) {
        return new CorporateActionResponse(
                row.id(), row.ticker(), row.stockName(),
                row.effectiveDate(), row.listingDate(), row.sourceDate(),
                row.type(), row.ratio(), row.detectionSource(), row.status(),
                row.sourceForm(), row.note(), row.appliedAt(), row.updatedAt(),
                row.prevClose(), row.close(), row.observedRatio(),
                row.sameDayShareRatio(), row.sameDaySnapped(), row.sameDayMatchesPrice(), row.priceRatio());
    }
}
