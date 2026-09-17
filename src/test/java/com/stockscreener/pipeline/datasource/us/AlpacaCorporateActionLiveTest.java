package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.CorporateAction;
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
 * 미국 기업 행위 어댑터 관통테스트. <b>실제 API를 호출한다</b>(실행당 2콜).
 *
 * <p>이 테스트가 지키는 것은 "우리 파싱이 맞는가"가 아니라 <b>"벤더가 아직 그대로인가"</b>다.
 * 파싱은 녹화된 fixture로 {@link AlpacaCorporateActionAdapterTest}가 검증하고, 소스의 모양은
 * {@link AlpacaCorporateActionProbeLiveTest}가 잰다. 여기는 <b>어댑터가 실제 소스에 통하는가</b>다.
 *
 * <p>둘째 시험이 이 파일의 존재 이유다. 어댑터는 <b>{@code process_date} 창으로 부르고
 * {@code ex_date}로 거른다</b> — 목으로는 그것을 증명할 수 없다. 목은 우리가 무엇을 보내든
 * 같은 응답을 주기 때문이다. 실제 소스에서 <b>효력일이 창 안이지만 처리일이 훨씬 뒤인 사건</b>이
 * 걸러지지 않고 오는지가 여기서만 드러난다(실측: T→WBD 분사는 {@code ex_date} 2022-04-11에
 * {@code process_date} 2025-07-18이다).
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+",
        disabledReason = "APCA_API_KEY_ID가 없어 건너뛴다")
class AlpacaCorporateActionLiveTest {

    private static final SecurityRef NVDA = new SecurityRef(1L, "US", "NVDA");
    private static final SecurityRef ATT = new SecurityRef(2L, "US", "T");

    @Test
    @DisplayName("[live] NVDA 2024-06-10 분할의 비율과 효력일이 실제 응답에서 나온다")
    void fetchesTheNvidiaSplit() throws Exception {
        List<CorporateAction> actions = adapter().fetchCorporateActions(
                NVDA, LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31")).actions();

        System.out.println("\n===== NVDA 2024 =====");
        actions.forEach(a -> System.out.printf("  %s %s ratio=%s form=%s%n",
                a.effectiveDate(), a.type(), a.ratio(), a.sourceForm()));

        assertThat(actions)
                .as("소스가 필드 이름이나 봉투 모양을 바꿨으면 여기서 0건이 된다")
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.type()).isEqualTo(CorporateAction.Type.SPLIT);
                    assertThat(action.ratio()).isEqualByComparingTo("10");
                    assertThat(action.effectiveDate())
                            .as("우리 봉의 불연속일과 같아야 한다. 하루만 밀려도 계수가 조용히 틀린다")
                            .isEqualTo(LocalDate.parse("2024-06-10"));
                    assertThat(action.sourceForm()).isEqualTo("forward_split");
                });
    }

    @Test
    @DisplayName("[live] 배당이 많은 종목에서도 값으로 나오는 것은 분할·병합뿐이다")
    void recordsOnlySplitsEvenWhenTheSourceReturnsMuchMore() throws Exception {
        // T는 같은 창에서 배당 15건 + 분사 1건을 준다(실측). 그 창에 분할·병합은 없다.
        List<CorporateAction> actions = adapter().fetchCorporateActions(
                ATT, LocalDate.parse("2023-12-01"), LocalDate.parse("2026-08-29")).actions();

        assertThat(actions)
                .as("types로 좁히지 않고 부르므로 배당·분사가 응답에 함께 온다. "
                        + "값으로 내지 않는 것이 어댑터의 일이다")
                .isEmpty();
    }

    /**
     * 어댑터가 실제 소스에서 무엇을 내는지 <b>원장에 쓰지 않고</b> 찍는다 — 어댑터를 고친 뒤의 드라이런.
     * {@code -Dprobe.symbols=FLG,DFTX,QXO -Dprobe.from=2019-09-03}. 창의 끝은 오늘이다.
     */
    @Test
    @DisplayName("[live][dry-run] 어댑터가 종목마다 낸 분할·병합과 안 담은 타입 수를 찍는다")
    void dryRunSymbols() throws Exception {
        String symbols = System.getProperty("probe.symbols", "NVDA");
        LocalDate from = LocalDate.parse(System.getProperty("probe.from", "2019-09-03"));
        AlpacaCorporateActionAdapter adapter = adapter();
        for (String symbol : symbols.split(",")) {
            var fetched = adapter.fetchCorporateActions(
                    new SecurityRef(0L, "US", symbol.trim()), from, LocalDate.now());
            System.out.printf("%n--- %s 사건 %d · 안 담은 타입 %s%n", symbol, fetched.actions().size(),
                    fetched.notStored());
            for (CorporateAction action : fetched.actions()) {
                System.out.printf("  %s %s ratio=%s form=%s%n", action.effectiveDate(), action.type(),
                        action.ratio().toPlainString(), action.sourceForm());
            }
        }
    }

    private AlpacaCorporateActionAdapter adapter() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getAlpaca().setApiKey(System.getenv("APCA_API_KEY_ID"));
        properties.getAlpaca().setSecretKey(System.getenv("APCA_API_SECRET_KEY"));
        return new AlpacaCorporateActionAdapter(
                new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("alpaca", 0, 0));
    }
}
