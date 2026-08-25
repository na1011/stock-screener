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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전체 재무제표가 <b>아직 재지 않은 계정 일곱</b>을 주는지 실측한다.
 *
 * <p>여는 것이 넷이다 — 계속영업손익(원본 §2-2 P-01), 매출총이익률·ROIC(§S-04 해자 프록시),
 * EBITDA(§H-03), 그리고 {@code financial_statement.eps} 대조.
 *
 * <p><b>IMPORTANT — 이 탐침의 판정은 비대칭이다. "닫혔다"는 결론만 낼 수 있고 "열렸다"는 못 낸다.</b>
 * 근거가 이 저장소 안에 둘 있다. ① {@code dart-financial.md}의 「아직 확인하지 못한 것 — 재무」 절이
 * <i>"계정 집합의 회사별 편차 범위 — 표본 4개사로 일반화할 수 없다"</i>를 스스로 적는다.
 * ② dart-financial.md의 「계정 집합이 회사마다 다르다」 절이 그 반례다 — 이익잉여금은 4개사 대조군에서
 * <b>전부 왔는데</b> 350종목 전수에서 3종목이
 * 비었다. V18(6개사 표본이 4자리 52종목을 놓쳤다)도 같은 병이다.
 *
 * <p>그러므로 결과는 셋으로만 읽는다.
 * <ul>
 *   <li><b>0/8</b> — 닫혔다. 그 항목은 열지 않는다
 *   <li><b>혼재</b> — 어느 업종에서 깨지는지가 소득이다. 계정명 폴백 후보군의 근거가 된다
 *   <li><b>8/8</b> — 유망하다. <b>그래도 커버리지는 미지다</b> — 350종목 재적재로 재기 전에는
 *       컬럼을 더할 수는 있어도 <b>지표를 걸지 않는다</b>
 * </ul>
 *
 * <p>MUST NOT: {@code xbrlTaxonomy.json}을 여기에 끼우지 않는다. 요청 {@code sj_div}가
 * {@code IS1}~{@code CF4} 등 <b>26코드</b>라 응답의 {@code sj_div}({@code IS}·{@code CIS}·
 * {@code CF})와 다른 것이고 손익만 덮어도 16콜이며, 무엇보다 <b>{@code corp_code}를 받지 않아
 * 회사별 값을 원리적으로 알 수 없다</b>(dart-financial.md의 「다중회사 주요계정」 절). 이 탐침의 질문에 답하지
 * 않는 엔드포인트다.
 *
 * <p>MUST NOT: 감가상각비가 왔다고 <b>조정</b> EBITDA가 열렸다고 읽지 않는다. 조정치를 막는 것은
 * 계정의 부재가 아니라 ① 발행사가 스스로 정의하는 값이라는 것 ② spec §7의 "외부가 계산해 준 값에
 * 의존하지 않는다"이며, <b>둘 다 이 탐침과 무관하게 남는다</b>(ADR-0059).
 *
 * <p>호출 수는 <b>회사당 1콜(8개사 = 8콜)</b>이다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartMissingAccountProbeLiveTest {

    /** 사업보고서. 이 탐침은 연간만 본다 — 분기를 함께 부르면 콜이 4배다. */
    private static final String ANNUAL_REPORT = "11011";

    /** 결산이 끝나 사업보고서가 확실히 있는 연도. */
    private static final String SETTLED_YEAR = "2025";

    /**
     * <b>중분류가 겹치지 않게</b> 고른 여덟. 기존 탐침(삼성전자·KB금융·카카오·NAVER)과 일부러
     * 다르다 — 그 4개사 표본이 dart-financial.md의 「계정 집합이 회사마다 다르다」 절과 V18에서 실제로 새어
     * 나갔기 때문이다.
     *
     * <p>카카오를 빼고 이마트를 넣었다. 카카오는 NAVER와 같은 {@code 63120}이라 표본 한 칸을
     * 낭비하고, 이마트는 dart-financial.md의 「전체 재무제표」 절이 이미 <b>계정명이 어긋나는 회사로 지목한
     * 종목</b>이다.
     */
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
     * 찾는 계정. {@code account_id}를 먼저 보고 계정명을 폴백으로 둔다(dart-financial.md의 「전체 재무제표」 절).
     *
     * <p>현금및현금성자산은 <b>넣지 않았다</b> — 이미 4/4로 dart-financial.md의 「계정별 가용성」 절에 있다.
     * 다시 재면 콜만 쓴다.
     */
    private record Target(String label, List<String> accountIds, List<String> nameFragments,
                          String opens) {
    }

    private static final List<Target> TARGETS = List.of(
            new Target("매출원가",
                    List.of("ifrs-full_CostOfSales"),
                    List.of("매출원가"),
                    "S-04 조건 a·b(매출총이익률)"),
            new Target("매출총이익",
                    List.of("ifrs-full_GrossProfit"),
                    List.of("매출총이익"),
                    "S-04 조건 a·b — 매출원가가 없어도 이쪽이 오면 열린다"),
            new Target("법인세비용",
                    List.of("ifrs-full_IncomeTaxExpenseContinuingOperations",
                            "dart_IncomeTaxExpenseContinuingOperations"),
                    List.of("법인세비용"),
                    "S-04 조건 c(NOPAT의 실효세율)"),
            new Target("감가상각비",
                    List.of("ifrs-full_DepreciationAndAmortisationExpense",
                            "ifrs-full_AdjustmentsForDepreciationAndAmortisationExpense"),
                    List.of("감가상각"),
                    "EBITDA·H-03 — 조정 EBITDA가 아니다"),
            new Target("기본주당이익",
                    List.of("ifrs-full_BasicEarningsLossPerShare"),
                    List.of("기본주당이익", "기본주당순이익"),
                    "financial_statement.eps 대조"),
            new Target("희석주당이익",
                    List.of("ifrs-full_DilutedEarningsLossPerShare"),
                    List.of("희석주당이익", "희석주당순이익"),
                    "P-01(계속사업 기준 희석 EPS)의 나머지 절반"),
            new Target("계속영업손익",
                    List.of("ifrs-full_ProfitLossFromContinuingOperations"),
                    List.of("계속영업"),
                    "지속사업순손실 · P-01"),
            new Target("중단영업손익",
                    List.of("ifrs-full_ProfitLossFromDiscontinuedOperations"),
                    List.of("중단영업"),
                    "계속영업의 짝 — 둘이 있어야 합이 검산된다"));

    /**
     * 총차입금은 <b>목록에 없다.</b> dart-financial.md의 「계정별 가용성」 절이 <i>"무엇을 더할지가 회사마다
     * 다르므로 총차입금은
     * 우리가 만드는 값"</i>이라고 적는다 — 8종목으로 나오는 것은 어떤 조합이 오는가까지이고
     * 합성 규칙이 350에 통하는지는 아니다. 그래서 아래는 <b>세지 않고 나열만</b> 한다.
     */
    private static final List<String> BORROWING_HINTS =
            List.of("차입금", "사채", "리스부채", "유동성장기");

    @Test
    @DisplayName("[live][probe] 전체 재무제표가 미측정 계정 여덟을 주는지 기록한다")
    void probeMissingAccounts() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        System.out.println("\n===== 미측정 계정 실측 (" + SETTLED_YEAR + " 사업보고서, CFS) =====");
        System.out.println("판정은 비대칭이다 — 0/8은 닫혔다는 결론이 되지만 8/8은 '유망'까지다.");

        Map<String, Integer> hits = new LinkedHashMap<>();
        Map<String, Set<String>> foundNames = new LinkedHashMap<>();
        for (Target target : TARGETS) {
            hits.put(target.label(), 0);
            foundNames.put(target.label(), new LinkedHashSet<>());
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
            String status = body == null ? "(본문 없음)" : body.path("status").asString("");
            JsonNode list = body == null ? null : body.get("list");

            System.out.printf("%n--- %s(%s) status=%s%n", sample.getValue(), sample.getKey(), status);
            if (list == null || list.isEmpty()) {
                // 013(데이터 없음)과 100(요청이 틀림)을 구분하지 않으면 탐침이 뜻을 잃는다(DartResponse.kindOf가 정본이다).
                System.out.println("    행이 없다 — status의 뜻은 DartResponse.kindOf가 든다. 이 회사는 판정에서 뺀다.");
                continue;
            }
            answered++;

            List<String> borrowings = new ArrayList<>();
            for (Target target : TARGETS) {
                String hit = findAccount(list, target);
                if (hit != null) {
                    hits.merge(target.label(), 1, Integer::sum);
                    foundNames.get(target.label()).add(hit);
                }
            }
            for (JsonNode row : list) {
                String name = row.path("account_nm").asString("");
                if (BORROWING_HINTS.stream().anyMatch(name::contains)) {
                    borrowings.add(name);
                }
            }

            for (Target target : TARGETS) {
                String hit = findAccount(list, target);
                System.out.printf("    %-8s %s%n", target.label(), hit == null ? "없음" : "→ " + hit);
            }
            System.out.println("    차입 계열 계정(합성 대상, 세지 않는다): " + borrowings);
        }

        System.out.println("\n===== 판정 =====");
        System.out.println("응답한 회사: " + answered + "/" + SAMPLES.size());
        for (Target target : TARGETS) {
            int count = hits.get(target.label());
            String verdict = count == 0 ? "닫혔다"
                    : count == answered ? "유망 — 커버리지는 재적재로만 안다"
                    : "혼재 — 계정명 폴백 후보군의 근거다";
            System.out.printf("%-8s %d/%d  %-30s (%s)%n",
                    target.label(), count, answered, verdict, target.opens());
            if (!foundNames.get(target.label()).isEmpty()) {
                System.out.println("         회사별 계정명: " + foundNames.get(target.label()));
            }
        }
        System.out.println("\n호출 수: 회사당 1콜 = " + SAMPLES.size() + "콜. xbrlTaxonomy는 부르지 않는다(dart-financial.md의 「다중회사 주요계정」 절).");
        System.out.println("MUST: 이 출력을 dart-financial.md의 「계정별 가용성」 절에 옮긴다. 4개사 표본 행은");
        System.out.println("      머리에 못박고 있어 다른 표본의 행을 섞으면 그 문장이 거짓이 된다.");

        // 탐침의 최소 단언 — 응답 자체가 오지 않으면 위 판정이 전부 무의미하다.
        assertThat(answered)
                .as("여덟 중 하나도 응답하지 않았다. 키·엔드포인트·연도를 먼저 의심한다")
                .isPositive();
    }

    /** {@code account_id} 완전일치 우선, 없으면 계정명 부분일치. 찾은 계정명을 돌려준다. */
    private static String findAccount(JsonNode list, Target target) {
        for (JsonNode row : list) {
            if (target.accountIds().contains(row.path("account_id").asString(""))) {
                return row.path("account_nm").asString("?")
                        + " [" + row.path("account_id").asString("?") + "]";
            }
        }
        for (JsonNode row : list) {
            String name = row.path("account_nm").asString("");
            if (target.nameFragments().stream().anyMatch(name::contains)) {
                return name + " [표준코드 미사용 또는 다른 ID]";
            }
        }
        return null;
    }
}
