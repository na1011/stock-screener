package com.stockscreener.indicator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지표의 <b>사용자 노출 문자열</b>과 <b>표시 순서</b>가 조용히 어긋나는 것을 막는다.
 *
 * <p>둘 다 실제로 어긋났고, 어느 쪽도 아무것도 잡지 못했다.
 *
 * <ul>
 *   <li><b>문구</b> — {@code ICHI_*} 다섯의 {@code interpretation}이 화면에
 *       {@code "MUST NOT: 크기로 비교하지 않는다"}를 그대로 냈다. 이 문자열들은
 *       종목 상세 카드·조건 빌더 픽커·조건 빌더 편집기·도움말 팝오버 <b>넷</b>에 렌더링되는데,
 *       그것을 지키는 검사가 하나도 없었다.
 *   <li><b>순서</b> — ADR-0060이 파생 지표의 순서를 원본에서 유도하면서 세운
 *       <i>"애너테이션 값이 전부 유일하므로 충돌이 구조적으로 사라진다"</i>가 거짓이 됐다.
 *       DB 쪽 premise 펜스가 있지만 그것은 <b>다음 세션 시작</b>에야 뜬다.
 * </ul>
 *
 * <p>DB를 붙이지 않는다 — 애너테이션 문자열과 정수를 보는 데 컨텍스트가 필요 없고,
 * 붙이면 이 검사가 DB 상태에 종속된다.
 */
class IndicatorCopyGuardTest {

    private static final String PACKAGE = "com.stockscreener.indicator";

    /**
     * 사용자 문구에 있으면 안 되는 낱말.
     *
     * <p>앞 넷은 <b>하네스 문체</b>다 — {@code .claude/rules/harness-core.md}가 지시문에
     * {@code MUST:} 접두어를 쓰라고 정하는데, 그 문체가 사용자 문자열로 새는 것이 발생점이었다.
     * 뒤 둘은 <b>코드·기록의 낱말</b>이라 화면에서 뜻이 통하지 않는다.
     */
    private static final List<String> FORBIDDEN =
            List.of("MUST", "IMPORTANT", "TODO", "FIXME", "ADR-", "{@");

    private record Copy(String owner, String field, String text) {
    }

    /** 계산기 애너테이션 + 계산기 없는 파생 선언. 화면에 나가는 문자열은 이 둘이 전부다. */
    private static List<Copy> allCopy() {
        List<Copy> copy = new ArrayList<>();
        forEachMeta((code, meta) -> {
            copy.add(new Copy(code, "name", meta.name()));
            copy.add(new Copy(code, "description", meta.description()));
            copy.add(new Copy(code, "interpretation", meta.interpretation()));
        });
        for (PercentileSpec spec : new PercentileConfig().percentileSpecs()) {
            copy.add(new Copy(spec.sourceCode() + " 백분위", "name", spec.name()));
            copy.add(new Copy(spec.sourceCode() + " 백분위", "description", spec.description()));
            copy.add(new Copy(spec.sourceCode() + " 백분위", "interpretation", spec.interpretation()));
        }
        for (SectorMetricSpec spec : new SectorMetricConfig().sectorMetricSpecs()) {
            String owner = spec.sourceCode() + " " + spec.kind();
            copy.add(new Copy(owner, "name", spec.name()));
            copy.add(new Copy(owner, "description", spec.description()));
            copy.add(new Copy(owner, "interpretation", spec.interpretation()));
        }
        return copy;
    }

    @Test
    @DisplayName("IMPORTANT — 사용자에게 보이는 지표 문구에 하네스·코드 낱말이 없다")
    void keepsHarnessProseOutOfUserFacingCopy() {
        List<String> offenders = new ArrayList<>();
        for (Copy c : allCopy()) {
            for (String word : FORBIDDEN) {
                if (c.text().contains(word)) {
                    offenders.add("%s.%s 에 「%s」 — %s".formatted(c.owner(), c.field(), word, c.text()));
                }
            }
        }
        assertThat(offenders)
                .as("이 문자열들은 화면에 그대로 그려진다. 코드를 고치는 사람에게 할 말은 "
                        + "javadoc에 두고, 사용자에게는 설명과 유리·불리한 방향만 남긴다")
                .isEmpty();
    }

    @Test
    @DisplayName("빈 설명을 만들지 않는다 — 값만 나열하면 초보 사용자가 읽을 수 없다")
    void everyIndicatorExplainsItself() {
        List<String> blanks = new ArrayList<>();
        for (Copy c : allCopy()) {
            if (!"interpretation".equals(c.field()) && c.text().isBlank()) {
                blanks.add(c.owner() + "." + c.field());
            }
        }
        // interpretation은 기본값이 빈 문자열이라 뺀다(IndicatorMeta). name·description은 필수다.
        assertThat(blanks)
                .as("spec §5 — 지표는 값만 나열되어서는 안 된다")
                .isEmpty();
    }

