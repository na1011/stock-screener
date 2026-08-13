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
        List<IndicatorValue> indicators,
        UnadjustedActions unadjustedActions
) {

    /**
     * 계수에 반영되지 않은 기업 행위. <b>지표가 왜 없는지에 답하는 유일한 자리다</b>(ADR-0055).
     *
     * <p>{@code notice}는 {@code PriceReference#disclaimer}와 같은 형태다 — 서버가 문구를
     * 소유하고 <b>항상 실린다.</b> 프론트가 자기 문구를 들면 두 곳이 갈리는데, 갈린 쪽은
     * 화면에서 드러나지 않는다.
     *
     * <p>MUST NOT: {@code corporate_action.note}를 여기 싣지 않는다. 그것은 운영자가 판정하며
     * 쓴 판단 기록이고 사용자 문구가 아니다 — ADR-0050이 소스 낱말({@code source_form})과
     * 사람의 말({@code note})을 가른 것과 같은 이유다.
     *
     * <p>{@code OBSERVED}(판정 전)와 {@code UNSUPPORTED}(못 고친다)를 구분해 내지 않는다.
     * 사용자에게 둘의 결과는 같고, 그 구분은 운영자의 것이라 관리자 API에 있다.
     *
     * @param effectiveDates 권리락일 오름차순. 없으면 빈 목록이다
     */
    public record UnadjustedActions(String notice, List<LocalDate> effectiveDates) {

        public static final String NOTICE =
                "이 종목에는 수정계수를 확정하지 못한 기업 행위가 있습니다. "
                        + "해당 일자를 지나는 구간의 지표는 값을 내지 않으므로, "
                        + "일부 지표가 비어 있고 이 종목이 프리셋 결과에서 빠질 수 있습니다.";

        public static UnadjustedActions of(List<LocalDate> effectiveDates) {
            return new UnadjustedActions(NOTICE, List.copyOf(effectiveDates));
        }
    }

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
     *
     * <p>{@code detailDefault}도 <b>여기</b> 싣는다(ADR-0058). 화면이 카탈로그에서 되짚을 수도
     * 있지만 그러면 셋이 어긋난다 — 카탈로그 요청과 이 요청의 <b>도착 순서가 보장되지 않아</b>
     * 감출 카드가 그려졌다가 접히고, 카탈로그가 실패하면 감춤이 통째로 무효가 되는데 그
     * 사실이 화면에 남지 않는다. 구간({@code bands})을 카탈로그에서 잇는 선례와는 방향이
     * 반대다 — 그쪽은 <b>덧붙이는</b> 것이라 실패해도 값과 설명이 남는다.
     */
    public record IndicatorValue(
            String code, String name, BigDecimal value, String unit, int decimalScale,
            IndicatorCategory category, Boolean higherIsBetter,
            String description, String interpretation, boolean detailDefault) {
    }
}
