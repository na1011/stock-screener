package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.support.ReadOnlyDb;

import com.stockscreener.pipeline.datasource.SecurityNameCache;
import com.stockscreener.pipeline.datasource.UniverseSnapshot;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생존 편향의 <b>크기와 부호</b>를 잰다(P17 탐침 ③, ADR-0071).
 *
 * <p><b>이 탐침이 답하려는 것은 유니버스 복원이 아니다.</b> 시점 유니버스는 DB로도 소스로도
 * 복원되지 않는다 — 이력이 2026-08-05부터고, 실제 편입 판정은 {@code UniverseReconciler}가
 * 히스테리시스를 끼고 집합 차집합으로 하므로 <b>경로 의존적</b>이다. 여기서 얻는 것은
 * <b>하한</b>이고, 결과에 「복원했다」로 적지 않는다.
 *
 * <p><b>가르는 것이 핵심이다.</b> 오늘 {@code stock}에 없는 과거 편입 종목은 성질이 둘로 갈린다.
 * <ul>
 *   <li><b>편출</b> — 오늘도 상장돼 있지만 지수에서 빠졌다. 수익률은 대체로 평범하다.
 *   <li><b>폐지</b> — 오늘 상장 목록에 아예 없다. 수익률이 −80~−100%다.
 * </ul>
 * <b>둘을 섞으면 편향의 부호를 말할 수 없다.</b> 폐지 종목은 거의 전부 게이트
 * <b>미충족</b> 집단에 있었을 것이므로 미충족 평균이 위로 당겨지고, 그러면
 * <b>「충족 − 미충족」 차가 0 쪽으로 눌린다.</b> 그 방향이 ADR-0061의 국내 수익성 재현율
 * 5.0%와 <b>같은 방향</b>이라, 가르지 않으면 <b>편향 하나를 「출처가 다른 두 증거」로 읽게
 * 된다.</b> 이 탐침이 없으면 그 오독을 막을 것이 아무것도 없다.
 *
 * <p>{@link MarketCapUniverseAdapter#fetchConstituents}를 그대로 쓴다. 히스테리시스는
 * {@code select()}에서 <b>디버그 로그로만</b> 쓰이고 반환은 순수 상위 N이므로, 과거 기준일에
 * 대해 <b>경로 독립</b>이다 — 그래서 하한을 재는 데 쓸 수 있다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DATA_GO_KR_SERVICE_KEY", matches = ".+",
        disabledReason = "DATA_GO_KR_SERVICE_KEY가 없어 건너뛴다")
class PastUniverseProbeLiveTest {

    /**
     * 백테스트 창을 분기로 훑는다. 수익률 축의 8시점을 포함하되 그 사이도 본다 —
     * 두 시점 사이에서 들어왔다 나간 종목은 어느 쪽 끝에서도 안 보인다.
     */
    private static final List<LocalDate> AS_OF = List.of(
            LocalDate.of(2022, 4, 1), LocalDate.of(2022, 7, 1),
            LocalDate.of(2022, 10, 4), LocalDate.of(2023, 1, 2),
            LocalDate.of(2023, 4, 3), LocalDate.of(2023, 7, 3),
            LocalDate.of(2023, 10, 4), LocalDate.of(2024, 1, 2),
            LocalDate.of(2024, 4, 1), LocalDate.of(2024, 7, 1),
            LocalDate.of(2024, 10, 2), LocalDate.of(2025, 1, 2),
            LocalDate.of(2025, 4, 1), LocalDate.of(2025, 7, 1),
            LocalDate.of(2025, 10, 2), LocalDate.of(2026, 2, 2));

    private static final List<String> UNIVERSES = List.of("KOSPI_TOP200", "KOSDAQ_TOP150");

    /** 폐지 종목의 실현 손실 가정. 하한·상한 둘로 재서 편향을 구간으로 낸다. */
    private static final double[] DELISTING_LOSS = {-0.80, -1.00};

    /**
     * 편향을 나눌 <b>미충족 집단</b>의 크기. 폐지 종목은 거의 전부 여기 있으므로 분모가
     * 유니버스가 아니라 이쪽이다 — 게이트가 조일수록 미충족이 커지고 편향은 <b>작아진다.</b>
     *
     * <p>실측 통과 수에서 왔다: {@code ROIC_MIN_3Y} 60/300, {@code PROFIT_STREAK_Y} 156/350.
     * 마지막 100은 통과가 많은 조건까지 덮는 <b>보수적 상한</b>이다.
     */
    private static final int[] UNSATISFIED_GROUP = {350, 278, 194, 100};

