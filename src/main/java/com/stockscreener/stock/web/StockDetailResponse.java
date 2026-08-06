package com.stockscreener.stock.web;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.stock.StockReader;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 종목 상세 — 메타데이터·기준일 시세·최신 지표.
 *
 * <p>IMPORTANT — 지지·저항이 <b>여기 없다.</b> 별도 엔드포인트({@code /price-reference})다.
 * 이유가 넷이다.
 * <ol>
 *   <li>spec §8·frontend.md의 "영역 단위 오류 격리"를 가장 싸게 이행한다 — 지지·저항 계산이
 *       실패해도 상세 전체가 500이 되지 않고 그 패널만 재시도할 수 있다.
 *   <li>"지표와 물리적으로 분리"가 URL 수준에서 참이 된다. 형제 필드보다 강한 분리다.
 *   <li>상세에서 유일하게 계산이 들어가는 부분이라 실패·지연 가능성이 거기 몰려 있다.
 *   <li>모바일에서 지연 로드·접기가 자연스러워진다(frontend.md의 디바이스별 정보 밀도).
 * </ol>
 */
public record StockDetailResponse(
        LocalDate asOf,
        Stock stock,
        Price price,
        List<IndicatorValue> indicators
) {

    public record Stock(
            long id, String ticker, String name, String marketCode, String sector,
            List<Universe> universes) {

        static Stock of(StockReader.StockRow row, List<StockReader.StockUniverse> universes) {
            return new Stock(row.id(), row.ticker(), row.name(), row.marketCode(), row.sector(),
                    universes.stream()
                            .map(u -> new Universe(u.code(), u.name(), u.since()))
                            .toList());
        }
    }

    public record Universe(String code, String name, LocalDate since) {
    }

    /**
     * 기준일 시세. <b>원주가</b>다 — 사용자가 실제로 지불하는 가격이다.
     *
     * <p>{@code tradeValue}는 소스가 주지 않으면 null이고, {@code listedShares}는 V9에서
     * 추가된 컬럼이라 옛 행에는 없다. 응답이 그것을 견뎌야 한다.
     */
    public record Price(
            LocalDate tradeDate,
            BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
            Long volume, BigDecimal tradeValue, BigDecimal listedShares) {
    }

    /**
     * 지표 하나의 값과 의미.
     *
     * <p>{@code description}·{@code interpretation}이 함께 실린다 — spec §5가 "값만 나열하지
     * 않는다"를 요구한다. 초보 사용자는 PER 8.1이 좋은 것인지 알 수 없다.
     */
    public record IndicatorValue(
            String code, String name, BigDecimal value, String unit, int decimalScale,
            IndicatorCategory category, Boolean higherIsBetter,
            String description, String interpretation) {
    }
}
