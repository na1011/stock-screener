package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.support.ReadOnlyDb;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC EDGAR가 실제로 무엇을 주는지 잰다. <b>P22의 착수 판단이 여기서 나온다.</b>
 *
 * <p>어댑터를 거치지 않고 {@code RestClient}를 직접 세운다 — 이 시점에는 어댑터가 없고,
 * 재는 대상도 우리 코드가 아니라 <b>소스</b>이기 때문이다({@link AlpacaBarsProbeLiveTest}와
 * 같은 이유·같은 형태다).
 *
 * <p><b>호스트가 둘이다.</b> JSON API는 {@code data.sec.gov}, 매핑 파일과 벌크는
 * {@code www.sec.gov}다. 인증은 없고 <b>신원을 밝히는 User-Agent</b>만 요구한다.
 *
 * <p><b>개발가이드가 이미 답한 것은 다시 재지 않는다.</b> {@code us-market-api-dev-guide.md}
 * §2가 적은 것들이다 — 엔드포인트 형태 · CIK 10자리 zero-padding · frames의 {@code I} ·
 * CORS 미지원 · 초당 10회 · 매핑 파일의 필드 이름. 다시 재면 <b>두 번째 정본</b>을 만든다.
 *
 * <p>여기서 재는 것은 그 문서가 <b>미검증으로 남긴 것</b>(§9의 「태그 편차의 실제 규모」)과
 * <b>이 저장소 고유의 제약</b>이다:
 * <ul>
 *   <li><b>우리 티커가 CIK에 붙는가</b> — {@code cusip}이 8,504건 전부 비어 매핑을 티커로만
 *       만들어야 한다(ADR-0086). 파일럿 11 중 몇이 붙는지가 <b>완료 정의를 정한다.</b></li>
 *   <li><b>{@code fy}/{@code fp}가 사실의 기간이 아니라 보고서를 가리키는가</b> — 참이면
 *       그것을 라벨로 쓰는 구현이 조용히 틀린다.</li>
 *   <li><b>한 보고서가 같은 종료일에 12개월과 3개월을 함께 싣는가</b> — 실으면 둘이 같은
 *       라벨을 받고, 저장 키에 기간이 없어 <b>하나가 조용히 사라진다.</b></li>
 *   <li><b>{@code units} 키의 실제 집합</b> — {@code currency_code}가 {@code character(3)}라
 *       3자를 넘는 키를 담으면 그 종목의 적재가 통째로 롤백된다.</li>
 *   <li><b>{@code fp} 값 집합과 빈 비율</b> — {@code ck_fs_period}가 {@code FY|Q1~Q4}뿐이다.</li>
 *   <li><b>주식수 태그의 실재</b> — 미국은 시세도 재무도 주식수가 비어 있어 PER·PBR의
 *       분모가 이것뿐이다(ADR-0013).</li>
 *   <li><b>17개 금액 컬럼이 실제로 어느 태그로 오는가</b> — 폴백 체인의 유일한 근거.</li>
 * </ul>
 *
 * <p><b>MUST NOT: 이 탐침의 출력에 User-Agent 값을 찍지 않는다.</b> 거기에 이메일이 들어
 * 있고 그 값이 그대로 {@code docs/api-spec/}으로 옮겨 갈 수 있다. {@code guard-secrets.sh}는
 * 경로만 보므로 <b>막는 것이 이 규칙뿐이다.</b>
 *
 * <p><b>MUST NOT: User-Agent를 빼거나 초당 한도를 넘겨 차단을 유발하지 않는다.</b> SEC가
 * IP를 일시 제한하면 그 세션의 나머지 측정이 전부 막힌다. 그 계약은 문서 근거로만 두고
 * 어댑터는 <b>모른다는 것을 전제로</b> 설계한다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SEC_EDGAR_USER_AGENT", matches = ".+",
        disabledReason = "SEC_EDGAR_USER_AGENT가 없어 건너뛴다")
class SecEdgarProbeLiveTest {

    private static final String DATA_URL = "https://data.sec.gov";
    private static final String FILES_URL = "https://www.sec.gov";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 공표 초당 10회의 절반으로 둔다(`api-sources` skill의 「공표값의 절반」). */
    private static final long THROTTLE_MS = 200;

    /** 시세가 이미 들어와 있는 파일럿. 우선주·주식 클래스·외국 발행사가 섞여 있다. */
    private static final List<String> PILOT = List.of(
            "AAPL", "BRK.A", "BRK.B", "F", "KIM.PRL", "META", "MSFT", "NVDA", "RH", "T", "VTEX");

