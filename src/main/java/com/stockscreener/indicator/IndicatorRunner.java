package com.stockscreener.indicator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 한 종목의 지표를 전부 계산해 저장한다.
 *
 * <p>이 클래스가 지표 계층의 조립점이다. 원자료를 한 번 읽고
 * ({@link IndicatorDataReader}), {@link IndicatorRegistry#executionOrder()}가 정한 순서대로
 * 계산기를 돌리며, 계산된 시계열을 뒤따르는 파생 지표에 넘겨준다
 * ({@link IndicatorContext#dependency(String)}).
 *
 * <p>IMPORTANT — 계산 순서가 위상 정렬이라는 점이 파생 지표의 유일한 근거다. MACD-V가
 * MACD·ATR을 다시 구현하지 않는 이유는 그 둘이 <b>먼저 계산되어 컨텍스트에 담겨</b> 오기
 * 때문이다. 순서를 코드에 손으로 적으면 지표를 추가할 때마다 그 목록을 고쳐야 하고,
 * 그것이 확장 비용 1을 깨뜨린다.
 *
 * <p>실패 격리 단위는 <b>종목</b>이다({@code data-pipeline.md}). 다만 이 클래스는 한 종목만
 * 다루며, 격리와 실패 기록은 이것을 호출하는 배치 계층이
 * {@code StockTaskExecutor}로 담당한다 — 트랜잭션 경계가 거기 있기 때문이다.
 */
@Component
public class IndicatorRunner {

    private static final Logger log = LoggerFactory.getLogger(IndicatorRunner.class);

    private final IndicatorRegistry registry;
    private final IndicatorDataReader reader;
    private final IndicatorValueWriter writer;

    public IndicatorRunner(IndicatorRegistry registry,
                           IndicatorDataReader reader,
                           IndicatorValueWriter writer) {
        this.registry = registry;
        this.reader = reader;
        this.writer = writer;
    }

    /**
     * 한 종목의 모든 지표를 {@code asOf} 기준으로 계산해 저장한다.
     *
     * <p>원자료는 <b>종목당 한 번만</b> 읽는다. 지표마다 읽으면 지표를 추가할 때마다 조회가
     * 늘어 850종목 × N지표의 N+1이 된다.
     *
     * <p>IMPORTANT — 계산은 창 전체로 하고 <b>저장만</b> 보존 경계로 자른다. 재귀 지표
     * ({@code ATR_14}·{@code RSI_14}·{@code MACD}·{@code MACD_V})는 창의 앞쪽부터 이어져야
     * 마지막 값이 맞으므로, 입력을 줄이면 값 자체가 달라진다. 여기서 거르는 것은
     * {@link IndicatorValueWriter}에 넘길 목록뿐이다.
     *
     * @param retentionCutoff 이 날짜 <b>이하</b>는 저장하지 않는다
     *                        ({@link IndicatorRetentionPolicy#writeCutoff()}).
     *                        비어 있으면 거르지 않는다 — 시세가 보존 기간보다 적게 쌓인 초기 상태다
     * @return 저장한 행 수
     */
    public long run(long stockId, LocalDate asOf, Optional<LocalDate> retentionCutoff) {
        IndicatorContext base = readRawData(stockId, asOf);
        if (base.priceBars().isEmpty()) {
            // 시세가 없으면 계산할 것이 없다. 신규 편입 직후나 거래정지 종목에서 정상적으로 생긴다.
            log.debug("시세가 없어 지표 계산을 건너뛴다. stockId={} asOf={}", stockId, asOf);
            return 0L;
        }

        // 이미 계산된 지표의 시계열. 뒤따르는 파생 지표가 여기서 부모를 찾는다.
        Map<String, NavigableMap<LocalDate, BigDecimal>> computed = new HashMap<>();
        long written = 0;

        for (IndicatorCalculator calculator : registry.executionOrder()) {
            List<IndicatorPoint> points = calculator.calculate(contextFor(base, calculator, computed));

            // 계산할 수 없으면 행을 쓰지 않는다(ADR-0002). 워밍업이 부족한 구간에서 정상이다.
            if (points.isEmpty()) {
                continue;
            }

            // 파생 지표에는 창 전체를 넘긴다. 저장만 자르는 것이므로 여기는 자르기 전 값이다.
            computed.put(calculator.code(), toSeries(points));
            written += writer.write(stockId, calculator.code(), retain(points, retentionCutoff));
        }
        return written;
    }

    /**
     * 보존 경계보다 오래된 포인트를 뺀다.
     *
     * <p>부등호가 {@link IndicatorRetentionPolicy#purge()}의 {@code trade_date <= cutoff}와
     * 같은 방향이어야 한다. 어긋나면 경계일 하루가 <b>매 실행 쓰였다가 매 정리에 지워지며</b>,
     * 그 왕복은 어떤 값도 바꾸지 않아 화면으로는 드러나지 않는다.
     */
    private List<IndicatorPoint> retain(List<IndicatorPoint> points, Optional<LocalDate> cutoff) {
        if (cutoff.isEmpty()) {
            return points;
        }
        LocalDate boundary = cutoff.get();
        return points.stream()
                .filter(point -> point.tradeDate().isAfter(boundary))
                .toList();
    }

    private IndicatorContext readRawData(long stockId, LocalDate asOf) {
        int bars = registry.maxEffectiveLookback();
        int years = registry.maxFinancialYears();
        return new IndicatorContext(
                stockId,
                // 종목당 한 번만 읽는다. 지표마다 읽으면 850종목 × N지표의 N+1이 된다.
                reader.readIndustryDivision(stockId),
                reader.readPriceBars(stockId, asOf, bars),
                reader.readAnnualFigures(stockId, asOf, years),
                reader.readQuarterlyFigures(stockId, asOf, years),
                Map.of());
    }

    /**
     * 계산기가 선언한 의존만 담은 컨텍스트를 만든다.
     *
     * <p>선언하지 않은 지표를 넘기지 <b>않는</b> 것이 중요하다.
     * {@link IndicatorContext#dependency(String)}가 미선언 접근에 예외를 던지므로,
     * 선언 없이 몰래 쓰는 계산기는 테스트에서 즉시 드러난다. 그 선언이 곧 워밍업 산정의
     * 근거이기도 해서, 몰래 쓰면 읽어오는 봉 수가 모자라게 된다.
     */
    private IndicatorContext contextFor(IndicatorContext base,
                                        IndicatorCalculator calculator,
                                        Map<String, NavigableMap<LocalDate, BigDecimal>> computed) {
        var dependsOn = calculator.dataRequirement().dependsOn();
        if (dependsOn.isEmpty()) {
            return base;
        }

        Map<String, NavigableMap<LocalDate, BigDecimal>> dependencies = new HashMap<>();
        for (String code : dependsOn) {
            // 부모가 값을 내지 못했으면(워밍업 부족) 빈 시계열을 준다. 자식도 자연히 값을
            // 내지 못하며, 그것이 옳다 — 없는 부모로 계산한 파생 값은 틀린 값이다.
            dependencies.put(code, computed.getOrDefault(code, new TreeMap<>()));
        }
        // MUST: 생성자로 다시 조립하지 않는다. 필드가 늘 때마다 여기서 조용히 빠지고,
        // 빠진 필드는 예외가 아니라 "값이 없는 지표"로 나타난다.
        return base.withDependencies(dependencies);
    }

    private static NavigableMap<LocalDate, BigDecimal> toSeries(List<IndicatorPoint> points) {
        NavigableMap<LocalDate, BigDecimal> series = new TreeMap<>();
        for (IndicatorPoint point : points) {
            series.put(point.tradeDate(), point.value());
        }
        return series;
    }
}
