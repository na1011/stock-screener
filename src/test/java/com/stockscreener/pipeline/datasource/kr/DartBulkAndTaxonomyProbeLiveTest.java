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
 * 「다중회사 주요계정」의 <b>복수조회가 실제로 되는지와 상한이 몇인지</b>, 그리고
 * 「XBRL택사노미」가 <b>무엇에 쓸 수 있는지</b>를 실측한다. <b>P10의 비용이 여기서 정해진다.</b>
 *
 * <p>지금 {@code financial-refill}은 종목당 1콜이라 350종목 × 4개년 × 4보고서 =
 * <b>5,600콜</b>이다(2026-08-10에 실제로 그만큼 태웠다). 복수조회가 되면 그 수가 수십분의 일이
 * 된다.
 *
 * <p><b>IMPORTANT — 개발가이드에는 복수조회 언급이 없다.</b> {@code corp_code}가
 * {@code STRING(8) Y}로 단수처럼 쓰여 있다. 복수조회의 유일한 근거는 카탈로그의
 * <i>"(대상법인 복수조회 가능)"</i> 한 줄이고, 그것은 문서지 실측이 아니다.
 *
 * <p>택사노미는 반대 방향의 관측이다. 요청 인자가 {@code crtfc_key} + {@code sj_div} 둘뿐이라
 * <b>{@code corp_code}를 받지 않는다</b> — 즉 api-spec §9가 적어 둔 "§8의 이익잉여금 결손이
 * '계정이 없는 것'인지 '우리가 이름을 모르는 것'인지 가르는 데 쓸 수 있다"는 <b>성립할 수
 * 없다.</b> 회사별 재무제표가 아니라 표준계정 사전이기 때문이다. 그 사실을 실측으로 확정하고,
 * 대신 P10의 계정 매칭 후보군을 손으로 늘리지 않고 만들 수 있는지를 본다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartBulkAndTaxonomyProbeLiveTest {

    /** 복수조회에 쓸 실존 대형주. 전부 코스피200이고 재무가 확실히 공시된다. */
    private static final List<String> TICKERS = List.of(
            "005930", "000660", "373220", "207940", "005380", "005490",
            "035420", "035720", "051910", "105560", "055550", "000810");

    private static final int SETTLED_YEAR = LocalDate.now().getYear() - 1;
    private static final String ANNUAL = "11011";

    @Test
    @DisplayName("[live][probe] 다중회사 주요계정의 복수조회 상한을 기록한다")
    void probeMultiAccount() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();

        List<String> corpCodes = new ArrayList<>();
        for (String ticker : TICKERS) {
            cache.findCorpCode(ticker).ifPresent(corpCodes::add);
        }

        System.out.println("\n===== fnlttMultiAcnt.json 실측 — 복수조회 =====");
        System.out.println("표본 corp_code " + corpCodes.size() + "건 확보");

        // 2건 → 되는가. 12건 → 규모가 늘어도 되는가. 101건 → 상한이 걸리는가.
        boolean twoWorked = callWith(client, corpCodes.subList(0, 2));
        boolean twelveWorked = callWith(client, corpCodes);
        List<String> overLimit = cycleTo(corpCodes, 101);
        boolean overWorked = callWith(client, overLimit);

        System.out.println("\n===== 판정 =====");
        System.out.println("2건 복수조회: " + (twoWorked ? "된다" : "안 된다"));
        System.out.println("12건 복수조회: " + (twelveWorked ? "된다" : "안 된다"));
        System.out.println("101건: " + (overWorked ? "통과했다(상한이 100이 아니다)" : "막혔다"));
        System.out.println("IMPORTANT — 101건 요청은 표본 12개를 순환 반복해 만든 것이다."
                + " DART가 중복을 접어 세면 실제 상한을 재지 못하므로, 위 status가 021이 아니면"
                + " '상한이 100보다 크다'가 아니라 '이 방법으로는 못 쟀다'로 읽는다.");
        System.out.println("→ 되면 financial-refill의 5,600콜이 종목 100개 묶음 기준"
                + " 4개년 × 4보고서 × 4묶음 = 64콜 수준으로 줄어든다.");

        assertThat(corpCodes).as("표본 corp_code를 하나도 못 찾으면 캐시가 깨진 것이다").isNotEmpty();
    }

    /**
     * 호출 하나. <b>어느 회사의 행인지 되돌릴 수 있는지</b>가 이 탐침의 두 번째 질문이다.
     *
     * <p>개발가이드의 응답 표에 {@code corp_code}가 없고 {@code stock_code}만 있다. 여러 회사를
     * 받았을 때 행을 회사에 되돌릴 키가 그것뿐이면 상장사만 다루는 우리에겐 문제가 없지만,
     * <b>없다고 가정하면 매핑이 조용히 어긋난다.</b>
     */
    private static boolean callWith(RestClient client, List<String> corpCodes) {
        String joined = String.join(",", corpCodes);
        JsonNode body = DartProbeSupport.get(client, uri -> uri
                .path("/fnlttMultiAcnt.json")
                .queryParam("crtfc_key", DartProbeSupport.apiKey())
                .queryParam("corp_code", joined)
                .queryParam("bsns_year", SETTLED_YEAR)
                .queryParam("reprt_code", ANNUAL)
                .build());

        System.out.printf("%n--- corp_code %d건 요청: status=%s%n",
                corpCodes.size(), DartProbeSupport.describeStatus(body));
        if (body == null) {
            return false;
        }
        JsonNode list = body.get("list");
        if (list == null || list.isEmpty()) {
            System.out.println("    행이 없다.");
            return false;
        }

        TreeSet<String> stockCodes = new TreeSet<>();
        boolean hasCorpCode = false;
        for (JsonNode row : list) {
            stockCodes.add(row.path("stock_code").asString("(없음)"));
            hasCorpCode |= !row.path("corp_code").asString("").isBlank();
        }
        System.out.println("    행 " + list.size() + "개, 구분된 stock_code " + stockCodes.size() + "개");
        System.out.println("    응답에 corp_code 필드: " + (hasCorpCode ? "있다" : "없다 — stock_code로만 되돌린다"));
        System.out.println("    stock_code 목록: " + stockCodes);

        // 요청한 수만큼 회사가 돌아왔는가. 하나만 오면 복수조회가 무시되고 첫 건만 처리된
        // 것이고, 그것이 가장 위험한 결과다 — 200 OK에 정상 데이터라 조용히 틀린다.
        if (stockCodes.size() < corpCodes.size()) {
            System.out.println("    !! 요청한 " + corpCodes.size() + "개 중 "
                    + stockCodes.size() + "개만 돌아왔다.");
        }
        return stockCodes.size() > 1;
    }

    /** 목록을 순환 반복해 target 길이로 만든다. 실존 corp_code만 쓰되 개수를 늘린다. */
    private static List<String> cycleTo(List<String> source, int target) {
        List<String> result = new ArrayList<>(target);
        for (int i = 0; i < target; i++) {
            result.add(source.get(i % source.size()));
        }
        return result;
    }

    @Test
    @DisplayName("[live][probe] XBRL택사노미가 회사별 진단에 쓸 수 없음을 확정한다")
    void probeTaxonomy() {
        RestClient client = DartProbeSupport.client();

        System.out.println("\n===== xbrlTaxonomy.json 실측 =====");
        System.out.println("요청 인자: crtfc_key + sj_div 둘뿐이다. corp_code를 받지 않는다.");

        // BS1 = 재무상태표·연결·유동/비유동법. 우리가 실제로 읽는 형태다.
        JsonNode body = DartProbeSupport.get(client, uri -> uri
                .path("/xbrlTaxonomy.json")
                .queryParam("crtfc_key", DartProbeSupport.apiKey())
                .queryParam("sj_div", "BS1")
                .build());

        System.out.println("status=" + DartProbeSupport.describeStatus(body));
        JsonNode list = body == null ? null : body.get("list");
        if (list == null || list.isEmpty()) {
            System.out.println("행이 없다.");
            return;
        }

        System.out.println("행 " + list.size() + "개");
        int shown = 0;
        boolean sawRetainedEarnings = false;
        for (JsonNode row : list) {
            String label = row.path("label_kor").asString("");
            if (label.contains("이익잉여금")) {
                sawRetainedEarnings = true;
                System.out.printf("    [이익잉여금] account_id=%s label_kor=%s data_tp=%s%n",
                        row.path("account_id").asString(""), label,
                        row.path("data_tp").asString(""));
            }
            if (shown < 5) {
                System.out.printf("    account_id=%-52s label_kor=%s%n",
                        row.path("account_id").asString(""), label);
                shown++;
            }
        }

        System.out.println("\n===== 판정 =====");
        System.out.println("이익잉여금 표준계정 존재: " + sawRetainedEarnings);
        System.out.println("IMPORTANT — 이 응답은 표준계정 '사전'이고 회사별 재무제표가 아니다."
                + " 오리온·오리온홀딩스·에스티큐브에 이익잉여금 행이 없는 것은"
                + " '이름을 모르는 것'이 아니라 '그 회사가 그 계정을 안 낸 것'이며,"
                + " 이 엔드포인트는 그것을 가리지 못한다. api-spec §9의 해당 서술은 오분류다.");
        System.out.println("쓸 수 있는 곳: account_id ↔ label_kor 사전을 만들어"
                + " P10의 계정 매칭 후보군을 손으로 늘리지 않고 생성하는 것. sj_div 하나당 1콜이다.");

        assertThat(list.size()).as("택사노미가 비면 sj_div 값이 틀린 것이다").isGreaterThan(0);
    }
}