    /**
     * 사전등록된 검출 가능 효과({@code docs/phase-criteria.md} §P17). <b>6개월당</b>이다 —
     * 창 전체 누적 폐지 수를 한 기간 유니버스로 나누면 이 값과 <b>단위가 달라</b>
     * 편향이 4~5배로 부풀고, 그러면 「수익률 축은 못 쓴다」는 결론이 산식에서 나온다.
     */
    private static final double DETECTABLE_EFFECT_PP = 3.5;

    @Test
    @DisplayName("[live][probe] 과거 유니버스와 오늘을 견줘 편출과 폐지를 가른다 — 약 34콜")
    void measureSurvivorshipBias() throws Exception {
        Set<String> inDatabase = readTickers("SELECT ticker FROM stock");
        assertThat(inDatabase).as("stock이 비면 잴 것이 없다").isNotEmpty();

        MarketCapUniverseAdapter adapter = adapter();
        Set<String> listedToday = fetchAllListedToday();

        System.out.println("\n===== P17 탐침 ③ 생존 편향 =====");
        System.out.printf("오늘 stock %d종목 · 오늘 상장 전체 %d종목 · 기준일 %d개 × 유니버스 %d%n",
                inDatabase.size(), listedToday.size(), AS_OF.size(), UNIVERSES.size());
        System.out.println("MUST: 상장 전체를 못 받았으면(0) 폐지/편출을 가를 수 없다 —"
                + " 그때는 이 탐침이 '편향이 작다'가 아니라 '못 쟀다'이다.");

        Set<String> everMissing = new TreeSet<>();
        Set<String> everDelisted = new TreeSet<>();
        int universeSizeSum = 0;
        int snapshots = 0;

        for (LocalDate asOf : AS_OF) {
            Set<String> members = new LinkedHashSet<>();
            boolean complete = true;
            for (String universe : UNIVERSES) {
                try {
                    UniverseSnapshot snapshot = adapter.fetchConstituents(universe, asOf);
                    members.addAll(snapshot.tickers());
                } catch (Exception e) {
                    // 부분 목록을 스냅샷으로 만들지 않는 것이 어댑터의 규칙이다(ADR-0008).
                    // 여기서 삼키면 '그날 편입이 적었다'로 읽히므로 표시하고 그 날짜를 버린다.
                    complete = false;
                    System.out.printf("%s %s: 스냅샷 실패 — %s%n", asOf, universe, e.getMessage());
                }
            }
            if (!complete) {
                continue;
            }
            snapshots++;
            universeSizeSum += members.size();

            List<String> missing = members.stream().filter(t -> !inDatabase.contains(t)).sorted().toList();
            List<String> delisted = missing.stream().filter(t -> !listedToday.contains(t)).toList();
            everMissing.addAll(missing);
            everDelisted.addAll(delisted);

            System.out.printf("%s: 편입 %d · 오늘 stock에 없음 %d (그중 폐지 %d · 편출 %d)%n",
                    asOf, members.size(), missing.size(), delisted.size(),
                    missing.size() - delisted.size());
            if (!delisted.isEmpty()) {
                System.out.println("      폐지: " + delisted);
            }
        }

        assertThat(snapshots)
                .as("스냅샷을 하나도 못 만들면 편향의 하한조차 못 잰다 — 멈추고 보고할 자리다")
                .isPositive();

        int avgUniverse = universeSizeSum / snapshots;
        Set<String> everDropped = new TreeSet<>(everMissing);
        everDropped.removeAll(everDelisted);

        System.out.println("\n----- 편향의 크기와 부호 (결과를 보기 전에 적는다) -----");
        System.out.printf("창 전체에서 한 번이라도 편입됐는데 오늘 stock에 없는 종목: %d%n",
                everMissing.size());
        System.out.printf("  그중 폐지(오늘 상장 아님): %d · 편출(오늘도 상장): %d%n",
                everDelisted.size(), everDropped.size());
        System.out.println("  폐지: " + everDelisted);
        System.out.printf("평균 유니버스 크기 %d%n", avgUniverse);

        // MUST: 누적 폐지 수를 그대로 쓰지 않는다. 검출 임계가 6개월당이므로 분자도 6개월당
        // 이어야 한다 — 창 전체 19종목을 한 기간 유니버스로 나누면 편향이 4~5배로 부푼다.
        LocalDate first = AS_OF.get(0);
        LocalDate last = AS_OF.get(AS_OF.size() - 1);
        double windowYears = (last.toEpochDay() - first.toEpochDay()) / 365.25;
        double periods = windowYears / 0.5;
        double delistedPerPeriod = everDelisted.size() / periods;

        System.out.printf("창 %.2f년 = 6개월 구간 %.1f개 · 6개월당 폐지 %.2f종목%n",
                windowYears, periods, delistedPerPeriod);
        System.out.println("\n  미충족 집단 크기별 편향 (%p / 6개월) — 분모가 유니버스가 아니라"
                + " 미충족인 이유는 폐지 종목이 거의 전부 거기 있기 때문이다:");
        for (int group : UNSATISFIED_GROUP) {
            StringBuilder line = new StringBuilder("    미충족 %3d명: ".formatted(group));
            for (double loss : DELISTING_LOSS) {
                line.append("손실 %.0f%% → %.2f%%p   "
                        .formatted(loss * 100,
                                100.0 * delistedPerPeriod * Math.abs(loss) / group));
            }
            System.out.println(line);
        }
        System.out.printf("%n  사전등록 검출 임계 %.1f%%p / 6개월(phase-criteria §P17)%n",
                DETECTABLE_EFFECT_PP);
        System.out.println("MUST: 편향이 임계를 넘으면 수익률 축은 부호를 말할 수 없다고 적는다.");
        System.out.println("MUST: 넘지 않아도 「편향 없음」이 아니다 — 편향은 차를 0 쪽으로"
                + " 누르므로, 임계 근처의 작은 양(+)의 효과는 여전히 못 가른다.");
        System.out.println("MUST NOT: 이 값을 「복원했다」로 읽지 않는다 — 폐지 종목은 지수에서"
                + " 빠진 뒤 상장폐지되는 경우가 많아 편입 상태로는 잡히지 않는다. 이것은 하한이다.");
    }

