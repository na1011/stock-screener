package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.StoredPriceSeries;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 DART 응답에 어댑터를 그대로 물린다. <b>DB는 쓰지 않는다</b> —
 * {@link StoredPriceSeries}에 {@code price_daily} 실측값을 손으로 심는다(2026-08-12).
 *
 * <p>단위 테스트가 못 잡는 것 하나를 여기서 잡는다: <b>우리가 만든 응답 픽스처가 실제
 * 응답과 같은가.</b> 낱말·일자 표기·수량 표기 중 하나만 달라도 어댑터는 조용히 0건을 낸다.
 *
 * <p>뒤쪽 절반은 <b>단언이 아니라 관측</b>이다. 셋이 아직 미실측이다 —
 * ① 분기보고서의 증자(감자) 현황이 연간 누적인가 ② 루닛 2026 무상증자가 어느 보고서에
 * 언제부터 실리는가 ③ {@code isu_dcrs_stock_knd}의 실제 값. 단언으로 만들면 <b>모르는 것을
 * 아는 것처럼 고정하게 된다.</b>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartCorporateActionAdapterLiveTest {

    private static final SecurityRef KAKAO = new SecurityRef(1L, "KOSPI", "035720");
    private static final SecurityRef LUNIT = new SecurityRef(2L, "KOSDAQ", "328130");

    @Test
    @DisplayName("[live] 실제 응답에서 카카오 2021 액면분할의 비율·효력일이 나온다")
    void derivesKakaoSplitFromLiveResponse() throws Exception {
        List<CorporateAction> actions = adapterWith(kakaoSeries()).fetchCorporateActions(
                KAKAO, LocalDate.parse("2021-04-01"), LocalDate.parse("2021-04-30")).actions();

        System.out.println("\n===== 카카오 2021 액면분할 =====");
        actions.forEach(DartCorporateActionAdapterLiveTest::print);

        assertThat(actions)
                .as("소스가 낱말·일자 표기를 바꿨으면 여기서 0건이 된다")
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.type()).isEqualTo(CorporateAction.Type.SPLIT);
                    assertThat(action.ratio()).isEqualByComparingTo("5");
                    assertThat(action.effectiveDate()).isEqualTo(LocalDate.parse("2021-04-15"));
                    assertThat(action.sourceDate())
                            .as("원값은 매매개시일보다 이르다")
                            .isEqualTo(LocalDate.parse("2021-04-14"));
                });
    }

    @Test
    @DisplayName("[live] 실제 응답에서 루닛 2023 무상증자의 세 날짜가 갈린다")
    void derivesLunitBonusIssueFromLiveResponse() throws Exception {
        List<CorporateAction> actions = adapterWith(lunitSeries()).fetchCorporateActions(
                LUNIT, LocalDate.parse("2023-11-01"), LocalDate.parse("2023-11-30")).actions();

        System.out.println("\n===== 루닛 2023 무상증자 =====");
        actions.forEach(DartCorporateActionAdapterLiveTest::print);

        assertThat(actions).singleElement().satisfies(action -> {
            assertThat(action.type()).isEqualTo(CorporateAction.Type.BONUS_ISSUE);
            assertThat(action.ratio()).isEqualByComparingTo("2");
            assertThat(action.effectiveDate()).isEqualTo(LocalDate.parse("2023-11-09"));
            assertThat(action.listingDate()).isEqualTo(LocalDate.parse("2023-12-01"));
        });
    }

    @Test
    @DisplayName("[live][probe] 분기보고서가 연간 누적인지, 루닛 2026이 어디 실리는지 기록한다")
    void probeReportCoverageAndStockKind() throws Exception {
        var client = DartProbeSupport.client();
        String corpCode = DartProbeSupport.corpCodeCache().findCorpCode(LUNIT.ticker()).orElseThrow();

        System.out.println("""

                ===== 미실측 셋 =====
                ① 분기·반기보고서의 증자(감자) 현황이 연간 누적인가
                   — DartIrdsReports는 지금 '자기 기간만'으로 보수적으로 잡는다. 누적이면 콜이 준다.
                ② 루닛 2026-05 무상증자(권리락 05-04)가 어느 보고서에 언제부터 실리는가
                   — 2026 1분기는 03-31까지만 담고, 2026 반기는 법정 제출기한이 08-14다.
                ③ isu_dcrs_stock_knd의 실제 값 — 어댑터는 '우선'이 든 것만 뺀다.""");

        TreeSet<String> kinds = new TreeSet<>();
        for (Probe probe : List.of(
                new Probe(2025, "11011", "2025 사업보고서"),
                new Probe(2026, "11013", "2026 1분기"),
                new Probe(2026, "11012", "2026 반기"))) {
            JsonNode body = DartProbeSupport.get(client, uri -> uri
                    .path("/irdsSttus.json")
                    .queryParam("crtfc_key", DartProbeSupport.apiKey())
                    .queryParam("corp_code", corpCode)
                    .queryParam("bsns_year", probe.year())
                    .queryParam("reprt_code", probe.code())
                    .build());

            List<String> dates = new ArrayList<>();
            JsonNode list = body == null ? null : body.get("list");
            if (list != null) {
                for (JsonNode row : list) {
                    dates.add(row.path("isu_dcrs_de").asString("") + " "
                            + row.path("isu_dcrs_stle").asString(""));
                    kinds.add(row.path("isu_dcrs_stock_knd").asString("(빈 값)"));
                }
            }
            System.out.printf("%n--- %s: status=%s 행=%d%n",
                    probe.label(), DartProbeSupport.describeStatus(body), dates.size());
            dates.forEach(d -> System.out.println("      " + d));
            System.out.println("    가장 이른 일자 = " + dates.stream().sorted().findFirst().orElse("(없음)")
                    + " → 이것이 그 보고서 회계기간보다 이르면 누적이다");
        }

        System.out.println("\n관측된 isu_dcrs_stock_knd 전부: " + kinds);
        System.out.println("""

                → '우선'이 안 들어간 우선주 표기가 있으면 어댑터의 필터를 그 값으로 고친다.
                  MUST: 관측 결과를 api-spec dart-corporate-action.md에 옮겨 적는다. 여기 출력만 남으면 증발한다.""");
    }

    private record Probe(int year, String code, String label) {
    }

    private static void print(CorporateAction action) {
        System.out.printf("  %s | 권리락 %s | 신주상장 %s | 원값 %s | 비율 %s%n",
                action.type(), action.effectiveDate(), action.listingDate(),
                action.sourceDate(), action.ratio());
    }

    private static DartCorporateActionAdapter adapterWith(StoredPriceSeries series) {
        return new DartCorporateActionAdapter(
                new ExternalApiClientFactory(), DartProbeSupport.properties(),
                new TokenBucketRateLimiter("dart", 300, 0),
                DartProbeSupport.corpCodeCache(), series);
    }

    /** 카카오 2021 — {@code price_daily} 실측. 분할 엿새 전 04-09에 잔변동이 있다. */
    private static StoredPriceSeries kakaoSeries() {
        return window(List.of(
                bar("2021-04-08", "548000", 88_746_712L),
                bar("2021-04-09", "558000", 88_761_861L),
                bar("2021-04-15", "120500", 443_809_305L),
                bar("2021-04-16", "119000", 443_809_305L)));
    }

    /** 루닛 2023 — 권리락과 신주상장 사이 11-24에 별개 증가가 낀다. */
    private static StoredPriceSeries lunitSeries() {
        return window(List.of(
                bar("2023-11-08", "170500", 12_381_004L),
                bar("2023-11-09", "90200", 12_381_004L),
                bar("2023-11-10", "96600", 12_381_004L),
                bar("2023-11-24", "93100", 14_238_154L),
                bar("2023-11-30", "99800", 14_238_154L),
                bar("2023-12-01", "96300", 28_476_308L)));
    }

    private static StoredPriceSeries window(List<StoredPriceSeries.Bar> bars) {
        return (stockId, from, to) -> bars.stream()
                .filter(bar -> !bar.tradeDate().isBefore(from) && !bar.tradeDate().isAfter(to))
                .toList();
    }

    private static StoredPriceSeries.Bar bar(String date, String close, long listedShares) {
        return new StoredPriceSeries.Bar(LocalDate.parse(date), new BigDecimal(close),
                BigDecimal.valueOf(listedShares));
    }
}
