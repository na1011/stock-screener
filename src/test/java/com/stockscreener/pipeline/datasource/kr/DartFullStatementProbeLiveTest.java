package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전체 재무제표({@code fnlttSinglAcntAll.json})가 무엇을 주는지 <b>실측</b>한다.
 *
 * <p>이 엔드포인트는 {@code docs/api-spec/dart-open-api.md}에 아직 실측 기록이 없다. 주요계정이
 * 주지 않는 넷 — 영업활동현금흐름·현금성자산·총차입금·이자비용 — 을 얻으려면 여기로 옮겨야
 * 하는데, 옮기기 전에 <b>정말 주는지</b>와 <b>비용이 얼마인지</b>를 숫자로 알아야 한다.
 *
 * <p>테스트라기보다 <b>탐침</b>이다. 단언은 최소한만 두고 관측한 것을 표준출력으로 낸다 —
 * 그 출력이 api-spec 문서에 그대로 옮겨진다. 알아야 할 것은 넷이다.
 * <ol>
 *   <li>{@code sj_div}에 CF(현금흐름표)가 실제로 오는가
 *   <li>{@code account_id}(XBRL 표준계정ID)가 채워지는가 — 채워지면 계정명 문자열 매칭을 버릴 수 있다
 *   <li>{@code fs_div} 파라미터가 필수인가 — 필수면 CFS·OFS 폴백에 호출이 배로 든다
 *   <li>응답 크기와 계정 수 — 재적재 비용의 근거다
 * </ol>
 *
 * <p>호출 수는 회사당 1콜이다(4개사 = 4콜). 일 20,000 한도에 견주면 무시할 만하다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartFullStatementProbeLiveTest {

    /** 업종이 서로 다른 넷. 금융지주(KB)가 유동/비유동 구분 없는 재무상태표의 표본이다. */
    private static final Map<String, String> SAMPLES = new LinkedHashMap<>(Map.of(
            "005930", "삼성전자",
            "105560", "KB금융",
            "035720", "카카오",
            "035420", "NAVER"));

    /** 직전 회계연도의 사업보고서. 확실히 공시된 시점을 고른다. */
    private static final int SETTLED_YEAR = LocalDate.now().getYear() - 1;
    private static final String ANNUAL_REPORT = "11011";

    /**
     * 이 전환으로 얻으려는 것들. <b>계정명이 아니라 {@code account_id}로 찾는다.</b>
     *
     * <p>1차 실측에서 같은 항목이 회사마다 다른 이름으로 왔다 — 영업활동 현금흐름이
     * "영업활동현금흐름"·"영업활동으로 인한 현금흐름"·"영업활동으로부터의 현금흐름" 셋이었다.
     * 반면 {@code account_id}는 셋 다 {@code ifrs-full_CashFlowsFromUsedInOperatingActivities}였다.
     * 계정명 매칭을 계속 쓰면 후보군을 회사마다 늘려야 하고 그 목록은 반드시 새어 나간다.
     */
    private static final List<String> WANTED_IDS = List.of(
            "CashFlowsFromUsedInOperatingActivities",
            "CashAndCashEquivalents",
            "ShorttermDeposits",
            "Borrowings",
            "BondsIssued",
            "InterestExpense",
            "FinanceCosts",
            "DepreciationAndAmortisation",
            "ProfitLossFromOperatingActivities");

    @Test
    @DisplayName("[live][probe] 전체 재무제표가 무엇을 주는지 기록한다")
    void probeFullStatement() throws Exception {
        DartCorpCodeCache cache = corpCodeCache();
        RestClient client = new ExternalApiClientFactory().create(properties().getDart());
        String apiKey = System.getenv("DART_API_KEY");

        System.out.println("\n===== fnlttSinglAcntAll 실측 (" + SETTLED_YEAR + " 사업보고서) =====");

        boolean sawCashFlow = false;
        boolean sawAccountId = false;

        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElseThrow();

            ResponseEntity<JsonNode> response = client.get().uri(uri -> uri
                            .path("/fnlttSinglAcntAll.json")
                            .queryParam("crtfc_key", apiKey)
                            .queryParam("corp_code", corpCode)
                            .queryParam("bsns_year", SETTLED_YEAR)
                            .queryParam("reprt_code", ANNUAL_REPORT)
                            .queryParam("fs_div", "CFS")
                            .build())
                    .retrieve()
                    .toEntity(JsonNode.class);

            JsonNode body = response.getBody();
            String status = body == null ? "(본문 없음)" : body.path("status").asString("");
            JsonNode list = body == null ? null : body.get("list");

            System.out.printf("%n--- %s(%s) status=%s%n", sample.getValue(), sample.getKey(), status);
            if (list == null || list.isEmpty()) {
                System.out.println("    행이 없다. CFS가 없는 회사이거나 fs_div가 다른 값을 요구한다.");
                continue;
            }

            // sj_div별 계정 수. CF가 있는지가 이 탐침의 첫 질문이다.
            Map<String, Integer> bySection = new TreeMap<>();
            Set<String> fsDivs = new LinkedHashSet<>();
            int withAccountId = 0;
            for (JsonNode row : list) {
                bySection.merge(row.path("sj_div").asString("?"), 1, Integer::sum);
                fsDivs.add(row.path("fs_div").asString("?"));
                if (!row.path("account_id").asString("").isBlank()
                        && !"-".equals(row.path("account_id").asString(""))) {
                    withAccountId++;
                }
            }
            System.out.println("    계정 " + list.size() + "행, sj_div=" + bySection
                    + ", fs_div=" + fsDivs
                    + ", account_id 채워짐=" + withAccountId + "/" + list.size());

            sawCashFlow |= bySection.containsKey("CF");
            sawAccountId |= withAccountId > 0;

            // 우리가 원하는 항목이 오는가. account_id로 찾고, 없으면 없다고 적는다 —
            // 침묵하면 "안 왔다"와 "안 찾아봤다"가 구분되지 않는다.
            for (String idFragment : WANTED_IDS) {
                JsonNode found = null;
                for (JsonNode row : list) {
                    if (row.path("account_id").asString("").contains(idFragment)) {
                        found = row;
                        break;
                    }
                }
                if (found == null) {
                    System.out.printf("    [%-38s] 없음%n", idFragment);
                } else {
                    System.out.printf("    [%-38s] %-4s %-28s = %s%n",
                            idFragment, found.path("sj_div").asString(""),
                            found.path("account_nm").asString(""),
                            found.path("thstrm_amount").asString(""));
                }
            }

            // 표준계정코드를 쓰지 않는 행이 얼마나 되는가. 그 행은 계정명으로 찾을 수밖에 없다.
            long sentinel = 0;
            for (JsonNode row : list) {
                if (row.path("account_id").asString("").startsWith("-")) {
                    sentinel++;
                }
            }
            System.out.println("    표준계정코드 미사용 행: " + sentinel + "/" + list.size());
        }

        System.out.println("\n===== 요약 =====");
        System.out.println("현금흐름표(CF) 확인: " + sawCashFlow);
        System.out.println("account_id 제공: " + sawAccountId);
        System.out.println("호출 수: 회사당 1콜 (fs_div=CFS 고정). OFS 폴백이 필요하면 최대 2배.");

        // 이 엔드포인트가 존재하고 응답한다는 것만 단언한다. 나머지는 관측이며,
        // 여기서 단언을 늘리면 벤더가 조금만 바뀌어도 탐침이 죽어 아무것도 못 보게 된다.
        assertThat(sawAccountId || sawCashFlow)
                .as("전체 재무제표가 주요계정과 다른 것을 하나도 주지 않는다면 전환할 이유가 없다")
                .isTrue();
    }

    private DartCorpCodeCache corpCodeCache() {
        return DartProbeSupport.corpCodeCache();
    }

    private static DataSourceProperties properties() {
        return DartProbeSupport.properties();
    }
}
