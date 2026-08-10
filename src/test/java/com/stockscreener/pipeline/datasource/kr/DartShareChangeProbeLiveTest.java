package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「증자(감자) 현황」({@code irdsSttus.json})이 <b>액면분할을 잡아 주는지</b> 실측한다.
 * <b>이 탐침이 P9(수정주가 정상화)의 크기를 정한다.</b>
 *
 * <p>배경은 api-spec §9다. DART 엔드포인트 카탈로그에 <b>액면분할 전용 API가 없고</b>
 * 주요사항보고서 36종의 "분할"은 전부 <b>회사분할</b>(회사를 쪼개는 것)이지 액면분할(1주를
 * N주로)이 아니다. 그래서 {@code adj_factor}를 만들 원자료를 엔드포인트 하나로 받을 수 없고,
 * 남는 길 셋 중 <b>가장 싼 것이 이 엔드포인트</b>다.
 *
 * <p>개발가이드를 받아 보니 필드가 셋 다 있다 — {@code isu_dcrs_de}(일자) ·
 * {@code isu_dcrs_stle}(발행/감소 <b>형태</b>) · {@code isu_dcrs_mstvdv_fval_amount}(주당
 * <b>액면가액</b>). <b>그러나 스펙에 필드가 있는 것과 액면분할이 그 표에 실리는 것은 다르다</b> —
 * 이 API의 이름은 "증자(감자)"이고 액면분할은 자본금을 바꾸지 않는다. 그것을 여기서 가른다.
 *
 * <p>테스트가 아니라 <b>탐침</b>이다. 단언은 최소한만 두고 관측을 표준출력으로 낸다.
 *
 * <p><b>표본을 정답이 알려진 것으로 골랐다.</b> "행이 왔다"가 아니라 "올바른 일자와 비율이
 * 왔다"까지 판정해야 P9를 이 위에 올릴 수 있다. 응답의 액면가가 분할 <b>전</b> 값이면 비율이
 * 뒤집히고, {@link com.stockscreener.pipeline.datasource.CorporateAction}의 javadoc이 경고하는
 * 바로 그 오류가 된다 — 과거 시세가 50배로 부풀고 차트를 직접 보기 전까지 드러나지 않는다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartShareChangeProbeLiveTest {

    /** 액면분할 이력이 확실한 표본. 일자·비율·액면가가 전부 공개된 사실이다. */
    private record KnownSplit(String ticker, String name, int fiscalYear,
                              String effectiveDate, int ratio,
                              int faceValueBefore, int faceValueAfter) {
    }

    private static final List<KnownSplit> KNOWN_SPLITS = List.of(
            new KnownSplit("005930", "삼성전자", 2018, "2018-05-04", 50, 5000, 100),
            new KnownSplit("035420", "NAVER", 2018, "2018-10-12", 5, 500, 100),
            new KnownSplit("035720", "카카오", 2021, "2021-04-15", 5, 500, 100));

    private static final String ANNUAL = "11011";
    /** 사업보고서에 안 나올 때만 부른다. 분기 셋. */
    private static final List<String> QUARTERLY = List.of("11013", "11012", "11014");

    /** 액면분할이 어떤 낱말로 오는지 모른다. 후보를 넓게 잡고 실제 값을 전부 출력한다. */
    private static final List<String> SPLIT_WORDS = List.of("액면분할", "주식분할", "분할", "액면");

    @Test
    @DisplayName("[live][probe] 증자(감자) 현황이 액면분할을 잡아 주는지 기록한다")
    void probeShareChange() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();

        System.out.println("\n===== irdsSttus.json 실측 — 액면분할을 잡는가 =====");

        TreeSet<String> allForms = new TreeSet<>();
        List<String> verdicts = new ArrayList<>();
        int splitRowsFound = 0;

        for (KnownSplit known : KNOWN_SPLITS) {
            String corpCode = cache.findCorpCode(known.ticker()).orElseThrow();
            System.out.printf("%n--- %s(%s) %d년 — 정답: %s에 %d:1, 액면가 %d→%d%n",
                    known.name(), known.ticker(), known.fiscalYear(),
                    known.effectiveDate(), known.ratio(),
                    known.faceValueBefore(), known.faceValueAfter());

            // 1) 사업보고서 먼저. 여기서 답하면 분기 셋을 부르지 않는다 — 조기 종료로
            //    콜을 아끼는 것이 아니라, 사업보고서만으로 되는지가 곧 적재 비용이다.
            JsonNode annual = fetch(client, corpCode, known.fiscalYear(), ANNUAL);
            List<JsonNode> rows = rowsOf(annual);
            System.out.println("    [사업보고서 11011] status=" + DartProbeSupport.describeStatus(annual)
                    + " 행=" + rows.size());
            List<JsonNode> hits = printRows(rows, allForms);

            // 2) 사업보고서가 비면 분기를 훑는다. 분기에만 실린다면 적재 비용이 4배가 된다.
            if (hits.isEmpty()) {
                System.out.println("    사업보고서에 분할로 보이는 행이 없다. 분기 셋을 훑는다.");
                for (String reportCode : QUARTERLY) {
                    JsonNode quarterly = fetch(client, corpCode, known.fiscalYear(), reportCode);
                    List<JsonNode> quarterlyRows = rowsOf(quarterly);
                    System.out.println("    [" + reportCode + "] status="
                            + DartProbeSupport.describeStatus(quarterly)
                            + " 행=" + quarterlyRows.size());
                    hits.addAll(printRows(quarterlyRows, allForms));
                    if (!hits.isEmpty()) {
                        System.out.println("    → 분기 보고서에서 찾았다. 적재는 분기까지 돌아야 한다.");
                        break;
                    }
                }
            }

            verdicts.add(judge(known, hits));
            splitRowsFound += hits.size();
        }

        probeCumulativeRange(client, cache);
        probeControlYear(client, cache);
        probeTotalShares(client, cache);

        System.out.println("\n===== 판정 =====");
        verdicts.forEach(v -> System.out.println("  " + v));
        System.out.println("\n관측된 isu_dcrs_stle 값 전부: " + allForms);
        System.out.println("분할로 보이는 행 합계: " + splitRowsFound);
        System.out.println("\n액면분할 행: " + (splitRowsFound > 0 ? "있음" : "없음"));
        if (splitRowsFound > 0) {
            System.out.println("→ P9는 이 엔드포인트 위에 올릴 수 있다. api-spec §9의"
                    + " '남는 길 셋'과 ADR-0039의 'P9가 그만큼 커진다'를 재판정한다.");
        } else {
            System.out.println("→ 닫혔다. api-spec §9의 판정이 유지되고 남는 길은 공시 원문 파싱이다."
                    + " 성격이 다른 일이므로 여기서 멈추고 보고한다(사용자 결정 2026-08-10).");
        }

        assertThat(allForms)
                .as("응답이 통째로 비면 요청 자체가 틀린 것이다 — status를 위 출력에서 확인한다")
                .isNotNull();
    }

    /**
     * 관측한 행을 전부 출력하고 분할로 보이는 것을 골라 낸다.
     *
     * <p><b>후보 낱말에 걸린 것만이 아니라 모든 행의 {@code isu_dcrs_stle}를 모은다.</b>
     * 액면분할이 어떤 낱말로 오는지 모르는 상태이므로, 후보에 안 걸린 값을 버리면
     * "없다"와 "우리가 이름을 모른다"가 구분되지 않는다 — api-spec §8이 이익잉여금에서
     * 정확히 그 오진을 기록해 뒀다.
     */
    private static List<JsonNode> printRows(List<JsonNode> rows, TreeSet<String> allForms) {
        List<JsonNode> hits = new ArrayList<>();
        for (JsonNode row : rows) {
            String form = row.path("isu_dcrs_stle").asString("");
            allForms.add(form.isBlank() ? "(빈 값)" : form);
            System.out.printf("      %s | %-16s | 수량 %-16s | 액면가 %-10s | 발행가 %s%n",
                    row.path("isu_dcrs_de").asString(""),
                    form,
                    row.path("isu_dcrs_qy").asString(""),
                    row.path("isu_dcrs_mstvdv_fval_amount").asString(""),
                    row.path("isu_dcrs_mstvdv_amount").asString(""));
            if (SPLIT_WORDS.stream().anyMatch(form::contains)) {
                hits.add(row);
            }
        }
        return hits;
    }

    /** 정답과 대조한다. 맞았는가만이 아니라 <b>어디가 틀렸는가</b>를 낸다. */
    private static String judge(KnownSplit known, List<JsonNode> hits) {
        if (hits.isEmpty()) {
            return known.name() + " " + known.fiscalYear() + ": 분할 행 없음";
        }
        for (JsonNode row : hits) {
            String date = row.path("isu_dcrs_de").asString("").replace(".", "-").trim();
            String faceValue = row.path("isu_dcrs_mstvdv_fval_amount").asString("")
                    .replace(",", "").trim();
            boolean dateMatches = date.contains(known.effectiveDate())
                    || known.effectiveDate().equals(date);
            String faceVerdict;
            if (faceValue.equals(String.valueOf(known.faceValueAfter()))) {
                faceVerdict = "액면가=분할 후(" + faceValue + ") → 비율 유도 가능";
            } else if (faceValue.equals(String.valueOf(known.faceValueBefore()))) {
                faceVerdict = "액면가=분할 전(" + faceValue + ") → IMPORTANT: 방향이 뒤집힌다";
            } else {
                faceVerdict = "액면가='" + faceValue + "' → 정답 어느 쪽과도 다르다";
            }
            return known.name() + " " + known.fiscalYear() + ": 일자 "
                    + (dateMatches ? "일치(" + date + ")" : "불일치(응답 " + date + ")")
                    + ", " + faceVerdict;
        }
        return known.name() + ": 판정 불가";
    }

    /**
     * <b>이 한 콜이 적재 비용을 10배 가른다.</b>
     *
     * <p>DART 정기보고서의 자본금 변동사항 표는 통상 최근 몇 년치를 함께 싣는다. 최근
     * 사업보고서 하나에 과거 분할이 함께 오면 종목당 1콜(350콜)로 끝나고, 아니면 연도마다
     * 돌아야 해서 350 × 연도수가 된다.
     */
    private static void probeCumulativeRange(RestClient client, DartCorpCodeCache cache)
            throws Exception {
        int recentYear = LocalDate.now().getYear() - 1;
        String corpCode = cache.findCorpCode("005930").orElseThrow();
        System.out.printf("%n--- 누적 범위: 삼성전자 %d 사업보고서에 2018년 분할이 함께 오는가%n",
                recentYear);

        JsonNode body = fetch(client, corpCode, recentYear, ANNUAL);
        List<JsonNode> rows = rowsOf(body);
        System.out.println("    status=" + DartProbeSupport.describeStatus(body)
                + " 행=" + rows.size());

        TreeSet<String> years = new TreeSet<>();
        for (JsonNode row : rows) {
            String date = row.path("isu_dcrs_de").asString("");
            if (date.length() >= 4) {
                years.add(date.substring(0, 4));
            }
            System.out.printf("      %s | %s | 액면가 %s%n",
                    date, row.path("isu_dcrs_stle").asString(""),
                    row.path("isu_dcrs_mstvdv_fval_amount").asString(""));
        }
        System.out.println("    포함된 연도: " + years);
        System.out.println("    → 여러 해가 오면 종목당 1콜(350콜)로 이력이 끝난다."
                + " 한 해뿐이면 350 × 연도수다.");
    }

    /** 분할이 없던 해에 무엇이 오는가. {@code 013}과 "빈 list"가 구분되는지 본다. */
    private static void probeControlYear(RestClient client, DartCorpCodeCache cache)
            throws Exception {
        String corpCode = cache.findCorpCode("035720").orElseThrow();
        System.out.println("\n--- 대조군: 카카오 2019(분할 없던 해)");
        JsonNode body = fetch(client, corpCode, 2019, ANNUAL);
        System.out.println("    status=" + DartProbeSupport.describeStatus(body)
                + " 행=" + rowsOf(body).size());
        System.out.println("    → 013이면 '그 해에 변동이 없다'이고, 000 + 빈 list면 다른 뜻이다."
                + " 둘을 섞으면 수집기가 결손을 정상으로 읽는다.");
    }

    /**
     * 폴백 경로이자 대조군. 「주식의 총수 현황」이 분할 전후로 발행주식총수가 N배가 되는 것을
     * 보여 주면, 분할 <b>탐지</b>는 여기로도 된다.
     *
     * <p>다만 api-spec §9가 적어 둔 대로 그것은 <b>추정이지 이력이 아니다</b> — 정확한 효력일을
     * 주지 않으므로 이것만으로 {@code adj_factor}를 만들지 않는다.
     */
    private static void probeTotalShares(RestClient client, DartCorpCodeCache cache)
            throws Exception {
        String corpCode = cache.findCorpCode("005930").orElseThrow();
        System.out.println("\n--- 대조군: 주식의 총수 현황(삼성전자 2017 vs 2018)");
        for (int year : new int[]{2017, 2018}) {
            JsonNode body = DartProbeSupport.get(client, uri -> uri
                    .path("/stockTotqySttus.json")
                    .queryParam("crtfc_key", DartProbeSupport.apiKey())
                    .queryParam("corp_code", corpCode)
                    .queryParam("bsns_year", year)
                    .queryParam("reprt_code", ANNUAL)
                    .build());
            System.out.println("    [" + year + "] status=" + DartProbeSupport.describeStatus(body));
            for (JsonNode row : rowsOf(body)) {
                System.out.printf("      se='%s' 발행총수=%s 자기주식=%s 유통주식=%s%n",
                        row.path("se").asString(""),
                        row.path("istc_totqy").asString(""),
                        row.path("tesstk_co").asString(""),
                        row.path("distb_stock_co").asString(""));
            }
        }
        System.out.println("    → 2018이 2017의 50배면 분할을 탐지할 수 있다."
                + " 단 효력일을 주지 않으므로 이력이 아니라 추정이다(api-spec §9).");
    }

    private static JsonNode fetch(RestClient client, String corpCode, int year, String reportCode) {
        return DartProbeSupport.get(client, uri -> uri
                .path("/irdsSttus.json")
                .queryParam("crtfc_key", DartProbeSupport.apiKey())
                .queryParam("corp_code", corpCode)
                .queryParam("bsns_year", year)
                .queryParam("reprt_code", reportCode)
                .build());
    }

    private static List<JsonNode> rowsOf(JsonNode body) {
        List<JsonNode> rows = new ArrayList<>();
        if (body == null) {
            return rows;
        }
        JsonNode list = body.get("list");
        if (list != null) {
            list.forEach(rows::add);
        }
        return rows;
    }
}
