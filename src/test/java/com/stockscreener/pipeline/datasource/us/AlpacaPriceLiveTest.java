package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미국 시세 어댑터 관통테스트. <b>실제 API를 호출한다</b>(실행당 2콜).
 *
 * <p>이 테스트가 지키는 것은 "우리 파싱이 맞는가"가 아니라 <b>"벤더가 아직 그대로인가"</b>다.
 * 파싱은 녹화된 fixture로 {@link AlpacaPriceAdapterTest}가 검증하고, 소스의 모양은
 * {@link AlpacaBarsProbeLiveTest}가 잰다. 여기는 <b>어댑터가 실제 소스에 통하는가</b>다.
 *
 * <p>둘째 시험이 이 파일의 존재 이유다. {@code to = 오늘}은 <b>실제 운영 경로</b>이고
 * ({@code InitialLoadJob}이 {@code asOf}를 그대로 넘긴다) 날짜를 그대로 보내면 403이다.
 * 어댑터가 {@code end}를 클램프하는 것을 <b>목으로는 증명할 수 없다</b> — 목은 무엇을 보내든
 * 200을 주기 때문이다. 그 403은 {@code AUTH}이고 {@code fatal()}이라 배치 전체를 세운다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+",
        disabledReason = "APCA_API_KEY_ID가 없어 건너뛴다")
class AlpacaPriceLiveTest {

    private static final SecurityRef AAPL = new SecurityRef(1L, "US", "AAPL");

    @Test
    @DisplayName("[live] 구간 조회가 봉을 반환하고, 소스가 안 주는 값은 여전히 안 온다")
    void fetchesDateRange() throws Exception {
        List<RawPriceBar> bars = adapter().fetchDailyBars(
                AAPL, LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 31));

        System.out.printf("%nAAPL 2024-01 = %d봉 (첫 %s / 마지막 %s)%n",
                bars.size(), bars.get(0).tradeDate(), bars.get(bars.size() - 1).tradeDate());

        assertThat(bars).as("1월 미국 거래일은 20일 안팎이다").hasSizeGreaterThan(15);
        assertThat(bars).extracting(RawPriceBar::tradeDate).isSorted();
        assertThat(bars).allSatisfy(bar -> {
            assertThat(bar.isValid()).as("OHLC 정합성").isTrue();
            assertThat(bar.close().signum()).isPositive();
            assertThat(bar.tradeValue()).as("거래대금이 생겼는지 확인 — 생기면 합성 금지 규칙을 다시 본다")
                    .isNull();
            assertThat(bar.listedShares()).as("상장주식수가 생겼는지 확인 — 생기면 ADR-0013의 폴백이 바뀐다")
                    .isNull();
            assertThat(bar.splitAdjustedBySource())
                    .as("adjustment=raw를 보내므로 언제나 false다").isFalse();
        });
    }

    @Test
    @DisplayName("[live] IMPORTANT — to가 오늘이어도 403이 아니다. end 클램프가 실제로 도는지")
    void clampsEndSoTodayDoesNotTripTheFifteenMinuteRule() throws Exception {
        // 날짜를 그대로 보내면 오늘도 어제도 403이다(탐침 실측). 그 403은 AUTH이고
        // fatal()이라 종목 하나가 아니라 배치 전체가 죽는다.
        LocalDate today = LocalDate.now();

        List<RawPriceBar> bars = adapter().fetchDailyBars(AAPL, today.minusDays(10), today);

        System.out.printf("AAPL 최근 10일(to=%s) = %d봉%n", today, bars.size());
        assertThat(bars)
                .as("예외 없이 여기 도달하는 것이 이 시험의 실질이다 — 주말·연휴면 적을 수 있다")
                .isNotNull();
    }

    private static AlpacaPriceAdapter adapter() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getAlpaca().setApiKey(System.getenv("APCA_API_KEY_ID"));
        properties.getAlpaca().setSecretKey(System.getenv("APCA_API_SECRET_KEY"));
        return new AlpacaPriceAdapter(
                new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("alpaca", 0, 0));
    }
}
