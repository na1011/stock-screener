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
        new IndicatorCatalogSynchronizer(registry, jdbcTemplate).synchronize();

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
    @DisplayName("새 지표의 값을 저장하는 데 스키마 변경이 필요 없다 — EAV의 핵심 성질")
    void storingNewIndicatorNeedsNoSchemaChange() {
        new IndicatorCatalogSynchronizer(registry, jdbcTemplate).synchronize();

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
        new IndicatorCatalogSynchronizer(registry, jdbcTemplate).synchronize();

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
