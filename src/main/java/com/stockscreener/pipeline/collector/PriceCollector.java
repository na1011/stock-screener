package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.PriceDataSource;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 한 종목의 시세를 수집해 저장한다.
 *
 * <p>벤더를 모른다 — {@link DataSourceRegistry}가 시장 코드로 어댑터를 골라준다.
 * 이 클래스에 벤더 이름이 등장하는 순간 소스 교체가 국소적이지 않게 된다(ADR-0006).
 */
@Component
public class PriceCollector {

    private static final Logger log = LoggerFactory.getLogger(PriceCollector.class);

    private final DataSourceRegistry registry;
    private final PriceWriter writer;
    private final AdjustmentRecalculator adjustmentRecalculator;

    public PriceCollector(DataSourceRegistry registry, PriceWriter writer,
                          AdjustmentRecalculator adjustmentRecalculator) {
        this.registry = registry;
        this.writer = writer;
        this.adjustmentRecalculator = adjustmentRecalculator;
    }

    /**
     * {@code [from, to]} 구간의 시세를 수집한다.
     *
     * <p>기업 행위를 함께 조회해 수정주가를 갱신한다. 소스가 제공하지 않으면 빈 목록이므로
     * 아무 일도 일어나지 않는다 — 그 경우의 탐지는
     * {@link AdjustmentRecalculator#detectCandidates}가 담당하며 사람의 확인을 거친다.
     *
     * @return 저장한 행 수
     */
    public long collect(SecurityRef security, LocalDate from, LocalDate to)
            throws DataSourceException {
        PriceDataSource source = registry.priceSource(security.marketCode());

        List<RawPriceBar> bars = source.fetchDailyBars(security, from, to);
        long written = writer.write(security.stockId(), bars);

        // 시세를 먼저 저장한 뒤 조정한다. 순서가 반대면 새로 들어온 봉이 조정되지 않은 채 남는다.
        for (CorporateAction action : source.fetchCorporateActions(security, from, to)) {
            if (action.source() == CorporateAction.DetectionSource.SOURCE_DISCLOSURE) {
                adjustmentRecalculator.apply(security.stockId(), action);
            }
        }

        if (bars.isEmpty()) {
            log.debug("수집된 시세가 없다. stockId={} {} ~ {} — 휴장이거나 거래정지일 수 있다.",
                    security.stockId(), from, to);
        }
        return written;
    }
}
