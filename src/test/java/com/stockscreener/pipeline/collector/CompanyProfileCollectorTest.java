package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CompanyProfile;
import com.stockscreener.pipeline.datasource.CompanyProfileDataSource;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.IndustryScheme;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 업종 저장. 자릿수 파생이 <b>DB의 생성 컬럼 한 곳에서만</b> 일어나는지가 요점이다.
 *
 * <p>파생이 두 곳에서 일어나면 어긋나도 아무 에러가 나지 않고 종목이 다른 업종으로 묶인다.
 * 그 상태는 업종 백분위가 값을 내므로 화면으로는 드러나지 않는다(ADR-0044).
 */
@JpaSliceTest
class CompanyProfileCollectorTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;
    private SecurityRef security;

    @BeforeEach
    void insertStock() {
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '005930', '삼성전자')
                RETURNING id
                """, Long.class);
        security = new SecurityRef(stockId, "KOSPI", "005930");
    }

    @Test
    @DisplayName("3자리 코드는 소분류가 자기 자신이고 중분류가 앞 2자리다")
    void derivesKeysFromThreeDigitCode() throws Exception {
        assertThat(collectorWith(new StubProfileSource(profile("264", 12))).collect(security))
                .isEqualTo(1);

        assertThat(column("industry_code")).isEqualTo("264");
        assertThat(column("industry_group")).isEqualTo("264");
        assertThat(column("industry_division")).isEqualTo("26");
    }

    @Test
    @DisplayName("5자리 코드도 같은 규칙으로 갈린다 — 앞 3자리가 소분류다")
    void derivesKeysFromFiveDigitCode() throws Exception {
        collectorWith(new StubProfileSource(profile("64992", 12))).collect(security);

        assertThat(column("industry_code")).isEqualTo("64992");
        assertThat(column("industry_group")).isEqualTo("649");
        assertThat(column("industry_division"))
                .as("금융업 판정이 이 값에 걸린다")
                .isEqualTo("64");
    }

    @Test
    @DisplayName("같은 값을 다시 받으면 0행이다 — 그 0이 멱등의 증거다")
    void isIdempotent() throws Exception {
        CompanyProfileCollector collector = collectorWith(new StubProfileSource(profile("264", 12)));

        assertThat(collector.collect(security)).isEqualTo(1);
        assertThat(collector.collect(security)).isZero();
    }

    @Test
    @DisplayName("소스가 종목을 모르면 0행이고 예외가 아니다")
    void absentProfileIsNotAFailure() throws Exception {
        assertThat(collectorWith(new StubProfileSource(null)).collect(security)).isZero();
        assertThat(column("industry_code")).isNull();
    }

    @Test
    @DisplayName("4자리(세분류)도 같은 규칙으로 갈린다 — 350종목 실측에서 52종목이 이 형태다")
    void derivesKeysFromFourDigitCode() throws Exception {
        // 문서의 "3자리와 5자리"는 6개사 표본의 과소추정이었다. SK하이닉스 2612가 그 예다.
        collectorWith(new StubProfileSource(profile("2612", 12))).collect(security);

        assertThat(column("industry_group")).isEqualTo("261");
        assertThat(column("industry_division")).isEqualTo("26");
    }

    @Test
    @DisplayName("2자리 이하는 제약이 막는다 — 중분류를 소분류라 부르게 된다")
    void rejectsTooShortCode() {
        // 실패 격리 단위가 종목이므로 이 예외는 그 한 종목만 실패로 기록한다.
        CompanyProfileCollector collector = collectorWith(new StubProfileSource(profile("26", 12)));

        assertThatThrownBy(() -> collector.collect(security))
                .hasMessageContaining("ck_stock_industry_code");
    }

    @Test
    @DisplayName("체계가 SIC면 sic_code에 담긴다 — 어느 컬럼인지는 어댑터가 아니라 체계가 정한다")
    void routesSicToItsOwnColumn() throws Exception {
        // 국내 종목에 SIC를 넣으려 하면 시장 제약이 막는다. 그것이 이 분기의 방어선이고,
        // 아래 두 테스트가 그 제약을 각각 확인한다.
        long usId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('US', 'AAPL', 'Apple Inc.')
                RETURNING id
                """, Long.class);
        SecurityRef us = new SecurityRef(usId, "US", "AAPL");

        assertThat(collectorWith(new StubProfileSource(sicProfile("3571", 9), "US")).collect(us))
                .isEqualTo(1L);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT sic_code FROM stock WHERE id = ?", String.class, usId)).isEqualTo("3571");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT industry_code FROM stock WHERE id = ?", String.class, usId)).isNull();
        // 접두가 값에 구워져 국내와 같은 파티션에 못 들어간다(ADR-0092).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT industry_group_key FROM stock WHERE id = ?", String.class, usId))
                .isEqualTo("SIC:357");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT industry_division_key FROM stock WHERE id = ?", String.class, usId))
                .isEqualTo("SIC:35");
    }

    // 두 방향을 한 테스트에 묶지 않는다. 제약 위반이 트랜잭션을 abort시켜 뒤 문장이
    // "current transaction is aborted"로 죽고, 그러면 두 번째 방향을 실제로 재지 못한다.

    @Test
    @DisplayName("IMPORTANT — 국내 종목에 SIC를 선언하면 그 종목만 실패한다. 컬럼 선택으로는 못 막는다")
    void rejectsSicOnDomesticStock() {
        CompanyProfileCollector wrong =
                collectorWith(new StubProfileSource(sicProfile("6500", 12), "KOSPI"));
        assertThatThrownBy(() -> wrong.collect(security))
                .hasMessageContaining("ck_stock_sic_is_us");
    }

    @Test
    @DisplayName("반대 방향도 막는다 — 미국 종목에 KSIC를 선언하면 industry_code 쪽 제약이 걸린다")
    void rejectsKsicOnUsStock() {
        long usId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('US', 'MSFT', 'Microsoft')
                RETURNING id
                """, Long.class);
        SecurityRef us = new SecurityRef(usId, "US", "MSFT");
        CompanyProfileCollector wrong =
                collectorWith(new StubProfileSource(profile("26111", 12), "US"));
        assertThatThrownBy(() -> wrong.collect(us))
                .hasMessageContaining("ck_stock_ksic_is_not_us");
    }

    @Test
    @DisplayName("SIC는 네 자리만 통과한다 — 소스가 앞자리 0을 채워 주므로 가공할 일이 없다")
    void rejectsSicThatIsNotFourDigits() throws Exception {
        long usId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('US', 'LMNR', 'Limoneira')
                RETURNING id
                """, Long.class);
        SecurityRef us = new SecurityRef(usId, "US", "LMNR");

        // 앞자리가 0인 코드는 정상이다(실측: LMNR=0100).
        assertThat(collectorWith(new StubProfileSource(sicProfile("0100", 10), "US")).collect(us))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT industry_division_key FROM stock WHERE id = ?", String.class, usId))
                .isEqualTo("SIC:01");

    }

    @Test
    @DisplayName("세 자리 SIC는 제약이 막는다 — 잘려 온 것을 담으면 중분류를 소분류라 부른다")
    void rejectsThreeDigitSic() {
        long usId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('US', 'ALCO', 'Alico')
                RETURNING id
                """, Long.class);
        SecurityRef us = new SecurityRef(usId, "US", "ALCO");
        CompanyProfileCollector threeDigits =
                collectorWith(new StubProfileSource(sicProfile("100", 10), "US"));
        assertThatThrownBy(() -> threeDigits.collect(us))
                .hasMessageContaining("ck_stock_sic_code");
    }

    private CompanyProfileCollector collectorWith(CompanyProfileDataSource source) {
        DataSourceRegistry registry = new DataSourceRegistry(
                List.of(), List.of(), List.of(), List.of(), List.of(source), List.of(), List.of(),
                List.of(),
                List.of(),
                List.of());
        return new CompanyProfileCollector(registry, jdbcTemplate);
    }

    private String column(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT " + name + " FROM stock WHERE id = ?", String.class, stockId);
    }

    private static CompanyProfile profile(String industryCode, Integer accountingMonth) {
        return new CompanyProfile(industryCode, IndustryScheme.KSIC, accountingMonth, "Y");
    }

    private static CompanyProfile sicProfile(String sicCode, Integer accountingMonth) {
        return new CompanyProfile(sicCode, IndustryScheme.SIC, accountingMonth, null);
    }

    /** 개황 소스를 고정한다. 이 테스트의 관심사는 저장과 파생이다. */
    private record StubProfileSource(CompanyProfile profile, String market)
            implements CompanyProfileDataSource {

        StubProfileSource(CompanyProfile profile) {
            this(profile, "KOSPI");
        }

        @Override
        public Set<String> supportedMarketCodes() {
            return Set.of(market);
        }

        @Override
        public String sourceName() {
            return "stub";
        }

        @Override
        public Optional<CompanyProfile> fetchProfile(SecurityRef security)
                throws DataSourceException {
            return Optional.ofNullable(profile);
        }
    }
}
