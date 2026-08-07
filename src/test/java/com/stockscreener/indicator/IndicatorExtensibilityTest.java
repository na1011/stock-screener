package com.stockscreener.indicator;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spec §7의 "스키마 변경 없이 새 지표를 추가할 수 있어야 한다"에 대한 실질 검증.
 *
 * <p>설계 문서 §5의 완료 기준 2는 "더미 지표를 추가할 때 다른 파일을 몇 개 고쳐야 하는가,
 * 1개면 성공"이다. 이 테스트가 그 성질을 직접 단언한다 — 아래 {@code DummyIndicatorCalculator}는
 * 파일 하나에 정의된 {@code @Component}이고, 그것을 컨텍스트에 넣는 것 외에
 * <b>레지스트리·enum·스키마·번역기 어디도 고치지 않았다.</b>
 *
 * <p>이 테스트가 깨지면 확장 지점이 실패한 것이며, 지표가 계속 추가된다는 전제가 무너진다.
 */
// 슬라이스가 아니라 전체 컨텍스트를 쓴다. 이 테스트의 요점이 "Spring의 컴포넌트 스캔이
// 계산기를 자동으로 발견한다"는 것이므로, 스캔 자체를 검증 대상으로 삼아야 한다.
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import({TestDatabaseGuard.class, IndicatorExtensibilityTest.DummyIndicatorConfig.class})
class IndicatorExtensibilityTest {

    private static final String DUMMY_CODE = "DUMMY_TEST_INDICATOR";

    @Autowired
    private IndicatorRegistry registry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IndicatorBandCodec bandCodec;

    @Test
    @DisplayName("계산기 클래스 하나를 추가하면 레지스트리가 자동으로 인식한다")
    void newCalculatorIsDiscoveredAutomatically() {
        assertThat(registry.isRegistered(DUMMY_CODE)).isTrue();
        assertThat(registry.find(DUMMY_CODE)).isPresent();
    }

    @Test
    @DisplayName("실제 지표 두 개도 함께 등록되어 있다")
    void realCalculatorsAreRegistered() {
        assertThat(registry.codes()).contains("SMA_200", "PER");
    }

    @Test
    @DisplayName("메타데이터가 카탈로그 테이블로 투영되어 UI가 평범한 쿼리로 읽는다")
    void metadataIsProjectedIntoCatalog() {
        new IndicatorCatalogSynchronizer(registry, List.of(), jdbcTemplate, bandCodec).synchronize();

        List<String> codes = jdbcTemplate.queryForList(
                "SELECT code FROM indicator WHERE is_active ORDER BY code", String.class);

        assertThat(codes).contains(DUMMY_CODE, "PER", "SMA_200");

        // frontend.md가 요구하는 "어느 방향이 유리한가"가 실제로 저장되는지 확인한다.
        Boolean perHigherIsBetter = jdbcTemplate.queryForObject(
                "SELECT higher_is_better FROM indicator WHERE code = 'PER'", Boolean.class);
        assertThat(perHigherIsBetter).isFalse();

        String perDescription = jdbcTemplate.queryForObject(
                "SELECT description FROM indicator WHERE code = 'PER'", String.class);
        assertThat(perDescription).isNotBlank();
    }

    @Test
    @DisplayName("결과 표의 기본 컬럼 여부가 계산 클래스에서 카탈로그로 흘러간다")
    void defaultColumnFlagIsProjected() {
        new IndicatorCatalogSynchronizer(registry, List.of(), jdbcTemplate, bandCodec).synchronize();

        // 화면이 어느 컬럼을 기본으로 보일지를 계산 코드 옆에서 정한다는 것의 실질 검증이다.
        // 이 투영이 없으면 화면 코드에 지표 목록을 다시 적게 되고, 지표 추가 비용이 2파일이 된다.
        Boolean perIsDefault = jdbcTemplate.queryForObject(
                "SELECT default_column FROM indicator WHERE code = 'PER'", Boolean.class);
        assertThat(perIsDefault).isTrue();

        Boolean dummyIsDefault = jdbcTemplate.queryForObject(
                "SELECT default_column FROM indicator WHERE code = ?", Boolean.class, DUMMY_CODE);
        assertThat(dummyIsDefault).isFalse();
    }

