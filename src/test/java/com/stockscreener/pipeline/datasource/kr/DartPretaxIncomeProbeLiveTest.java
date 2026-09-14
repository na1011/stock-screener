package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전체 재무제표가 <b>세전이익</b>을 주는지, 그리고 그것이 <b>법인세비용과 같은 기저</b>인지를
 * 실측한다. 세후 투하자본이익률의 유효세율이 이 둘 위에 선다.
 *
 * <h2>왜 지금 재는가 — 분모를 아무도 안 물어봤다</h2>
 *
 * <p>{@code dart-financial.md}의 「계정별 가용성」 표가 <b>법인세비용을 8/8로</b> 적어 두었고
 * {@code RoicPretaxCalculator}가 <i>"법인세비용을 적재하면 그때 세후로 승격한다"</i>고 재개
 * 조건까지 남겼다. 그런데 유효세율은 {@code 법인세비용 ÷ 세전이익}이고, <b>그 표 어디에도
 * 세전이익 행이 없다.</b> 저장소 전수에서 국내 세전이익을 잰 기록이 0건이다 —
 * ADR-0067 §규칙의 <i>"결손을 「소스가 주지 않는다」로 적기 전에 재보지 않은 것이 아닌지
 * 확인한다"</i>에 걸리는 세 번째 사례다(앞의 둘은 자본적지출·재고자산).
 *
 * <h2>IMPORTANT — 판정은 비대칭이다</h2>
 *
 * <p>표본 여덟은 <b>「닫히지 않았다」까지만</b> 낸다. 커버리지와 분포는 전수가 답한다
 * (ADR-0062 §규칙). 이익잉여금이 반례다 — 4개사에서 전부 왔는데 350종목에서 셋이 비었다.
 * <b>8/8이 나와도 재적재 전에는 커버리지를 한 줄도 주장하지 않는다.</b>
 *
 * <h2>이 탐침이 재고자산 탐침과 다르게 하는 것 셋</h2>
 *
 * <p><b>① {@code sj_div}를 고정하지 않는다.</b> 재고자산은 {@code BS}로 확정이었지만
 * 세금·세전이익은 <b>어느 표에서 오는지가 묻는 것 자체</b>다 — 같은 문서가
 * <i>"손익이 {@code IS}에만 있다고 가정하면 안 된다. 삼성전자만 {@code IS}가 있고 나머지 셋은
 * {@code CIS}뿐"</i>이라 적는다. 그래서 <b>전 {@code sj_div}에서 찾고 어디서 나왔는지를
 * 기록한다.</b> 그리고 8/8을 낸 {@code DartMissingAccountProbeLiveTest}는 {@code sj_div}를 안
 * 좁혔으므로 그 수는 <b>「어느 표에 있었는지 모른 채 어딘가에 있었다」까지</b>다.
 *
 * <p><b>② 연도를 다섯 해 돈다.</b> 5년 최저·평균 지표가 걸리는 축은 종목 간 분산이 아니라
 * <b>한 종목 안 세율의 해마다 변동</b>이다. 최솟값 지표는 <b>한 해만 튀면 값이 통째로
 * 정해지고</b>, 세금 환입이 난 해는 {@code t < 0}이라 그 해가 빠져 <b>5년 전체가 값 없음</b>이
 * 된다. 한 해만 재면 그 축이 안 보인다.
 *
 * <p><b>③ 항등식을 검산으로만 찍는다.</b> {@code 세전이익 = 순이익 + 법인세비용}은
 * <b>컬럼을 줄이는 근거로 쓰지 않는다</b> — 법인세비용의 표준 ID가
 * {@code …IncomeTaxExpenseContinuingOperations}(계속영업)인데 흔한 분모 후보
 * {@code ifrs-full_ProfitLossBeforeTax}는 <b>총액</b>이라 중단영업이 있는 해에 어긋나고,
 * {@code ifrs-full_ProfitLoss}(우리 {@code net_income})는 <b>연결 총액</b>이다.
 * 그 어긋남이 <b>소수 회사에서만 나므로 표본 여덟으로는 빈도를 못 잰다.</b>
 *
 * <h2>MUST NOT: 계정명 폴백을 두지 않는다</h2>
 *
 * <p>간접법 현금흐름표에 <b>「법인세납부」</b> 행이 따로 있는데 그것은 <b>현금 유출이지
 * 비용이 아니다.</b> 포괄손익계산서에는 「법인세비용차감전기타포괄손익」이 있어
 * 「법인세비용차감전순이익」과 낱말을 공유한다. 감가상각비가 정확히 그 형태로 3/8 → 2/8
 * 오진을 냈다({@code dart-financial.md} 「계정명 부분일치를 쓰지 않는다」).
 * <b>이름은 세지 않고 나열만 해서 사람이 후보 부족을 판정하게 한다.</b>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartPretaxIncomeProbeLiveTest {

    /** 사업보고서. */
    private static final String ANNUAL_REPORT = "11011";
    /** 1분기보고서. 분기에도 오는지만 본다 — 안 오는 것과 안 쓰는 것은 다르다. */
    private static final String QUARTER_REPORT = "11013";
    /** 결산이 끝난 가장 최근 연도. 여기서 거슬러 {@code probe.years}개 연도를 돈다. */
    private static final String SETTLED_YEAR = "2025";
    /** 5년 최저·평균이 요구하는 창과 같다. */
    private static final String DEFAULT_YEAR_SPAN = "5";

    /**
     * 표본. <b>법인세비용 8/8을 낸 표본과 같아야</b> 두 수를 나란히 읽는다
     * ({@code DartMissingAccountProbeLiveTest}의 중분류 비중복 8종목).
     */
    private static final Map<String, String> SAMPLES = new LinkedHashMap<>();

    static {
        for (String ticker : parameter("probe.tickers", "")) {
            SAMPLES.put(ticker, ticker + "(지정 표본)");
        }
        if (SAMPLES.isEmpty()) {
            SAMPLES.put("005930", "삼성전자(26 전자부품)");
            SAMPLES.put("105560", "KB금융(64 금융)");
            SAMPLES.put("035420", "NAVER(63 정보서비스)");
            SAMPLES.put("271560", "오리온(10 식료품)");
            SAMPLES.put("005490", "POSCO홀딩스(24 1차금속)");
            SAMPLES.put("000120", "CJ대한통운(49 육상운송)");
            SAMPLES.put("207940", "삼성바이오로직스(21 의약품)");
            SAMPLES.put("139480", "이마트(47 소매)");
        }
    }

    /**
     * 찾는 계정. <b>{@code sjDiv}가 없다</b> — 어느 표에서 오는지가 이 탐침이 묻는 것이다.
     *
     * @param accountIds 완전일치로 세는 표준계정ID. <b>계정명 폴백을 두지 않는다</b>
     */
    private record Target(String label, List<String> accountIds, String opens) { }

    private static final List<Target> TARGETS = List.of(
            new Target("법인세비용",
                    parameter("probe.taxIds",
                            "ifrs-full_IncomeTaxExpenseContinuingOperations,"
                                    + "dart_IncomeTaxExpenseContinuingOperations"),
                    "유효세율의 분자"),
            new Target("세전이익(총액)",
                    parameter("probe.pretaxIds",
                            "ifrs-full_ProfitLossBeforeTax,dart_ProfitLossBeforeTax"),
                    "유효세율의 분모 — 분자와 기저가 다를 수 있다"),
            new Target("세전이익(계속영업)",
                    parameter("probe.pretaxContinuingIds",
                            "ifrs-full_ProfitLossFromContinuingOperationsBeforeTax,"
                                    + "dart_ProfitLossFromContinuingOperationsBeforeTax"),
                    "분자와 같은 기저 — 오면 이쪽이 옳다"),
            new Target("순이익(연결총액)",
                    List.of("ifrs-full_ProfitLoss", "ifrs_ProfitLoss"),
                    "항등식 검산용 — 적재는 이미 돼 있다"));

    /** 이름 축 나열. <b>세지 않는다</b> — 오진 후보를 사람이 보게 하는 재료다. */
    private static final List<String> NAME_HINTS =
            List.of("법인세", "차감전", "세전", "계속영업", "중단영업");

    /** 코드 축 나열. <b>세지 않는다</b> — 후보 ID가 모자랐는지 사람이 판정한다. */
    private static final List<String> ID_HINTS =
            List.of("IncomeTax", "BeforeTax", "ContinuingOperations", "DiscontinuedOperations");

    @Test
    @DisplayName("[live][probe] 세전이익이 오는가 · 어느 sj_div인가 · 세율이 해마다 얼마나 흔들리는가")
    void probePretaxIncome() throws Exception {
        List<String> years = yearsToProbe();
        System.out.println("\n===== 세전이익·법인세비용 실측 =====");
        System.out.println("표본(probe.tickers) = " + SAMPLES.keySet());
        System.out.println("연도(probe.years) = " + years + " · 분기 " + SETTLED_YEAR + "/" + QUARTER_REPORT);
        System.out.println("MUST: 판정은 비대칭이다 — 0/8은 「닫혔다」가 되지만 8/8은 「유망」까지다.");
        System.out.println("MUST: 아래 「판정」보다 그 위의 나열을 먼저 읽는다 —");
        System.out.println("      나열이 비었으면 소스에 없는 것이고, 나열은 있는데 안 걸렸으면 후보 부족이다.");

        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        Map<String, Set<String>> foundNames = new LinkedHashMap<>();
        Map<String, Set<String>> foundDivs = new LinkedHashMap<>();
        // Target → 그 계정이 한 번이라도 잡힌 종목코드. 분모는 SAMPLES가 아니라 응답한 회사 수다.
        Map<String, Set<String>> stocksHit = new LinkedHashMap<>();
        for (Target target : TARGETS) {
            foundNames.put(target.label(), new LinkedHashSet<>());
            foundDivs.put(target.label(), new LinkedHashSet<>());
            stocksHit.put(target.label(), new LinkedHashSet<>());
        }
        // 종목 → (연도 → 유효세율). 두 축을 한 자료에서 낸다.
        Map<String, Map<String, BigDecimal>> ratesByStock = new LinkedHashMap<>();
        int answered = 0;
        int calls = 0;
        int ofsFallbacks = 0;
        int identityChecked = 0;
        int identityMatched = 0;

        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElse(null);
            if (corpCode == null) {
                System.out.printf("%n--- %s(%s): corp_code를 찾지 못했다%n",
                        sample.getValue(), sample.getKey());
                continue;
            }
            System.out.printf("%n--- %s(%s)%n", sample.getValue(), sample.getKey());
            Map<String, BigDecimal> rates = new TreeMap<>();
            boolean respondedOnce = false;
            // 나열에 쓸 최근 연도 응답. 다시 부르지 않는다 — 종목당 1~2콜이 그냥 준다.
            JsonNode latestList = null;

            for (String year : years) {
                Fetched fetched = fetch(client, apiKey, corpCode, year, ANNUAL_REPORT);
                calls += fetched.calls();
                ofsFallbacks += fetched.usedOfs() ? 1 : 0;
                if (fetched.list() == null) {
                    System.out.printf("    %s: 행이 없다 — %s%n", year,
                            DartProbeSupport.describeStatus(fetched.body()));
                    continue;
                }
                respondedOnce = true;
                if (latestList == null) {
                    latestList = fetched.list();
                }
                Map<String, JsonNode> rows = new LinkedHashMap<>();
                for (Target target : TARGETS) {
                    for (JsonNode row : findRows(fetched.list(), target)) {
                        rows.putIfAbsent(target.label(), row);
                        foundDivs.get(target.label()).add(row.path("sj_div").asString("?"));
                        foundNames.get(target.label()).add(row.path("account_nm").asString("?"));
                        stocksHit.get(target.label()).add(sample.getKey());
                    }
                }
                // 그 연도에 잡힌 것만 센다. 연도별 합이 아니라 「한 번이라도 왔는가」다.
                describeYear(year, fetched.usedDiv(), rows);

                BigDecimal tax = amountOf(rows.get("법인세비용"));
                BigDecimal pretax = amountOf(rows.get("세전이익(계속영업)"));
                if (pretax == null) {
                    pretax = amountOf(rows.get("세전이익(총액)"));
                }
                if (tax != null && pretax != null && pretax.signum() > 0) {
                    rates.put(year, tax.multiply(BigDecimal.valueOf(100))
                            .divide(pretax, 2, RoundingMode.HALF_UP));
                }
                BigDecimal net = amountOf(rows.get("순이익(연결총액)"));
                if (tax != null && pretax != null && net != null) {
                    identityChecked++;
                    if (pretax.subtract(net.add(tax)).abs()
                            .compareTo(pretax.abs().divide(BigDecimal.valueOf(100), 0,
                                    RoundingMode.HALF_UP)) <= 0) {
                        identityMatched++;
                    }
                }
            }

            if (respondedOnce) {
                answered++;
            }
            if (!rates.isEmpty()) {
                ratesByStock.put(sample.getValue(), rates);
                System.out.println("    유효세율(%) 연도별: " + rates
                        + "  폭 " + spreadOf(rates));
            }

            // 분기에도 오는지 — 연간 세율을 쓰기로 했어도 「안 오는 것」과 「안 쓰는 것」은 다르다.
            Fetched quarter = fetch(client, apiKey, corpCode, SETTLED_YEAR, QUARTER_REPORT);
            calls += quarter.calls();
            ofsFallbacks += quarter.usedOfs() ? 1 : 0;
            if (quarter.list() == null) {
                System.out.println("    [분기] 행이 없다 — " + DartProbeSupport.describeStatus(quarter.body()));
            } else {
                List<String> present = new ArrayList<>();
                for (Target target : TARGETS) {
                    if (!findRows(quarter.list(), target).isEmpty()) {
                        present.add(target.label());
                    }
                }
                System.out.println("    [분기] 잡힌 계정: " + (present.isEmpty() ? "없음" : present));
            }

            if (latestList != null) {
                System.out.println("    나열 ① 이름 축(세지 않는다 — 전 sj_div, 응답한 최근 연도):");
                listRows(latestList, true).forEach(r -> System.out.println("        " + r));
                System.out.println("    나열 ② 코드 축(세지 않는다 — 전 sj_div):");
                listRows(latestList, false).forEach(r -> System.out.println("        " + r));
            }
        }

        System.out.println("\n===== 판정 =====");
        System.out.println("응답한 회사: " + answered + "/" + SAMPLES.size()
                + " (OFS 폴백 " + ofsFallbacks + "건 · 총 " + calls + "콜)");
        for (Target target : TARGETS) {
            Set<String> divs = foundDivs.get(target.label());
            int count = stocksHit.get(target.label()).size();
            String verdict = count == 0 ? "닫혔다(또는 후보 부족 — 위 나열을 본다)"
                    : count == answered ? "유망 — 커버리지는 재적재로만 안다"
                    : "혼재 — 어느 업종에서 깨지는지가 소득이다";
            System.out.printf("%-22s %d/%d  %-42s (%s)%n",
                    target.label(), count, answered, verdict, target.opens());
            if (!divs.isEmpty()) {
                System.out.println("                       관측된 sj_div: " + divs
                        + "  ← 둘 이상이면 sections(IS, CIS) 순서가 뜻을 정한다");
                System.out.println("                       회사별 계정명: " + foundNames.get(target.label()));
            }
        }

        System.out.println("\n----- 유효세율 두 축 -----");
        System.out.println("① 종목 간(한 시점): 아래 연도별 표의 같은 연도 열을 세로로 읽는다");
        System.out.println("② 한 종목 안(해마다): 각 행의 「폭」이 그 축이다");
        System.out.println("MUST: ②가 넓으면 5년 최저 지표가 그 해 하나로 정해진다.");
        ratesByStock.forEach((name, rates) ->
                System.out.printf("    %-28s %s  폭 %s%n", name, rates, spreadOf(rates)));

        System.out.println("\n----- 항등식(세전 = 순이익 + 법인세, 오차 1% 이내) -----");
        System.out.printf("    %d/%d 일치%n", identityMatched, identityChecked);
        System.out.println("MUST NOT: 이 수를 컬럼을 줄이는 근거로 쓰지 않는다 —");
        System.out.println("      중단영업·비지배가 있는 회사에서만 깨지므로 표본 여덟이 그 빈도를 못 잰다.");

        System.out.println("\nMUST: 이 출력을 dart-financial.md의 「계정별 가용성」 절에 행으로 옮긴다");
        System.out.println("      (그 절이 머리에 8종목 표본을 못박고 있어 다른 표본의 수를 섞으면 거짓이 된다).");

        assertThat(answered)
                .as("여덟 중 하나도 응답하지 않았다. 키·엔드포인트·연도를 먼저 의심한다")
                .isPositive();
    }

    private static void describeYear(String year, String usedDiv, Map<String, JsonNode> rows) {
        List<String> parts = new ArrayList<>();
        rows.forEach((label, row) -> parts.add(label + "[" + row.path("sj_div").asString("?") + "]="
                + row.path("thstrm_amount").asString("")));
        System.out.printf("    %s [%s] %s%n", year, usedDiv, parts.isEmpty() ? "잡힌 계정 없음" : parts);
    }

    /** 연도 목록. 최근부터 거슬러 {@code probe.years}개. */
    private static List<String> yearsToProbe() {
        int span = Integer.parseInt(parameter("probe.years", DEFAULT_YEAR_SPAN).get(0));
        int latest = Integer.parseInt(parameter("probe.latestYear", SETTLED_YEAR).get(0));
        List<String> years = new ArrayList<>();
        for (int i = 0; i < span; i++) {
            years.add(String.valueOf(latest - i));
        }
        return years;
    }

    /** 세율의 최대−최소. 한 종목 안 변동 축을 한 수로 본다. */
    private static String spreadOf(Map<String, BigDecimal> rates) {
        if (rates.size() < 2) {
            return "잴 수 없음(연도 " + rates.size() + ")";
        }
        BigDecimal min = null;
        BigDecimal max = null;
        for (BigDecimal value : rates.values()) {
            min = min == null || value.compareTo(min) < 0 ? value : min;
            max = max == null || value.compareTo(max) > 0 ? value : max;
        }
        return max.subtract(min) + "%p";
    }

    private record Fetched(JsonNode body, JsonNode list, String usedDiv, int calls, boolean usedOfs) { }

    /** CFS로 부르고 비면 OFS로 다시 부른다. 폴백이 없으면 그것이 「계정 없음」으로 섞인다. */
    private static Fetched fetch(RestClient client, String apiKey, String corpCode,
                                 String year, String reportCode) {
        JsonNode body = call(client, apiKey, corpCode, "CFS", year, reportCode);
        JsonNode list = body == null ? null : body.get("list");
        if (list != null && !list.isEmpty()) {
            return new Fetched(body, list, "CFS", 1, false);
        }
        JsonNode separate = call(client, apiKey, corpCode, "OFS", year, reportCode);
        JsonNode separateList = separate == null ? null : separate.get("list");
        if (separateList != null && !separateList.isEmpty()) {
            return new Fetched(separate, separateList, "OFS", 2, true);
        }
        return new Fetched(body, null, "CFS", 2, true);
    }

    private static JsonNode call(RestClient client, String apiKey, String corpCode,
                                 String fsDiv, String year, String reportCode) {
        ResponseEntity<JsonNode> response = client.get()
                .uri(uri -> uri.path("/fnlttSinglAcntAll.json")
                        .queryParam("crtfc_key", apiKey)
                        .queryParam("corp_code", corpCode)
                        .queryParam("bsns_year", year)
                        .queryParam("reprt_code", reportCode)
                        .queryParam("fs_div", fsDiv)
                        .build())
                .retrieve()
                .toEntity(JsonNode.class);
        return response.getBody();
    }

    /**
     * {@code account_id} 완전일치로 <b>전 {@code sj_div}에서</b> 찾는다. 계정명 폴백을 두지 않는다.
     *
     * <p>재고자산 탐침과 달리 {@code sj_div}로 좁히지 않는 이유는 <b>어느 표에서 오는지가
     * 이 탐침이 묻는 것</b>이기 때문이다. 둘 이상에서 나오면 그 사실이 소득이다.
     */
    private static List<JsonNode> findRows(JsonNode list, Target target) {
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode row : list) {
            if (target.accountIds().contains(row.path("account_id").asString(""))) {
                matches.add(row);
            }
        }
        return matches;
    }

    /** 세지 않는 나열. {@code byName}이면 이름 축, 아니면 코드 축. 둘 다 전 {@code sj_div}다. */
    private static List<String> listRows(JsonNode list, boolean byName) {
        List<String> collected = new ArrayList<>();
        for (JsonNode row : list) {
            String sjDiv = row.path("sj_div").asString("");
            String name = row.path("account_nm").asString("");
            String id = row.path("account_id").asString("?");
            boolean matched = byName
                    ? NAME_HINTS.stream().anyMatch(name::contains)
                    : ID_HINTS.stream().anyMatch(id::contains);
            if (matched) {
                collected.add("[" + sjDiv + "] " + name + "="
                        + row.path("thstrm_amount").asString("") + " [" + id + "]");
            }
        }
        return collected;
    }

    /** 금액. 콤마가 섞여 오고 빈 값은 {@code "-"}로 온다. */
    private static BigDecimal amountOf(JsonNode row) {
        if (row == null) {
            return null;
        }
        String cleaned = row.path("thstrm_amount").asString("").replace(",", "").trim();
        if (cleaned.isEmpty() || "-".equals(cleaned)) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            // 삼키지 않는다 — 파싱 불가는 그 자체가 소득이다(계정 형식이 다르다는 뜻).
            System.out.println("        파싱 불가: " + cleaned);
            return null;
        }
    }

    /**
     * 쉼표 목록을 받는다. <b>MUST: 받은 값을 출력한다</b> —
     * {@code build.gradle}이 {@code probe.} 접두를 전부 넘기므로 오타가 조용히 기본값으로 돈다.
     */
    private static List<String> parameter(String key, String fallback) {
        String raw = System.getProperty(key, fallback);
        List<String> out = new ArrayList<>();
        for (String piece : raw.split(",")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
