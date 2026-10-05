package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.stockscreener.pipeline.support.ReadOnlyDb;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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

    /**
     * <b>저장본 대 새 산출.</b> 라벨 규칙을 고친 뒤 <b>기존 종목의 라벨이 설명 없이 바뀌지
     * 않았는가</b>를 재는 것이 이 시험의 전부다.
     *
     * <p><b>비교 키가 라벨이 아니라 기간이다.</b> 라벨을 키로 하면 재라벨이 「없어짐 +
     * 새로생김」으로 보여, 읽는 사람이 <i>"추가만 있으니 안전하다"</i>로 오판한다. 기간을
     * 키로 하면 같은 자리가 <b>「값 변경」</b>으로 드러난다.
     *
     * <p><b>옛 코드를 다시 돌리지 않는다</b> — 저장본이 곧 「고치기 전 코드가 만든 것」이라
     * 콜이 절반이다. 다만 <b>그 사이 소스가 재발행되면 그것도 차분에 섞인다</b>: 신규 공시는
     * 「새로생김」으로, 새 연차보고서가 만든 창은 {@code projectYear} 기준을 바꿔 「라벨바뀜」
     * 으로 나온다. <b>라벨바뀜은 종목을 열어 「수정 때문인가 신규 공시 때문인가」를 가른
     * 뒤에 판정한다.</b>
     *
     * <p><b>MUST: {@code -Dprobe.fromFiscalYear}를 저장본을 만든 값과 맞춘다.</b> 다르면
     * 경계 연도가 통째로 「사라짐/새로생김」이 된다. 지금 저장본의 실질 하한은 <b>2019</b>다.
     */
    @Test
    @DisplayName("[live][probe] 저장본 대 새 산출 — 라벨이 설명 없이 바뀌지 않았는가")
    void diffStoredVersusRebuilt() throws Exception {
        List<String> tickers = List.of(
                System.getProperty("probe.tickers", DEFAULT_TICKERS).split(","));
        int fromFiscalYear = Integer.parseInt(System.getProperty("probe.fromFiscalYear", "2019"));
        // 「우리 코드가 무엇을 만드는가」는 보충까지다 — 설정을 명시해야 돈다(ADR-0173).
        java.util.Optional<SecFilingSupplement> supplement = ProbeSupplement.fromEnv();

        RestClient www = client("https://www.sec.gov");
        RestClient data = client("https://data.sec.gov");
        Map<String, Long> cikOf = tickerToCik(www);

        int relabeled = 0;
        int vanished = 0;
        int appeared = 0;
        int emptied = 0;
        int recovered = 0;
        int stillMalformed = 0;
        List<String> relabelDetail = new ArrayList<>();

        try (java.sql.Connection db = ReadOnlyDb.open()) {
            System.out.println("\n===== 저장본 대 새 산출 (기간 키) =====");
            for (String raw : tickers) {
                String t = raw.trim();
                Long cik = cikOf.get(t);
                if (cik == null) {
                    System.out.printf("%-6s CIK 매핑 없음%n", t);
                    continue;
                }
                Map<String, String> stored = storedLabels(db, t);

                pause();
                String body = data.get()
                        .uri("/api/xbrl/companyfacts/CIK%010d.json".formatted(cik))
                        .retrieve().body(String.class);
                if (body == null || body.isBlank() || body.startsWith("<")) {
                    System.out.printf("%-6s companyfacts 응답 없음/HTML%n", t);
                    continue;
                }

                Map<String, String> rebuilt = new LinkedHashMap<>();
                int dropped = -1;
                try {
                    tools.jackson.databind.node.ObjectNode facts =
                            (tools.jackson.databind.node.ObjectNode) MAPPER.readTree(body).path("facts");
                    long cikValue = cik;
                    supplement.ifPresent(s -> s.supplement(cikValue, facts, fromFiscalYear, t));
                    SecEdgarFacts.Result result = SecEdgarFacts.build(facts, fromFiscalYear);
                    dropped = result.dropped();
                    for (var r : result.reports()) {
                        rebuilt.put(r.periodStart() + "~" + r.periodEnd(),
                                r.fiscalYear() + "/" + r.fiscalPeriod());
                    }
                } catch (Exception e) {
                    stillMalformed++;
                    System.out.printf("%-6s 여전히 거절: %s%n", t,
                            String.valueOf(e.getMessage()).replaceAll("\\s+", " "));
                    continue;
                }
                if (stored.isEmpty() && !rebuilt.isEmpty()) {
                    recovered++;
                }
                if (!stored.isEmpty() && rebuilt.isEmpty()) {
                    emptied++;
                }

                int changed = 0;
                for (Map.Entry<String, String> e : stored.entrySet()) {
                    String now = rebuilt.get(e.getKey());
                    if (now == null) {
                        vanished++;
                    } else if (!now.equals(e.getValue())) {
                        changed++;
                        relabeled++;
                        if (relabelDetail.size() < 40) {
                            relabelDetail.add("%s %s: %s → %s"
                                    .formatted(t, e.getKey(), e.getValue(), now));
                        }
                    }
                }
                for (String k : rebuilt.keySet()) {
                    if (!stored.containsKey(k)) {
                        appeared++;
                    }
                }
                System.out.printf("%-6s 저장 %3d · 산출 %3d · 라벨바뀜 %2d · dropped %d%n",
                        t, stored.size(), rebuilt.size(), changed, dropped);
            }
        }

        System.out.println("\n===== 집계 =====");
        System.out.printf("라벨바뀜 %d · 사라짐 %d · 새로생김 %d · 행이 0이 된 종목 %d "
                        + "· 회수 %d · 여전히 거절 %d%n",
                relabeled, vanished, appeared, emptied, recovered, stillMalformed);
        System.out.println("(dropped는 기간이 아니라 팩트를 센다 — 컬럼×태그마다 다시 세므로 "
                + "결함 3 뒤 크게 뛰는 것이 정상이다.)");
        if (!relabelDetail.isEmpty()) {
            System.out.println("\n===== 라벨바뀜 내역 =====");
            relabelDetail.forEach(d -> System.out.println("  " + d));
        }
    }

    /** 저장된 최신 라벨. 기간을 키로, 라벨을 값으로 낸다. */
    private static Map<String, String> storedLabels(java.sql.Connection db, String ticker)
            throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        String sql = """
                SELECT f.period_start, f.period_end, f.fiscal_year, f.fiscal_period
                  FROM financial_statement f JOIN stock s ON s.id = f.stock_id
                 WHERE s.market_code = 'US' AND s.ticker = ? AND f.is_latest
                """;
        try (var ps = db.prepareStatement(sql)) {
            ps.setString(1, ticker);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getDate(1) + "~" + rs.getDate(2),
                            rs.getInt(3) + "/" + rs.getString(4));
                }
            }
        }
        return out;
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
