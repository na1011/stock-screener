package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전체 재무제표가 <b>자본적지출</b>을 주는지 실측한다. 무형자산·영업권을 함께 잰다.
 *
 * <h2>왜 지금 재는가 — 이 항목만 판정 없이 결손으로 적혀 있었다</h2>
 *
 * <p>{@code PresetCatalog}의 프리셋 설명 둘이 <i>"감가상각비·총차입금·자본적지출을 소스가 주지
 * 않아 빠졌으며"</i>라고 적는다. 그런데 <b>어느 api-spec 문서에도 자본적지출의 측정이
 * 없다</b> — 감가상각비는 뒤에 3/8로 다시 재졌고({@code DartDepreciationProbeLiveTest}), 이자비용은 4개사 1/4이,
 * 총차입금은 「우리가 만드는 값」이라 재서 닫았지만 자본적지출은 어느 표에도 없다. <b>재지 않은 판정이 잰 판정 옆에 같은 문장으로
 * 앉아 있으면 다음 세션은 둘을 구별하지 못한다.</b>
 *
 * <p>여는 것이 넷이다 — 문서 3.3(오너 어닝의 대체), 1.6(자본 소요), <b>4.2(안전마진 —
 * P/FCF·잉여현금흐름 수익률)</b>, 5.4(자본집약 회피). 버핏 방법론의 4층이 통째로 여기 달려 있다.
 *
 * <p><b>IMPORTANT — 판정은 비대칭이다. "닫혔다"는 결론만 낼 수 있고 "열렸다"는 못 낸다</b>
 * ({@link DartMissingAccountProbeLiveTest}와 같다). dart-financial.md의 「계정 집합이 회사마다 다르다」 절이 그
 * 반례다 — 이익잉여금은 4개사에서
 * 전부 왔는데 350종목에서 3종목이 비었다. <b>8/8이 나와도 350종목 재적재로 재기 전에는 지표를
 * 걸지 않는다</b>(dart-financial.md의 「계정별 가용성」 절의 MUST).
 *
 * <h2>이 탐침이 감가상각비 탐침과 다르게 하는 것 둘</h2>
 *
 * <p><b>① 부호를 함께 기록한다.</b> 현금흐름표의 투자활동 유출은 <b>음수로 올 수 있다.</b>
 * {@code FCF = 영업활동현금흐름 − 자본적지출}인데 자본적지출이 이미 음수면 실제 계산은
 * <b>더하기</b>가 된다. 부호를 틀리면 FCF가 부풀고 <b>값이 그럴듯해 화면 어디에도 안 드러난다.</b>
 * 그래서 {@code thstrm_amount}를 판정과 함께 찍는다.
 *
 * <p><b>② {@code 취득} 부분일치를 세지 않는다.</b> {@code DartMissingAccountProbeLiveTest}가 {@code 감가상각}
 * 부분일치로 오리온의
 * {@code 투자부동산감가상각비 조정}을 잡아 3/8을 2/8로 정정한 전례가 있고, <b>이쪽은 후보가 훨씬
 * 많다</b> — 무형자산 취득·투자부동산 취득·종속기업 취득·자기주식 취득이 전부 걸린다.
 * 그러므로 {@code account_id} 완전일치만 <b>세고</b>, {@code 취득}이 든 계정은 <b>세지 않고
 * 나열만</b> 한다(dart-financial.md의 「계정별 가용성」 절의 차입 계열과 같은 취급). 나열은 후보 {@code
 * account_id}가 모자랐는지
 * 사람이 판정하기 위한 것이다 — <b>목록 없이 8콜을 태우면 "닫혔다"가 소스 부재인지 후보 부족인지
 * 갈리지 않는다.</b>
 *
 * <p>MUST: {@code sj_div}로 좁힌다. 자본적지출은 {@code CF}, 무형자산·영업권은 {@code BS}다.
 * dart-financial.md의 「계정별 가용성」 절이 현금및현금성자산에서 같은 함정을 적었다 — KB금융은 CF의 {@code
 * 기초 현금 및 현금성자산}이
 * 먼저 잡혔다.
 *
 * <p>표본은 {@code DartMissingAccountProbeLiveTest}와 <b>같은 여덟</b>이다. 같은 절에 결과를
 * 나란히 적기 위해서이며, 4개사 표본처럼 표본을
 * 머리에 못박은 절에는 섞지 않는다.
 *
 * <p>호출 수는 <b>회사당 1콜(8개사 = 8콜)</b>이다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartCapexProbeLiveTest {

    /** 사업보고서. 연간만 본다 — 분기를 함께 부르면 콜이 4배다. */
    private static final String ANNUAL_REPORT = "11011";

    /** 결산이 끝나 사업보고서가 확실히 있는 연도. */
    private static final String SETTLED_YEAR = "2025";

    /** {@code DartMissingAccountProbeLiveTest}와 같은 여덟. 중분류가 겹치지 않는다. */
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

    /**
     * 찾는 계정.
     *
     * @param sjDiv       이 계정이 있어야 하는 재무제표 구분. 다른 구분의 동명 계정을 잡지 않는다
     * @param accountIds  완전일치로 <b>세는</b> 표준계정ID. 계정명 폴백을 두지 않는다 — 위 javadoc ②
     * @param opens       이 계정이 여는 것
     */
    private record Target(String label, String sjDiv, List<String> accountIds, String opens) {
    }

    private static final List<Target> TARGETS = List.of(
            new Target("유형자산의 취득", "CF",
                    List.of("ifrs-full_PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities",
                            "dart_PurchaseOfPropertyPlantAndEquipment",
                            "ifrs-full_PurchaseOfPropertyPlantAndEquipment"),
                    "자본적지출의 본체 — 3.3·1.6·4.2·5.4"),
            new Target("무형자산의 취득", "CF",
                    List.of("ifrs-full_PurchaseOfIntangibleAssetsClassifiedAsInvestingActivities",
                            "dart_PurchaseOfIntangibleAssets",
                            "ifrs-full_PurchaseOfIntangibleAssetsOtherThanGoodwill"),
                    "자본적지출의 확장 정의. 본체와 합칠지는 실측 뒤에 정한다"),
            new Target("유·무형 합산 취득", "CF",
                    List.of("ifrs-full_PurchaseOfPropertyPlantAndEquipmentIntangibleAssets"
                            + "OtherThanGoodwillInvestmentPropertyAndOtherNoncurrentAssets"),
                    "둘을 한 줄로 내는 회사가 있는지 — 있으면 합산 규칙이 회사마다 달라진다"),
            new Target("무형자산(잔액)", "BS",
                    List.of("ifrs-full_IntangibleAssetsOtherThanGoodwill",
                            "ifrs-full_IntangibleAssetsAndGoodwill"),
                    "3.6 유형자본 대비 수익률 — 이번 phase는 쓰지 않는다"),
            new Target("영업권(잔액)", "BS",
                    List.of("ifrs-full_Goodwill"),
                    "3.6 — 이번 phase는 쓰지 않는다"));

    /**
     * 세지 않고 나열만 하는 것. {@code account_id}가 모자랐는지 사람이 판정하는 재료다.
     *
     * <p>MUST NOT: 이 목록에 걸린 것을 발견 수에 더하지 않는다. {@code DartMissingAccountProbeLiveTest}의 오리온
     * {@code 투자부동산감가상각비 조정}이 정확히 그렇게 3/8이라는 거짓 판정을 만들었다.
     */
    private static final List<String> INVESTING_HINTS = List.of("취득", "처분", "증가", "감소");

    @Test
    @DisplayName("[live][probe] 전체 재무제표가 자본적지출을 주는지 부호와 함께 기록한다")
    void probeCapex() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        System.out.println("\n===== 자본적지출 실측 (" + SETTLED_YEAR + " 사업보고서, CFS) =====");
        System.out.println("판정은 비대칭이다 — 0/8은 닫혔다는 결론이 되지만 8/8은 '유망'까지다.");
        System.out.println("MUST: 부호를 함께 읽는다. 음수로 오면 FCF는 OCF + |capex|가 아니라 OCF + capex다.");

        Map<String, Integer> hits = new LinkedHashMap<>();
        Map<String, Set<String>> foundNames = new LinkedHashMap<>();
        Map<String, Set<String>> signs = new LinkedHashMap<>();
        for (Target target : TARGETS) {
            hits.put(target.label(), 0);
            foundNames.put(target.label(), new LinkedHashSet<>());
            signs.put(target.label(), new LinkedHashSet<>());
        }
        int answered = 0;

        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElse(null);
            if (corpCode == null) {
                System.out.printf("%n--- %s(%s): corp_code를 찾지 못했다 — 상장사 매핑을 확인한다%n",
                        sample.getValue(), sample.getKey());
                continue;
            }

            ResponseEntity<JsonNode> response = client.get().uri(uri -> uri
                            .path("/fnlttSinglAcntAll.json")
                            .queryParam("crtfc_key", apiKey)
                            .queryParam("corp_code", corpCode)
                            .queryParam("bsns_year", SETTLED_YEAR)
                            .queryParam("reprt_code", ANNUAL_REPORT)
                            .queryParam("fs_div", "CFS")
                            .build())
                    .retrieve()
                    .toEntity(JsonNode.class);

            JsonNode body = response.getBody();
            JsonNode list = body == null ? null : body.get("list");

            System.out.printf("%n--- %s(%s) %s%n", sample.getValue(), sample.getKey(),
                    DartProbeSupport.describeStatus(body));
            if (list == null || list.isEmpty()) {
                // 013(데이터 없음)과 100(요청이 틀림)을 구분하지 않으면 탐침이 뜻을 잃는다(DartResponse.kindOf가 정본이다).
                System.out.println("    행이 없다 — 이 회사는 판정에서 뺀다.");
                continue;
            }
            answered++;

            for (Target target : TARGETS) {
                JsonNode row = findRow(list, target);
                if (row == null) {
                    System.out.printf("    %-14s 없음%n", target.label());
                    continue;
                }
                hits.merge(target.label(), 1, Integer::sum);
                String name = row.path("account_nm").asString("?");
                String amount = row.path("thstrm_amount").asString("");
                foundNames.get(target.label()).add(name);
                signs.get(target.label()).add(signOf(amount));
                System.out.printf("    %-14s → %s = %s  [부호 %s]%n",
                        target.label(), name, amount.isBlank() ? "(빈 값)" : amount, signOf(amount));
            }

            // 세지 않는다 — 후보 account_id가 모자랐는지 사람이 판정하는 재료다.
            List<String> investingRows = new ArrayList<>();
            for (JsonNode row : list) {
                if (!"CF".equals(row.path("sj_div").asString(""))) {
                    continue;
                }
                String name = row.path("account_nm").asString("");
                if (INVESTING_HINTS.stream().anyMatch(name::contains)) {
                    investingRows.add(name + "=" + row.path("thstrm_amount").asString("")
                            + " [" + row.path("account_id").asString("?") + "]");
                }
            }
            System.out.println("    CF의 취득·처분 계열(세지 않는다, 후보 부족 판정용):");
            investingRows.forEach(r -> System.out.println("        " + r));
        }

        System.out.println("\n===== 판정 =====");
        System.out.println("응답한 회사: " + answered + "/" + SAMPLES.size());
        for (Target target : TARGETS) {
            int count = hits.get(target.label());
            String verdict = count == 0 ? "닫혔다(또는 후보 부족 — 위 나열을 본다)"
                    : count == answered ? "유망 — 커버리지는 재적재로만 안다"
                    : "혼재 — 어느 업종에서 깨지는지가 소득이다";
            System.out.printf("%-14s %d/%d  %-40s (%s)%n",
                    target.label(), count, answered, verdict, target.opens());
            if (!foundNames.get(target.label()).isEmpty()) {
                System.out.println("               회사별 계정명: " + foundNames.get(target.label()));
                System.out.println("               관측된 부호: " + signs.get(target.label())
                        + "  ← 섞여 있으면 부호 규약을 회사마다 정해야 한다");
            }
        }
        System.out.println("\n호출 수: 회사당 1콜 = " + SAMPLES.size() + "콜.");
        System.out.println("MUST: 이 출력을 dart-financial.md의 「계정별 가용성」 절에 옮긴다.");
        System.out.println("      4개사 표본 표와 8개사 표를 섞으면 표본을 밝힌 문장이 거짓이 된다.");
        System.out.println("MUST: 8/8이어도 350종목 재적재 전에는 지표를 걸지 않는다(dart-financial.md의 「계정별 가용성」 절).");

        assertThat(answered)
                .as("여덟 중 하나도 응답하지 않았다. 키·엔드포인트·연도를 먼저 의심한다")
                .isPositive();
    }

    /** {@code sj_div}로 좁힌 뒤 {@code account_id} 완전일치. 계정명 폴백을 두지 않는다. */
    private static JsonNode findRow(JsonNode list, Target target) {
        for (JsonNode row : list) {
            if (!target.sjDiv().equals(row.path("sj_div").asString(""))) {
                continue;
            }
            if (target.accountIds().contains(row.path("account_id").asString(""))) {
                return row;
            }
        }
        return null;
    }

    /** 부호만 뽑는다. 금액은 {@code "-1,234"}처럼 콤마가 섞여 온다. */
    private static String signOf(String amount) {
        String cleaned = amount == null ? "" : amount.replace(",", "").trim();
        if (cleaned.isEmpty() || "-".equals(cleaned)) {
            return "빈 값";
        }
        try {
            int signum = new BigDecimal(cleaned).signum();
            return signum < 0 ? "음수" : signum > 0 ? "양수" : "0";
        } catch (NumberFormatException e) {
            // 삼키지 않는다 — 파싱 불가는 그 자체가 소득이다(계정 형식이 다르다는 뜻).
            return "파싱 불가(" + cleaned + ")";
        }
    }
}
