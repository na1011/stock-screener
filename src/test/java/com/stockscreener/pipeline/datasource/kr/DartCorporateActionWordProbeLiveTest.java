package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「증자(감자) 현황」({@code irdsSttus.json})이 <b>액면병합과 무상증자를 어떤 낱말로 주는지</b>
 * 실측한다. ADR-0049가 착수 전에 재라고 지목한 값이다.
 *
 * <p>{@link DartShareChangeProbeLiveTest}가 액면분할이 {@code 주식분할}로 온다는 것을 이미
 * 확인했다(api-spec dart-corporate-action.md의 「증자(감자) 현황」 절). 남은 둘은 미관측이고, <b>낱말을 틀리면
 * 0건이 되며 그것이
 * "그런 사건이 없다"로 읽힌다.</b> 그래서 별도 탐침으로 둔다 — 저쪽을 다시 돌리면 이미 답이
 * 나온 분할에 콜을 다시 태운다.
 *
 * <p><b>표본을 정답이 알려진 것으로 골랐고, 정답의 근거가 우리 DB 안에 있다.</b>
 * {@code price_daily.listed_shares}가 두 사건 모두에서 정확한 배수를 보여 주므로,
 * 응답의 비율이 맞는지를 <b>외부 지식 없이</b> 판정할 수 있다.
 *
 * <ul>
 *   <li><b>대한전선 10:1 병합</b> — 주식수 1,244,473,009 → 124,447,300(÷10). 종가는
 *       1,492 → 15,860(×10.63)이고 그 사이 <b>11거래일이 거래정지</b>다.
 *   <li><b>루닛 1:1 무상증자</b> — 주식수 14,238,154 → 28,476,308(×2). 권리락(가격이
 *       반토막 나는 날) 2023-11-09와 신주상장(주식수가 늘어나는 날) 2023-12-01이
 *       <b>16거래일</b> 벌어져 있다.
 * </ul>
 *
 * <p>테스트가 아니라 <b>탐침</b>이다. 단언은 최소한만 두고 관측을 표준출력으로 낸다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartCorporateActionWordProbeLiveTest {

    /**
     * 정답이 알려진 기업 행위. {@code sharesBefore}·{@code sharesAfter}는 우리
     * {@code price_daily.listed_shares} 실측이며 비율의 독립 증거다.
     */
    private record KnownAction(String ticker, String name, int eventYear, String label,
                               String priceJumpDate, String shareChangeDate,
                               long sharesBefore, long sharesAfter) {

        /** "1주가 몇 주가 되었는가". `CorporateAction.ratio`의 계약과 같은 방향이다. */
        double expectedRatio() {
            return (double) sharesAfter / sharesBefore;
        }
    }

    private static final List<KnownAction> KNOWN = List.of(
            new KnownAction("001440", "대한전선", 2023, "10:1 액면병합",
                    "2023-05-16", "2023-05-16", 1_244_473_009L, 124_447_300L),
            new KnownAction("328130", "루닛", 2023, "1:1 무상증자",
                    "2023-11-09", "2023-12-01", 14_238_154L, 28_476_308L));

    private static final String ANNUAL = "11011";

    /**
     * 최근 사업보고서 하나가 5년을 준다는 dart-corporate-action.md의 실측을 이 표본으로 다시 건다.
     * 참이면 두 사건 모두 이 해의 보고서 하나에 실려 있어야 한다.
     */
    private static final int RECENT_ANNUAL_YEAR = 2025;

    @Test
    @DisplayName("[live][probe] 액면병합과 무상증자가 isu_dcrs_stle에 어떤 낱말로 오는지 기록한다")
    void probeCorporateActionWords() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();

        System.out.println("\n===== irdsSttus.json — 병합·무상증자의 낱말 =====");

        TreeSet<String> allForms = new TreeSet<>();
        List<String> verdicts = new ArrayList<>();

        for (KnownAction known : KNOWN) {
            String corpCode = cache.findCorpCode(known.ticker()).orElseThrow();
            System.out.printf("%n--- %s(%s) — 정답: %s / 가격 점프 %s / 주식수 변경 %s / 비율 %.4f%n",
                    known.name(), known.ticker(), known.label(),
                    known.priceJumpDate(), known.shareChangeDate(), known.expectedRatio());

            // 최근 사업보고서 하나로 끝나는지가 곧 적재 비용이다(dart-corporate-action.md의 350콜 주장).
            JsonNode recent = fetch(client, corpCode, RECENT_ANNUAL_YEAR);
            List<JsonNode> rows = rowsOf(recent);
            System.out.println("    [" + RECENT_ANNUAL_YEAR + " 사업보고서] status="
                    + DartProbeSupport.describeStatus(recent) + " 행=" + rows.size());
            List<JsonNode> inYear = printRows(rows, allForms, known.eventYear());

            // 안 실렸으면 사건이 난 해를 직접 부른다. 그러면 350콜 주장이 깨진 것이다.
            if (inYear.isEmpty()) {
                // 갈래가 둘이다 — 누적 범위가 짧은 것과 그 해에 실릴 사건이 없었던 것.
                // 둘을 섞으면 "5년 누적이 깨졌다"는 틀린 결론이 나온다.
                System.out.println("    최근 사업보고서에 " + known.eventYear()
                        + "년 행이 없다. 누적 범위가 짧은 것인지 그 해에 실릴 사건이 없었던 것인지"
                        + " 위 출력의 다른 연도로 가른다. 사건 연도를 직접 부른다.");
                JsonNode eventYear = fetch(client, corpCode, known.eventYear());
                List<JsonNode> eventRows = rowsOf(eventYear);
                System.out.println("    [" + known.eventYear() + " 사업보고서] status="
                        + DartProbeSupport.describeStatus(eventYear) + " 행=" + eventRows.size());
                inYear = printRows(eventRows, allForms, known.eventYear());
            }

            verdicts.add(judge(known, inYear));
        }

        System.out.println("\n===== 판정 =====");
        verdicts.forEach(v -> System.out.println("  " + v));
        System.out.println("\n관측된 isu_dcrs_stle 값 전부: " + allForms);
        System.out.println("""

                → 이 목록이 어댑터의 '가격을 바꾸는 행위' 판정표의 씨앗이다.
                  MUST: 목록에 없는 낱말을 조용히 버리지 않는다. 버리면 '그런 사건이 없다'와
                  '우리가 그 이름을 모른다'가 구분되지 않는다(api-spec dart-financial.md의 「계정 집합이 회사마다 다르다」 절의 이익잉여금 전례).""");

        assertThat(allForms)
                .as("응답이 통째로 비면 요청 자체가 틀린 것이다 — status를 위 출력에서 확인한다")
                .isNotEmpty();
    }

    /**
     * 관측한 행을 전부 출력하고 사건 연도의 것만 골라 낸다.
     *
     * <p><b>낱말로 거르지 않는다.</b> 무엇이 오는지 모르는 것이 이 탐침의 질문이므로,
     * 후보 목록으로 좁히면 답을 미리 가정하는 것이 된다.
     */
    private static List<JsonNode> printRows(List<JsonNode> rows, TreeSet<String> allForms,
                                            int eventYear) {
        List<JsonNode> inYear = new ArrayList<>();
        for (JsonNode row : rows) {
            String form = row.path("isu_dcrs_stle").asString("");
            String date = row.path("isu_dcrs_de").asString("");
            allForms.add(form.isBlank() ? "(빈 값)" : form);
            System.out.printf("      %-12s | %-20s | 수량 %-18s | 액면가 %-10s | 발행가 %s%n",
                    date, form,
                    row.path("isu_dcrs_qy").asString(""),
                    row.path("isu_dcrs_mstvdv_fval_amount").asString(""),
                    row.path("isu_dcrs_mstvdv_amount").asString(""));
            if (date.startsWith(String.valueOf(eventYear))) {
                inYear.add(row);
            }
        }
        return inYear;
    }

    /**
     * 정답과 대조한다. <b>어느 날짜에 걸리는지가 요점이다</b> — 무상증자에서 소스 일자가
     * 신주상장 쪽이면 그것을 그대로 {@code effective_date}로 쓸 수 없다. ADR-0049가
     * 탐색 폭을 30거래일로 잡은 근거가 여기서 확정된다.
     */
    private static String judge(KnownAction known, List<JsonNode> rows) {
        if (rows.isEmpty()) {
            return known.name() + ": " + known.eventYear() + "년 행이 없다 — 낱말 이전에 행 자체가 안 온다";
        }
        StringBuilder sb = new StringBuilder(known.name() + " (" + known.label() + "): ");
        for (JsonNode row : rows) {
            String date = row.path("isu_dcrs_de").asString("").replace(".", "-").trim();
            String form = row.path("isu_dcrs_stle").asString("");
            String which;
            if (date.equals(known.priceJumpDate()) && date.equals(known.shareChangeDate())) {
                which = "가격·주식수 같은 날";
            } else if (date.equals(known.priceJumpDate())) {
                which = "가격 점프일과 일치";
            } else if (date.equals(known.shareChangeDate())) {
                which = "신주상장일과 일치 → effective_date로 쓰면 안 된다";
            } else {
                which = "정답 두 날짜 어느 쪽과도 다르다";
            }
            sb.append("['").append(form).append("' ").append(date).append(" — ").append(which).append("] ");
        }
        return sb.toString();
    }

    private static JsonNode fetch(RestClient client, String corpCode, int year) {
        return DartProbeSupport.get(client, uri -> uri
                .path("/irdsSttus.json")
                .queryParam("crtfc_key", DartProbeSupport.apiKey())
                .queryParam("corp_code", corpCode)
                .queryParam("bsns_year", year)
                .queryParam("reprt_code", ANNUAL)
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