    /** 파일럿이 안 덮는 모양을 채우는 확장 표본. 은행·1월 결산·5월 결산·바이오·REIT. */
    private static final List<String> EXTRA = List.of(
            "JPM", "WMT", "NKE", "MRNA", "O", "XOM", "KO", "TSLA", "COST");

    /**
     * 17개 금액 컬럼별 태그 후보. <b>가설이고, 이 탐침이 채점한다.</b>
     *
     * <p>후보에 없는 태그를 회사가 쓰면 이 표에 안 잡힌다 — 그래서 아래에서 표본의
     * {@code us-gaap} 개념 <b>전체 수</b>도 함께 찍어 「우리가 몇 %를 보고 있는가」를 남긴다.
     */
    private static final Map<String, List<String>> TAG_CANDIDATES = new LinkedHashMap<>();

    static {
        TAG_CANDIDATES.put("revenue", List.of(
                "RevenueFromContractWithCustomerExcludingAssessedTax",
                "RevenueFromContractWithCustomerIncludingAssessedTax",
                "Revenues", "SalesRevenueNet", "SalesRevenueGoodsNet"));
        TAG_CANDIDATES.put("operating_income", List.of("OperatingIncomeLoss"));
        TAG_CANDIDATES.put("net_income", List.of(
                "NetIncomeLoss", "ProfitLoss",
                "NetIncomeLossAvailableToCommonStockholdersBasic"));
        TAG_CANDIDATES.put("total_assets", List.of("Assets"));
        TAG_CANDIDATES.put("total_liabilities", List.of("Liabilities"));
        TAG_CANDIDATES.put("total_equity", List.of(
                "StockholdersEquity",
                "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"));
        TAG_CANDIDATES.put("current_assets", List.of("AssetsCurrent"));
        TAG_CANDIDATES.put("current_liabilities", List.of("LiabilitiesCurrent"));
        TAG_CANDIDATES.put("retained_earnings", List.of("RetainedEarningsAccumulatedDeficit"));
        TAG_CANDIDATES.put("operating_cash_flow", List.of(
                "NetCashProvidedByUsedInOperatingActivities",
                "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations"));
        TAG_CANDIDATES.put("shares_outstanding", List.of(
                "dei:EntityCommonStockSharesOutstanding",
                "CommonStockSharesOutstanding",
                "WeightedAverageNumberOfDilutedSharesOutstanding",
                "WeightedAverageNumberOfSharesOutstandingBasic"));
        TAG_CANDIDATES.put("eps", List.of("EarningsPerShareBasic", "EarningsPerShareDiluted"));
        TAG_CANDIDATES.put("cost_of_sales", List.of(
                "CostOfGoodsAndServicesSold", "CostOfRevenue", "CostOfGoodsSold", "CostOfSales"));
        TAG_CANDIDATES.put("cash_and_equivalents", List.of(
                "CashAndCashEquivalentsAtCarryingValue",
                "CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents"));
        TAG_CANDIDATES.put("capex", List.of(
                "PaymentsToAcquirePropertyPlantAndEquipment",
                "PaymentsToAcquireProductiveAssets"));
        TAG_CANDIDATES.put("inventories", List.of("InventoryNet"));
        // bps는 후보가 없다 — 자본총계/주식수로 우리가 계산한다(spec §7).
    }

