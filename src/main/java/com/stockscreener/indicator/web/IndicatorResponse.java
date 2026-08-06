package com.stockscreener.indicator.web;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorDescriptor;
import com.stockscreener.indicator.IndicatorValueType;

import java.math.BigDecimal;

/**
 * 조건 빌더가 지표 하나를 그리는 데 필요한 전부.
 *
 * <p>{@code description}·{@code interpretation}이 반드시 실린다 — spec §5와 frontend.md가
 * "지표를 값만 나열하지 않는다"를 요구한다. 초보 사용자는 PER 8.1이 좋은 것인지 알 수 없다.
 *
 * @param percentile 백분위 지표일 때만 채워진다.
 */
public record IndicatorResponse(
        String code,
        String name,
        String description,
        String interpretation,
        IndicatorCategory category,
        IndicatorValueType valueType,
        String unit,
        int decimalScale,
        Boolean higherIsBetter,
        boolean defaultColumn,
        int sortOrder,
        String percentileOf,
        PercentileGuide percentile
) {

    public static IndicatorResponse of(IndicatorDescriptor descriptor, IndicatorDescriptor source) {
        return new IndicatorResponse(
                descriptor.code(), descriptor.name(), descriptor.description(),
                descriptor.interpretation(), descriptor.category(), descriptor.valueType(),
                descriptor.unit(), descriptor.decimalScale(), descriptor.higherIsBetter(),
                descriptor.defaultColumn(), descriptor.sortOrder(),
                descriptor.sourceIndicatorCode(),
                descriptor.isPercentile() ? PercentileGuide.of(source) : null);
    }

    /**
     * 백분위를 조건으로 쓸 때의 방향 안내.
     *
     * <p>IMPORTANT — 이 필드가 있는 이유는 <b>백분위가 언제나 오름차순인데 "상위 20%"의
     * 부등호는 원본 지표의 유불리에 따라 뒤집히기</b> 때문이다. PER은 낮을수록 좋으므로
     * "상위 20%"가 {@code <= 0.2}이고, ROE는 높을수록 좋으므로 {@code >= 0.8}이다.
     *
     * <p>프론트가 스스로 판단하려면 지표 코드를 알아야 하고, 그것은 프론트로 옮겨간
     * 하드코딩 분기다. 서버가 계산하는 것은 지표 코드에 대한 분기가 아니라 원본의
     * {@code higher_is_better}에 대한 순수 함수다.
     *
     * @param ascending  항상 참. 예외가 없다는 사실 자체를 계약으로 남긴다.
     * @param favorableEnd HIGH / LOW / UNKNOWN
     * @param topQuintileOperator "상위 20%"를 표현하는 연산자. 방향을 모르면 null.
     * @param topQuintileThreshold 그 경계값. 방향을 모르면 null.
     */
    public record PercentileGuide(
            boolean ascending,
            IndicatorDescriptor.FavorableEnd favorableEnd,
            IndicatorDescriptor.ComparisonOperatorHint topQuintileOperator,
            BigDecimal topQuintileThreshold,
            String label
    ) {

        static PercentileGuide of(IndicatorDescriptor source) {
            IndicatorDescriptor.FavorableEnd end = IndicatorDescriptor.favorableEndOf(source);
            String label = switch (end) {
                case HIGH -> "값이 클수록 유리하다. 상위 20%는 0.8 이상이다.";
                case LOW -> "값이 작을수록 유리하다. 상위 20%는 0.2 이하다.";
                // 방향이 없는 지표(RSI처럼 중간이 좋은 것)에 "상위/하위"를 제시하면
                // 사용자가 없는 의미를 읽는다.
                case UNKNOWN -> "이 지표는 유불리 방향이 정해져 있지 않다. 값의 위치만 나타낸다.";
            };
            return new PercentileGuide(
                    true, end, end.topQuintileOperator(), end.topQuintileThreshold(), label);
        }
    }
}
