package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>미국 거버넌스 축의 원자료가 이미 열려 있는지</b>를 잰다. 어댑터를 만들기 전이 아니라
 * <b>만들지 말지를 정하기 전</b>의 탐침이다.
 *
 * <p>묻는 것은 하나다 — 국내 거버넌스 17개 사실 중 <b>우리가 이미 받고 있는 `companyfacts`
 * 응답 안에</b> 몇 개가 들어 있는가. `api-sources` skill의 다섯 단계에서 <b>B(이미 열려 있는데
 * 안 쓴다)와 D(그 소스 밖의 소스가 필요하다)를 가르는</b> 측정이다.
 *
 * <p><b>실측 (2026-08-29, 표본 5 · 실행당 9콜) — 이 파일이 그 답의 정본이다.</b>
 * 미국 거버넌스 축을 열지 말지의 판단 재료이고, `docs/api-spec/`에 파일을 만들지 않은 이유는
 * <b>그 축이 아직 안 열렸기 때문</b>이다(축마다 파일 하나 — 열면 그때 옮긴다).
 *
 * <ul>
 *   <li><b>이미 받는 응답 안에 있는 것 넷</b> — {@code CommonStockSharesIssued}(5/5) ·
 *       {@code TreasuryStockShares}(3/5)+{@code …CommonShares}(2/5) ·
 *       {@code CommonStockDividendsPerShareDeclared}(4/5) · {@code PaymentsOfDividends…}(3/5·2/5).
 *       <b>새 콜이 0이다</b> — 미국 재무 적재가 이 응답을 이미 받는다.</li>
 *   <li><b>부분</b> — 임원보수는 {@code ecd:PeoTotalCompAmt}로 오지만 <b>5 중 2</b>(JPM·RH)다.
 *       {@code ecd}는 2022년 pay-versus-performance 규정으로 생겼고 <b>모집단 커버리지는 안 쟀다.</b></li>
 *   <li><b>감사인은 「없다」가 아니라 「이 API가 안 준다」</b> — {@code dei:AuditorName}·
 *       {@code AuditorFirmId}·{@code EntityRegistrantName}이 {@code companyconcept}에서 전부
 *       <b>404</b>이고, <b>같은 회사의 수치 개념은 200</b>이다(대조군). 즉 XBRL API는 수치 사실만
 *       낸다. 10-K 표지에 태깅되므로 <b>제출 서류의 XBRL 인스턴스를 직접 파싱하면 닿는다.</b></li>
 *   <li><b>서술 문서에만 있는 것 아홉</b> — 이사 수·사외이사 수·중도퇴임(DEF 14A 본문) ·
 *       최대주주·소액주주 지분(DEF 14A 소유표·SC 13D/G) · 최대주주 변경(8-K Item 5.01) ·
 *       감사의견 강조사항·부적정(감사보고서 본문).</li>
 * </ul>
 *
 * <p><b>MUST NOT: 「미국은 거버넌스 소스가 없다」로 요약하지 않는다.</b> 위가 그 요약을 반증한다 —
 * 갈리는 것은 <b>어느 단계인가</b>이고, 넷은 이미 B(열려 있는데 안 쓴다)다.
 *
 * <p><b>재보지 않은 것</b>: {@code PROFIT_RETIRED_CUM}(이익소각 <b>누계 주식수</b>)의 대응은
 * {@code StockRepurchasedAndRetiredDuringPeriodShares}인데 <b>안 물어봤다</b> —
 * 이 탐침이 후보로 넣었던 {@code PaymentsForRepurchaseOfCommonStock}은 <b>자사주 매입 현금</b>이라
 * 뜻이 다르다(감자를 일부러 뺀 값과 짝이 안 맞는다).
 *
 * <p><b>MUST NOT: 이 탐침의 출력에 User-Agent를 찍지 않는다</b>(이메일이 들어 있다).
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SEC_EDGAR_USER_AGENT", matches = ".+",
        disabledReason = "SEC_EDGAR_USER_AGENT가 없어 건너뛴다")
class SecEdgarGovernanceProbeLiveTest {

    private static final String DATA_URL = "https://data.sec.gov";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long THROTTLE_MS = 200;

