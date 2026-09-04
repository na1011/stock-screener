package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>우리 코드가 무엇을 만드는가</b>를 잰다 — 소스가 무엇을 주는가를 재는 다른 탐침들과 목적이
 * 다르다.
 *
 * <p>답할 것이 둘이다.
 * <ol>
 *   <li><b>회계연도 창이 어느 보고서에서 오는가.</b> {@code SecEdgarFacts.annualWindows}는 길이
 *       350~380일이면서 <b>앵커의 종료일과 같은</b> duration 팩트를 전부 회계연도 창으로
 *       등록한다 — 앵커의 {@code form}을 보지 않는다. 10-Q가 「Twelve Months Ended」 열을
 *       실으면 그 10-Q의 앵커 종료일(분기말)과 팩트 종료일이 같아져 <b>롤링 창이 회계연도가
 *       된다.</b> 이 탐침이 그것을 form과 함께 찍는다.</li>
 *   <li><b>회계연도 종료일이 신년을 얼마나 넘나드는가.</b> 「12월 31일에 가장 가까운 요일」로
 *       결산하는 회사는 종료일이 12-25 … 01-04를 떠돈다. 그 분포의 빈 구간이 회계연도 번호를
 *       며칠 물러서서 읽을 때 쓸 상수의 근거다.</li>
 * </ol>
 *
 * <p><b>MUST: 모집단을 {@link SecEdgarColumn}에서 가져온다.</b> 태그 목록을 손으로 박으면
 * <b>적재의 창과 다른 것을 재게 된다</b>({@code SecEdgarOperatingIncomeTagProbeLiveTest}가
 * 같은 함정을 javadoc에 적어 뒀다).
 *
 * <p><b>MUST NOT: {@code SEC_EDGAR_USER_AGENT}를 출력에 찍지 않는다</b> — 이메일이 들어 있다.
 *
 * <p>표본은 {@code -Dprobe.tickers=AMZN,JNJ,…}로 준다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SEC_EDGAR_USER_AGENT", matches = ".+",
        disabledReason = "SEC_EDGAR_USER_AGENT가 없어 건너뛴다")
class SecEdgarLabelDiffProbeLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 기본 표본 14. <b>유형을 섞은 것이 계약이다.</b>
     *
     * <p>거명된 일곱(A1 여섯 + A2 하나)만으로는 「10-Q에서 오는가」를 판정할 수 없다 —
     * A2×FY가 0이기 때문이다. 그래서 A2×FY 셋({@code BIVI}·{@code COSM}·{@code XBIO}),
     * 결함 3의 적재창 안 전형 셋({@code HURA}·{@code MAGN}·{@code NTRP}), 결산월 변경 대조
     * {@code ARTW}, 관용의 거울 {@code STEX}, 그리고 <b>조용한 증상</b>의 대조군
     * {@code KBR}를 함께 넣는다.
     */
    private static final String DEFAULT_TICKERS =
            "AMZN,JNJ,ILMN,CDNS,LHX,DPZ,MAR,BIVI,COSM,XBIO,HURA,MAGN,NTRP,ARTW,STEX,KBR";

    private static final int ANNUAL_MIN = 350;
    private static final int ANNUAL_MAX = 380;

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

    /** 한 팩트의 최소 정보. 앵커 계산과 창 판정에 필요한 것만 담는다. */
    private record Fact(String accn, String form, LocalDate start, LocalDate end, String tag) {
        long days() {
            return ChronoUnit.DAYS.between(start, end);
        }
    }

    @Test
    @DisplayName("[live][probe] 회계연도 창이 어느 form에서 오는가 · 종료일이 신년을 얼마나 넘나드는가")
    void probeAnnualWindowSources() {
        List<String> tickers = List.of(
                System.getProperty("probe.tickers", DEFAULT_TICKERS).split(","));

        RestClient www = client("https://www.sec.gov");
        RestClient data = client("https://data.sec.gov");
        Map<String, Long> cikOf = tickerToCik(www);

        Map<String, Integer> formCount = new TreeMap<>();
        Map<String, Integer> endHistogram = new TreeMap<>();
        int withTenQ = 0;

        System.out.println("\n===== 회계연도 창이 되는 12개월 팩트 (앵커 종료일과 같은 것만) =====");
        for (String raw : tickers) {
            String t = raw.trim();
            Long cik = cikOf.get(t);
            if (cik == null) {
                System.out.printf("%-6s CIK 매핑 없음%n", t);
                continue;
            }
            pause();
            String body = data.get()
                    .uri("/api/xbrl/companyfacts/CIK%010d.json".formatted(cik))
                    .retrieve().body(String.class);
            if (body == null || body.isBlank() || body.startsWith("<")) {
                System.out.printf("%-6s companyfacts 응답 없음/HTML%n", t);
                continue;
            }

            List<Fact> durations = durationFacts(MAPPER.readTree(body).path("facts"));
            // 앵커: 보고서(accn)별로 유량 팩트 중 종료일이 가장 늦은 것.
            Map<String, LocalDate> anchorEnd = new HashMap<>();
            Map<String, String> anchorForm = new HashMap<>();
            for (Fact f : durations) {
                LocalDate prev = anchorEnd.get(f.accn());
                if (prev == null || f.end().isAfter(prev)) {
                    anchorEnd.put(f.accn(), f.end());
                    anchorForm.put(f.accn(), f.form());
                }
            }

            // 회계연도 창이 되는 팩트 = 12개월이고 앵커 종료일과 같다. 종료일별로 접는다.
            Map<LocalDate, java.util.SortedSet<String>> windowForm = new TreeMap<>();
            for (Fact f : durations) {
                if (f.days() < ANNUAL_MIN || f.days() > ANNUAL_MAX) {
                    continue;
                }
                if (!f.end().equals(anchorEnd.get(f.accn()))) {
                    continue;
                }
                windowForm.computeIfAbsent(f.end(), k -> new java.util.TreeSet<>()).add(f.form());
            }

            List<String> tenQ = new ArrayList<>();
            for (Map.Entry<LocalDate, java.util.SortedSet<String>> e : windowForm.entrySet()) {
                String forms = String.join("+", e.getValue());
                formCount.merge(forms, 1, Integer::sum);
                String md = "%02d-%02d".formatted(e.getKey().getMonthValue(), e.getKey().getDayOfMonth());
                if (e.getKey().getMonthValue() == 1 || (e.getKey().getMonthValue() == 12
                        && e.getKey().getDayOfMonth() >= 24)) {
                    endHistogram.merge(md, 1, Integer::sum);
                }
                if (e.getValue().contains("10-Q")) {
                    tenQ.add(e.getKey() + "(" + forms + ")");
                }
            }
            if (!tenQ.isEmpty()) {
                withTenQ++;
            }
            System.out.printf("%-6s 창 %2d개 · 10-Q에서 온 것 %d %s%n",
                    t, windowForm.size(), tenQ.size(), tenQ.isEmpty() ? "" : tenQ);
        }

        System.out.println("\n===== 창을 만든 form 분포 =====");
        formCount.forEach((f, n) -> System.out.printf("  %-14s %d%n", f, n));
        System.out.printf("10-Q가 만든 창을 가진 종목: %d / %d%n", withTenQ, tickers.size());

        System.out.println("\n===== 회계연도 창 종료일 — 12월 하순 · 1월 =====");
        endHistogram.forEach((md, n) -> System.out.printf("  %s %d%n", md, n));
        System.out.println("(빈 구간이 NEW_YEAR_DRIFT_DAYS의 근거다. 01-04~01-24가 비어야 4가 선다.)");
    }

    /** {@link SecEdgarColumn}의 duration 컬럼 후보 태그에서 유량 팩트를 모은다. */
    private static List<Fact> durationFacts(JsonNode facts) {
        List<Fact> out = new ArrayList<>();
        for (SecEdgarColumn column : SecEdgarColumn.values()) {
            if (column.instant()) {
                continue;
            }
            for (String candidate : column.candidates()) {
                boolean dei = candidate.startsWith("dei:");
                JsonNode tag = facts.path(dei ? "dei" : "us-gaap")
                        .path(dei ? candidate.substring(4) : candidate);
                for (String unit : tag.path("units").propertyNames()) {
                    if (!column.acceptsUnit(unit)) {
                        continue;
                    }
                    for (JsonNode node : tag.path("units").path(unit)) {
                        String start = node.path("start").asString("");
                        String end = node.path("end").asString("");
                        if (start.isEmpty() || end.isEmpty()) {
                            continue;
                        }
                        out.add(new Fact(node.path("accn").asString(""),
                                node.path("form").asString(""),
                                LocalDate.parse(start), LocalDate.parse(end), candidate));
                    }
                }
            }
        }
        return out;
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
