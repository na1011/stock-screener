package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CompanyProfile;
import com.stockscreener.pipeline.datasource.CompanyProfileDataSource;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
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

    private CompanyProfileCollector collectorWith(CompanyProfileDataSource source) {
        DataSourceRegistry registry = new DataSourceRegistry(
                List.of(), List.of(), List.of(), List.of(source), List.of(), List.of(), List.of());
        return new CompanyProfileCollector(registry, jdbcTemplate);
    }

    private String column(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT " + name + " FROM stock WHERE id = ?", String.class, stockId);
    }

    private static CompanyProfile profile(String industryCode, Integer accountingMonth) {
        return new CompanyProfile(industryCode, accountingMonth, "Y");
    }

    /** 개황 소스를 고정한다. 이 테스트의 관심사는 저장과 파생이다. */
    private record StubProfileSource(CompanyProfile profile) implements CompanyProfileDataSource {

        @Override
        public Set<String> supportedMarketCodes() {
            return Set.of("KOSPI");
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
