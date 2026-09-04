package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>상장주식수가 왜 비거나 부푸는가</b>를 소스에서 잰다.
 *
 * <p>DB 실측이 셋을 냈고 코드만으로는 어느 것도 확정되지 않는다.
 * <ol>
 *   <li><b>결손 165종목</b> — 재무는 있는데 {@code shares_outstanding}이 <b>전량 NULL</b>이다.
 *       그 목록이 {@code BRK.A/BRK.B}·{@code GEF/GEF.B}·{@code DGICA/DGICB}·{@code BELFA/BELFB}처럼
 *       <b>복수 클래스 쌍</b>에 몰려 있고 {@code V}(VISA)·{@code BRK}(버크셔)가 그 안이다.
 *       가설: {@code readUnits}가 {@code Slot(accn, start, end)}를 키로 쓰는데 복수 클래스는
 *       <b>같은 accn·같은 종료일</b>에 클래스별 팩트를 내므로 서로 덮는다.</li>
 *   <li><b>부풀림</b> — {@code TBLA} 2,905억 주(중앙값의 966배) · {@code AKTX} 1,557억 주 ·
 *       {@code RPAY} 829억 주. 셋 다 최신 행이고 시총 순위를 왜곡한다.</li>
 *   <li><b>1e6 배수</b> — {@code EIX}·{@code AJG}·{@code AA}·{@code HXL}의 옛 행이 정확히
 *       백만 배다(EIX 362조 주).</li>
 * </ol>
 *
 * <p><b>MUST: 후보 태그를 {@link SecEdgarColumn}에서 가져온다.</b> 손으로 박으면 적재와 다른
 * 것을 재게 된다.
 *
 * <p><b>MUST NOT: {@code SEC_EDGAR_USER_AGENT}를 출력에 찍지 않는다</b> — 이메일이 들어 있다.
 *
 * <p>표본은 {@code -Dprobe.tickers=BRK.A,V,…}로 준다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SEC_EDGAR_USER_AGENT", matches = ".+",
        disabledReason = "SEC_EDGAR_USER_AGENT가 없어 건너뛴다")
class SecEdgarSharesProbeLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 기본 표본 15. <b>세 증상 + 대조군을 섞은 것이 계약이다.</b>
     *
     * <p>결손 여섯({@code BRK.A}·{@code BRK.B}·{@code V}·{@code STZ}·{@code GEF}·{@code PLNT}) ·
     * 부풀림 셋({@code TBLA}·{@code AKTX}·{@code RPAY}) · 1e6 셋({@code EIX}·{@code AJG}·{@code AA}) ·
     * 재무 0행 둘({@code XOM}·{@code TSM}) · <b>정상 대조군</b> {@code AMZN}.
     */
    private static final String DEFAULT_TICKERS =
            "BRK.A,BRK.B,V,STZ,GEF,PLNT,TBLA,AKTX,RPAY,EIX,AJG,AA,XOM,TSM,AMZN";

    private static RestClient client(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                // MUST NOT: 이 값을 출력에 찍지 않는다 — 이메일이 들어 있다.
                .defaultHeader("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT"))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    private static void pause() {
        try {
            Thread.sleep(220);   // 공표 초당 10회의 절반 아래로 둔다(api-sources skill)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("[live][probe] 상장주식수가 소스에서 어떤 모양으로 오는가")
    void probeSharesOutstanding() {
        List<String> tickers = List.of(
                System.getProperty("probe.tickers", DEFAULT_TICKERS).split(","));

        RestClient www = client("https://www.sec.gov");
        RestClient data = client("https://data.sec.gov");
        Map<String, Long> cikOf = tickerToCik(www);

        // MUST: 적재와 같은 창으로 본다.
        List<String> candidates = SecEdgarColumn.SHARES_OUTSTANDING.candidates();
        System.out.println("\n후보 태그(적재와 같은 순서) = " + candidates);

        for (String raw : tickers) {
            String t = raw.trim();
            Long cik = cikOf.get(t);
            if (cik == null) {
                System.out.printf("%n%-7s CIK 매핑 없음%n", t);
                continue;
            }
            pause();
            String body = data.get()
                    .uri("/api/xbrl/companyfacts/CIK%010d.json".formatted(cik))
                    .retrieve().body(String.class);
            if (body == null || body.isBlank() || body.startsWith("<")) {
                System.out.printf("%n%-7s companyfacts 응답 없음/HTML (CIK=%d)%n", t, cik);
                continue;
            }

            JsonNode facts = MAPPER.readTree(body).path("facts");
            System.out.printf("%n===== %s (CIK=%d) · facts 최상위 키 %s =====%n",
                    t, cik, keysOf(facts));

            for (String candidate : candidates) {
                boolean dei = candidate.startsWith("dei:");
                JsonNode tag = facts.path(dei ? "dei" : "us-gaap")
                        .path(dei ? candidate.substring(4) : candidate);
                if (tag.isMissingNode() || tag.path("units").isMissingNode()) {
                    System.out.printf("  %-46s 없음%n", candidate);
                    continue;
                }
                for (String unit : tag.path("units").propertyNames()) {
                    // 같은 (accn,start,end)에 팩트가 여럿이면 적재의 Slot 키가 덮는다.
                    Map<String, List<BigDecimal>> bySlot = new LinkedHashMap<>();
                    Map<String, Integer> formCount = new TreeMap<>();
                    int total = 0;
                    for (JsonNode node : tag.path("units").path(unit)) {
                        String form = node.path("form").asString("");
                        formCount.merge(form, 1, Integer::sum);
                        if (!node.has("val")) {
                            continue;
                        }
                        total++;
                        String slot = node.path("accn").asString("") + "|"
                                + node.path("start").asString("") + "|"
                                + node.path("end").asString("");
                        bySlot.computeIfAbsent(slot, k -> new ArrayList<>())
                                .add(new BigDecimal(node.path("val").asString("0")));
                    }
                    long collided = bySlot.values().stream().filter(v -> v.size() > 1).count();
                    System.out.printf("  %-46s unit=%-12s 팩트=%d 슬롯=%d 충돌슬롯=%d form=%s%n",
                            candidate, unit, total, bySlot.size(), collided, formCount);
                    // 충돌 슬롯을 최대 셋까지 값과 함께 보인다 — 클래스별 분해인지 여기서 갈린다.
                    bySlot.entrySet().stream().filter(e -> e.getValue().size() > 1).limit(3)
                            .forEach(e -> System.out.printf("      충돌 %s → %s%n",
                                    e.getKey(), e.getValue()));
                    // 값의 범위. 1e6 배수와 부풀림이 여기서 드러난다.
                    bySlot.values().stream().flatMap(List::stream)
                            .mapToDouble(BigDecimal::doubleValue).min()
                            .ifPresent(mn -> System.out.printf("      값 최소=%.0f 최대=%.0f%n",
                                    mn, bySlot.values().stream().flatMap(List::stream)
                                            .mapToDouble(BigDecimal::doubleValue).max().orElse(0)));
                }
            }
        }
    }

    private static String keysOf(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.propertyNames().forEach(keys::add);
        return keys.toString();
    }

    private static Map<String, Long> tickerToCik(RestClient www) {
        JsonNode map = MAPPER.readTree(
                www.get().uri("/files/company_tickers_exchange.json").retrieve().body(String.class));
        JsonNode fields = map.path("fields");
        int iTicker = -1;
        int iCik = -1;
        for (int i = 0; i < fields.size(); i++) {
            String name = fields.get(i).asString("");
            if ("ticker".equals(name)) {
                iTicker = i;
            }
            if ("cik".equals(name)) {
                iCik = i;
            }
        }
        Map<String, Long> cikOf = new LinkedHashMap<>();
        for (JsonNode row : map.path("data")) {
            cikOf.putIfAbsent(row.get(iTicker).asString(""), row.get(iCik).asLong());
        }
        return cikOf;
    }
}
