package com.stockscreener.indicator;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 등록된 지표 계산기 전체를 보유하고, 계산 순서를 결정한다.
 *
 * <p>Spring이 {@link IndicatorCalculator}를 구현한 모든 {@code @Component}를 주입한다.
 * 그래서 지표를 추가할 때 이 클래스를 <b>고치지 않는다</b> — 그것이 확장 비용 1의 근거다.
 *
 * <p>코드 중복과 의존 순환을 생성자에서 검사해 <b>기동 시점에</b> 실패시킨다.
 * 새벽 배치 도중에 터지는 것보다 애플리케이션이 뜨지 않는 편이 낫다.
 */
@Component
public class IndicatorRegistry {

    private final Map<String, IndicatorCalculator> byCode;
    private final List<IndicatorCalculator> executionOrder;
    private final Map<String, Integer> effectiveLookback;

    public IndicatorRegistry(List<IndicatorCalculator> calculators) {
        this.byCode = indexByCode(calculators);
        assertDependenciesExist();
        this.executionOrder = topologicalSort();
        this.effectiveLookback = computeEffectiveLookbacks();
    }

    private static Map<String, IndicatorCalculator> indexByCode(List<IndicatorCalculator> calculators) {
        Map<String, IndicatorCalculator> map = new LinkedHashMap<>();
        for (IndicatorCalculator calculator : calculators) {
            String code = calculator.code();
            if (code == null || code.isBlank()) {
                throw new IllegalStateException(
                        calculator.getClass().getName() + "의 code()가 비어 있다.");
            }
            IndicatorCalculator previous = map.put(code, calculator);
            if (previous != null) {
                throw new IllegalStateException(
                        "지표 코드가 중복되었다: '" + code + "' — "
                                + previous.getClass().getName() + ", " + calculator.getClass().getName());
            }
        }
        return map;
    }

    private void assertDependenciesExist() {
        for (IndicatorCalculator calculator : byCode.values()) {
            for (String dependency : calculator.dataRequirement().dependsOn()) {
                if (!byCode.containsKey(dependency)) {
                    throw new IllegalStateException(
                            "'" + calculator.code() + "'가 등록되지 않은 지표에 의존한다: '" + dependency + "'");
                }
            }
        }
    }

    /**
     * 의존 지표가 먼저 계산되도록 정렬한다. 순환이 있으면 기동에 실패한다.
     */
    private List<IndicatorCalculator> topologicalSort() {
        List<IndicatorCalculator> sorted = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String code : byCode.keySet()) {
            visit(code, done, visiting, sorted, new ArrayList<>());
        }
        return List.copyOf(sorted);
    }

    private void visit(String code, Set<String> done, Set<String> visiting,
                       List<IndicatorCalculator> sorted, List<String> path) {
        if (done.contains(code)) {
            return;
        }
        if (!visiting.add(code)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(code);
            throw new IllegalStateException("지표 의존에 순환이 있다: " + String.join(" -> ", cycle));
        }
        path.add(code);
        for (String dependency : byCode.get(code).dataRequirement().dependsOn()) {
            visit(dependency, done, visiting, sorted, path);
        }
        path.remove(path.size() - 1);
        visiting.remove(code);
        done.add(code);
        sorted.add(byCode.get(code));
    }

    /**
     * 의존 그래프를 따라 실제로 필요한 시세 봉 수를 유도한다.
     *
     * <p>{@code 유효 워밍업(X) = X.priceBarsNeeded + max(의존 지표들의 유효 워밍업)}.
     * 개발자는 자기 몫의 증분만 선언하고, 부모의 워밍업까지 합산하는 것은 여기서 한다.
     */
    private Map<String, Integer> computeEffectiveLookbacks() {
        Map<String, Integer> result = new HashMap<>();
        for (IndicatorCalculator calculator : executionOrder) {
            DataRequirement requirement = calculator.dataRequirement();
            int parentMax = requirement.dependsOn().stream()
                    .mapToInt(dep -> result.getOrDefault(dep, 0))
                    .max()
                    .orElse(0);
            result.put(calculator.code(), requirement.priceBarsNeeded() + parentMax);
        }
        return Map.copyOf(result);
    }

    /** 의존 지표가 먼저 오도록 정렬된 계산기 목록. */
    public List<IndicatorCalculator> executionOrder() {
        return executionOrder;
    }

    public Optional<IndicatorCalculator> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    public boolean isRegistered(String code) {
        return byCode.containsKey(code);
    }

    public Set<String> codes() {
        return byCode.keySet();
    }

    /** 의존까지 감안해 실제로 읽어와야 하는 시세 봉 수. */
    public int effectiveLookback(String code) {
        Integer lookback = effectiveLookback.get(code);
        if (lookback == null) {
            throw new IllegalArgumentException("등록되지 않은 지표다: " + code);
        }
        return lookback;
    }

    /**
     * 가장 긴 유효 워밍업. <b>읽어올 봉 수가 아니다</b> — 그것은 {@link #readWindowBars()}다.
     *
     * <p>낱말이 같아 계속 헷갈리므로 갈라 적는다. 이 값은 <b>한 지표가 값 하나를 내는 데 필요한
     * 봉 수의 최댓값</b>이고, {@code UnadjustedActionGuard}가 억제 창의 상한으로 쓰는 축이기도 하다.
     */
    public int maxEffectiveLookback() {
        return effectiveLookback.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /**
     * 러너가 종목당 한 번에 읽어올 시세 봉 수(ADR-0079).
     *
     * <p>계산기는 워밍업 W일 때 {@code bars.size() − W + 1}개 포인트를 낸다. 그래서 읽어오는 봉이
     * 워밍업 최댓값과 <b>같으면</b> 그 지표는 <b>{@code asOf}당 한 일자</b>밖에 못 내고, 보존 창을
     * 채우려면 날짜마다 한 번씩 180번을 돌아야 한다. 보존 일수만큼을 얹어 그 굴레를 없앤다.
     *
     * <p>MUST NOT: 러너의 두 호출 지점(시세·지수) 중 한쪽만 이것을 쓰지 않는다. 갈리면 오래된
     * 산출 일자가 전부 「지수 결손」으로 접혀 사라지고 백필은 <b>SUCCESS로 끝난다.</b>
     */
    public int readWindowBars() {
        return maxEffectiveLookback() + IndicatorRetentionPolicy.RETAINED_TRADING_DAYS;
    }

    /** 모든 지표를 계산하기 위해 읽어야 하는 최대 거버넌스 회계연도 수(ADR-0081). */
    public int maxGovernanceYears() {
        return byCode.values().stream()
                .mapToInt(c -> c.dataRequirement().governanceYearsNeeded())
                .max()
                .orElse(0);
    }

    /** 모든 지표를 계산하기 위해 읽어야 하는 최대 재무 연수. */
    public int maxFinancialYears() {
        return byCode.values().stream()
                .mapToInt(c -> c.dataRequirement().financialYearsNeeded())
                .max()
                .orElse(0);
    }
}
