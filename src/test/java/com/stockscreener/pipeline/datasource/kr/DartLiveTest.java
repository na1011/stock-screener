package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DART 관통테스트. <b>실제 API를 호출한다.</b>
 *
 * <p>{@code ./gradlew liveTest}로만 실행된다. 키가 없으면 스스로 스킵한다.
 *
 * <p>벤더가 아직 그대로인지를 지킨다 — 계정명이 바뀌거나 corp_code 매핑이 깨지면
 * 여기서 드러난다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartLiveTest {

    private static final SecurityRef SAMSUNG = new SecurityRef(1L, "KOSPI", "005930");

    /** 직전 회계연도. 사업보고서가 확실히 공시된 시점을 고른다. */
    private static final int SETTLED_YEAR = LocalDate.now().getYear() - 1;

    @Test
    @DisplayName("[live] 종목코드를 corp_code로 매핑한다 — 상장사가 수천 건이어야 한다")
    void mapsStockCodeToCorpCode() throws Exception {
        DartCorpCodeCache cache = corpCodeCache();

        Optional<String> corpCode = cache.findCorpCode(SAMSUNG.ticker());

        assertThat(corpCode).isPresent();
        assertThat(corpCode.get()).hasSize(8);
        assertThat(cache.size())
                .as("상장사 규모가 급변하면 파싱이 깨졌을 수 있다")
                .isGreaterThan(2000);
    }

    @Test
    @DisplayName("[live] 사업보고서에서 6개 계정을 모두 채운다 — 계정명이 바뀌면 여기서 드러난다")
    void fillsAllSixAccounts() throws Exception {
        List<RawFinancialReport> reports = adapter().fetchReports(SAMSUNG, SETTLED_YEAR).reports();

        RawFinancialReport annual = reports.stream()
                .filter(r -> r.fiscalYear() == SETTLED_YEAR && "FY".equals(r.fiscalPeriod()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        SETTLED_YEAR + " 사업보고서를 받지 못했다. 받은 것: " + reports.stream()
                                .map(r -> r.fiscalYear() + "/" + r.fiscalPeriod()).toList()));

        assertThat(annual.revenue()).as("매출액").isNotNull();
        assertThat(annual.operatingIncome()).as("영업이익").isNotNull();
        assertThat(annual.netIncome()).as("당기순이익").isNotNull();
        assertThat(annual.totalAssets()).as("자산총계").isNotNull();
        assertThat(annual.totalLiabilities()).as("부채총계").isNotNull();
        assertThat(annual.totalEquity()).as("자본총계").isNotNull();

        // 회계 항등식. 매핑이 뒤섞이면 여기서 깨진다.
        assertThat(annual.totalAssets())
                .as("자산총계 ≈ 부채총계 + 자본총계")
                .isCloseTo(annual.totalLiabilities().add(annual.totalEquity()),
                        org.assertj.core.data.Offset.offset(
                                annual.totalAssets().multiply(new BigDecimal("0.01"))));
    }

    @Test
    @DisplayName("[live] Q4를 유도하고 값이 온전하다 — FY보다 작고 양수여야 한다")
    void derivesSaneFourthQuarter() throws Exception {
        List<RawFinancialReport> reports = adapter().fetchReports(SAMSUNG, SETTLED_YEAR).reports();

        Optional<RawFinancialReport> q4 = reports.stream()
                .filter(r -> r.fiscalYear() == SETTLED_YEAR && "Q4".equals(r.fiscalPeriod()))
                .findFirst();
        Optional<RawFinancialReport> annual = reports.stream()
                .filter(r -> r.fiscalYear() == SETTLED_YEAR && "FY".equals(r.fiscalPeriod()))
                .findFirst();

        assertThat(q4).as("FY와 Q3가 있으면 Q4가 유도되어야 한다").isPresent();
        assertThat(q4.get().revenue()).isNotNull();
        assertThat(q4.get().revenue().signum())
                .as("Q4 매출이 음수면 누적/단독 컬럼을 뒤바꾼 것이다")
                .isPositive();
        assertThat(q4.get().revenue()).isLessThan(annual.orElseThrow().revenue());
        // 재무상태표는 차감하지 않으므로 연말 잔액과 같아야 한다.
        assertThat(q4.get().totalAssets()).isEqualByComparingTo(annual.get().totalAssets());
    }

    @Test
    @DisplayName("[live] 잘못된 키는 AUTH다 — DART는 인증 실패도 HTTP 200으로 준다")
    void mapsInvalidKeyToAuth() {
        DartFinancialAdapter invalid = adapterWithKey("0000000000000000000000000000000000000000");

        assertThatThrownBy(() -> invalid.fetchReports(SAMSUNG, SETTLED_YEAR))
                .isInstanceOf(DataSourceException.class)
                .satisfies(e -> assertThat(((DataSourceException) e).kind())
                        .isEqualTo(DataSourceException.Kind.AUTH));
    }

    @Test
    @DisplayName("[live] 아주 오래된 연도는 데이터 없음으로 조용히 비어 있다")
    void ancientYearYieldsNothing() throws Exception {
        // 2010년은 DART 재무 API 제공 범위(2015~) 밖이다.
        List<RawFinancialReport> reports = adapter().fetchReports(
                new SecurityRef(1L, "KOSPI", "005930"), 2010).reports().stream()
                .filter(r -> r.fiscalYear() == 2010)
                .toList();

        assertThat(reports).isEmpty();
    }

    private DartFinancialAdapter adapter() {
        return adapterWithKey(System.getenv("DART_API_KEY"));
    }

    private DartFinancialAdapter adapterWithKey(String apiKey) {
        return new DartFinancialAdapter(
                new ExternalApiClientFactory(), propertiesWith(apiKey),
                new TokenBucketRateLimiter("dart", 300, 0), corpCodeCache());
    }

    private DartCorpCodeCache corpCodeCache() {
        return new DartCorpCodeCache(
                new ExternalApiClientFactory(),
                propertiesWith(System.getenv("DART_API_KEY")),
                new TokenBucketRateLimiter("dart", 300, 0));
    }

    private static DataSourceProperties propertiesWith(String apiKey) {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getDart().setApiKey(apiKey == null ? "" : apiKey);
        return properties;
    }
}
