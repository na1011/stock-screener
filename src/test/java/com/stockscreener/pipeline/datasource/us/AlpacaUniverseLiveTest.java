package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.SecurityDescriptor;
import com.stockscreener.pipeline.datasource.SecurityNameCache;
import com.stockscreener.pipeline.datasource.UniverseSnapshot;
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

/**
 * 어댑터가 <b>실제 소스로도 도는지</b>를 지킨다. 탐침({@code AlpacaAssetProbeLiveTest})이
 * 소스의 모양을 재는 것과 달리, 여기는 우리 코드가 그 응답을 제대로 통과시키는지를 본다 —
 * 헤더 배선·봉투 판별·하한·이름 캐시가 한 번에 걸린다.
 *
 * <p>실행당 <b>1콜</b>이다({@code exchange}가 콤마 목록을 받는다는 것을 실측했다).
 *
 * <p>Spring 컨텍스트를 띄우지 않고 생성자를 직접 부른다 — {@code DartLiveTest}와 같은 형태다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+",
        disabledReason = "APCA_API_KEY_ID가 없어 건너뛴다")
class AlpacaUniverseLiveTest {

    /**
     * 소스가 <b>실제로</b> 이름을 티커와 같게 주는 종목(2026-08-29 실측 8,504종목 중 2개).
     *
     * <p>{@code RH}는 Restoration Hardware, {@code VTEX}는 VTEX다 — 등록명 자체가 티커
     * 문자열이다. 여기 없는 이름이 뜨면 <b>소스가 아니라 우리 경로를 먼저 의심한다</b>:
     * {@code StockRegistrar.describe()}가 마스터 조회에 실패하면 이름을 티커로 대체하는데,
     * 그 상태는 에러도 실패 기록도 없이 수천 행에 굳는다.
     */
    private static final List<String> NAME_EQUALS_TICKER = List.of("RH", "VTEX");

    @Test
    @DisplayName("[live] 실제 자산 목록으로 유니버스 스냅샷을 만들고 이름·거래소를 캐시에 심는다")
    void buildsSnapshotFromLiveSource() throws Exception {
        SecurityNameCache nameCache = new SecurityNameCache();
        DataSourceProperties properties = new DataSourceProperties();
        DataSourceProperties.Source source = properties.getAlpaca();
        source.setApiKey(System.getenv("APCA_API_KEY_ID"));
        source.setSecretKey(System.getenv("APCA_API_SECRET_KEY"));

        AlpacaUniverseAdapter adapter = new AlpacaUniverseAdapter(
                new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("alpaca", 0, 0), nameCache);

        UniverseSnapshot snapshot = adapter.fetchConstituents(
                AlpacaUniverseAdapter.UNIVERSE_CODE, LocalDate.now());

        System.out.printf("%n실제 유니버스 = %d종목, 이름 캐시 = %d건%n",
                snapshot.tickers().size(), nameCache.size());

        // 하한(2026-08-29 실측 8,504의 절반)을 통과했다는 것은 어댑터 안에서 이미 확인됐다.
        // 여기서는 그 위에 계약 셋을 더 본다.
        assertThat(snapshot.tickers()).hasSizeGreaterThan(4_252);
        assertThat(nameCache.size()).isEqualTo(snapshot.tickers().size());

        // 이름이 티커와 같은 것은 마스터 경로가 안 돌 때의 신호이지만, 소스에도 실재한다.
        // 그래서 개수가 아니라 **집합**으로 판정한다 — 새 이름이 뜨면 실제로 그 종목을 보고
        // 목록을 늘릴지 결함인지 가른다. 개수 상한으로 두면 그 판단을 건너뛰게 된다.
        assertThat(snapshot.tickers().stream()
                .map(ticker -> nameCache.find(AlpacaSecurityMasterAdapter.MARKET_CODE, ticker))
                .filter(Optional::isPresent).map(Optional::get)
                .filter(descriptor -> descriptor.name().equals(descriptor.ticker()))
                .map(SecurityDescriptor::ticker).sorted().toList())
                .as("이름이 티커와 같은 종목 — 여기 없는 것이 뜨면 마스터 경로를 의심한다")
                .isSubsetOf(NAME_EQUALS_TICKER);

        // 거래소는 두 값 중 하나여야 한다. 그 밖이면 ck_stock_listing_exchange가 적재를 되돌린다.
        assertThat(snapshot.tickers().stream()
                .map(ticker -> nameCache.find(AlpacaSecurityMasterAdapter.MARKET_CODE, ticker))
                .filter(Optional::isPresent).map(Optional::get)
                .map(SecurityDescriptor::listingExchange)
                .distinct().toList())
                .containsExactlyInAnyOrder("NYSE", "NASDAQ");
    }
}
