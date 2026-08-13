package com.stockscreener.indicator;

import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 등록된 계산기의 {@link IndicatorMeta}를 {@code indicator} 테이블로 투영한다.
 *
 * <p>메타데이터의 근거는 계산 클래스의 애너테이션이고, 이 테이블은 그 사본이다.
 * 그래야 지표 추가가 파일 하나로 끝나면서도 API와 조건 빌더 UI가 평범한 쿼리로 읽을 수 있다.
 *
 * <p>사라진 지표는 <b>삭제하지 않고</b> {@code is_active=false}로 둔다. 저장된 커스텀 화면이
 * 그 지표를 참조하고 있을 수 있고, 삭제하면 사용자의 화면이 깨진다.
 */
@Component
public class IndicatorCatalogSynchronizer {

    private final IndicatorRegistry registry;
    private final List<PercentileSpec> percentileSpecs;
    private final List<SectorMetricSpec> sectorMetricSpecs;
    private final JdbcTemplate jdbcTemplate;
    private final IndicatorBandCodec bandCodec;

    public IndicatorCatalogSynchronizer(IndicatorRegistry registry,
                                        List<PercentileSpec> percentileSpecs,
                                        List<SectorMetricSpec> sectorMetricSpecs,
                                        JdbcTemplate jdbcTemplate,
                                        IndicatorBandCodec bandCodec) {
        this.registry = registry;
        this.percentileSpecs = List.copyOf(percentileSpecs);
        this.sectorMetricSpecs = List.copyOf(sectorMetricSpecs);
        this.jdbcTemplate = jdbcTemplate;
        this.bandCodec = bandCodec;
    }

    /**
     * 카탈로그를 현재 등록된 계산기 집합에 맞춘다.
     *
     * @return upsert된 지표 수
     */
    @Transactional
    public int synchronize() {
        List<String> activeCodes = new ArrayList<>();

        for (IndicatorCalculator calculator : registry.executionOrder()) {
            IndicatorMeta meta = AnnotationUtils.findAnnotation(calculator.getClass(), IndicatorMeta.class);
            if (meta == null) {
                throw new IllegalStateException(
                        calculator.getClass().getName() + "에 @IndicatorMeta가 없다. "
                                + "지표의 표시 정보는 계산 코드 옆에 두어야 빠뜨리지 않는다.");
            }
            upsert(calculator.code(), meta);
            activeCodes.add(calculator.code());
        }

        // 백분위 지표는 계산기가 없다(유니버스 횡단 집계라 IndicatorCalculator가 될 수 없다).
        // 그래도 카탈로그 행은 있어야 한다 — indicator_value의 FK가 이 행을 요구하고,
        // 여기 빠지면 아래 deactivateMissing이 방금 만든 행을 곧바로 비활성으로 돌린다.
        for (PercentileSpec spec : percentileSpecs) {
            if (!registry.isRegistered(spec.sourceCode())) {
                throw new IllegalStateException(
                        "백분위의 원본 지표가 등록되어 있지 않다: " + spec.sourceCode()
                                + " — 계산기가 사라졌거나 코드를 잘못 적었다.");
            }
            upsertPercentile(spec);
            activeCodes.add(spec.code());
        }

        // 업종 상대값도 계산기가 없다(같은 이유 — 여러 종목을 봐야 정해진다).
        // 선언 하나가 지표 둘을 만든다: 상대값과 그 값을 낼 때 비교한 표본 수.
        for (SectorMetricSpec spec : sectorMetricSpecs) {
            if (!registry.isRegistered(spec.sourceCode())) {
                throw new IllegalStateException(
                        "업종 상대값의 원본 지표가 등록되어 있지 않다: " + spec.sourceCode()
                                + " — 계산기가 사라졌거나 코드를 잘못 적었다.");
            }
            upsertSectorMetric(spec);
            upsertSectorPeerCount(spec);
            activeCodes.add(spec.code());
            activeCodes.add(spec.peerCountCode());
        }

        deactivateMissing(activeCodes);
        return activeCodes.size();
    }

    /**
     * 업종 상대값의 카탈로그 행.
     *
     * <p>{@code source_indicator_code}를 <b>백분위에만</b> 건다. 그 링크가 화면에
     * "상위 20% = 0.2 이하" 안내를 만드는 근거인데, 중앙값 배수는 1.0이 중앙이라 그 안내가
     * 뜻을 잃는다(ADR-0044). 대신 배수는 스스로 유불리 방향을 갖는다 — 부채가 업종 대비
     * 가벼운 쪽이 좋으므로 낮은 쪽이 유리하다.
     */
    private void upsertSectorMetric(SectorMetricSpec spec) {
        boolean percentile = spec.kind() == SectorMetricSpec.Kind.PERCENTILE;
        upsertDerived(spec.code(), spec.name(), spec.description(), spec.interpretation(),
                categoryOf(spec.sourceCode()), IndicatorValueType.RATIO, percentile ? 4 : 2,
                // 백분위는 방향이 원본에 있고, 배수는 자기 자신이 들고 있다.
                percentile ? null : Boolean.FALSE,
                910, percentile ? spec.sourceCode() : null, IndicatorPopulation.SECTOR);
    }