    /** 표본. 대형·은행·소형·1월 결산을 섞는다. */
    private static final Map<String, Long> SAMPLE = new LinkedHashMap<>();

    static {
        SAMPLE.put("AAPL", 320193L);
        SAMPLE.put("MSFT", 789019L);
        SAMPLE.put("JPM", 19617L);
        SAMPLE.put("KO", 21344L);
        SAMPLE.put("RH", 1528849L);
    }

    /** 국내 거버넌스 사실 → 미국에서 그 자리를 채울 후보 태그. <b>가설이고 이 탐침이 채점한다.</b> */
    private static final Map<String, List<String>> CANDIDATES = new LinkedHashMap<>();

    static {
        CANDIDATES.put("ISSUED_SHARES_COMMON", List.of(
                "us-gaap:CommonStockSharesIssued", "us-gaap:CommonStockSharesOutstanding",
                "dei:EntityCommonStockSharesOutstanding"));
        CANDIDATES.put("TREASURY_SHARES_COMMON", List.of(
                "us-gaap:TreasuryStockCommonShares", "us-gaap:TreasuryStockShares"));
        CANDIDATES.put("DPS_COMMON", List.of(
                "us-gaap:CommonStockDividendsPerShareDeclared",
                "us-gaap:CommonStockDividendsPerShareCashPaid"));
        CANDIDATES.put("CASH_DIVIDEND_TOTAL", List.of(
                "us-gaap:PaymentsOfDividendsCommonStock", "us-gaap:PaymentsOfDividends",
                "us-gaap:DividendsCommonStock"));
        // MUST NOT: 이 둘이 맞았다고 읽지 않는다 — 국내 값은 **이익소각 누계 주식수**이고
        // 이 둘은 자사주 매입 **현금**이다. 진짜 대응 후보는
        // StockRepurchasedAndRetiredDuringPeriodShares이고 아직 안 물어봤다.
        CANDIDATES.put("PROFIT_RETIRED_CUM (뜻이 다르다)", List.of(
                "us-gaap:TreasuryStockValueAcquiredCostMethod",
                "us-gaap:PaymentsForRepurchaseOfCommonStock"));
        CANDIDATES.put("AUDITOR_CHANGED", List.of(
                "dei:AuditorName", "dei:AuditorFirmId", "dei:AuditorLocation"));
        CANDIDATES.put("EXEC_PAY_TOTAL", List.of(
                "ecd:PeoTotalCompAmt", "ecd:NonPeoNeoAvgTotalCompAmt"));
        // 아래 넷은 후보를 못 세웠다 — 그 사실 자체가 이 탐침의 결과다.
        CANDIDATES.put("DIRECTOR_COUNT / OUTSIDE_DIRECTOR_COUNT", List.of());
        CANDIDATES.put("DIRECTOR_DISMISSED / DIRECTOR_RESIGNED", List.of());
        CANDIDATES.put("LARGEST_HOLDER / MINORITY_SHARES", List.of());
        CANDIDATES.put("CONTROL_TRANSFERS_3Y", List.of());
        CANDIDATES.put("AUDIT_EMPHASIS / AUDIT_OPINION_ADVERSE", List.of());
    }