    /**
     * 오늘 상장돼 있는 전 종목. <b>지수 편입이 아니라 상장 여부</b>를 묻는 것이라
     * 어댑터의 상위 N을 쓸 수 없다 — {@code getStockPriceInfo}를 시장별로 한 번씩 부른다.
     *
     * <p>기준일을 며칠 거슬러 올라가며 찾는다. 소스가 거래일 T의 종가를 <b>T의 다음 영업일
     * 13시 이후</b>에 내므로, 오늘을 그대로 주면 {@code totalCount=0}이 온다 — 그 0을
     * "상장 종목이 없다"로 읽으면 <b>전 종목이 폐지로 잡힌다.</b>
     */
    private static Set<String> fetchAllListedToday() {
        RestClient client = new ExternalApiClientFactory().create(properties().getDataGoKr());
        String serviceKey = ExternalApiClientFactory.normalizeServiceKey(
                properties().getDataGoKr().getApiKey());
        Set<String> tickers = new TreeSet<>();

        for (int back = 1; back <= 10 && tickers.isEmpty(); back++) {
            LocalDate basDt = LocalDate.now().minusDays(back);
            String yyyymmdd = "%04d%02d%02d".formatted(
                    basDt.getYear(), basDt.getMonthValue(), basDt.getDayOfMonth());
            JsonNode body = DartProbeSupport.get(client, uri -> uri
                    .path("/getStockPriceInfo")
                    .queryParam("serviceKey", serviceKey)
                    .queryParam("resultType", "json")
                    .queryParam("numOfRows", 20000)
                    .queryParam("pageNo", 1)
                    .queryParam("basDt", yyyymmdd)
                    .build());
            if (body == null) {
                continue;
            }
            JsonNode response = body.has("response") ? body.path("response").path("body") : body;
            int totalCount = response.path("totalCount").asInt(0);
            for (JsonNode item : response.path("items").path("item")) {
                String ticker = item.path("srtnCd").asText("");
                if (!ticker.isBlank()) {
                    tickers.add(ticker);
                }
            }
            System.out.printf("오늘 상장 조회 basDt=%s totalCount=%d 수집 %d%n",
                    yyyymmdd, totalCount, tickers.size());
        }
        return tickers;
    }

    private static MarketCapUniverseAdapter adapter() {
        return new MarketCapUniverseAdapter(
                new ExternalApiClientFactory(), properties(),
                new TokenBucketRateLimiter("data-go-kr", 120, 0),
                new SecurityNameCache());
    }

    private static DataSourceProperties properties() {
        DataSourceProperties properties = new DataSourceProperties();
        String key = System.getenv("DATA_GO_KR_SERVICE_KEY");
        properties.getDataGoKr().setApiKey(key == null ? "" : key);
        return properties;
    }

    private static Set<String> readTickers(String sql) throws Exception {
        Set<String> tickers = new TreeSet<>();
        try (Connection connection = ReadOnlyDb.open();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                tickers.add(rs.getString(1));
            }
        }
        return tickers;
    }
}