    /**
     * 「최근 4개 분기」를 말하면서 기간 기저 문구가 없는 설명문을 <b>전수로</b> 잡는다.
     *
     * <p>ADR-0164가 유량 TTM의 기저를 「연속 4분기 또는 회계연도 한 판」으로 바꿨는데
     * 설명문 여섯이 그대로 남아 <b>46종목에 대해 거짓</b>이었다. 값은 옳고 문구만 틀렸으므로
     * 어떤 계산 검사도 잡지 못한다 — 그래서 문구 쪽에 검사를 둔다.
     *
     * <p><b>목록을 손으로 들지 않는다.</b> 「그 낱말을 말하는가」로 물으면 새 계산기가
     * 같은 문장을 쓰는 순간 걸리고, 유지할 목록이 없다.
     */
    @Test
    @DisplayName("IMPORTANT — 분기를 말하는 설명문은 기간 기저 폴백을 함께 말한다")
    void quarterClaimsDisclosePeriodBasisFallback() {
        List<String> offenders = new ArrayList<>();
        for (Copy c : allCopy()) {
            if (!"description".equals(c.field())) {
                continue;
            }
            // PROFITABLE_QUARTERS_4는 정의가 「4분기 중 몇 분기」라 폴백을 일부러 안 탄다.
            // 그 설명문의 「4개 분기」는 참이므로 예외다(IndicatorMeta.PERIOD_BASIS_NOTE의 javadoc).
            if (c.owner().startsWith("ProfitableQuarters4")) {
                continue;
            }
            boolean claimsQuarters = c.text().contains("4개 분기") || c.text().contains("4분기");
            if (claimsQuarters && !c.text().contains(IndicatorMeta.PERIOD_BASIS_NOTE.trim())) {
                offenders.add(c.owner() + " — " + c.text());
            }
        }
        assertThat(offenders)
                .as("분기 공시가 연속 12개월을 못 덮는 종목은 회계연도 한 판으로 값이 난다. "
                        + "설명문이 분기라고만 말하면 그 종목의 사용자가 실적표와 대조하다 "
                        + "어긋난 것으로 읽는다")
                .isEmpty();
    }

    @Test
    @DisplayName("IMPORTANT — 애너테이션 sortOrder가 전역에서 유일하다(ADR-0060이 이 위에 선다)")
    void sortOrderStaysGloballyUnique() {
        Map<Integer, List<String>> byOrder = new TreeMap<>();
        forEachMeta((code, meta) ->
                byOrder.computeIfAbsent(meta.sortOrder(), k -> new ArrayList<>()).add(code));

        Map<Integer, List<String>> collisions = new LinkedHashMap<>();
        byOrder.forEach((order, codes) -> {
            if (codes.size() > 1) {
                codes.sort(String::compareTo);
                collisions.put(order, codes);
            }
        });

        assertThat(collisions)
                .as("겹치면 저장값도 겹쳐 화면 순서가 ORDER BY sort_order, code의 알파벳순으로 "
                        + "조용히 갈린다. 파생 지표가 원본 뒤 +1~5에 앉는 것도 이 유일성에 기댄다")
                .isEmpty();
    }

    /** {@code @IndicatorMeta}가 붙은 계산기를 클래스패스에서 훑는다. */
    private static void forEachMeta(java.util.function.BiConsumer<String, IndicatorMeta> visitor) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(IndicatorMeta.class));
        Set<BeanDefinition> found = scanner.findCandidateComponents(PACKAGE);

        List<Class<?>> production = new ArrayList<>();
        for (BeanDefinition definition : found) {
            Class<?> type = ClassUtils.resolveClassName(
                    definition.getBeanClassName(), IndicatorCopyGuardTest.class.getClassLoader());
            if (isProduction(type)) {
                production.add(type);
            }
        }

        // 스캔이 0건이면 아래 단언이 전부 조용히 통과한다 — 패키지 이름이 바뀐 자리다.
        // 실측 124개(2026-09-02). 하한만 두는 것은 지표가 계속 늘기 때문이다.
        assertThat(production)
                .as("%s에서 운영 @IndicatorMeta를 찾지 못했다 — 패키지가 바뀌었는지 본다", PACKAGE)
                .hasSizeGreaterThanOrEqualTo(120);

        for (Class<?> type : production) {
            visitor.accept(type.getSimpleName(), type.getAnnotation(IndicatorMeta.class));
        }
    }

    /**
     * 테스트 픽스처를 걷어낸다.
     *
     * <p>스캔은 테스트 클래스패스도 훑어 {@code IndicatorExtensibilityTest}의 더미 계산기까지
     * 집어 온다. 그것을 그대로 두면 <b>테스트 픽스처가 운영 불변식에 참여한다</b> —
     * 더미의 {@code sortOrder}를 바꾸는 것만으로 여기가 붉을 수 있다.
     */
    private static boolean isProduction(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        return source != null && source.getLocation().getPath().contains("classes/java/main");
    }
}
