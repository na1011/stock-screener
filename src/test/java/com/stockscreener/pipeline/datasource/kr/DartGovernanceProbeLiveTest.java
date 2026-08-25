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
 * <p>둘 다 dart-governance.md의 「주식의 총수 현황」·「회계감사인의 명칭 및 감사의견」 절에 필드 표가 있지만
 * <b>필드 표가 있는 것과 파서를 쓸 수 있는 것은
 * 다르다.</b> 이 탐침이 묻는 것은 표에 없는 것들이다 — {@code se}가 실제로 몇 종류로 오는지,
 * 센티널 {@code -}가 어느 자리에 오는지, 감사의견의 {@code adt_opinion}이 어떤 문자열인지.
 *
 * <p><b>MUST NOT: 이 결과로 감사의견 판정 지표를 만들지 않는다.</b> dart-governance.md가 <i>"표본 둘 다
 * `적정의견`이라 비적정의 표기는 미확인이다. 그것을 보기 전에는 판정 로직을 만들지 않는다"</i>고
 * 적었고, 이 표본도 활성 유니버스 안이라 <b>같은 한계를 물려받는다.</b> 여기서 얻는 것은
 * 「적정」쪽 표기와 응답 구조뿐이다.
 *
 * <p>표본은 앞선 계정 탐침 셋과 <b>같은 여덟</b>이다. 호출 수는 <b>엔드포인트당 8콜</b>이고
 * 최대주주 변동현황만 두 해를 물어 16콜이라 <b>합쳐서 56콜</b>이다.
 *
 * <p>여덟 중 둘(주식의 총수·감사의견)은 2026-08-21에 관측해 dart-governance.md가 됐고, 나머지 여섯은
 * 2026-08-23에 더했다. <b>여섯의 값 집합은 개발가이드가 답하지 못한다</b> — dart-governance.md의 「여덟이 여덟
 * 모양이다」 절이 그 가이드의
 * 오류 둘을 이미 적어 뒀고({@code alotMatter}의 {@code se} 설명이 다른 엔드포인트 것,
 * {@code outcmpnyDrctrNdChangeSttus}의 {@code bsns_year} 타입 오기), 「3개년을 준다」도
 * <b>갈무리이지 실측이 아니다.</b>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartGovernanceProbeLiveTest {

    private static final String ANNUAL_REPORT = "11011";
    private static final String SETTLED_YEAR = "2025";

    /**
     * 최대주주 변동현황을 두 번째로 물을 해.
     *
     * <p>「요청한 해만 주는가, 이력 전체를 주는가」는 <b>한 해만 물어서는 원리적으로 안 갈린다</b> —
     * 표본이 대형주라 사건이 없으면 어느 쪽이든 {@code 013}이 온다. 적재 콜 수 700이 이 답에
     * 걸려 있어 8콜을 더 쓴다.
     */
    private static final String EARLIER_YEAR = "2023";

    /** 실측된 센티널. 빈 문자열이 아니라 이 문자열이다(dart-corporate-action.md의 「증자(감자) 현황」 절). */
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

    /**
     * 응답 하나를 받는다. 여섯이 요청 인자가 같아 이 자리에 모은다.
     *
     * <p>여기서 {@code bsns_year}를 <b>네 자리로</b> 보내는 것이 관측이기도 하다 —
     * 개발가이드가 {@code outcmpnyDrctrNdChangeSttus}의 타입을 {@code STRING(1)}로 적었고
     * dart-governance.md가 문서 오기로 판정했는데, 그 판정을 실물로 확인하는 것이 이 호출이다.
     */
    private static JsonNode fetch(RestClient client, String apiKey, String path,
                                  String corpCode, String year) {
        ResponseEntity<JsonNode> response = client.get().uri(uri -> uri
                        .path(path)
                        .queryParam("crtfc_key", apiKey)
                        .queryParam("corp_code", corpCode)
                        .queryParam("bsns_year", year)
                        .queryParam("reprt_code", ANNUAL_REPORT)
                        .build())
                .retrieve().toEntity(JsonNode.class);
        return response.getBody();
    }

    @Test
    @DisplayName("[live][probe] 배당에 관한 사항 — se 항목명 집합을 관측한다 (개발가이드가 답하지 못하는 자리)")
    void probeDividend() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        System.out.println("\n===== 배당에 관한 사항 (" + SETTLED_YEAR + " 사업보고서) =====");
        System.out.println("개발가이드의 se 값 설명에 '유상증자(주주배정), 전환권행사 등'이 적혀 있는데");
        System.out.println("그것은 다른 엔드포인트의 설명이다(개발가이드 오기). 실제 항목명은 여기서만 나온다.");

        Set<String> seValues = new LinkedHashSet<>();
        Set<String> stockKinds = new LinkedHashSet<>();
        Map<Integer, Integer> rowCounts = new TreeMap<>();
        int answered = 0;
        int lwfrFilled = 0;
        int lwfrTotal = 0;

        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElse(null);
            if (corpCode == null) {
                continue;
            }
            JsonNode body = fetch(client, apiKey, "/alotMatter.json", corpCode, SETTLED_YEAR);
            JsonNode list = body == null ? null : body.get("list");
            System.out.printf("%n--- %s %s%n", sample.getValue(), DartProbeSupport.describeStatus(body));
            if (list == null || list.isEmpty()) {
                System.out.println("    행이 없다.");
                continue;
            }
            answered++;
            rowCounts.merge(list.size(), 1, Integer::sum);
            for (JsonNode row : list) {
                seValues.add(row.path("se").asString(""));
                stockKinds.add(row.path("stock_knd").asString(""));
                lwfrTotal++;
                String lwfr = row.path("lwfr").asString("").trim();
                if (!lwfr.isEmpty() && !DASH.equals(lwfr)) {
                    lwfrFilled++;
                }
                System.out.printf("    se=%-22s 종류=%-6s 당기=%-16s 전기=%-16s 전전기=%-16s%n",
                        abbreviate(row.path("se").asString("")),
                        row.path("stock_knd").asString(""),
                        row.path("thstrm").asString(""),
                        row.path("frmtrm").asString(""),
                        row.path("lwfr").asString(""));
            }
        }

        System.out.println("\n===== 판정 =====");
        System.out.println("응답한 회사: " + answered + "/" + SAMPLES.size());
        System.out.println("회사별 행 수 분포: " + rowCounts);
        System.out.println("se 값 집합: " + seValues);
        System.out.println("  ← DPS_COMMON·CASH_DIVIDEND_TOTAL이 어느 행인지가 이것으로 정해진다");
        System.out.println("stock_knd 값 집합: " + stockKinds + "  ← 보통주 행을 고르는 낱말");
        System.out.printf("전전기(lwfr)가 채워진 칸: %d/%d%n", lwfrFilled, lwfrTotal);
        System.out.println("  ← 3개년이 열로 온다는 것은 갈무리이지 실측이 아니었다. 이 수가 그것을 답한다");
        System.out.println("호출 수: 회사당 1콜 = " + SAMPLES.size() + "콜.");

        assertThat(answered).as("여덟 중 하나도 응답하지 않았다").isPositive();
    }

    @Test
    @DisplayName("[live][probe] 최대주주 현황 — 합계 행이 있는지 본다 (있으면 전 행 합산이 두 배가 된다)")
    void probeLargestHolder() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        System.out.println("\n===== 최대주주 현황 (" + SETTLED_YEAR + " 사업보고서) =====");
        System.out.println("묻는 것: nm/relate에 '계'·'합계' 행이 있는가. 있으면 Σ가 두 배가 된다.");

        Set<String> relateValues = new LinkedHashSet<>();
        Set<String> stockKinds = new LinkedHashSet<>();
        Set<String> totalLikeNames = new LinkedHashSet<>();
        Map<Integer, Integer> rowCounts = new TreeMap<>();
        int answered = 0;

        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElse(null);
            if (corpCode == null) {
                continue;
            }
            JsonNode body = fetch(client, apiKey, "/hyslrSttus.json", corpCode, SETTLED_YEAR);
            JsonNode list = body == null ? null : body.get("list");
            System.out.printf("%n--- %s %s%n", sample.getValue(), DartProbeSupport.describeStatus(body));
            if (list == null || list.isEmpty()) {
                System.out.println("    행이 없다.");
                continue;
            }
            answered++;
            rowCounts.merge(list.size(), 1, Integer::sum);
            for (JsonNode row : list) {
                String nm = row.path("nm").asString("");
                String relate = row.path("relate").asString("");
                relateValues.add(relate);
                stockKinds.add(row.path("stock_knd").asString(""));
                if (nm.contains("계") || relate.contains("계")) {
                    totalLikeNames.add(nm + " / " + relate);
                }
                System.out.printf("    성명=%-16s 관계=%-12s 종류=%-6s 기말주식수=%-16s 기말지분율=%s%n",
                        abbreviate(nm), abbreviate(relate),
                        row.path("stock_knd").asString(""),
                        row.path("trmend_posesn_stock_co").asString(""),
                        row.path("trmend_posesn_stock_qota_rt").asString(""));
            }
        }

        System.out.println("\n===== 판정 =====");
        System.out.println("응답한 회사: " + answered + "/" + SAMPLES.size());
        System.out.println("회사별 행 수 분포: " + rowCounts);
        System.out.println("relate 값 집합: " + relateValues);
        System.out.println("stock_knd 값 집합: " + stockKinds);
        System.out.println("'계'가 든 행: " + (totalLikeNames.isEmpty() ? "(없음)" : totalLikeNames));
        System.out.println("  ← 비어 있지 않으면 유도가 그 행을 반드시 버려야 한다. 안 버리면 지분율이 두 배다");
        System.out.println("MUST NOT: qota_rt를 지표에 쓰지 않는다(spec §7). 분자는 주식수, 분모는 「주식의 총수 현황」 절의 istc_totqy다.");
        System.out.println("호출 수: 회사당 1콜 = " + SAMPLES.size() + "콜.");

        assertThat(answered).as("여덟 중 하나도 응답하지 않았다").isPositive();
    }

    @Test
    @DisplayName("[live][probe] 사외이사 및 그 변동현황 — 회사당 행 수와 bsns_year 4자리를 관측한다")
    void probeOutsideDirector() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        System.out.println("\n===== 독립(사외)이사 및 그 변동현황 (" + SETTLED_YEAR + " 사업보고서) =====");
        System.out.println("묻는 것 둘: ① 회사당 행이 몇인가(1이 아니면 행 선택 규칙이 필요하다)");
        System.out.println("           ② bsns_year를 네 자리로 보내도 되는가(가이드는 STRING(1)로 적었다)");

        Map<Integer, Integer> rowCounts = new TreeMap<>();
        Map<String, Integer> dashByField = new TreeMap<>();
        int answered = 0;

        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElse(null);
            if (corpCode == null) {
                continue;
            }
            JsonNode body = fetch(client, apiKey, "/outcmpnyDrctrNdChangeSttus.json",
                    corpCode, SETTLED_YEAR);
            JsonNode list = body == null ? null : body.get("list");
            System.out.printf("%n--- %s %s%n", sample.getValue(), DartProbeSupport.describeStatus(body));
            if (list == null || list.isEmpty()) {
                System.out.println("    행이 없다.");
                continue;
            }
            answered++;
            rowCounts.merge(list.size(), 1, Integer::sum);
            for (JsonNode row : list) {
                for (String f : new String[]{"drctr_co", "otcmp_drctr_co", "apnt", "rlsofc",
                        "mdstrm_resig"}) {
                    if (DASH.equals(row.path(f).asString("").trim())) {
                        dashByField.merge(f, 1, Integer::sum);
                    }
                }
                System.out.printf("    이사수=%-6s 사외이사=%-6s 선임=%-4s 해임=%-4s 중도퇴임=%-4s 결산일=%s%n",
                        row.path("drctr_co").asString(""),
                        row.path("otcmp_drctr_co").asString(""),
                        row.path("apnt").asString(""),
                        row.path("rlsofc").asString(""),
                        row.path("mdstrm_resig").asString(""),
                        row.path("stlm_dt").asString(""));
            }
        }

        System.out.println("\n===== 판정 =====");
        System.out.println("응답한 회사: " + answered + "/" + SAMPLES.size());
        System.out.println("회사별 행 수 분포: " + rowCounts + "  ← 1뿐이면 행 선택 규칙이 필요 없다");
        System.out.println("'-'가 온 필드: " + (dashByField.isEmpty() ? "(없음)" : dashByField));
        System.out.println("응답이 왔다는 것 자체가 bsns_year 네 자리가 옳다는 관측이다(가이드의 STRING(1)은 오기).");
        System.out.println("호출 수: 회사당 1콜 = " + SAMPLES.size() + "콜.");

        assertThat(answered).as("여덟 중 하나도 응답하지 않았다").isPositive();
    }

    @Test
    @DisplayName("[live][probe] 소액주주 현황 — 분자·분모가 한 응답에 함께 오는지 본다")
    void probeMinorityHolder() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        System.out.println("\n===== 소액주주 현황 (" + SETTLED_YEAR + " 사업보고서) =====");

        Set<String> seValues = new LinkedHashSet<>();
        Map<Integer, Integer> rowCounts = new TreeMap<>();
        int answered = 0;
        int bothPresent = 0;

        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElse(null);
            if (corpCode == null) {
                continue;
            }
            JsonNode body = fetch(client, apiKey, "/mrhlSttus.json", corpCode, SETTLED_YEAR);
            JsonNode list = body == null ? null : body.get("list");
            System.out.printf("%n--- %s %s%n", sample.getValue(), DartProbeSupport.describeStatus(body));
            if (list == null || list.isEmpty()) {
                System.out.println("    행이 없다.");
                continue;
            }
            answered++;
            rowCounts.merge(list.size(), 1, Integer::sum);
            for (JsonNode row : list) {
                seValues.add(row.path("se").asString(""));
                String held = row.path("hold_stock_co").asString("").trim();
                String total = row.path("stock_tot_co").asString("").trim();
                if (!held.isEmpty() && !DASH.equals(held) && !total.isEmpty() && !DASH.equals(total)) {
                    bothPresent++;
                }
                System.out.printf("    se=%-10s 주주수=%-12s 보유주식=%-16s 총발행=%-16s%n",
                        row.path("se").asString(""),
                        row.path("shrholdr_co").asString(""),
                        held, total);
            }
        }

        System.out.println("\n===== 판정 =====");
        System.out.println("응답한 회사: " + answered + "/" + SAMPLES.size());
        System.out.println("회사별 행 수 분포: " + rowCounts);
        System.out.println("se 값 집합: " + seValues);
        System.out.println("분자·분모가 함께 온 행: " + bothPresent);
        System.out.println("  ← 함께 오면 MINORITY_HOLDER_STAKE는 다른 엔드포인트를 안 거친다(축이 안 갈린다)");
        System.out.println("MUST NOT: hold_stock_rate를 쓰지 않는다(spec §7). 우리가 나눈다.");
        System.out.println("호출 수: 회사당 1콜 = " + SAMPLES.size() + "콜.");

        assertThat(answered).as("여덟 중 하나도 응답하지 않았다").isPositive();
    }

    @Test
    @DisplayName("[live][probe] 최대주주 변동현황 — 두 해를 물어 「요청한 해만」인지 「이력 전체」인지 가른다")
    void probeControlChange() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        System.out.println("\n===== 최대주주 변동현황 (" + SETTLED_YEAR + " · " + EARLIER_YEAR + ") =====");
        System.out.println("한 해만 물으면 표본이 대형주라 둘 다 013이 오고, 그것은");
        System.out.println("「요청한 해만 준다」와 「이력 전체인데 사건이 없다」를 구분하지 못한다.");
        System.out.println("콜 수 700이 여기 걸려 있어 8콜을 더 쓴다.");

        Set<String> changeCauses = new LinkedHashSet<>();
        Set<String> dateForms = new LinkedHashSet<>();
        int answered = 0;
        int sameAcrossYears = 0;
        int comparable = 0;
        int calls = 0;
        int malformed = 0;
        int emptyOneRow = 0;
        int earlierIsSubset = 0;

        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElse(null);
            if (corpCode == null) {
                continue;
            }
            JsonNode recent = fetch(client, apiKey, "/hyslrChgSttus.json", corpCode, SETTLED_YEAR);
            JsonNode earlier = fetch(client, apiKey, "/hyslrChgSttus.json", corpCode, EARLIER_YEAR);
            calls += 2;
            malformed += malformedCount(recent) + malformedCount(earlier);
            JsonNode recentList = recent == null ? null : recent.get("list");
            JsonNode earlierList = earlier == null ? null : earlier.get("list");

            System.out.printf("%n--- %s%n", sample.getValue());
            System.out.printf("    %s: %s, 행=%d%n", SETTLED_YEAR,
                    DartProbeSupport.describeStatus(recent),
                    recentList == null ? 0 : recentList.size());
            System.out.printf("    %s: %s, 행=%d%n", EARLIER_YEAR,
                    DartProbeSupport.describeStatus(earlier),
                    earlierList == null ? 0 : earlierList.size());

            if (isEmptySingleRow(recentList)) {
                // §P18 축①이 경고한 자리다 — 전 필드가 '-'인 1행이 status 000으로 온다.
                // 행 수만 세면 그것이 "1건 있음"이 된다.
                emptyOneRow++;
                System.out.println("      전 필드가 '-'인 1행이다 — 사건 없음이지 데이터 있음이 아니다.");
            } else if (recentList != null && !recentList.isEmpty()) {
                answered++;
                System.out.printf("      change_on 범위: %s ~ %s%n",
                        minDate(recentList), maxDate(recentList));
                if (earlierList != null && !isEmptySingleRow(earlierList)
                        && changeKeys(recentList).containsAll(changeKeys(earlierList))) {
                    earlierIsSubset++;
                    System.out.printf("      %s의 %d행이 %s의 %d행에 전부 들어 있다 — 누적 이력이다.%n",
                            EARLIER_YEAR, earlierList.size(), SETTLED_YEAR, recentList.size());
                }
                for (JsonNode row : recentList) {
                    changeCauses.add(row.path("change_cause").asString(""));
                    dateForms.add(row.path("change_on").asString("").replaceAll("[0-9]+", "N"));
                    System.out.printf("      변동일=%-14s 최대주주=%-16s 주식수=%-16s 원인=%s%n",
                            row.path("change_on").asString(""),
                            abbreviate(row.path("mxmm_shrholdr_nm").asString("")),
                            row.path("posesn_stock_co").asString(""),
                            abbreviate(row.path("change_cause").asString("")));
                }
            }
            if (recentList != null && earlierList != null
                    && !recentList.isEmpty() && !earlierList.isEmpty()) {
                comparable++;
                if (recentList.toString().equals(earlierList.toString())) {
                    sameAcrossYears++;
                }
            }
        }

        System.out.println("\n===== 판정 =====");
        System.out.println("사건 행이 실제로 온 회사: " + answered + "/" + SAMPLES.size());
        System.out.println("전 필드가 '-'인 1행만 온 회사: " + emptyOneRow
                + "  ← 이것을 '1건 있음'으로 세면 커버리지가 부푼다(§P18 축①)");
        System.out.println("두 해를 견줄 수 있던 회사: " + comparable + ", 응답이 완전히 같았던 회사: " + sameAcrossYears);
        System.out.println("이른 해가 늦은 해에 포함된 회사: " + earlierIsSubset + "/" + comparable);
        System.out.println("  ← 포함되면 「요청 연도까지의 누적」이다. 그러면 최신 해 1콜로 창을 우리가 자른다(-700콜).");
        System.out.println("  ← 포함되지 않으면 「요청한 해만」이고 세 해를 각각 불러야 한다.");
        System.out.println("change_on 형태(숫자를 N으로): " + dateForms + "  ← 다른 엔드포인트와 또 다르다");
        System.out.println("change_cause 값 집합: " + changeCauses);
        System.out.println("호출 수: 회사당 2콜 = " + (SAMPLES.size() * 2) + "콜.");

        // 여기서 013은 정상이다 — 「그 해에 최대주주 변동이 없었다」가 곧 관측이다. 그래서
        // 「응답이 왔는가」로는 단언할 수 없고, 대신 **요청이 틀렸는가**를 단언한다. status 100은
        // 필드의 부적절한 값이라 우리가 인자를 잘못 보낸 경우이고, 그것만이 이 탐침의 실패다.
        assertThat(malformed).as("status 100이 왔다 — 요청 인자가 틀렸다(bsns_year·reprt_code)").isZero();
        assertThat(calls).as("한 번도 호출하지 못했다").isPositive();
    }

    /**
     * 전 필드가 {@code -}인 1행인가.
     *
     * <p>이 소스는 <b>데이터가 없을 때 {@code 013}이 아니라 {@code 000} + 빈 1행</b>을 주는
     * 회사가 있다. 행 수만 세면 「1건 있음」이 되고, 그 수 위에 커버리지 판정이 선다.
     */
    private static boolean isEmptySingleRow(JsonNode list) {
        if (list == null || list.size() != 1) {
            return false;
        }
        JsonNode row = list.get(0);
        return DASH.equals(row.path("change_on").asString("").trim())
                && DASH.equals(row.path("mxmm_shrholdr_nm").asString("").trim());
    }

    /** 두 응답이 같은 사건을 담고 있는지 견주기 위한 키. 행 순서가 날짜순이 아니라 집합으로 본다. */
    private static Set<String> changeKeys(JsonNode list) {
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode row : list) {
            keys.add(row.path("change_on").asString("") + "|"
                    + row.path("mxmm_shrholdr_nm").asString("") + "|"
                    + row.path("posesn_stock_co").asString(""));
        }
        return keys;
    }

    private static String minDate(JsonNode list) {
        return list.valueStream().map(r -> r.path("change_on").asString(""))
                .filter(d -> !d.isBlank() && !DASH.equals(d)).sorted().findFirst().orElse("(없음)");
    }

    private static String maxDate(JsonNode list) {
        return list.valueStream().map(r -> r.path("change_on").asString(""))
                .filter(d -> !d.isBlank() && !DASH.equals(d))
                .sorted(java.util.Comparator.reverseOrder()).findFirst().orElse("(없음)");
    }

    /** status 100(필드의 부적절한 값)만 센다 — 013은 「데이터 없음」이라 실패가 아니다(DartResponse.kindOf가 정본이다). */
    private static int malformedCount(JsonNode body) {
        return body != null && "100".equals(body.path("status").asString("")) ? 1 : 0;
    }

    @Test
    @DisplayName("[live][probe] 이사·감사 전체의 보수(지급) — fscl_year 표기와 3개년 행을 관측한다")
    void probeExecPay() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        System.out.println("\n===== 이사·감사 전체의 보수현황(지급금액) (" + SETTLED_YEAR + " 사업보고서) =====");

        Set<String> fiscalYearForms = new LinkedHashSet<>();
        Map<Integer, Integer> rowCounts = new TreeMap<>();
        int answered = 0;

        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElse(null);
            if (corpCode == null) {
                continue;
            }
            JsonNode body = fetch(client, apiKey, "/hmvAuditAllSttus.json", corpCode, SETTLED_YEAR);
            JsonNode list = body == null ? null : body.get("list");
            System.out.printf("%n--- %s %s%n", sample.getValue(), DartProbeSupport.describeStatus(body));
            if (list == null || list.isEmpty()) {
                System.out.println("    행이 없다.");
                continue;
            }
            answered++;
            rowCounts.merge(list.size(), 1, Integer::sum);
            for (JsonNode row : list) {
                fiscalYearForms.add(row.path("fscl_year").asString("").replaceAll("[0-9]+", "N"));
                System.out.printf("    fscl_year=%-12s 인원=%-6s 보수총액=%-18s 1인평균=%s%n",
                        row.path("fscl_year").asString("").replace("\n", "\\n"),
                        row.path("nmpr").asString(""),
                        row.path("mendng_totamt").asString(""),
                        row.path("jan_avrg_mendng_am").asString(""));
            }
        }

        System.out.println("\n===== 판정 =====");
        System.out.println("응답한 회사: " + answered + "/" + SAMPLES.size());
        System.out.println("회사별 행 수 분포: " + rowCounts + "  ← 3이면 3개년이 행으로 온다");
        System.out.println("fscl_year 형태(숫자를 N으로): " + fiscalYearForms);
        System.out.println("  ← 연도가 아니다. 당기/전기/전전기 토큰으로 요청 연도에서 0/1/2를 뺀다");
        System.out.println("MUST NOT: jan_avrg_mendng_am을 쓰지 않는다(spec §7). mendng_totamt만 담는다.");
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
