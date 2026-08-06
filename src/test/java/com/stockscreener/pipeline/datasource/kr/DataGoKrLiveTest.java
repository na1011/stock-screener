package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.datasource.SecurityDescriptor;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 공공데이터포털 관통테스트. <b>실제 API를 호출한다.</b>
 *
 * <p>{@code ./gradlew liveTest}로만 실행되며 기본 {@code test}에서는 제외된다. 이유가 둘이다 —
 * 네트워크가 없거나 벤더가 점검 중이면 무관한 테스트까지 빨간불이 되고, 매 실행이
 * 초기 적재에 쓸 일일 쿼터를 태운다.
 *
 * <p>키가 없으면 스스로 스킵한다(실패가 아니다).
 *
 * <p>이 테스트가 지키는 것은 "우리 파싱이 맞는가"가 아니라 <b>"벤더가 아직 그대로인가"</b>다.
 * 파싱은 녹화된 fixture로 {@link DataGoKrPriceAdapterTest}가 검증한다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DATA_GO_KR_SERVICE_KEY", matches = ".+",
        disabledReason = "DATA_GO_KR_SERVICE_KEY가 없어 건너뛴다")
class DataGoKrLiveTest {

    private static final SecurityRef SAMSUNG = new SecurityRef(1L, "KOSPI", "005930");

    @Test
    @DisplayName("[live] 종목별 기간조회가 봉을 반환한다 — 초기 적재를 350콜로 만드는 전제다")
    void fetchesDateRangeInOneCall() throws Exception {
        LocalDate to = LocalDate.now().minusDays(1);
        LocalDate from = to.minusDays(14);

        List<RawPriceBar> bars = priceAdapter().fetchDailyBars(SAMSUNG, from, to);

        assertThat(bars)
                .as("2주 구간이면 최소 몇 거래일은 있어야 한다")
                .isNotEmpty();
        assertThat(bars).extracting(RawPriceBar::tradeDate).isSorted();
        assertThat(bars).allSatisfy(bar -> {
            assertThat(bar.isValid()).as("OHLC 정합성").isTrue();
            assertThat(bar.close().signum()).isPositive();
            // KRX는 거래대금을 제공한다. 사라지면 파생 지표 계획이 바뀐다.
            assertThat(bar.tradeValue()).as("거래대금이 사라졌는지 확인").isNotNull();
        });
    }

    @Test
    @DisplayName("[live] 미등록 키는 AUTH로 매핑된다 — 봉투가 정상 응답과 다르다")
    void mapsUnregisteredKeyToAuth() {
        DataGoKrPriceAdapter adapter = priceAdapter("DEFINITELY_NOT_A_VALID_KEY");

        assertThatThrownBy(() -> adapter.fetchDailyBars(
                SAMSUNG, LocalDate.now().minusDays(7), LocalDate.now().minusDays(1)))
                .isInstanceOf(DataSourceException.class)
                .satisfies(e -> assertThat(((DataSourceException) e).kind())
                        .isEqualTo(DataSourceException.Kind.AUTH));
    }

    @Test
    @DisplayName("[live] 종목 마스터에서 이름과 시장을 얻는다")
    void fetchesSecurityMaster() throws Exception {
        Optional<SecurityDescriptor> found =
                masterAdapter().findByTicker("KOSPI", SAMSUNG.ticker());

        assertThat(found).isPresent();
        assertThat(found.get().name()).isNotBlank();
        assertThat(found.get().marketCode()).isIn("KOSPI", "KOSDAQ");
    }

    @Test
    @DisplayName("[live] 없는 종목은 빈 결과다 — 예외가 아니다")
    void unknownTickerYieldsEmpty() throws Exception {
        assertThat(masterAdapter().findByTicker("KOSPI", "999999")).isEmpty();
    }

    private DataGoKrPriceAdapter priceAdapter() {
        return priceAdapter(System.getenv("DATA_GO_KR_SERVICE_KEY"));
    }

    private DataGoKrPriceAdapter priceAdapter(String apiKey) {
        return new DataGoKrPriceAdapter(
                new ExternalApiClientFactory(), propertiesWith(apiKey),
                new TokenBucketRateLimiter("data-go-kr", 120, 0));
    }

    private DataGoKrSecurityMasterAdapter masterAdapter() {
        // 빈 캐시를 준다 — 이 테스트의 목적이 실제 API 응답 확인이므로 캐시가 가로채면 안 된다.
        return new DataGoKrSecurityMasterAdapter(
                new ExternalApiClientFactory(),
                propertiesWith(System.getenv("DATA_GO_KR_SERVICE_KEY")),
                new TokenBucketRateLimiter("data-go-kr", 120, 0),
                new SecurityNameCache());
    }

    private static DataSourceProperties propertiesWith(String apiKey) {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getDataGoKr().setApiKey(apiKey == null ? "" : apiKey);
        return properties;
    }
}