    @Test
    @DisplayName("[live][probe] 국내 거버넌스 17개 사실 중 몇 개가 companyfacts에 이미 있는가")
    void probeGovernanceFactsInCompanyFacts() throws Exception {
        RestClient data = client();
        Map<String, JsonNode> factsByTicker = new LinkedHashMap<>();
        Set<String> allNamespaces = new TreeMap<String, Boolean>().keySet().isEmpty()
                ? new LinkedHashSet<>() : new LinkedHashSet<>();

        for (Map.Entry<String, Long> e : SAMPLE.entrySet()) {
            String raw = body(data, "/api/xbrl/companyfacts/CIK%010d.json".formatted(e.getValue()));
            JsonNode facts = MAPPER.readTree(raw).path("facts");
            factsByTicker.put(e.getKey(), facts);
            facts.propertyNames().forEach(allNamespaces::add);
        }

        System.out.printf("%n===== 네임스페이스 (표본 %d) =====%n%s%n", SAMPLE.size(), allNamespaces);

        System.out.printf("%n===== 국내 거버넌스 사실 → 미국 후보 태그의 실재 =====%n");
        for (Map.Entry<String, List<String>> c : CANDIDATES.entrySet()) {
            if (c.getValue().isEmpty()) {
                System.out.printf("  %-42s 후보 없음 — XBRL로 태깅되는 자리를 못 찾았다%n", c.getKey());
                continue;
            }
            List<String> hits = new ArrayList<>();
            for (String tag : c.getValue()) {
                int n = 0;
                for (JsonNode facts : factsByTicker.values()) {
                    String[] parts = tag.split(":", 2);
                    if (facts.path(parts[0]).has(parts[1])) {
                        n++;
                    }
                }
                if (n > 0) {
                    hits.add("%s(%d/%d)".formatted(tag, n, SAMPLE.size()));
                }
            }
            System.out.printf("  %-42s %s%n", c.getKey(),
                    hits.isEmpty() ? "후보 전부 없음" : String.join(" · ", hits));
        }

        System.out.printf("%n===== 종목별 ecd·dei 개념 수 =====%n");
        factsByTicker.forEach((t, facts) -> {
            int ecd = facts.path("ecd").size();
            int dei = facts.path("dei").size();
            List<String> deiKeys = new ArrayList<>();
            facts.path("dei").propertyNames().forEach(deiKeys::add);
            System.out.printf("  %-6s ecd=%d dei=%d  dei키: %s%n", t, ecd, dei, deiKeys);
        });

        assertThat(factsByTicker).as("하나도 못 받으면 위 표는 「잴 것이 없었다」다").isNotEmpty();
    }

    @Test
    @DisplayName("[live][probe] 텍스트 사실(감사인)이 XBRL API로 닿는가 — 닿으면 감사 축이 열린다")
    void probeTextFactsAreReachable() {
        RestClient data = client();
        // companyfacts의 dei가 수치 개념 두셋뿐이었다. 그것이 "그 회사가 안 냈다"인지
        // "이 API가 텍스트를 안 준다"인지는 **개념을 직접 물어야** 갈린다.
        for (String concept : List.of("AuditorName", "AuditorFirmId", "EntityRegistrantName")) {
            sleep();
            ResponseEntity<String> r = data.get()
                    .uri("/api/xbrl/companyconcept/CIK0000320193/dei/{c}.json", concept)
                    .retrieve().toEntity(String.class);
            System.out.printf("  dei:%-22s HTTP %s%n", concept, r.getStatusCode());
        }
        System.out.println("  → 전부 404면 이 API는 수치 사실만 낸다. 감사인은 다른 경로가 필요하다");
        System.out.println("     (10-K 표지에 XBRL로 태깅되지만 frames/companyfacts가 안 실어 준다)");

        // 대조군 — 같은 회사의 수치 개념은 200이어야 한다. 아니면 위 404는 경로 오타다.
        sleep();
        ResponseEntity<String> control = data.get()
                .uri("/api/xbrl/companyconcept/CIK0000320193/dei/EntityCommonStockSharesOutstanding.json")
                .retrieve().toEntity(String.class);
        System.out.printf("  대조군 dei:EntityCommonStockSharesOutstanding HTTP %s%n",
                control.getStatusCode());
        assertThat(control.getStatusCode().is2xxSuccessful())
                .as("대조군이 200이 아니면 위 404는 「없다」가 아니라 경로 문제다").isTrue();
    }

    private static void sleep() {
        try {
            Thread.sleep(THROTTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("탐침이 인터럽트됐다", e);
        }
    }

    private static RestClient client() {
        return RestClient.builder()
                .baseUrl(DATA_URL)
                // MUST NOT: 이 값을 출력에 찍지 않는다. 이메일이 들어 있다.
                .defaultHeader("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT"))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    private static String body(RestClient client, String path) {
        try {
            Thread.sleep(THROTTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("탐침이 인터럽트됐다", e);
        }
        ResponseEntity<String> response =
                client.get().uri(path).retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("HTTP %s — 상태를 안 보면 이 본문이 「0건」으로 접힌다", response.getStatusCode())
                .isTrue();
        return response.getBody();
    }
}
