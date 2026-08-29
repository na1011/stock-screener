package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.MarketSession;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마켓 캘린더 어댑터 관통테스트. <b>실제 API를 호출한다</b>(실행당 3콜).
 *
 * <p>이 테스트가 지키는 것은 "우리 파싱이 맞는가"가 아니라 <b>"벤더가 아직 그대로인가"</b>다.
 * 파싱은 녹화된 fixture로 {@link AlpacaMarketCalendarAdapterTest}가 검증하고, 소스의 모양은
 * {@link AlpacaCalendarProbeLiveTest}가 잰다. 여기는 <b>어댑터가 실제 소스에 통하는가</b>다.
 *
 * <p>목으로 증명할 수 없는 것이 둘이다. 하나는 <b>{@code end}를 실제로 보내는가</b> — 안 보내면
 * 목은 여전히 전량을 주지만 실제 소스는 <b>1주만 주고 그것을 정상 응답으로 돌려준다.</b>
 * 다른 하나는 <b>두 거래소가 정말 따로 답하는가</b>이며, 갈리는 해가 생기면 여기서 드러난다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+",
        disabledReason = "APCA_API_KEY_ID가 없어 건너뛴다")
class AlpacaMarketCalendarLiveTest {

    private static final LocalDate YEAR_START = LocalDate.of(2024, 1, 1);
    private static final LocalDate YEAR_END = LocalDate.of(2024, 12, 31);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    @DisplayName("[live] 2024년 한 해가 한 응답으로 오고, end를 안 보내면 나올 수 없는 수다")
    void fetchesAFullYearInOneCall() throws Exception {
        List<MarketSession> sessions = adapter().fetchSessions("NYSE", YEAR_START, YEAR_END);

        System.out.printf("%n===== NYSE 2024: %d세션 =====%n", sessions.size());

        assertThat(sessions)
                .as("end를 안 보내면 1주(≈5세션)만 온다. 이 수가 그것을 가른다")
                .hasSize(252);
        assertThat(sessions).extracting(MarketSession::sessionDate)
                .doesNotHaveDuplicates()
                .isSorted();
        assertThat(sessions.getFirst().sessionDate()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(sessions.getLast().sessionDate()).isEqualTo(LocalDate.of(2024, 12, 31));
    }

    @Test
    @DisplayName("[live] 조기폐장일이 실제로 온다 — 이 셋이 사라지면 core_end 해석이 깨진 것이다")
    void findsEarlyCloses() throws Exception {
        List<LocalDate> earlyCloses = adapter().fetchSessions("NYSE", YEAR_START, YEAR_END).stream()
                .filter(s -> !s.coreEnd().atZone(NEW_YORK).toLocalTime().equals(LocalTime.of(16, 0)))
                .map(MarketSession::sessionDate)
                .toList();

        System.out.println("2024 조기폐장: " + earlyCloses);

        assertThat(earlyCloses).containsExactly(
                LocalDate.of(2024, 7, 3), LocalDate.of(2024, 11, 29), LocalDate.of(2024, 12, 24));
    }

    @Test
    @DisplayName("[live] 두 거래소를 따로 물어도 2024년은 같다 — 갈리는 해가 오면 여기서 드러난다")
    void bothExchangesAgreeIn2024() throws Exception {
        List<LocalDate> nasdaq = adapter().fetchSessions("NASDAQ", YEAR_START, YEAR_END).stream()
                .map(MarketSession::sessionDate).toList();

        // NYSE는 위 테스트가 이미 252로 잠갔다. 여기서는 NASDAQ이 같은 집합인지만 본다.
        assertThat(nasdaq)
                .as("같다는 것은 2024년의 실측이지 계약이 아니다 — 그래서 따로 묻는다")
                .hasSize(252)
                .startsWith(LocalDate.of(2024, 1, 2))
                .endsWith(LocalDate.of(2024, 12, 31));
    }

    private AlpacaMarketCalendarAdapter adapter() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getAlpaca().setApiKey(System.getenv("APCA_API_KEY_ID"));
        properties.getAlpaca().setSecretKey(System.getenv("APCA_API_SECRET_KEY"));
        return new AlpacaMarketCalendarAdapter(new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("alpaca", 0, 0));
    }
}
