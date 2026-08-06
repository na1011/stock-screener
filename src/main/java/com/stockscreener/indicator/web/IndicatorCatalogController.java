package com.stockscreener.indicator.web;

import com.stockscreener.indicator.IndicatorCatalog;
import com.stockscreener.indicator.IndicatorDescriptor;
import com.stockscreener.screening.ComparisonOperator;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 조건 빌더가 쓰는 지표 카탈로그.
 *
 * <p>연산자 목록을 함께 싣는 이유: 연산자는 이 도메인의 유일한 닫힌 집합이고 한글 라벨의
 * 정본이 {@link ComparisonOperator#displayName()}이다. 프론트가 목록을 복제하면 연산자가
 * 추가될 때 두 곳을 고쳐야 하고, 한쪽을 잊으면 만들 수 없는 조건이 생긴다.
 */
@RestController
@RequestMapping("/api/indicators")
public class IndicatorCatalogController {

    private final IndicatorCatalog catalog;

    public IndicatorCatalogController(IndicatorCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public CatalogResponse list() {
        List<IndicatorDescriptor> active = catalog.activeIndicators();
        Map<String, IndicatorDescriptor> byCode = active.stream()
                .collect(Collectors.toMap(IndicatorDescriptor::code, Function.identity()));

        List<IndicatorResponse> indicators = active.stream()
                .map(descriptor -> IndicatorResponse.of(
                        descriptor,
                        // 백분위면 원본을 넘겨 부등호 방향을 계산하게 한다.
                        descriptor.isPercentile() ? byCode.get(descriptor.sourceIndicatorCode()) : null))
                .toList();

        List<OperatorResponse> operators = java.util.Arrays.stream(ComparisonOperator.values())
                .map(op -> new OperatorResponse(op.name(), op.displayName(), op.requiresUpperBound()))
                .toList();

        return new CatalogResponse(indicators, operators);
    }

    public record CatalogResponse(List<IndicatorResponse> indicators, List<OperatorResponse> operators) {
    }

    public record OperatorResponse(String name, String label, boolean requiresUpperBound) {
    }
}