    /**
     * 표본 수의 카탈로그 행.
     *
     * <p>결과 표 기본 컬럼이 <b>아니다.</b> 값이 아니라 값의 신뢰도를 말하는 수라 늘 보이면
     * 표가 시끄러워진다. 다만 카탈로그에 있으므로 사용자가 컬럼으로 켜거나 조건으로 걸 수 있고,
     * 그것이 "업종당 둘뿐인데 값은 나온다"를 스스로 확인할 수단이다.
     */
    private void upsertSectorPeerCount(SectorMetricSpec spec) {
        upsertDerived(spec.peerCountCode(),
                spec.name() + " 표본 수",
                "이 종목의 " + spec.name() + "을(를) 낼 때 실제로 비교한 같은 업종 종목 수. "
                        + "소분류 표본이 5 미만이면 중분류로 올라가며, 그 경우 이 수가 그 사실을 드러낸다.",
                "클수록 업종 비교가 통계적으로 든든하다. 5는 값을 낼 수 있는 최소치일 뿐이므로, "
                        + "5에 가까운 값은 업종 대비 순위를 느슨하게 읽어야 한다.",
                categoryOf(spec.sourceCode()), IndicatorValueType.COUNT, 0, Boolean.TRUE, 911,
                null, IndicatorPopulation.SECTOR);
    }

    /**
     * 계산기가 없는 파생 지표 행 하나.
     *
     * <p>{@link #upsertPercentile}과 컬럼 집합이 같아 한 자리로 모았다. 구간({@code bands})은
     * 넣지 않는다 — 여기 오는 값은 백분위·배수·개수라 자연어 구간이 붙을 자리가 아니다.
     *
     * <p>MUST: {@code category}를 여기서 정하지 않고 <b>원본 지표에서 받는다</b>(ADR-0057).
     * 예전에는 {@code 'DERIVED'} 리터럴이었는데, 사용자에게 "PER 업종 백분위"는 파생 계산이
     * 아니라 재무 지표다. {@code DO UPDATE SET}에 {@code category}가 함께 있어야 한다 —
     * 하나만 고치면 <b>이미 있는 행이 갱신되지 않아</b> 원본이 옮겨가도 파생만 제자리에 남는다.
     */
    private void upsertDerived(String code, String name, String description, String interpretation,
                               IndicatorCategory category, IndicatorValueType valueType,
                               int decimalScale, Boolean higherIsBetter, int sortOrder,
                               String sourceIndicatorCode, IndicatorPopulation population) {
        jdbcTemplate.update("""
                INSERT INTO indicator (code, name, description, interpretation, category, value_type,
                                       unit, decimal_scale, higher_is_better, is_active, sort_order,
                                       default_column, source_indicator_code, population,
                                       created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, true, ?, false, ?, ?, now(), now())
                ON CONFLICT (code) DO UPDATE SET
                    name                  = EXCLUDED.name,
                    description           = EXCLUDED.description,
                    interpretation        = EXCLUDED.interpretation,
                    category              = EXCLUDED.category,
                    value_type            = EXCLUDED.value_type,
                    decimal_scale         = EXCLUDED.decimal_scale,
                    higher_is_better      = EXCLUDED.higher_is_better,
                    is_active             = true,
                    sort_order            = EXCLUDED.sort_order,
                    source_indicator_code = EXCLUDED.source_indicator_code,
                    population            = EXCLUDED.population,
                    updated_at            = now()
                """,
                code, name, emptyToNull(description), emptyToNull(interpretation),
                category.name(), valueType.name(), decimalScale, higherIsBetter, sortOrder,
                sourceIndicatorCode, population == null ? null : population.name());
    }

    /**
     * 원본 지표의 분류. 백분위·업종 상대값이 그것을 물려받는다(ADR-0057).
     *
     * <p>호출부가 이미 {@code registry.isRegistered(sourceCode())}로 걸렀으므로 여기서 비면
     * 그 검사가 무너진 것이다 — 조용히 기본값을 주지 않고 던진다.
     */
    private IndicatorCategory categoryOf(String sourceCode) {
        IndicatorCalculator source = registry.find(sourceCode).orElseThrow(() ->
                new IllegalStateException("파생 지표의 원본이 등록되어 있지 않다: " + sourceCode));
        IndicatorMeta meta = AnnotationUtils.findAnnotation(source.getClass(), IndicatorMeta.class);
        if (meta == null) {
            throw new IllegalStateException(
                    source.getClass().getName() + "에 @IndicatorMeta가 없다 — 파생의 분류를 정할 수 없다.");
        }
        return meta.category();
    }

