package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 거버넌스 원자료 둘을 <b>적재 코드보다 먼저</b> 관측한다. 저장 표의 모양을 이 결과가 정한다.
 *
 * <p>둘 다 §11-H·§11-I에 필드 표가 있지만 <b>필드 표가 있는 것과 파서를 쓸 수 있는 것은
 * 다르다.</b> 이 탐침이 묻는 것은 표에 없는 것들이다 — {@code se}가 실제로 몇 종류로 오는지,
 * 센티널 {@code -}가 어느 자리에 오는지, 감사의견의 {@code adt_opinion}이 어떤 문자열인지.
 *
 * <p><b>MUST NOT: 이 결과로 감사의견 판정 지표를 만들지 않는다.</b> §10-D가 <i>"표본 둘 다
 * `적정의견`이라 비적정의 표기는 미확인이다. 그것을 보기 전에는 판정 로직을 만들지 않는다"</i>고
 * 적었고, 이 표본도 활성 유니버스 안이라 <b>같은 한계를 물려받는다.</b> 여기서 얻는 것은
 * 「적정」쪽 표기와 응답 구조뿐이다.
 *
 * <p>표본은 §12·§13·§15와 <b>같은 여덟</b>이다. 호출 수는 <b>엔드포인트당 8콜, 합쳐서 16콜</b>.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartGovernanceProbeLiveTest {

    private static final String ANNUAL_REPORT = "11011";
    private static final String SETTLED_YEAR = "2025";

    /** 실측된 센티널. 빈 문자열이 아니라 이 문자열이다(§10-B). */
    private static final String DASH = "-";

    private static final Map<String, String> SAMPLES = new LinkedHashMap<>();

    static {
        SAMPLES.put("005930", "삼성전자(26 전자부품)");
        SAMPLES.put("105560", "KB금융(64 금융)");
        SAMPLES.put("035420", "NAVER(63 정보서비스)");
        SAMPLES.put("271560", "오리온(10 식료품)");
        SAMPLES.put("005490", "POSCO홀딩스(24 1차금속)");
        SAMPLES.put("000120", "CJ대한통운(49 육상운송)");
        SAMPLES.put("207940", "삼성바이오로직스(21 의약품)");
        SAMPLES.put("139480", "이마트(47 소매)");
    }

    @Test
    @DisplayName("[live][probe] 주식의 총수 현황 — se 구분·센티널·감소 내역을 관측한다")
    void probeShareComposition() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        System.out.println("\n===== 주식의 총수 현황 (" + SETTLED_YEAR + " 사업보고서) =====");
        System.out.println("표 설계에 필요한 것: se 값 집합 · '-'가 오는 자리 · Ⅱ와 Ⅳ의 차이");

        Set<String> seValues = new LinkedHashSet<>();
        Map<String, Integer> dashByField = new TreeMap<>();
        int answered = 0;
        int rowsTotal = 0;
        int withRetirement = 0;

        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElse(null);
            if (corpCode == null) {
                System.out.printf("%n--- %s: corp_code 없음%n", sample.getValue());
                continue;
            }
            ResponseEntity<JsonNode> response = client.get().uri(uri -> uri
                            .path("/stockTotqySttus.json")
                            .queryParam("crtfc_key", apiKey)
                            .queryParam("corp_code", corpCode)
                            .queryParam("bsns_year", SETTLED_YEAR)
                            .queryParam("reprt_code", ANNUAL_REPORT)
                            .build())
                    .retrieve().toEntity(JsonNode.class);

            JsonNode body = response.getBody();
            JsonNode list = body == null ? null : body.get("list");
            System.out.printf("%n--- %s %s%n", sample.getValue(), DartProbeSupport.describeStatus(body));
            if (list == null || list.isEmpty()) {
                System.out.println("    행이 없다.");
                continue;
            }
            answered++;
            boolean sawRetirement = false;
            for (JsonNode row : list) {
                rowsTotal++;
                String se = row.path("se").asString("");
                seValues.add(se);
                for (String f : new String[]{"isu_stock_totqy", "now_to_isu_stock_totqy",
                        "now_to_dcrs_stock_totqy", "redc", "profit_incnr", "rdmstk_repy",
                        "etc", "istc_totqy", "tesstk_co", "distb_stock_co", "stlm_dt"}) {
                    if (DASH.equals(row.path(f).asString("").trim())) {
                        dashByField.merge(f, 1, Integer::sum);
                    }
                }
                if (!DASH.equals(row.path("profit_incnr").asString("").trim())
                        && !row.path("profit_incnr").asString("").isBlank()
                        && !"0".equals(row.path("profit_incnr").asString("").replace(",", ""))) {
                    sawRetirement = true;
                }
                System.out.printf("    se=%-5s Ⅱ발행누계=%-16s Ⅲ감소=%-14s Ⅳ발행총수=%-16s Ⅴ자기주식=%-14s Ⅵ유통=%-16s 이익소각=%s%n",
                        se,
                        row.path("now_to_isu_stock_totqy").asString(""),
                        row.path("now_to_dcrs_stock_totqy").asString(""),
                        row.path("istc_totqy").asString(""),
                        row.path("tesstk_co").asString(""),
                        row.path("distb_stock_co").asString(""),
                        row.path("profit_incnr").asString(""));
            }
            if (sawRetirement) {
                withRetirement++;
            }
            System.out.println("    rcept_no=" + list.get(0).path("rcept_no").asString("")
                    + "  ← 앞 8자리가 공시일이다(DartReportPeriods가 이미 유도한다)");
        }

        System.out.println("\n===== 판정 =====");
        System.out.println("응답한 회사: " + answered + "/" + SAMPLES.size() + ", 총 행: " + rowsTotal);
        System.out.println("se 값 집합: " + seValues + "  ← 표의 share_class CHECK가 이것으로 정해진다");
        System.out.println("'-'가 온 필드: " + (dashByField.isEmpty() ? "(없음)" : dashByField));
        System.out.println("이익소각이 0이 아닌 회사: " + withRetirement + "/" + answered);
        System.out.println("\nMUST: se가 '비고'를 포함하면 그 행은 버린다(전 필드가 '-'다).");
        System.out.println("MUST: Ⅳ(istc_totqy)를 쓴다. Ⅱ(now_to_isu_stock_totqy)는 발행 '누계'라 다르다.");
        System.out.println("호출 수: 회사당 1콜 = " + SAMPLES.size() + "콜.");

        assertThat(answered).as("여덟 중 하나도 응답하지 않았다").isPositive();
    }

    @Test
    @DisplayName("[live][probe] 감사의견 — 문자열·기수 표기·행 배수를 관측한다 (판정 로직은 만들지 않는다)")
    void probeAuditOpinion() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        System.out.println("\n===== 회계감사인의 명칭 및 감사의견 (" + SETTLED_YEAR + " 사업보고서) =====");
        System.out.println("MUST NOT: 이 결과로 판정 로직을 만들지 않는다 — 비적정 표기는 여기 없다.");

        Set<String> opinions = new LinkedHashSet<>();
        Set<String> yearForms = new LinkedHashSet<>();
        Map<Integer, Integer> rowCounts = new TreeMap<>();
        int answered = 0;

        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElse(null);
            if (corpCode == null) {
                continue;
            }
            ResponseEntity<JsonNode> response = client.get().uri(uri -> uri
                            .path("/accnutAdtorNmNdAdtOpinion.json")
                            .queryParam("crtfc_key", apiKey)
                            .queryParam("corp_code", corpCode)
                            .queryParam("bsns_year", SETTLED_YEAR)
                            .queryParam("reprt_code", ANNUAL_REPORT)
                            .build())
                    .retrieve().toEntity(JsonNode.class);

            JsonNode body = response.getBody();
            JsonNode list = body == null ? null : body.get("list");
            System.out.printf("%n--- %s %s%n", sample.getValue(), DartProbeSupport.describeStatus(body));
            if (list == null || list.isEmpty()) {
                System.out.println("    행이 없다.");
                continue;
            }
            answered++;
            rowCounts.merge(list.size(), 1, Integer::sum);
            for (JsonNode row : list) {
                String year = row.path("bsns_year").asString("");
                String opinion = row.path("adt_opinion").asString("");
                yearForms.add(year.replaceAll("[0-9]+", "N"));
                opinions.add(opinion);
                System.out.printf("    bsns_year=%-18s 감사인=%-14s 의견=%-10s 강조=%s 핵심=%s%n",
                        year.replace("\n", "\\n"),
                        row.path("adtor").asString(""),
                        opinion,
                        abbreviate(row.path("emphs_matter").asString("")),
                        abbreviate(row.path("core_adt_matter").asString("")));
            }
        }

        System.out.println("\n===== 판정 =====");
        System.out.println("응답한 회사: " + answered + "/" + SAMPLES.size());
        System.out.println("회사별 행 수 분포: " + rowCounts + "  ← 3개년인데 6이면 2배로 오는 것이 재현된 것이다");
        System.out.println("관측된 adt_opinion: " + opinions + "  ← 허용목록의 시작점. 여기 없는 값은 판정불가로 둔다");
        System.out.println("bsns_year 형태(숫자를 N으로): " + yearForms + "  ← 연도로 파싱하면 실패한다");
        System.out.println("\nMUST: 비적정 표기는 활성 유니버스 밖에서만 관측된다. 350종목 적재도 그것을 못 답한다.");
        System.out.println("호출 수: 회사당 1콜 = " + SAMPLES.size() + "콜.");

        assertThat(answered).as("여덟 중 하나도 응답하지 않았다").isPositive();
    }

    private static String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "(빈 값)";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 24 ? flat : flat.substring(0, 24) + "…";
    }
}
