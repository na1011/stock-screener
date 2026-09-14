package com.stockscreener.indicator;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 화면이 <b>계산기와 같은 술어</b>로 매출총이익률의 전제를 다시 묻는 자리.
 *
 * <p>사유는 응답 시점에 {@link UnavailabilityRule.Context}로 유도하는데, 그 레코드가 들고 있던
 * 넷(값·업종·시장·소스 부재) 중 <b>어느 것도 재무 이력이 아니다.</b> 그래서 전제 판정을
 * 그대로 두면 {@code CNP}·{@code JEF}·{@code MUR}·{@code ELV}·{@code MCY}의 값이
 * <b>사유 없이 그냥 사라진다</b> — 침묵은 「모른다」인데 우리는 알고 있다.
 *
 * <p><b>MUST: {@code asOf}를 그대로 넘긴다.</b> 판정이 봉 시점 재무의 함수이므로(ADR-0128)
 * 과거 기준일 화면은 <b>그때의 판정</b>을 말해야 한다. 최신 관측 하나를 과거에 붙이면
 * {@code financial_column_absence}가 데인 자리를 그대로 반복한다.
 *
 * <p><b>어려워지는 것</b> — 종목 상세마다 질의가 하나 는다. ADR-0060이 <i>"사유 규칙이 늘면
 * 그 판정이 응답 시점 비용이 된다"</i>를 이미 적어 뒀다.
 */
@Component
public class GrossProfitPremiseReader {

    /**
     * 읽을 연수. <b>{@code KNOWN_ON}은 이 값을 안 쓴다</b>(그 갈래는 판 이력을 통째로 준다) —
     * 0 이하면 빈 목록을 내는 가드만 통과하면 된다. 그래도 뜻이 드러나게 적재 창과 같은 수를 둔다.
     */
    private static final int YEARS = 11;

    private final IndicatorDataReader dataReader;

    public GrossProfitPremiseReader(IndicatorDataReader dataReader) {
        this.dataReader = dataReader;
    }

    /** 전제가 깨졌으면 그 갈래, 아니면 {@code null}. */
    public GrossProfitPremise.Break brokenAt(long stockId, LocalDate asOf) {
        List<FinancialFigures> annual = dataReader.readAnnualFigures(
                stockId, asOf, YEARS, IndicatorDataReader.FinancialVisibility.KNOWN_ON);
        return GrossProfitPremise.of(annual);
    }
}
