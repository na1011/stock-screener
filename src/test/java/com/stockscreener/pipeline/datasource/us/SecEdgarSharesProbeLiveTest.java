package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p><b>MUST: 티커를 {@code SecTickerCikCache}와 같게 정규화한다</b>({@code '.' → '-'}).
 * 2026-09-14까지 이 탐침은 그것을 빼먹어 기본 표본의 {@code BRK.A}·{@code BRK.B}가
 * <b>「CIK 매핑 없음」으로 조용히 빠졌다</b> — 그 출력은 「소스에 개념이 없다」와 생김새가 같다.
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
            Long cik = cikOf.get(t.replace('.', '-'));
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

    @Test
    @DisplayName("[live][probe] 같은 보고서 안에서 태그끼리 값이 얼마나 갈리는가")
    void probeCrossTagSpreadWithinOneFiling() {
        List<String> tickers = List.of(
                System.getProperty("probe.tickers", DEFAULT_TICKERS).split(","));

        RestClient www = client("https://www.sec.gov");
        RestClient data = client("https://data.sec.gov");
        Map<String, Long> cikOf = tickerToCik(www);
        List<String> candidates = SecEdgarColumn.SHARES_OUTSTANDING.candidates();

        // 같은 시점의 같은 회사 주식수는 태그가 달라도 자릿수가 같아야 한다. 갈린다면
        // 한쪽이 다른 것을 재고 있거나 오염된 것이고, 그 배수가 게이트 임계의 근거다.
        System.out.println("\n===== accn 하나 안에서 태그 간 max/min =====");
        for (String raw : tickers) {
            String t = raw.trim();
            // MUST: SEC 표기로 정규화한다 — 이 파일은 점 표기(BRK.A)를 그대로 물으면
            // 「CIK 매핑 없음」을 내는데, 적재는 SecTickerCikCache가 하이픈으로 바꿔 찾는다.
            // 안 맞추면 탐침의 결함을 대상의 결함으로 읽는다(2026-09-04에 실제로 그랬다).
            Long cik = cikOf.get(t.replace('.', '-'));
            if (cik == null) {
                cik = cikOf.get(t.replace('.', '-'));
            }
            if (cik == null) {
                System.out.printf("%-7s CIK 매핑 없음%n", t);
                continue;
            }
            pause();
            String body = data.get()
                    .uri("/api/xbrl/companyfacts/CIK%010d.json".formatted(cik))
                    .retrieve().body(String.class);
            if (body == null || body.isBlank() || body.startsWith("<")) {
                System.out.printf("%-7s companyfacts 응답 없음/HTML%n", t);
                continue;
            }
            JsonNode facts = MAPPER.readTree(body).path("facts");

            // accn|end → 태그별 값
            Map<String, Map<String, BigDecimal>> byFiling = new LinkedHashMap<>();
            for (String candidate : candidates) {
                boolean dei = candidate.startsWith("dei:");
                JsonNode tag = facts.path(dei ? "dei" : "us-gaap")
                        .path(dei ? candidate.substring(4) : candidate);
                for (String unit : tag.path("units").propertyNames()) {
                    for (JsonNode node : tag.path("units").path(unit)) {
                        if (!node.has("val")) {
                            continue;
                        }
                        BigDecimal v = new BigDecimal(node.path("val").asString("0"));
                        if (v.signum() <= 0) {
                            continue;   // 0은 비율의 분모가 못 된다
                        }
                        String key = node.path("accn").asString("") + "|"
                                + node.path("end").asString("");
                        byFiling.computeIfAbsent(key, k -> new LinkedHashMap<>())
                                .merge(candidate, v, BigDecimal::max);
                    }
                }
            }
            double worst = 0;
            String worstKey = "";
            int compared = 0;
            for (Map.Entry<String, Map<String, BigDecimal>> e : byFiling.entrySet()) {
                if (e.getValue().size() < 2) {
                    continue;
                }
                compared++;
                double mx = e.getValue().values().stream()
                        .mapToDouble(BigDecimal::doubleValue).max().orElse(0);
                double mn = e.getValue().values().stream()
                        .mapToDouble(BigDecimal::doubleValue).min().orElse(1);
                if (mn > 0 && mx / mn > worst) {
                    worst = mx / mn;
                    worstKey = e.getKey() + " " + e.getValue();
                }
            }
            System.out.printf("%-7s 비교가능 보고서=%d/%d 최대배수=%.1f  %s%n",
                    t, compared, byFiling.size(), worst,
                    worst > 100 ? worstKey : "");
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
        // MUST: 적재와 같은 승자 규칙을 쓴다 — SecTickerCikCache.parse가 map.put이라
        // **마지막 행이 이긴다.** putIfAbsent(첫 행 승)로 두면 매핑 파일에 중복 티커가 있을 때
        // 탐침이 적재와 **다른 회사**를 재고, 그 차이는 출력 어디에도 드러나지 않는다.
        Map<String, Long> cikOf = new LinkedHashMap<>();
        for (JsonNode row : map.path("data")) {
            cikOf.put(row.get(iTicker).asString(""), row.get(iCik).asLong());
        }
        return cikOf;
    }

    // ---------- 세 번째 시험: WeightedAverage* 후보를 뺐을 때 무엇이 달라지는가 ----------

    /**
     * 잰 것을 담는다. {@code error}가 있으면 그 팔은 재구성에 실패한 것이고 나머지는 비어 있다.
     *
     * <p><b>MUST: 실패를 「값 없음」과 섞지 않는다.</b> {@code build}는 「같은 라벨에 기간이 둘」에서
     * 던지는데(SecEdgarFacts), 그것을 결손으로 세면 <b>회수된 종목과 부순 종목이 한 칸에 들어간다.</b>
     */
    private record Built(Map<String, java.math.BigDecimal> shares,
                         Map<String, java.time.LocalDate> published,
                         Map<String, Integer> nonNullByColumn,
                         int reports, int dropped, String error) {
    }

    /** 주식수 외 컬럼이 사라지거나 되살아나는 것을 세는 축. 창이 바뀌면 여기가 움직인다. */
    private static final List<String> WATCHED_COLUMNS =
            List.of("revenue", "operatingIncome", "netIncome", "totalAssets", "totalEquity",
                    "operatingCashFlow");

    private static Built build(JsonNode facts, int fromFiscalYear) {
        try {
            SecEdgarFacts.Result result = SecEdgarFacts.build(facts, fromFiscalYear);
            Map<String, java.math.BigDecimal> shares = new LinkedHashMap<>();
            Map<String, java.time.LocalDate> published = new LinkedHashMap<>();
            Map<String, Integer> columns = new LinkedHashMap<>();
            WATCHED_COLUMNS.forEach(c -> columns.put(c, 0));
            for (com.stockscreener.pipeline.datasource.RawFinancialReport r : result.reports()) {
                String label = r.fiscalYear() + "/" + r.fiscalPeriod();
                // 같은 라벨의 판이 여럿이면 **가장 늦은 공시일**이 저장본의 is_latest에 대응한다.
                java.time.LocalDate seen = published.get(label);
                if (seen == null || r.publishedAt().isAfter(seen)) {
                    published.put(label, r.publishedAt());
                    shares.put(label, r.sharesOutstanding());
                }
                bump(columns, "revenue", r.revenue());
                bump(columns, "operatingIncome", r.operatingIncome());
                bump(columns, "netIncome", r.netIncome());
                bump(columns, "totalAssets", r.totalAssets());
                bump(columns, "totalEquity", r.totalEquity());
                bump(columns, "operatingCashFlow", r.operatingCashFlow());
            }
            return new Built(shares, published, columns, result.reports().size(),
                    result.dropped(), null);
        } catch (RuntimeException | com.stockscreener.pipeline.datasource.DataSourceException e) {
            return new Built(Map.of(), Map.of(), Map.of(), 0, 0,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void bump(Map<String, Integer> counts, String key, java.math.BigDecimal v) {
        if (v != null) {
            counts.merge(key, 1, Integer::sum);
        }
    }

    /** 저장본. {@code is_latest} 판의 라벨별 공시일과 주식수다. */
    private static Map<String, Object[]> storedOf(java.sql.Connection db, String ticker)
            throws Exception {
        Map<String, Object[]> out = new LinkedHashMap<>();
        try (java.sql.PreparedStatement ps = db.prepareStatement("""
                SELECT f.fiscal_year, f.fiscal_period, f.published_at, f.shares_outstanding
                FROM financial_statement f JOIN stock s ON s.id = f.stock_id
                WHERE s.ticker = ? AND s.market_code = 'US' AND f.is_latest
                """)) {
            ps.setString(1, ticker);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getInt(1) + "/" + rs.getString(2),
                            new Object[] {rs.getDate(3).toLocalDate(), rs.getBigDecimal(4)});
                }
            }
        }
        return out;
    }

    /**
     * 대상. {@code US_TOP1000} 편입 ∪ 1000배 계단 종목 ∪ 가드 이상치.
     *
     * <p><b>왜 이 합집합인가</b>: 태그 변경은 이미 저장된 값을 못 바꾸므로 그 효과는 <b>앞으로
     * 들어올 행</b>에만 나고, 그 행이 실제로 들어오는 것은 잡의 대상인 편입 종목이다. 나머지 둘은
     * <b>이번에 고치려는 오염 자체</b>라 대조군으로 함께 넣는다.
     */
    private static List<String> targets() throws Exception {
        List<String> out = new ArrayList<>();
        try (java.sql.Connection c = com.stockscreener.pipeline.support.ReadOnlyDb.open();
             java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("""
                WITH seq AS (
                  SELECT f.stock_id, s.ticker, f.shares_outstanding sh,
                         lag(f.shares_outstanding) OVER (
                             PARTITION BY f.stock_id ORDER BY f.period_end) prev
                  FROM financial_statement f JOIN stock s ON s.id = f.stock_id
                  WHERE s.market_code = 'US' AND f.is_latest AND f.shares_outstanding > 0),
                med AS (
                  SELECT stock_id, (percentile_cont(0.5) WITHIN GROUP (
                             ORDER BY shares_outstanding))::numeric AS md
                  FROM financial_statement WHERE shares_outstanding > 0
                  GROUP BY 1 HAVING count(*) >= 4),
                cur AS (
                  SELECT DISTINCT ON (f.stock_id) f.stock_id, f.shares_outstanding AS cur
                  FROM financial_statement f
                  WHERE f.shares_outstanding > 0 AND f.is_latest
                  ORDER BY f.stock_id, f.period_end DESC)
                SELECT s.ticker FROM universe_membership m JOIN stock s ON s.id = m.stock_id
                WHERE m.universe_code = 'US_TOP1000' AND m.effective_to IS NULL
                UNION
                SELECT ticker FROM seq
                WHERE prev > 0 AND (sh / prev BETWEEN 900 AND 1100
                                    OR prev / sh BETWEEN 900 AND 1100)
                UNION
                SELECT s.ticker FROM cur c JOIN med ON med.stock_id = c.stock_id
                     JOIN stock s ON s.id = c.stock_id
                WHERE c.cur / med.md > 50 AND s.market_code = 'US'
                ORDER BY 1
                """)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    @Test
    @DisplayName("[live][probe] WeightedAverage* 후보를 빼면 결손이 얼마나 느는가")
    void probeWeightedAverageRemoval() throws Exception {
        // MUST: 적재가 실제로 쓴 값이다. batch_execution.note가 「연도=2018~2026」이고 DB의
        // min(fiscal_year)도 2018이다. 기본 3년(→2023)을 주면 SecEdgarFacts의 라벨 충돌 게이트가
        // 적재 창 밖으로 좁혀져 **예외 축이 0으로 나오고** "빼도 예외는 안 변한다"는 거짓이 된다.
        int fromFiscalYear = Integer.getInteger("probe.fromFiscalYear", 2018);
        String scope = System.getProperty("probe.shareTagScope", "timing");

        // MUST: 지울 태그를 손으로 박지 않는다 — 적재와 다른 것을 재게 된다.
        List<String> weighted = SecEdgarColumn.SHARES_OUTSTANDING.candidates().stream()
                .filter(c -> c.startsWith("WeightedAverage")).toList();
        assertThat(weighted)
                .as("후보에서 WeightedAverage* 둘을 찾지 못했다 — 이 시험이 잴 것이 없다")
                .hasSize(2);
        // MUST: 다른 컬럼이 같은 태그를 후보로 갖지 않는 것을 잠근다. 지금 성립하는 것은
        // **우연**이고, 깨지면 트리에서 지우는 것이 「후보에서 빼는 것」과 달라져 조용히 다른
        // 것을 잰다(트리 삭제는 모든 컬럼에서 사라진다).
        for (SecEdgarColumn column : SecEdgarColumn.values()) {
            if (column != SecEdgarColumn.SHARES_OUTSTANDING) {
                assertThat(column.candidates()).as("%s가 같은 태그를 공유한다", column)
                        .doesNotContainAnyElementsOf(weighted);
            }
        }

        List<String> all = targets();
        List<String> tickers = new ArrayList<>();
        if ("full".equals(scope)) {
            tickers.addAll(all);
        } else {
            // MUST: 접두가 아니라 균등 간격으로 뽑는다. 목록이 티커 정렬이라 앞에서 자르면
            // 표본이 아니라 접두이고, 그 위에 세운 소요 추정은 알파벳 편향을 물려받는다.
            int want = Math.min(25, all.size());
            for (int k = 0; k < want; k++) {
                tickers.add(all.get((int) ((long) k * all.size() / want)));
            }
            // MUST: 최대 응답 종목을 반드시 넣는다. JPM이 7.9MB이고(api-spec), 빠지면
            // 512MB 힙 판정이 **거짓 음성**이 된다.
            if (!tickers.contains("JPM") && all.contains("JPM")) {
                tickers.add("JPM");
            }
        }

        System.out.printf("%n===== WeightedAverage* 제거 대조 =====%n");
        System.out.printf("scope=%s 대상=%d/%d · fromFiscalYear=%d · 지울 태그=%s%n",
                scope, tickers.size(), all.size(), fromFiscalYear, weighted);
        System.out.println("**A는 DB 저장본이 아니다** — 지금 코드로 지금 소스를 재구성한 것이다.");
        System.out.println("**배포한다고 저장된 값이 저절로 안 바뀐다** — FinancialWriter가"
                + " 기존 non-null을 안 덮는다(같은 공시일이면 fillMissing이 NULL만 채운다).");

        RestClient www = client("https://www.sec.gov");
        RestClient data = client("https://data.sec.gov");
        Map<String, Long> cikOf = tickerToCik(www);

        long[] elapsed = new long[tickers.size()];
        long[] bytes = new long[tickers.size()];
        int measured = 0;
        int noCik = 0;
        int fetchFailed = 0;

        int lostAll = 0;          // ① 주식수가 전량 사라진 종목
        int lostRankRow = 0;      // ② 순위가 쓰는 「최신 행 하나」를 잃은 종목
        int becameZero = 0;
        int fakeRevisions = 0;    // ③ B의 공시일이 저장본과 다른 (종목,라벨)
        int fakeRevisionStocks = 0;
        int columnGained = 0;     // ④
        int columnLost = 0;
        int droppedDelta = 0;     // ⑤
        int throwOnlyA = 0;       // ⑥
        int throwOnlyB = 0;
        int throwBoth = 0;
        int basicDiffers = 0;     // ⑩
        int dilutedDiffers = 0;
        List<String> valueChanged = new ArrayList<>();
        List<String> notable = new ArrayList<>();

        Set<String> watch = Set.of("TBLA", "EVER", "SENEA", "SENEB", "RPAY", "DDS", "PKG", "ECHO",
                "AAPL", "NVDA", "AMZN", "QXO", "DVLT", "CMG", "GRMN", "AKTX", "JPM");

        try (java.sql.Connection db = com.stockscreener.pipeline.support.ReadOnlyDb.open()) {
            for (int i = 0; i < tickers.size(); i++) {
                String t = tickers.get(i);
                Long cik = cikOf.get(t.replace('.', '-'));
                if (cik == null) {
                    cik = cikOf.get(t.replace('.', '-'));
                }
                if (cik == null) {
                    noCik++;
                    continue;
                }
                pause();
                long began = System.nanoTime();
                org.springframework.http.ResponseEntity<String> response = data.get()
                        .uri("/api/xbrl/companyfacts/CIK%010d.json".formatted(cik))
                        .retrieve().toEntity(String.class);
                JsonNode facts;
                try {
                    // MUST: 상태코드를 먼저 본다. 본문만 파싱하면 403·429가 「팩트 없음」으로
                    // 접혀 **결손이 폭증한 것처럼** 보인다(.claude/rules/data-pipeline.md).
                    facts = SecEdgarResponse.requireFacts(
                            response.getStatusCode(), response.getBody(), "companyfacts " + t);
                } catch (com.stockscreener.pipeline.datasource.DataSourceException e) {
                    fetchFailed++;
                    System.out.printf("  %-7s 수집 실패 %s — %s%n", t, response.getStatusCode(),
                            e.kind());
                    if (!response.getStatusCode().is2xxSuccessful()
                            && response.getStatusCode().value() != 404) {
                        // MUST: 스로틀·차단은 미측정이다. 유발하면 IP가 일시 제한되어 남은
                        // 측정이 전부 막히므로 즉시 멈춘다.
                        System.out.println("  → 404가 아닌 실패다. 스로틀일 수 있어 멈춘다.");
                        break;
                    }
                    continue;
                }
                bytes[measured] = response.getBody() == null ? 0 : response.getBody().length();

                Built a = build(facts, fromFiscalYear);
                Built b1 = buildWithout(facts, List.of(weighted.get(0)), fromFiscalYear);
                Built b2 = buildWithout(facts, List.of(weighted.get(1)), fromFiscalYear);
                Built b3 = buildWithout(facts, weighted, fromFiscalYear);
                elapsed[measured] = System.nanoTime() - began;
                measured++;

                if (a.error() != null && b3.error() != null) {
                    throwBoth++;
                } else if (a.error() != null) {
                    throwOnlyA++;
                    notable.add(t + " A만 던짐: " + a.error());
                } else if (b3.error() != null) {
                    throwOnlyB++;
                    notable.add(t + " B만 던짐: " + b3.error());
                }
                if (!b1.shares().equals(b3.shares())) {
                    dilutedDiffers++;
                }
                if (!b2.shares().equals(b3.shares())) {
                    basicDiffers++;
                }
                droppedDelta += b3.dropped() - a.dropped();
                for (String column : WATCHED_COLUMNS) {
                    int delta = b3.nonNullByColumn().getOrDefault(column, 0)
                            - a.nonNullByColumn().getOrDefault(column, 0);
                    if (delta > 0) {
                        columnGained += delta;
                    } else {
                        columnLost += -delta;
                    }
                }

                long aShares = a.shares().values().stream().filter(java.util.Objects::nonNull)
                        .filter(v -> v.signum() > 0).count();
                long bShares = b3.shares().values().stream().filter(java.util.Objects::nonNull)
                        .filter(v -> v.signum() > 0).count();
                long bZero = b3.shares().values().stream().filter(java.util.Objects::nonNull)
                        .filter(v -> v.signum() == 0).count();
                becameZero += bZero;
                if (aShares > 0 && bShares == 0) {
                    lostAll++;
                    notable.add(t + " 주식수 전량 소멸 (A " + aShares + "라벨)");
                }
                // ② 순위 축: 최근 2년 안 최신 행 하나가 남는가. 라벨이 아니라 **기간**으로 봐야
                // 하므로 저장본의 라벨 집합과 교차해 본다.
                Map<String, Object[]> stored = storedOf(db, t);
                boolean rankKeptA = a.shares().entrySet().stream()
                        .anyMatch(e -> e.getValue() != null && e.getValue().signum() > 0);
                boolean rankKeptB = b3.shares().entrySet().stream()
                        .anyMatch(e -> e.getValue() != null && e.getValue().signum() > 0);
                if (rankKeptA && !rankKeptB) {
                    lostRankRow++;
                }
                // ③ **B와 저장본**을 견준다. FinancialWriter가 비교하는 상대가 DB이기 때문이다.
                int stockFake = 0;
                for (Map.Entry<String, java.time.LocalDate> e : b3.published().entrySet()) {
                    Object[] row = stored.get(e.getKey());
                    if (row != null && !e.getValue().equals(row[0])) {
                        stockFake++;
                    }
                }
                if (stockFake > 0) {
                    fakeRevisions += stockFake;
                    fakeRevisionStocks++;
                }
                // ⑧⑨ 대조군의 값 변화
                if (watch.contains(t)) {
                    String latest = latestLabel(a.shares().keySet());
                    valueChanged.add("%-6s A=%s B=%s  (라벨 %s · A보고서 %d → B %d)".formatted(
                            t, fmt(a.shares().get(latest)), fmt(b3.shares().get(latest)),
                            latest, a.reports(), b3.reports()));
                }

                if (measured % 25 == 0) {
                    System.out.printf("  … %d/%d 확인 · 전량소멸 %d · 가짜정정 %d행/%d종목%n",
                            measured, tickers.size(), lostAll, fakeRevisions, fakeRevisionStocks);
                }
            }
        }

        System.out.printf("%n===== 소요 (측정 %d종목) =====%n", measured);
        if (measured > 0) {
            long[] ms = new long[measured];
            for (int i = 0; i < measured; i++) {
                ms[i] = elapsed[i] / 1_000_000;
            }
            long[] sorted = ms.clone();
            java.util.Arrays.sort(sorted);
            long total = java.util.Arrays.stream(ms).sum();
            long maxMs = sorted[measured - 1];
            double avg = total / (double) measured;
            long totalBytes = java.util.Arrays.stream(bytes, 0, measured).sum();
            System.out.printf("종목당 평균 %.0fms · 중앙값 %dms · 최대 %dms · 응답 평균 %.2fMB · 최대 %.2fMB%n",
                    avg, sorted[measured / 2], maxMs,
                    totalBytes / (double) measured / 1e6,
                    java.util.Arrays.stream(bytes, 0, measured).max().orElse(0) / 1e6);
            System.out.printf("**전량 %d종목 추정: 평균기준 %.1f분 · 최댓값기준 %.1f분**%n",
                    all.size(), avg * all.size() / 60000.0, maxMs * (double) all.size() / 60000.0);
            System.out.println("**점추정을 신뢰하지 않는다** — 같은 저장소에서 균등간격 40종목의"
                    + " 점추정이 census 대비 1.9배 빗나간 전례가 있다"
                    + "(SecEdgarIfrsAndBulkProbeLiveTest).");
            System.out.println("**이 수가 대체하는 것은 전송·파싱 시간뿐이다** — 스로틀·힙 누적·GC는"
                    + " 이 규모에서 원리적으로 발현되지 않는다.");
        }

        System.out.printf("%n===== 결과 =====%n");
        System.out.printf("① 주식수 전량 소멸: **%d종목**%n", lostAll);
        System.out.printf("② 순위 축(최신 주식수 하나)을 잃은 종목: **%d** · 0이 된 라벨: %d%n",
                lostRankRow, becameZero);
        System.out.printf("③ 가짜 정정(B의 공시일 ≠ 저장본): **%d행 / %d종목**%n",
                fakeRevisions, fakeRevisionStocks);
        System.out.printf("④ 주식수 외 컬럼: 되살아남 %d · 사라짐 %d%n", columnGained, columnLost);
        System.out.printf("⑤ dropped 증감 합: %d%n", droppedDelta);
        System.out.printf("⑥ 예외: A만 %d · B만 %d · 양쪽 %d (양쪽은 이 측정의 분모에서 빠진다)%n",
                throwOnlyA, throwOnlyB, throwBoth);
        System.out.printf("⑩ Diluted만 빼면 달라지는 종목 %d · Basic만 빼면 %d%n",
                dilutedDiffers, basicDiffers);
        System.out.printf("수집: CIK 없음 %d · 수집 실패 %d%n", noCik, fetchFailed);
        System.out.println("\\n⑧⑨ 대조군");
        valueChanged.forEach(line -> System.out.println("  " + line));
        if (!notable.isEmpty()) {
            System.out.println("\\n예외·소멸 상세");
            notable.stream().forEach(line -> System.out.println("  " + line));
        }

        assertThat(measured).as("한 종목도 못 쟀으면 위 수는 「잴 것이 없었다」다").isPositive();
    }

    /** 그 태그들을 {@code us-gaap}에서 뺀 트리로 재구성한다. 끝나면 원래대로 돌려놓는다. */
    private static Built buildWithout(JsonNode facts, List<String> tags, int fromFiscalYear) {
        JsonNode usGaap = facts.path("us-gaap");
        // IFRS 전용 발행사는 이 노드가 MissingNode다(사내 census 494종목). 캐스팅하면
        // ClassCastException이 나 집계가 통째로 중단된다.
        if (!usGaap.isObject()) {
            return build(facts, fromFiscalYear);
        }
        tools.jackson.databind.node.ObjectNode node = (tools.jackson.databind.node.ObjectNode) usGaap;
        Map<String, JsonNode> removed = new LinkedHashMap<>();
        for (String tag : tags) {
            JsonNode gone = node.remove(tag);
            if (gone != null) {
                removed.put(tag, gone);
            }
        }
        try {
            return build(facts, fromFiscalYear);
        } finally {
            removed.forEach(node::set);
        }
    }

    private static String latestLabel(Set<String> labels) {
        return labels.stream().max(String::compareTo).orElse("-");
    }

    private static String fmt(java.math.BigDecimal v) {
        return v == null ? "NULL" : v.toPlainString();
    }
}
