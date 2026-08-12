package com.stockscreener.pipeline.collector;

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
    private final CorporateActionCollector corporateActions;

    public PriceCollector(DataSourceRegistry registry, PriceWriter writer,
                          CorporateActionCollector corporateActions) {
        this.registry = registry;
        this.writer = writer;
        this.corporateActions = corporateActions;
    }

    /**
     * {@code [from, to]} 구간의 시세를 수집한다.
     *
     * <p>기업 행위를 함께 조회해 <b>원장에 적기만 한다.</b> 계수를 실제로 다시 쓰는 것은
     * {@link AdjustmentRecalculator#rebuild}이고, 그것을 부르는 것은 승인 경로다.
     *
     * <p>MUST NOT: 여기서 계수를 다시 쓰지 않는다. 수집은 15일 창으로 돌아 같은 날짜를 평일
     * 11~12회 다시 보므로(ADR-0042), 저장된 시세를 소급해 다시 쓰는 일이 그 횟수만큼 일어나게
     * 된다. <b>되돌릴 수 없는 쓰기의 진입점은 하나여야 한다.</b>
     *
     * <p>소스가 기업 행위를 제공하지 않으면 빈 목록이므로 아무 일도 일어나지 않는다 — 그
     * 경우의 탐지는 {@link AdjustmentRecalculator#detectCandidates}가 담당한다.
     *
     * @return 저장한 행 수
     */
    public long collect(SecurityRef security, LocalDate from, LocalDate to)
            throws DataSourceException {
        PriceDataSource source = registry.priceSource(security.marketCode());

        List<RawPriceBar> bars = source.fetchDailyBars(security, from, to);
        long written = writer.write(security.stockId(), bars);

        // 시세를 먼저 저장한 뒤 원장에 적는다. 원장의 근접일 검사가 그 종목의 봉을 세므로
        // 순서가 반대면 방금 들어온 거래일이 그 판정에서 빠진다.
        //
        // MUST: 원장에 적는 것은 CorporateActionCollector를 지난다. 여기서 직접 적으면 원장을
        // 만지는 곳이 둘이 되고, 두 곳의 NEAR_DUPLICATE 처리가 갈리면 **알림이 안 오는 형태로만**
        // 드러난다. 국내는 이 목록이 언제나 비어 있고(DART는 시세 소스가 아니다), 채우는 것은
        // 시세와 기업 행위를 함께 주는 v1.1의 미국 소스다(ADR-0049 §버린 대안).
        for (CorporateActionCollector.NearDuplicate conflict
                : corporateActions.recordAll(security,
                        source.fetchCorporateActions(security, from, to)).nearDuplicates()) {
            // 조용히 넘기지 않는다. 같은 사건의 일자가 어긋났거나 진짜 별개 사건인데
            // 원장에 자리가 없는 것이며, 둘 다 사람이 갈라야 한다.
            log.warn("기업 행위가 기존 원장 행과 겹쳐 적지 않았다. ticker={} 새 일자={} 기존 일자={}",
                    conflict.ticker(), conflict.proposed(), conflict.existing());
        }

        if (bars.isEmpty()) {
            log.debug("수집된 시세가 없다. stockId={} {} ~ {} — 휴장이거나 거래정지일 수 있다.",
                    security.stockId(), from, to);
        }
        return written;
    }
}