    private static RestClient client(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                // MUST NOT: 이 값을 로그·출력에 찍지 않는다. 이메일이 들어 있다.
                .defaultHeader("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT"))
                // 4xx에 예외를 던지지 않게 한다 — 상태코드와 본문을 그대로 봐야 한다.
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    private static void throttle() {
        try {
            Thread.sleep(THROTTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("탐침이 인터럽트됐다", e);
        }
    }

    /**
     * 상태코드를 반드시 보고 본문을 돌려준다.
     *
     * <p>안 보면 오류 본문이 정상 경로 파서를 통과해 <b>「0건」으로 접힌다</b> —
     * Alpaca 탐침에서 실제로 두 번 났고 한 번은 정반대 결론이 나왔다
     * ({@code .claude/rules/data-pipeline.md}의 「재는 코드에도 같은 함정이 있다」).
     */
    private static String body(RestClient client, String path) {
        throttle();
        ResponseEntity<String> response =
                client.get().uri(path).retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("HTTP %s — %s. 상태를 안 보면 이 본문이 「0건」으로 접힌다",
                        response.getStatusCode(),
                        response.getBody() == null ? "(본문 없음)"
                                : response.getBody().substring(0, Math.min(200, response.getBody().length())))
                .isTrue();
        return response.getBody();
    }

    private static String cikPath(long cik) {
        return "/api/xbrl/companyfacts/CIK%010d.json".formatted(cik);
    }

    @Test
    @DisplayName("[live][probe] 티커↔CIK 매핑 파일과, 우리 8,504종목이 거기에 붙는 비율을 잰다")
    void probeTickerCikMapping() throws Exception {
        String raw = body(client(FILES_URL), "/files/company_tickers_exchange.json");
        JsonNode root = MAPPER.readTree(raw);
        JsonNode data = root.path("data");

        System.out.println("\n===== company_tickers_exchange.json 실측 =====");
        System.out.printf("바이트=%d · 행=%d · fields=%s%n",
                raw.getBytes(StandardCharsets.UTF_8).length, data.size(), root.path("fields"));
        assertThat(data.isArray()).as("data가 배열이 아니면 스펙이 바뀐 것이다").isTrue();
        assertThat(data.size()).as("0행이면 「없다」가 아니라 「잴 것이 없었다」다").isPositive();

        // 필드 순서를 이름으로 푼다 — 위치로 박으면 소스가 순서를 바꾸는 날 조용히 어긋난다.
        Map<String, Integer> at = new LinkedHashMap<>();
        for (int i = 0; i < root.path("fields").size(); i++) {
            at.put(root.path("fields").get(i).asString(""), i);
        }

        Map<String, Long> tickerToCik = new LinkedHashMap<>();
        Map<Long, List<String>> cikToTickers = new LinkedHashMap<>();
        Map<String, Integer> exchanges = new TreeMap<>();
        for (JsonNode row : data) {
            long cik = row.get(at.get("cik")).asLong();
            String ticker = row.get(at.get("ticker")).asString("");
            String exchange = row.get(at.get("exchange")).asString("(없음)");
            tickerToCik.put(ticker, cik);
            cikToTickers.computeIfAbsent(cik, c -> new ArrayList<>()).add(ticker);
            exchanges.merge(exchange, 1, Integer::sum);
        }
        System.out.printf("서로 다른 CIK=%d · 거래소 분포=%s%n", cikToTickers.size(), exchanges);

        long multi = cikToTickers.values().stream().filter(t -> t.size() > 1).count();
        System.out.printf("티커가 둘 이상인 CIK=%d건 (예: %s)%n", multi,
                cikToTickers.values().stream().filter(t -> t.size() > 1).limit(3).toList());
        System.out.println("  → 같은 재무가 여러 stock 행에 실린다는 뜻이다. BRK.A/BRK.B가 그 경우다");

        // ---- 우리 유니버스와 대조 ----
        List<String> ours = new ArrayList<>();
        try (Connection connection = ReadOnlyDb.open();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT ticker FROM stock WHERE market_code = 'US' ORDER BY ticker")) {
            while (rs.next()) {
                ours.add(rs.getString("ticker"));
            }
        }
        assertThat(ours).as("US 종목이 하나도 없으면 아래 비율은 「잴 것이 없었다」다").isNotEmpty();

        int exact = 0;
        int normalized = 0;
        Map<String, Integer> unmatchedByShape = new TreeMap<>();
        List<String> unmatchedSample = new ArrayList<>();
        for (String t : ours) {
            if (tickerToCik.containsKey(t)) {
                exact++;
            } else if (tickerToCik.containsKey(t.replace('.', '-'))) {
                normalized++;
            } else {
                unmatchedByShape.merge(shapeOf(t), 1, Integer::sum);
                if (unmatchedSample.size() < 12) {
                    unmatchedSample.add(t);
                }
            }
        }
        System.out.printf("%n우리 US %d종목 — 그대로 붙음 %d · 점→하이픈으로 붙음 %d · 못 붙음 %d%n",
                ours.size(), exact, normalized, ours.size() - exact - normalized);
        System.out.printf("못 붙은 것의 모양별: %s%n  예: %s%n", unmatchedByShape, unmatchedSample);
        System.out.println("  → 「못 붙음」은 결손이 아니라 대부분 우선주·유닛·워런트다.");
        System.out.println("     합계로 적으면 그 구분이 사라진다(P22 ③축).");

        // 우선주는 소스가 `.PRL` 대신 `-PL`로 싣는다(예: JPM-PC). 규칙 하나로 몇이 더 붙는지
        // 재 둔다 — 쓸지 말지는 별개이고, 재지 않으면 「원리적으로 못 붙는다」로 오독된다.
        int preferredWouldMatch = 0;
        for (String t : ours) {
            int dot = t.indexOf('.');
            if (dot < 0 || !t.startsWith("PR", dot + 1) || tickerToCik.containsKey(t)) {
                continue;
            }
            String candidate = t.substring(0, dot) + "-P" + t.substring(dot + 3);
            if (tickerToCik.containsKey(candidate)) {
                preferredWouldMatch++;
            }
        }
        System.out.printf("참고 — `.PR<X>`를 `-P<X>`로 바꾸면 우선주 %d개가 더 붙는다%n",
                preferredWouldMatch);
        System.out.println("     그 CIK는 보통주와 같다. 붙이면 발행사 재무가 우선주 행에 실린다");

        System.out.println("\n----- 파일럿 11 개별 판정 (완료 정의가 여기서 정해진다) -----");
        for (String t : PILOT) {
            Long cik = tickerToCik.get(t);
            String how = "그대로";
            if (cik == null) {
                cik = tickerToCik.get(t.replace('.', '-'));
                how = "점→하이픈";
            }
            System.out.printf("  %-8s %s%n", t,
                    cik == null ? "붙지 않는다" : "CIK%010d (%s)".formatted(cik, how));
        }
    }

    /** 티커의 모양. 못 붙은 것을 이유별로 가르기 위한 것이다. */
    private static String shapeOf(String ticker) {
        if (!ticker.contains(".")) {
            return "점없음";
        }
        String suffix = ticker.substring(ticker.indexOf('.') + 1);
        if (suffix.length() == 1) {
            return "." + (suffix.equals("U") ? "U(유닛)" : "한글자(주식클래스)");
        }
        if (suffix.startsWith("PR")) {
            return ".PR*(우선주)";
        }
        return "." + suffix.length() + "자(" + suffix + " 등)";
    }

    @Test
    @DisplayName("[live][probe] companyfacts의 크기·단위 키·기간 표시·태그 편차를 잰다")
    void probeCompanyFacts() throws Exception {
        Map<String, Long> ciks = resolveCiks();
        RestClient data = client(DATA_URL);

        System.out.println("\n===== companyfacts 실측 =====");
        System.out.printf("%-6s %10s %8s %6s  %-28s %s%n",
                "종목", "바이트", "us-gaap", "dei", "제출자", "네임스페이스");

        Map<String, Integer> unitKeys = new TreeMap<>();
        Map<String, Integer> fpValues = new TreeMap<>();
        Map<String, Integer> formValues = new TreeMap<>();
        Map<String, Set<String>> tagHits = new LinkedHashMap<>();
        Map<String, Integer> columnCoverage = new LinkedHashMap<>();
        TAG_CANDIDATES.keySet().forEach(c -> {
            tagHits.put(c, new LinkedHashSet<>());
            columnCoverage.put(c, 0);
        });
        List<String> noSharesTag = new ArrayList<>();
        Map<String, Integer> signs = new TreeMap<>();
        int missingFp = 0;
        int totalFacts = 0;
        long maxBytes = 0;
        long totalBytes = 0;

        for (Map.Entry<String, Long> e : ciks.entrySet()) {
            String symbol = e.getKey();
            String raw = body(data, cikPath(e.getValue()));
            long bytes = raw.getBytes(StandardCharsets.UTF_8).length;
            maxBytes = Math.max(maxBytes, bytes);
            totalBytes += bytes;

            JsonNode parsed = MAPPER.readTree(raw);
            JsonNode facts = parsed.path("facts");
            List<String> namespaces = new ArrayList<>(facts.propertyNames());
            JsonNode usGaap = facts.path("us-gaap");
            JsonNode dei = facts.path("dei");
            // 제출자 이름을 함께 찍는 이유는 **응답 크기가 100배 차이 나기 때문**이다.
            // 작은 응답이 「결손」인지 「다른 회사」인지는 크기만으로 못 가른다.
            System.out.printf("%-6s %10d %8d %6d  %-28s %s%n",
                    symbol, bytes, usGaap.size(), dei.size(),
                    parsed.path("entityName").asString("(없음)"), namespaces);

            // 이 종목이 우리 17개 컬럼의 후보 태그 중 무엇을 실제로 갖고 있는가.
            for (Map.Entry<String, List<String>> col : TAG_CANDIDATES.entrySet()) {
                boolean any = false;
                for (String candidate : col.getValue()) {
                    boolean isDei = candidate.startsWith("dei:");
                    String tag = isDei ? candidate.substring(4) : candidate;
                    if ((isDei ? dei : usGaap).has(tag)) {
                        tagHits.get(col.getKey()).add(candidate);
                        any = true;
                    }
                }
                if (any) {
                    columnCoverage.merge(col.getKey(), 1, Integer::sum);
                }
            }
            if (!dei.has("EntityCommonStockSharesOutstanding")) {
                noSharesTag.add(symbol);
            }

            // capex의 부호. 뒤집혀 담기면 잉여현금흐름이 두 배로 부풀고 값이 그럴듯하다(ADR-0067).
            for (String tag : TAG_CANDIDATES.get("capex")) {
                for (JsonNode f : usGaap.path(tag).path("units").path("USD")) {
                    signs.merge(f.path("val").asDouble(0) >= 0 ? "capex 양수" : "capex 음수",
                            1, Integer::sum);
                }
            }
            for (String tag : TAG_CANDIDATES.get("operating_cash_flow")) {
                for (JsonNode f : usGaap.path(tag).path("units").path("USD")) {
                    signs.merge(f.path("val").asDouble(0) >= 0 ? "OCF 양수" : "OCF 음수",
                            1, Integer::sum);
                }
            }

            // 단위 키·기간 표시는 팩트를 훑어야 나온다. 표본을 줄이려고 us-gaap만 센다.
            for (String tag : usGaap.propertyNames()) {
                JsonNode units = usGaap.path(tag).path("units");
                for (String unit : units.propertyNames()) {
                    unitKeys.merge(unit, 1, Integer::sum);
                    for (JsonNode fact : units.path(unit)) {
                        totalFacts++;
                        String fp = fact.path("fp").asString("");
                        if (fp.isBlank()) {
                            missingFp++;
                        } else {
                            fpValues.merge(fp, 1, Integer::sum);
                        }
                        formValues.merge(fact.path("form").asString("(없음)"), 1, Integer::sum);
                    }
                }
            }
        }

        System.out.printf("%n표본 %d종목 · 합계 %.1fMB · 최대 %.1fMB · 팩트 %d개%n",
                ciks.size(), totalBytes / 1e6, maxBytes / 1e6, totalFacts);
        System.out.printf("전량 8,504종목을 같은 평균으로 받으면 약 %.0fGB다%n",
                (totalBytes / (double) ciks.size()) * 8504 / 1e9);

        System.out.printf("%n----- units 키 (currency_code가 character(3)이다) -----%n%s%n", unitKeys);
        List<String> tooLong = unitKeys.keySet().stream().filter(u -> u.length() > 3).toList();
        System.out.printf("3자를 넘는 키 %d종: %s%n", tooLong.size(), tooLong);
        System.out.println("  → 이것을 통화로 담으면 value too long으로 그 종목이 통째로 롤백된다");

        System.out.printf("%n----- fp 값 (ck_fs_period는 FY|Q1~Q4뿐이다) -----%n%s · 빈 값 %d개%n",
                fpValues, missingFp);
        System.out.printf("form 값: %s%n", formValues);

        System.out.printf("%n----- 컬럼별 커버리지 (분모 %d종목) 와 실제로 존재한 태그 -----%n",
                ciks.size());
        for (Map.Entry<String, Set<String>> col : tagHits.entrySet()) {
            System.out.printf("  %-22s %2d/%d  %s%n", col.getKey(),
                    columnCoverage.get(col.getKey()), ciks.size(),
                    col.getValue().isEmpty() ? "★ 후보 중 아무것도 없다" : col.getValue());
        }
        System.out.println("  → 앞의 수가 「몇 종목이 그 컬럼을 채울 수 있는가」다.");
        System.out.println("     뒤의 목록만 보면 「표본 어딘가에 있다」로 읽혀 커버리지를 덮는다");
        System.out.printf("%n부호 분포: %s%n", signs);
        System.out.println("  → capex가 음수로 오면 포트 계약(유출을 양수 크기로)과 반대다");
        System.out.printf("%ndei:EntityCommonStockSharesOutstanding이 없는 종목 %d개: %s%n",
                noSharesTag.size(), noSharesTag);
        System.out.println("  → 미국은 시세도 재무도 주식수가 비어 있어 PER·PBR의 분모가 이것뿐이다");
    }

    /** 파일럿과 확장 표본의 CIK. 안 붙는 티커는 이유와 함께 빠진다. */
    private Map<String, Long> resolveCiks() throws Exception {
        JsonNode root = MAPPER.readTree(body(client(FILES_URL), "/files/company_tickers_exchange.json"));
        Map<String, Integer> at = new LinkedHashMap<>();
        for (int i = 0; i < root.path("fields").size(); i++) {
            at.put(root.path("fields").get(i).asString(""), i);
        }
        Map<String, Long> byTicker = new LinkedHashMap<>();
        for (JsonNode row : root.path("data")) {
            byTicker.put(row.get(at.get("ticker")).asString(""), row.get(at.get("cik")).asLong());
        }
        Map<String, Long> resolved = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        for (String t : concat(PILOT, EXTRA)) {
            Long cik = byTicker.get(t);
            if (cik == null) {
                cik = byTicker.get(t.replace('.', '-'));
            }
            if (cik == null) {
                skipped.add(t);
            } else {
                resolved.put(t, cik);
            }
        }
        assertThat(resolved).as("표본이 하나도 안 붙으면 아래 수는 「잴 것이 없었다」다").isNotEmpty();
        if (!skipped.isEmpty()) {
            System.out.printf("표본에서 빠진 티커(매핑 파일에 없다): %s%n", skipped);
        }
        return resolved;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** 기간 재구성을 재는 표본. 9월 결산·6월 결산·은행(유동 구분 없음)을 하나씩 둔다. */
    private static final List<String> PERIOD_SAMPLE = List.of("AAPL", "MSFT", "JPM");

    @Test
    @DisplayName("[live][probe] 기간 라벨·기간 길이·정정 재보고를 잰다 — 재구성 규칙이 여기서 정해진다")
    void probePeriodReconstruction() throws Exception {
        Map<String, Long> all = resolveCiks();
        RestClient data = client(DATA_URL);

        System.out.println("\n===== 기간 재구성 실측 =====");
        for (String symbol : PERIOD_SAMPLE) {
            Long cik = all.get(symbol);
            assertThat(cik).as("%s가 표본에서 빠졌다 — 아래 수는 「잴 것이 없었다」다", symbol)
                    .isNotNull();
            JsonNode usGaap = MAPPER.readTree(body(data, cikPath(cik))).path("facts").path("us-gaap");

            String incomeTag = firstPresent(usGaap, TAG_CANDIDATES.get("net_income"));
            String ocfTag = firstPresent(usGaap, TAG_CANDIDATES.get("operating_cash_flow"));
            String balanceTag = firstPresent(usGaap, TAG_CANDIDATES.get("total_assets"));
            System.out.printf("%n----- %s (손익=%s · 현금흐름=%s · 잔액=%s) -----%n",
                    symbol, incomeTag, ocfTag, balanceTag);

            // P6 — 팩트 하나의 필드가 무엇인가.
            JsonNode sample = usGaap.path(incomeTag).path("units").path("USD").get(0);
            System.out.printf("팩트 필드: %s%n", sample == null ? "(없음)" : sample.propertyNames());

            // P7 — 같은 (fy, fp)에 서로 다른 종료일이 몇 개인가.
            Map<String, Set<String>> endsPerLabel = new TreeMap<>();
            for (JsonNode f : usGaap.path(incomeTag).path("units").path("USD")) {
                endsPerLabel.computeIfAbsent(
                                f.path("fy").asString("?") + "/" + f.path("fp").asString("?"),
                                k -> new LinkedHashSet<>())
                        .add(f.path("end").asString(""));
            }
            int labelsWithManyEnds = (int) endsPerLabel.values().stream().filter(v -> v.size() > 1).count();
            System.out.printf("fy/fp 라벨 %d개 중 종료일이 둘 이상인 것 %d개 (최대 %d개)%n",
                    endsPerLabel.size(), labelsWithManyEnds,
                    endsPerLabel.values().stream().mapToInt(Set::size).max().orElse(0));
            System.out.println("  → 0이 아니면 fy/fp는 「그 사실의 기간」이 아니라 「그것을 실은 보고서」다");

            // P9 — 기간 길이 분포. 손익과 현금흐름을 갈라 센다.
            System.out.printf("손익 기간 길이(일): %s%n", durationHistogram(usGaap, incomeTag));
            System.out.printf("현금흐름 기간 길이(일): %s%n", durationHistogram(usGaap, ocfTag));
            System.out.println("  → 손익에 3개월이 있는데 현금흐름에 없으면, 한 행에 기간이 다른 두 수가 앉는다");

            // P8 — 한 보고서가 자기 종료일에 몇 가지 기간 길이를 함께 싣는가.
            Map<String, Set<Long>> lengthsAtOwnEnd = new LinkedHashMap<>();
            Map<String, String> ownEnd = new LinkedHashMap<>();
            for (JsonNode f : usGaap.path(incomeTag).path("units").path("USD")) {
                String accn = f.path("accn").asString("");
                String end = f.path("end").asString("");
                ownEnd.merge(accn, end, (a, b) -> a.compareTo(b) >= 0 ? a : b);
            }
            for (JsonNode f : usGaap.path(incomeTag).path("units").path("USD")) {
                String accn = f.path("accn").asString("");
                if (!f.path("end").asString("").equals(ownEnd.get(accn)) || !f.has("start")) {
                    continue;
                }
                lengthsAtOwnEnd.computeIfAbsent(accn, a -> new LinkedHashSet<>())
                        .add(days(f.path("start").asString(""), f.path("end").asString("")));
            }
            long manyLengths = lengthsAtOwnEnd.values().stream().filter(v -> v.size() > 1).count();
            System.out.printf("보고서 %d개 중 자기 종료일에 기간 길이가 둘 이상인 것 %d개 (예: %s)%n",
                    lengthsAtOwnEnd.size(), manyLengths,
                    lengthsAtOwnEnd.entrySet().stream().filter(e -> e.getValue().size() > 1)
                            .limit(2).map(e -> e.getKey() + "=" + e.getValue()).toList());
            System.out.println("  → 0이 아니면 회계연도만 앵커에서 가져오고 기간은 길이로 갈라야 한다");

            // P10 — 같은 기간을 여러 보고서가 다시 싣는가. 값은 실제로 달라지는가.
            Map<String, Set<String>> accnsPerPeriod = new LinkedHashMap<>();
            Map<String, Set<String>> valuesPerPeriod = new LinkedHashMap<>();
            for (JsonNode f : usGaap.path(incomeTag).path("units").path("USD")) {
                String period = f.path("start").asString("-") + "~" + f.path("end").asString("-");
                accnsPerPeriod.computeIfAbsent(period, k -> new LinkedHashSet<>())
                        .add(f.path("accn").asString(""));
                valuesPerPeriod.computeIfAbsent(period, k -> new LinkedHashSet<>())
                        .add(f.path("val").asString(""));
            }
            long reported = accnsPerPeriod.values().stream().filter(v -> v.size() > 1).count();
            long changed = valuesPerPeriod.entrySet().stream()
                    .filter(e -> accnsPerPeriod.get(e.getKey()).size() > 1)
                    .filter(e -> e.getValue().size() > 1).count();
            System.out.printf("기간 %d개 중 보고서 둘 이상이 실은 것 %d개 · 그중 값이 실제로 달라진 것 %d개%n",
                    accnsPerPeriod.size(), reported, changed);
            System.out.printf("  → 공시일만 보고 정정으로 올리면 %d개가 가짜 정정이 된다%n", reported - changed);
        }
    }

    private static String firstPresent(JsonNode usGaap, List<String> candidates) {
        for (String c : candidates) {
            if (!c.startsWith("dei:") && usGaap.has(c)) {
                return c;
            }
        }
        return candidates.get(0);
    }

    private static Map<Long, Integer> durationHistogram(JsonNode usGaap, String tag) {
        Map<Long, Integer> byLength = new TreeMap<>();
        for (JsonNode f : usGaap.path(tag).path("units").path("USD")) {
            if (f.has("start")) {
                byLength.merge(bucket(days(f.path("start").asString(""), f.path("end").asString(""))),
                        1, Integer::sum);
            }
        }
        return byLength;
    }

    /** 길이를 개월 경계로 접는다. 회사마다 며칠씩 달라 원값으로 세면 표가 안 읽힌다. */
    private static long bucket(long d) {
        if (d <= 100) {
            return 3;
        }
        if (d <= 200) {
            return 6;
        }
        if (d <= 300) {
            return 9;
        }
        return 12;
    }

    private static long days(String start, String end) {
        return java.time.temporal.ChronoUnit.DAYS.between(
                LocalDate.parse(start), LocalDate.parse(end));
    }

    @Test
    @DisplayName("[live][probe] 실패 계약과 압축 해제를 잰다 — 어댑터의 Kind 매핑이 여기서 정해진다")
    void probeFailureContractAndEncoding() {
        RestClient data = client(DATA_URL);

        System.out.println("\n===== 실패 계약 =====");
        // 없는 CIK. 어댑터가 「봉이 없다」와 「종목을 모른다」를 갈라야 하는 자리다.
        throttle();
        ResponseEntity<String> unknown = data.get()
                .uri(cikPath(9_999_999_999L)).retrieve().toEntity(String.class);
        System.out.printf("모르는 CIK: HTTP %s · 본문 %s%n", unknown.getStatusCode(),
                preview(unknown.getBody()));

        // 형식이 아예 틀린 경로(zero-padding 누락). 개발가이드가 404의 최다 원인으로 적었다.
        throttle();
        ResponseEntity<String> unpadded = data.get()
                .uri("/api/xbrl/companyfacts/CIK320193.json").retrieve().toEntity(String.class);
        System.out.printf("padding 누락: HTTP %s · 본문 %s%n", unpadded.getStatusCode(),
                preview(unpadded.getBody()));
        System.out.println("  → MUST NOT: 스로틀·차단 응답은 여기서 재지 않는다.");
        System.out.println("     유발하면 IP가 일시 제한되어 남은 측정이 전부 막힌다");

        System.out.println("\n===== Accept-Encoding =====");
        // 손으로 붙였을 때 본문이 풀려 오는가. HttpURLConnection은 자기가 붙인 헤더에만
        // 자동 해제를 걸어 주므로, 명시하면 압축된 바이트가 그대로 올 수 있다.
        RestClient explicit = RestClient.builder()
                .baseUrl(DATA_URL)
                .defaultHeader("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT"))
                .defaultHeader("Accept-Encoding", "gzip, deflate")
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
        throttle();
        ResponseEntity<String> gz = explicit.get()
                .uri(cikPath(320193L)).retrieve().toEntity(String.class);
        boolean parsed;
        try {
            parsed = MAPPER.readTree(gz.getBody()).path("facts").has("us-gaap");
        } catch (RuntimeException e) {
            parsed = false;
        }
        System.out.printf("Accept-Encoding 명시: HTTP %s · %d바이트 · JSON 파싱 %s%n",
                gz.getStatusCode(),
                gz.getBody() == null ? 0 : gz.getBody().getBytes(StandardCharsets.UTF_8).length,
                parsed ? "성공" : "★ 실패 — 압축이 안 풀린다");
        System.out.println("  → 실패하면 어댑터는 이 헤더를 붙이지 않는다(가이드 §2.1이 권하더라도)");
    }

    @Test
    @DisplayName("[live][probe] 벌크 파일 크기를 잰다 — 전량을 종목당 1콜로 받을지의 대조군이다")
    void probeBulkSize() {
        // 이 phase는 파일럿만 넣으므로 여기서 계획이 갈리지 않는다. 그래도 재는 이유는
        // **전량 여부가 열린 결정으로 남아 있고**(docs/progress.md), 종목당 1콜이 약 29GB라
        // 그 결정에 견줄 값이 이것뿐이기 때문이다. HEAD 1콜이다.
        throttle();
        ResponseEntity<Void> head = client(FILES_URL).head()
                .uri("/Archives/edgar/daily-index/xbrl/companyfacts.zip")
                .retrieve().toBodilessEntity();
        long length = head.getHeaders().getContentLength();
        System.out.printf("%n===== companyfacts.zip =====%nHTTP %s · Content-Length=%s%n",
                head.getStatusCode(),
                length < 0 ? "(헤더 없음)" : "%.1fGB".formatted(length / 1e9));
        System.out.println("  → 종목당 1콜은 약 29GB다(표본 19종목 65.2MB 평균). 이 값과 견준다");
        System.out.println("     크기만으로 정하지 않는다 — 벌크는 하루 한 번 재발행이라 신선도가 다르다");
    }

    private static String preview(String body) {
        if (body == null) {
            return "(없음)";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
    }
}
