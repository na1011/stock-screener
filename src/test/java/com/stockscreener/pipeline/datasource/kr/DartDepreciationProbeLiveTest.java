package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전체 재무제표가 <b>감가상각비</b>를 주는지 <b>후보 ID를 넓혀</b> 다시 잰다.
 *
 * <h2>세 번째로 재는 이유 — 앞의 둘이 시험한 표준코드는 하나뿐이었다</h2>
 *
 * <p>4개사 표본이 1/4, {@code DartMissingAccountProbeLiveTest}가 8개사에서 실질 2/8로 닫았다. 그런데 <b>그 둘이
 * 완전일치로 센
 * {@code account_id}는 {@code ifrs-full_AdjustmentsForDepreciationAndAmortisationExpense}
 * 하나</b>이고 나머지는 계정명 부분일치였다(그중 하나가 오리온 오진이라 3/8 → 2/8로 정정됐다).
 * <b>「그 하나가 안 왔다」와 「감가상각비 계정이 없다」는 다르다.</b>
 *
 * <p>IFRS 택사노미는 감가상각을 <b>여러 축</b>으로 낸다 — 현금흐름표의 조정 항목, 성격별 분류
 * 손익계산서의 비용 항목, 유형자산 주석. 축마다 {@code account_id}가 다르고 회사가 어느 축을
 * 쓰는지는 회계정책이 정한다. 이 탐침은 <b>축을 넓혀</b> 같은 여덟에게 다시 묻는다.
 *
 * <p><b>MUST NOT: 택사노미 사전({@code xbrlTaxonomy.json})으로 이 물음에 답하려 하지 않는다.</b>
 * 그것은 {@code corp_code}를 받지 않아 <b>회사별 진단에 원리적으로 쓸 수 없고</b>, api-spec §9가
 * 적어 둔 같은 기대를 {@link DartBulkAndTaxonomyProbeLiveTest}가 이미 실측으로 기각했다.
 * 후보를 넓히는 일은 사전 없이 할 수 있고, <b>회사가 그 계정을 실제로 내는가는 회사에 물어야만
 * 답이 난다.</b>
 *
 * <p><b>IMPORTANT — 판정은 비대칭이다.</b> 0/8은 「닫혔다」가 되지만 8/8은 「유망」까지다. §8이
 * 그 반례다 — 이익잉여금은 4개사에 다 왔는데 350종목에서 셋이 비었다. <b>350종목 재적재로 재기
 * 전에는 지표를 걸지 않는다</b>(dart-financial.md의 「계정별 가용성」 절의 MUST).
 *
 * <p><b>MUST NOT: 계정명 부분일치를 발견 수에 더하지 않는다.</b> {@code DartMissingAccountProbeLiveTest}가
 * 정확히 그 방식으로 3/8을
 * 냈다가 오리온의 {@code 투자부동산감가상각비 조정}을 걸러 2/8로 정정했다 — 그것은 투자부동산 한
 * 항목의 조정액이지 총 감가상각비가 아니고, <b>이 오진은 틀린 값이 훨씬 작아 눈에 덜 띈다.</b>
 * 그래서 이름에 {@code 감가상각}·{@code 상각}이 든 행은 <b>세지 않고 나열만</b> 한다. 나열의
 * 목적은 후보 {@code account_id}가 아직도 모자라는지 사람이 판정하는 것이다.
 *
 * <p>이것이 여는 것은 EBITDA이고, 그 위에 EV/EBITDA와 순부채/EBITDA(원본 H-03)가 선다.
 * <b>다만 EBITDA가 열려도 EV는 총차입금 없이 서지 않는다</b> — 그쪽은 dart-financial.md의 「계정별 가용성」
 * 절이 「우리가 만드는
 * 값」이라 적었고 ADR-0062 §버린 대안이 기각했다. <b>이 탐침의 결과를 「EV/EBITDA를 만들어도
 * 된다」로 읽지 않는다.</b>
 *
 * <p>표본은 {@code DartMissingAccountProbeLiveTest}·{@code DartCapexProbeLiveTest}와 <b>같은 여덟</b>이다. 호출
 * 수는 <b>회사당 1콜 = 8콜</b>.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartDepreciationProbeLiveTest {

    /** 사업보고서. 연간만 본다 — 분기를 함께 부르면 콜이 4배다. */
    private static final String ANNUAL_REPORT = "11011";

    /** 결산이 끝나 사업보고서가 확실히 있는 연도. */
    private static final String SETTLED_YEAR = "2025";

    /** 앞선 탐침 둘과 같은 여덟. 중분류가 겹치지 않는다. */
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
     * 찾는 계정. {@code sjDivs}가 여럿인 것은 회사가 어느 표에 싣는지가 회계정책이라서다.
     *
     * @param accountIds 완전일치로 <b>세는</b> 표준계정ID. 계정명 폴백을 두지 않는다
     */
    private record Target(String label, List<String> sjDivs, List<String> accountIds, String note) {
    }

    private static final List<Target> TARGETS = List.of(
            new Target("CF 감가상각+무형상각 조정", List.of("CF"),
                    List.of("ifrs-full_AdjustmentsForDepreciationAndAmortisationExpense",
                            "dart_AdjustmentsForDepreciationAndAmortisationExpense",
                            "ifrs_AdjustmentsForDepreciationAndAmortisationExpense"),
                    "앞선 탐침 둘이 유일하게 완전일치로 센 축. 그 결과가 1/4·1/8이었다"),
            new Target("CF 감가상각 조정(단독)", List.of("CF"),
                    List.of("ifrs-full_AdjustmentsForDepreciationExpense",
                            "dart_AdjustmentsForDepreciationExpense"),
                    "감가상각과 무형상각을 갈라 내는 회사 — 앞의 축이 못 잡는다"),
            new Target("CF 무형상각 조정(단독)", List.of("CF"),
                    List.of("ifrs-full_AdjustmentsForAmortisationExpense",
                            "dart_AdjustmentsForAmortisationExpense"),
                    "위와 짝. 둘을 더해야 총액이 된다"),
            new Target("CF 상각+손상 합산 조정", List.of("CF"),
                    List.of("ifrs-full_AdjustmentsForDepreciationAmortisationAndImpairment"
                                    + "LossReversalOfImpairmentLossRecognisedInProfitOrLoss",
                            "ifrs-full_AdjustmentsForDepreciationAndAmortisationExpense"
                                    + "AndImpairmentLossReversalOfImpairmentLossRecognisedInProfitOrLoss"),
                    "손상차손이 섞인 축. 섞여 오면 EBITDA에 손상까지 더하게 된다 — 세되 갈라 적는다"),
            new Target("IS/CIS 감가상각+무형상각 비용", List.of("IS", "CIS"),
                    List.of("ifrs-full_DepreciationAndAmortisationExpense",
                            "dart_DepreciationAndAmortisationExpense"),
                    "성격별 분류 손익계산서를 쓰는 회사"),
            new Target("IS/CIS 감가상각비(단독)", List.of("IS", "CIS"),
                    List.of("ifrs-full_DepreciationExpense", "dart_DepreciationExpense"),
                    "위와 같은 축의 분해"),
            new Target("유형자산 감가상각(주석축)", List.of("BS", "IS", "CIS", "CF"),
                    List.of("ifrs-full_DepreciationPropertyPlantAndEquipment"),
                    "주석 계정이 본표에 섞여 오는지. 오면 그것은 총액이 아니다 — 세되 쓰지 않는다"));

    /**
     * 세지 않고 나열만 하는 것. 후보 {@code account_id}가 모자랐는지 사람이 판정하는 재료다.
     *
     * <p>MUST NOT: 여기 걸린 것을 발견 수에 더하지 않는다. {@code DartMissingAccountProbeLiveTest}의 오리온
     * {@code 투자부동산감가상각비 조정}이 정확히 그렇게 3/8이라는 거짓 판정을 만들었다.
     */
    private static final List<String> NAME_HINTS = List.of("감가상각", "상각");

    @Test
    @DisplayName("[live][probe] 감가상각비를 후보 ID를 넓혀 다시 잰다 — 후보 부족이었는지 가른다")
    void probeDepreciation() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        System.out.println("\n===== 감가상각비 재측정 (" + SETTLED_YEAR + " 사업보고서, CFS) =====");
        System.out.println("4개사는 1/4, 8개사는 실질 2/8로 닫았다. 그 둘이 완전일치로 센 ID는 하나뿐이다.");
        System.out.println("이 탐침이 새로 하는 것은 축을 넓히는 것뿐이다 — 표본도 방법도 같다.");
        System.out.println("MUST: 이름 부분일치는 세지 않는다. 아래 '나열'은 후보 부족 판정용이다.");

        Map<String, Integer> hits = new LinkedHashMap<>();
        Map<String, Set<String>> foundNames = new LinkedHashMap<>();
        for (Target target : TARGETS) {
            hits.put(target.label(), 0);
            foundNames.put(target.label(), new LinkedHashSet<>());
        }
        // 어느 회사든 축 하나라도 잡혔는가 — 이것이 EBITDA 가능 여부의 실질 답이다.
        int companiesWithAnyStandardCode = 0;
        // 이름에는 걸리는데 표준코드로는 못 잡은 account_id 모음. 다음 후보가 여기서 나온다.
        Map<String, Integer> unmatchedIds = new TreeMap<>();
        int answered = 0;

        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElse(null);
            if (corpCode == null) {
                System.out.printf("%n--- %s(%s): corp_code를 찾지 못했다%n",
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
                System.out.println("    행이 없다 — 이 회사는 판정에서 뺀다.");
                continue;
            }
            answered++;

            boolean anyHit = false;
            Set<String> matchedIds = new LinkedHashSet<>();
            for (Target target : TARGETS) {
                JsonNode row = findRow(list, target);
                if (row == null) {
                    continue;
                }
                anyHit = true;
                matchedIds.add(row.path("account_id").asString(""));
                hits.merge(target.label(), 1, Integer::sum);
                String name = row.path("account_nm").asString("?");
                foundNames.get(target.label()).add(name);
                System.out.printf("    %-26s → %s = %s%n",
                        target.label(), name, row.path("thstrm_amount").asString("(빈 값)"));
            }
            if (anyHit) {
                companiesWithAnyStandardCode++;
            } else {
                System.out.println("    표준코드로 잡힌 축이 하나도 없다.");
            }

            // 세지 않는다 — 다음 후보를 사람이 고르기 위한 나열이다.
            List<String> named = new ArrayList<>();
            for (JsonNode row : list) {
                String name = row.path("account_nm").asString("");
                if (NAME_HINTS.stream().noneMatch(name::contains)) {
                    continue;
                }
                String id = row.path("account_id").asString("?");
                named.add(row.path("sj_div").asString("?") + " | " + name
                        + " = " + row.path("thstrm_amount").asString("") + " [" + id + "]");
                if (!matchedIds.contains(id) && !DartFullAccountMapper.NO_STANDARD_CODE.equals(id)) {
                    unmatchedIds.merge(id, 1, Integer::sum);
                }
            }
            System.out.println("    이름에 '감가상각'·'상각'이 든 행(세지 않는다):");
            if (named.isEmpty()) {
                System.out.println("        (없음) ← 이 회사는 그 낱말 자체가 본표에 없다");
            } else {
                named.forEach(r -> System.out.println("        " + r));
            }
        }

        System.out.println("\n===== 판정 =====");
        System.out.println("응답한 회사: " + answered + "/" + SAMPLES.size());
        for (Target target : TARGETS) {
            int count = hits.get(target.label());
            System.out.printf("%-26s %d/%d   (%s)%n",
                    target.label(), count, answered, target.note());
            if (!foundNames.get(target.label()).isEmpty()) {
                System.out.println("                           회사별 계정명: "
                        + foundNames.get(target.label()));
            }
        }
        System.out.printf("%n축을 하나라도 잡은 회사: %d/%d  ← EBITDA 가능 여부의 실질 답이다%n",
                companiesWithAnyStandardCode, answered);
        System.out.println("\n이름에는 걸리는데 후보에 없는 account_id (다음 후보의 재료):");
        if (unmatchedIds.isEmpty()) {
            System.out.println("    (없음) ← 후보 부족이 아니다. 소스가 안 주는 것이다");
        } else {
            unmatchedIds.forEach((id, n) -> System.out.printf("    %-70s %d개사%n", id, n));
        }
        System.out.println("\n호출 수: 회사당 1콜 = " + SAMPLES.size() + "콜.");
        System.out.println("MUST: 이 출력을 dart-financial.md의 「계정별 가용성」 절에 옮긴다.");
        System.out.println("      앞선 탐침들의 표에 섞지 않는다 — 그 절들이 머리에 표본을 못박고 있다.");
        System.out.println("MUST NOT: 결과를 'EV/EBITDA를 만들어도 된다'로 읽지 않는다. EV는 총차입금이 막는다.");

        assertThat(answered)
                .as("여덟 중 하나도 응답하지 않았다. 키·엔드포인트·연도를 먼저 의심한다")
                .isPositive();
    }

    /** {@code sj_div}로 좁힌 뒤 {@code account_id} 완전일치. 계정명 폴백을 두지 않는다. */
    private static JsonNode findRow(JsonNode list, Target target) {
        for (JsonNode row : list) {
            if (!target.sjDivs().contains(row.path("sj_div").asString(""))) {
                continue;
            }
            if (target.accountIds().contains(row.path("account_id").asString(""))) {
                return row;
            }
        }
        return null;
    }
}
