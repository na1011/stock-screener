package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.GovernanceEndpoint;
import com.stockscreener.pipeline.datasource.GovernanceReportRow;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미국 배당 어댑터 관통테스트. <b>실제 API를 호출한다</b>(실행당 2콜).
 *
 * <p>이 테스트가 지키는 것은 "우리 유도가 맞는가"가 아니라 <b>"벤더가 아직 그대로인가"</b>다.
 * 유도는 {@link AlpacaGovernanceFactMapperTest}가, 소스의 모양은
 * {@link AlpacaDividendProbeLiveTest}가 잰다.
 *
 * <p><b>둘째 시험이 이 파일의 존재 이유다.</b> 「그 해에 배당이 없었다」를 <b>빈 목록이 아니라
 * 행으로</b> 돌려주는 것이 이 어댑터의 계약인데, 목으로는 그것을 증명할 수 없다 — 목은 우리가
 * 무엇을 보내든 같은 응답을 준다. <b>실제로 배당이 없는 종목·해</b>에서만 드러난다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+",
        disabledReason = "APCA_API_KEY_ID가 없어 건너뛴다")
class AlpacaDividendLiveTest {

    /** 2024년에 분기배당 넷을 냈다(실측: 0.24 · 0.25 × 3 = 0.99). */
    private static final SecurityRef APPLE = new SecurityRef(1L, "US", "AAPL");

    /** 배당을 한 번도 안 한다. 「사건 0건」이 행으로 오는지를 이 종목이 답한다. */
    private static final SecurityRef TESLA = new SecurityRef(2L, "US", "TSLA");

    @Test
    @DisplayName("[live] 결제마다 한 행이 오고 효력일·금액이 원문 그대로 실린다")
    void fetchesEachPaymentAsARow() throws Exception {
        List<GovernanceReportRow> rows = adapter().fetchReport(
                APPLE, GovernanceEndpoint.DIVIDEND, 2024, AlpacaDividendAdapter.REPORT_CODE);

        System.out.println("\n===== AAPL 2024 cash_dividends =====");
        rows.forEach(row -> System.out.printf("  %s rate=%s special=%s foreign=%s%n",
                row.publishedAt(), row.field("rate"),
                row.field("special"), row.field("foreign")));

        assertThat(rows)
                .as("소스가 필드 이름이나 봉투 모양을 바꿨으면 여기서 0건이 된다")
                .hasSize(4)
                .allSatisfy(row -> {
                    assertThat(row.isNoData()).isFalse();
                    assertThat(row.bsnsYear())
                            .as("역년으로 걸러 담는다 — 요청 연도가 아니라 ex_date의 해다")
                            .isEqualTo(2024);
                    assertThat(row.publishedAt()).isNotNull();
                    assertThat(row.receiptNo())
                            .as("정정본 선택이 문자열 비교라 숫자 여덟 자리여야 한다")
                            .hasSize(8).containsOnlyDigits();
                    assertThat(new BigDecimal(row.field("rate"))).isPositive();
                });
    }

    @Test
    @DisplayName("[live] 배당이 없던 해는 빈 목록이 아니라 「사건 0건」 행으로 온다")
    void marksYearsWithoutPaymentsInsteadOfReturningNothing() throws Exception {
        List<GovernanceReportRow> rows = adapter().fetchReport(
                TESLA, GovernanceEndpoint.DIVIDEND, 2024, AlpacaDividendAdapter.REPORT_CODE);

        assertThat(rows)
                .as("빈 목록으로 접으면 「아직 안 받았다」와 구별되지 않는다")
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.isNoData()).isTrue();
                    assertThat(row.receiptNo())
                            .as("값을 줘야 국내용 스탬프 경로와 미스탬프 집계가 이 행을 안 건드린다")
                            .isEqualTo("20241231");
                    assertThat(row.publishedAt())
                            .as("없는 날짜를 지어내지 않는다")
                            .isNull();
                });
    }

    private AlpacaDividendAdapter adapter() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getAlpaca().setApiKey(System.getenv("APCA_API_KEY_ID"));
        properties.getAlpaca().setSecretKey(System.getenv("APCA_API_SECRET_KEY"));
        return new AlpacaDividendAdapter(
                new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("alpaca", 0, 0));
    }
}