    /**
     * 백분위 지표의 카탈로그 행.
     *
     * <p>값이 항상 0~1의 비율이고 큰 쪽이 "원본 값이 크다"를 뜻하므로, 표시 속성은 원본과
     * 무관하게 고정이다. 유불리 방향을 {@code NEUTRAL}로 두는 이유는 원본 지표마다 다르고
     * ({@code PER_PCTL}은 낮은 쪽이, {@code ROE_PCTL}은 높은 쪽이 좋다) 그 판단은 원본
     * 지표의 {@code higher_is_better}가 이미 들고 있기 때문이다.
     */
    private void upsertPercentile(PercentileSpec spec) {
        upsertDerived(spec.code(), spec.name(), spec.description(), spec.interpretation(),
                categoryOf(spec.sourceCode()), IndicatorValueType.RATIO, 4,
                // 유불리 방향은 원본이 들고 있다. 여기서 정하면 두 곳이 갈린다.
                null, 900,
                // 부등호 방향 판단이 이 링크에 걸린다 — 화면은 원본의 higher_is_better를 보고
                // "상위 20%"가 >= 0.8인지 <= 0.2인지 정한다.
                spec.sourceCode(),
                IndicatorPopulation.UNIVERSE);
    }

    private void upsert(String code, IndicatorMeta meta) {
        jdbcTemplate.update("""
                INSERT INTO indicator (code, name, description, interpretation, category, value_type,
                                       unit, decimal_scale, higher_is_better, is_active, sort_order,
                                       default_column, bands, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?, CAST(? AS jsonb), now(), now())
                ON CONFLICT (code) DO UPDATE SET
                    name             = EXCLUDED.name,
                    description      = EXCLUDED.description,
                    interpretation   = EXCLUDED.interpretation,
                    category         = EXCLUDED.category,
                    value_type       = EXCLUDED.value_type,
                    unit             = EXCLUDED.unit,
                    decimal_scale    = EXCLUDED.decimal_scale,
                    higher_is_better = EXCLUDED.higher_is_better,
                    is_active        = true,
                    sort_order       = EXCLUDED.sort_order,
                    default_column   = EXCLUDED.default_column,
                    bands            = EXCLUDED.bands,
                    -- 계산기가 내는 값은 상대 지표가 아니다. 코드가 파생에서 계산기로 옮겨간
                    -- 경우에 옛 모집단이 남으면 화면이 없는 비교를 설명하게 된다.
                    population       = NULL,
                    updated_at       = now()
                """,
                code,
                meta.name(),
                emptyToNull(meta.description()),
                emptyToNull(meta.interpretation()),
                meta.category().name(),
                meta.valueType().name(),
                emptyToNull(meta.unit()),
                meta.decimalScale(),
                meta.direction().toHigherIsBetter(),
                meta.sortOrder(),
                meta.defaultColumn(),
                bandCodec.toJson(bandsOf(code, meta)));
    }

    /**
     * 애너테이션의 구간을 도메인 형태로 옮긴다.
     *
     * <p>애너테이션은 null을 담을 수 없어 무한대를 "열린 쪽"의 표식으로 쓴다. 여기서 null로
     * 되돌리지 않으면 저장된 JSON에 {@code "Infinity"}가 들어가 파싱이 깨진다.
     *
     * <p>구간이 겹치거나 순서가 어긋나면 던진다. 화면은 구간을 순서대로 나열하고 사용자는 그것을
     * 하나의 척도로 읽으므로, 겹친 구간은 <b>같은 값이 두 이름을 갖는 상태</b>다. 기동에 실패하는
     * 편이 낫다 — 눈으로는 드러나지 않는다.
     */
    private static List<IndicatorBand> bandsOf(String code, IndicatorMeta meta) {
        List<IndicatorBand> bands = new ArrayList<>();
        for (IndicatorMeta.Band band : meta.bands()) {
            bands.add(new IndicatorBand(band.label(), boundOf(band.from()), boundOf(band.to())));
        }
        for (int i = 1; i < bands.size(); i++) {
            IndicatorBand previous = bands.get(i - 1);
            IndicatorBand current = bands.get(i);
            if (previous.from() == null || current.to() == null
                    || previous.from().compareTo(current.to()) < 0) {
                throw new IllegalStateException(
                        code + "의 구간이 내림차순으로 이어지지 않는다: '" + previous.label()
                                + "'(하한 " + previous.from() + ") 다음이 '" + current.label()
                                + "'(상한 " + current.to() + ")이다. "
                                + "구간은 큰 값에서 작은 값 순으로 적고 서로 겹치지 않아야 한다.");
            }
        }
        return bands;
    }

    /** 무한대는 "그쪽으로 열려 있다"는 뜻이므로 경계가 없다. */
    private static BigDecimal boundOf(double value) {
        return Double.isInfinite(value) ? null : BigDecimal.valueOf(value);
    }

    private void deactivateMissing(List<String> activeCodes) {
        if (activeCodes.isEmpty()) {
            jdbcTemplate.update("UPDATE indicator SET is_active = false, updated_at = now() WHERE is_active");
            return;
        }
        String placeholders = String.join(",", activeCodes.stream().map(c -> "?").toList());
        jdbcTemplate.update(
                "UPDATE indicator SET is_active = false, updated_at = now() "
                        + "WHERE is_active AND code NOT IN (" + placeholders + ")",
                activeCodes.toArray());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