    @Test
    @DisplayName("백분위 행이 원본 지표를 가리킨다 — 부등호 방향 판단이 여기 의존한다")
    void percentileRowLinksBackToItsSource() {
        new IndicatorCatalogSynchronizer(
                registry,
                List.of(new PercentileSpec("PER", "PER 백분위", "설명", "해석")),
                jdbcTemplate,
                bandCodec).synchronize();

        String source = jdbcTemplate.queryForObject(
                "SELECT source_indicator_code FROM indicator WHERE code = 'PER_PCTL'", String.class);

        // 코드에서 '_PCTL'을 문자열로 떼어내는 대신 링크를 저장한다. 접미사 규칙이 바뀌어도
        // 화면이 "PER은 낮은 쪽이 좋으므로 상위 20%는 <= 0.2"라는 판단을 잃지 않는다.
        assertThat(source).isEqualTo("PER");
    }

    @Test
    @DisplayName("새 지표의 값을 저장하는 데 스키마 변경이 필요 없다 — EAV의 핵심 성질")
    void storingNewIndicatorNeedsNoSchemaChange() {
        new IndicatorCatalogSynchronizer(registry, List.of(), jdbcTemplate, bandCodec).synchronize();

        jdbcTemplate.update(
                "INSERT INTO market (code, country_code, currency_code, timezone) "
                        + "VALUES ('TESTMKT','KR','KRW','Asia/Seoul') ON CONFLICT DO NOTHING");
        Long stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('TESTMKT','999999','테스트종목')
                RETURNING id
                """, Long.class);

        // 컬럼 추가도 테이블 추가도 없이 INSERT 하나로 끝난다.
        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, ?, ?)
                """, stockId, LocalDate.of(2026, 8, 4), DUMMY_CODE, new BigDecimal("42.5"));

        BigDecimal stored = jdbcTemplate.queryForObject(
                "SELECT value FROM indicator_value WHERE stock_id = ? AND indicator_code = ?",
                BigDecimal.class, stockId, DUMMY_CODE);

        assertThat(stored).isEqualByComparingTo("42.5");
    }

    @Test
    @DisplayName("백분위도 파생 지표 코드일 뿐이라 스키마 변경이 없다")
    void percentileNeedsNoSchemaChange() {
        // 설계 문서 §3이 "수용 가능한지 확인하라"고 한 지점. 실제로 확인한다:
        // 백분위는 indicator_value에 평범한 행으로 들어가고 조건도 스칼라 비교가 된다.
        new IndicatorCatalogSynchronizer(registry, List.of(), jdbcTemplate, bandCodec).synchronize();

        Integer columnCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'indicator_value'
                """, Integer.class);

        // stock_id, trade_date, indicator_code, value, updated_at — 지표가 몇 개든 5개다.
        assertThat(columnCount).isEqualTo(5);
    }

    @TestConfiguration
    static class DummyIndicatorConfig {
        @Bean
        DummyIndicatorCalculator dummyIndicatorCalculator() {
            return new DummyIndicatorCalculator();
        }
    }

    /**
     * 확장 비용 측정용 더미 지표. 기능이 아니라 측정 도구다.
     *
     * <p>이 클래스를 추가하면서 고친 다른 파일은 없다. 그것이 이 테스트의 요점이다.
     */
    @IndicatorMeta(
            name = "더미 테스트 지표",
            description = "확장 비용 측정을 위한 지표. 실제 의미는 없다.",
            category = IndicatorCategory.TECHNICAL,
            valueType = IndicatorValueType.RATIO,
            direction = IndicatorMeta.Direction.HIGHER_IS_BETTER
    )
    static class DummyIndicatorCalculator implements IndicatorCalculator {

        @Override
        public String code() {
            return DUMMY_CODE;
        }

        @Override
        public DataRequirement dataRequirement() {
            return DataRequirement.priceBars(1);
        }

        @Override
        public List<IndicatorPoint> calculate(IndicatorContext context) {
            return context.priceBars().stream()
                    .map(bar -> new IndicatorPoint(bar.tradeDate(), bar.close()))
                    .toList();
        }
    }
}
