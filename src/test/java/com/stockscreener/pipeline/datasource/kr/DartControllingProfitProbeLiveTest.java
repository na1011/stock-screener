package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전체 재무제표가 <b>지배기업 소유주 귀속 순이익</b>을 주는지, 그리고 <b>분기에도</b> 주는지 실측한다.
 *
 * <p>여는 것은 하나다 — 주당 지표(PER·EPS_TTM·EPS_CAGR_5Y·EPS_GROWTH_TTM_YOY·PER_MEDIAN_5Y)의
 * 분자를 K-IFRS 1033이 정의한 <b>지배기업 보통주 귀속 이익</b>으로 바꾸는 것.
 *
 * <p><b>IMPORTANT — 분기를 함께 재는 것이 이 탐침의 핵심이다.</b> 주당 지표는 전부
 * {@code quarterlyFigures()}를 읽는데, 전체 재무제표 경로는 지금까지 FY만 채웠다(실측: 국내 분기
 * 12,287행에 그 경로가 쓴 값이 <b>한 칸도 없다</b>). 연간에만 오는 계정이면 이 전환은 성립하지 않는다.
 *
 * <p><b>싼 대안은 이미 닫혔다.</b> 주요계정({@code fnlttSinglAcnt})은 이미 받고 있어 거기 있으면
 * 추가 호출이 0이었는데, 픽스처 실측에서 {@code 당기순이익(손실)} 중복 두 행의 <b>값이 같다</b>
 * (FY2025 ord 29·61 둘 다 45,206,805,000,000 · Q3 둘 다 12,225,747,000,000). 지분 구분이 아니다.
 *
 * <p>판정은 {@link DartMissingAccountProbeLiveTest}와 같은 이유로 <b>비대칭이다</b> — "닫혔다"는
 * 결론만 낼 수 있고 "열렸다"는 못 낸다. 표본 여덟은 「닫히지 않았다」까지이고 커버리지는 적재가 답한다.
 *
 * <p>호출 수는 <b>회사당 2콜(FY + 분기), 8개사 = 16콜</b>이다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartControllingProfitProbeLiveTest {

    /** 사업보고서(연간 누계)와 반기보고서. 분기가 오는지가 이 탐침의 절반이다. */
    private static final String ANNUAL_REPORT = "11011";
    private static final String HALF_REPORT = "11012";

    private static final String SETTLED_YEAR = "2025";
    /** 반기보고서가 이미 공시된 해. DB의 2026 Q2가 {@code published_at 2026-08-14}다. */
    private static final String QUARTER_YEAR = "2026";

    /**
     * 표본과 기간을 시스템 프로퍼티로 덮는다 — <b>결손 종목을 겨냥해 다시 재기 위한 것이다.</b>
     *
     * <p>전수 적재 뒤에 「FY에는 있는데 그 분기만 없는」 종목이 남았을 때, 원인이 <b>소스가
     * 안 주는 것</b>인지 <b>우리가 못 읽는 것</b>인지를 갈라야 처방이 정해진다. 표본을 코드에
     * 박아 두면 그때마다 파일을 고치게 되고, 그 수정이 커밋에 섞인다.
     *
     * <pre>./gradlew liveTest --tests "*DartControllingProfitProbe*" \
     *   -Dprobe.tickers=004800,028670 -Dprobe.quarterYear=2026 -Dprobe.quarterReport=11012</pre>
     */
    private static String prop(String key, String fallback) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * <b>비지배지분이 큰 지주회사를 일부러 넣었다.</b> 기존 탐침들의 표본(업종 분산)과 다른 이유는
     * 이 탐침의 질문이 업종이 아니라 <b>지분 구조</b>이기 때문이다 — 종속기업이 없는 회사는 애초에
     * 이 계정을 내지 않고, 그것은 결손이 아니라 정상이다.
     *
     * <p>배수는 배당표의 {@code (연결)당기순이익}(지배주주 귀속)과 우리 {@code net_income}(연결 총액)의
     * FY2025 실측 대조다. 512쌍 전수에서 나온 꼬리 쪽을 골랐다.
     *
     * <p>삼성바이오로직스와 NAVER는 <b>반대쪽 대조군</b>이다 — 계정이 없을 때 그것이
     * "못 받았다"가 아니라 "원래 없다"임을 {@code ifrs-full_ProfitLoss}와의 값 일치로 가른다.
     */
    private static final Map<String, String> SAMPLES = new LinkedHashMap<>();

    static {
        String override = prop("probe.tickers", null);
        if (override != null) {
            for (String ticker : override.split(",")) {
                SAMPLES.put(ticker.strip(), ticker.strip() + "(지정 표본)");
            }
        }
    }

    static {
        if (!SAMPLES.isEmpty()) {
            // 지정 표본이 있으면 기본 여덟을 넣지 않는다 — 섞으면 콜만 두 배가 된다.
        } else {
        SAMPLES.put("032190", "다우데이타(배수 5.48 — 전수 최대)");
        SAMPLES.put("000880", "한화(5.35 — 지주)");
        SAMPLES.put("267250", "HD현대(3.82 — 지주)");
        SAMPLES.put("000150", "두산(3.29 — 지주)");
        SAMPLES.put("005930", "삼성전자(1.0214 — 배당표 대조군이 있는 종목)");
        SAMPLES.put("105560", "KB금융(64 금융 — 계정 체계가 다르다)");
        SAMPLES.put("035420", "NAVER(63 정보서비스)");
        SAMPLES.put("207940", "삼성바이오로직스(21 의약품)");
        }
    }

    /**
     * 찾는 계정 셋. <b>{@code account_id} 완전일치로만 문다.</b>
     *
     * <p>MUST NOT: 부분일치를 쓰지 않는다 — {@code ifrs-full_ProfitLoss}가
     * {@code ifrs-full_ProfitLossAttributableToOwnersOfParent}에 걸려 <b>정반대 값</b>을 문다.
     * {@code DartFullAccountMapper} javadoc이 자본총계에서 같은 함정을 경고한다.
     */
    private record Target(String label, List<String> accountIds) {
    }

    private static final Target CONTROLLING = new Target("지배주주",
            List.of("ifrs-full_ProfitLossAttributableToOwnersOfParent",
                    "ifrs_ProfitLossAttributableToOwnersOfParent"));
    private static final Target NONCONTROLLING = new Target("비지배",
            List.of("ifrs-full_ProfitLossAttributableToNoncontrollingInterests",
                    "ifrs_ProfitLossAttributableToNoncontrollingInterests"));
    private static final Target TOTAL = new Target("연결총액",
            List.of("ifrs-full_ProfitLoss", "ifrs_ProfitLoss"));

    @Test
    @DisplayName("[live][probe] 전체 재무제표가 지배주주 귀속 순이익을 연간·분기에 주는지 기록한다")
    void probeControllingProfit() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        System.out.println("\n===== 지배주주 귀속 순이익 실측 =====");
        System.out.println("판정은 비대칭이다 — 0/8은 닫혔다는 결론이 되지만 8/8은 '유망'까지다.");
        System.out.println("분기 열이 비면 이 전환은 성립하지 않는다(주당 지표가 분기를 읽는다).");

        Map<String, Integer> annualHits = new LinkedHashMap<>();
        Map<String, Integer> quarterHits = new LinkedHashMap<>();
        Set<String> sjDivs = new LinkedHashSet<>();
        Set<String> accountNames = new LinkedHashSet<>();
        int annualAnswered = 0;
        int quarterAnswered = 0;

        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElse(null);
            System.out.printf("%n--- %s(%s)%n", sample.getValue(), sample.getKey());
            if (corpCode == null) {
                System.out.println("    corp_code를 찾지 못했다 — 상장사 매핑을 확인한다. 판정에서 뺀다.");
                continue;
            }

            for (String[] period : new String[][] {
                    {"연간", prop("probe.annualYear", SETTLED_YEAR),
                            prop("probe.annualReport", ANNUAL_REPORT)},
                    {"분기", prop("probe.quarterYear", QUARTER_YEAR),
                            prop("probe.quarterReport", HALF_REPORT)}}) {
                JsonNode body = DartProbeSupport.get(client, uri -> uri
                        .path("/fnlttSinglAcntAll.json")
                        .queryParam("crtfc_key", apiKey)
                        .queryParam("corp_code", corpCode)
                        .queryParam("bsns_year", period[1])
                        .queryParam("reprt_code", period[2])
                        .queryParam("fs_div", "CFS")
                        .build());

                JsonNode list = body == null ? null : body.get("list");
                if (list == null || list.isEmpty()) {
                    // 013(데이터 없음)과 100(요청이 틀림)을 가르지 않으면 탐침이 뜻을 잃는다.
                    System.out.printf("    [%s] 행이 없다 — status=%s. 판정에서 뺀다.%n",
                            period[0], DartProbeSupport.describeStatus(body));
                    continue;
                }
                if ("연간".equals(period[0])) {
                    annualAnswered++;
                } else {
                    quarterAnswered++;
                }

                JsonNode controlling = find(list, CONTROLLING);
                JsonNode noncontrolling = find(list, NONCONTROLLING);
                JsonNode total = find(list, TOTAL);

                Map<String, Integer> hits = "연간".equals(period[0]) ? annualHits : quarterHits;
                if (controlling != null) {
                    hits.merge(CONTROLLING.label(), 1, Integer::sum);
                    sjDivs.add(controlling.path("sj_div").asString("?"));
                    accountNames.add(controlling.path("account_nm").asString("?"));
                }
                if (noncontrolling != null) {
                    hits.merge(NONCONTROLLING.label(), 1, Integer::sum);
                }
                if (total != null) {
                    hits.merge(TOTAL.label(), 1, Integer::sum);
                }

                System.out.printf("    [%s] 지배주주=%s | 비지배=%s | 연결총액=%s%n", period[0],
                        describe(controlling), describe(noncontrolling), describe(total));
                System.out.println("         " + verdictFor(controlling, noncontrolling, total));
            }
        }

        System.out.println("\n===== 판정 =====");
        System.out.printf("응답: 연간 %d/%d · 분기 %d/%d%n",
                annualAnswered, SAMPLES.size(), quarterAnswered, SAMPLES.size());
        for (Target target : List.of(CONTROLLING, NONCONTROLLING, TOTAL)) {
            System.out.printf("%-6s 연간 %d/%d · 분기 %d/%d%n", target.label(),
                    annualHits.getOrDefault(target.label(), 0), annualAnswered,
                    quarterHits.getOrDefault(target.label(), 0), quarterAnswered);
        }
        System.out.println("지배주주 행의 sj_div: " + sjDivs + "  (IS가 아니라 CIS일 수 있다)");
        System.out.println("지배주주 행의 계정명: " + accountNames);
        System.out.println("\n호출 수: 회사당 2콜 = " + (SAMPLES.size() * 2) + "콜.");
        System.out.println("MUST: 이 출력을 dart-financial.md의 「계정별 가용성」 절에 옮긴다.");
        System.out.println("MUST: 분기 열이 0이면 여기서 멈추고 사용자에게 다시 묻는다 —");
        System.out.println("      주당 지표는 분기를 읽으므로 연간만으로는 전환이 성립하지 않는다.");

        assertThat(annualAnswered)
                .as("여덟 중 하나도 연간에 응답하지 않았다. 키·엔드포인트·연도를 먼저 의심한다")
                .isPositive();
    }

    /**
     * 세 계정의 관계를 읽는다. <b>「계정이 없다」와 「종속기업이 없다」를 가르는 것이 목적이다.</b>
     *
     * <p>지배주주 행이 없어도 {@code 비지배 == null}이고 연결총액만 있으면 그 회사는 종속기업이
     * 없거나 비지배지분이 0인 것이고, 그때 <b>연결총액이 곧 지배주주 귀속</b>이라 폴백이 옳다.
     */
    private static String verdictFor(JsonNode controlling, JsonNode noncontrolling, JsonNode total) {
        if (controlling == null && noncontrolling == null) {
            return "→ 지분 구분 행이 없다. 비지배지분이 없는 회사이면 정상이고 연결총액이 곧 지배주주다.";
        }
        if (controlling == null) {
            return "→ 비지배만 있고 지배주주가 없다. 계정 후보군을 넓혀야 하는 신호다.";
        }
        BigDecimal c = amount(controlling);
        BigDecimal n = amount(noncontrolling);
        BigDecimal t = amount(total);
        if (c == null || n == null || t == null) {
            return "→ 금액이 비어 합을 검산할 수 없다.";
        }
        BigDecimal diff = t.subtract(c.add(n));
        return diff.signum() == 0
                ? "→ 검산 통과: 연결총액 = 지배주주 + 비지배."
                : "→ 검산 불일치 " + diff + " — 같은 sj_div의 행을 물었는지 확인한다.";
    }

    private static String describe(JsonNode row) {
        if (row == null) {
            return "없음";
        }
        return row.path("account_nm").asString("?")
                + "=" + row.path("thstrm_amount").asString("?")
                + " (" + row.path("sj_div").asString("?") + ")";
    }

    /** 콤마 포함 문자열이다. {@code DartAccountMapper.parseAmount}와 같은 형태로 판다. */
    private static BigDecimal amount(JsonNode row) {
        if (row == null) {
            return null;
        }
        String raw = row.path("thstrm_amount").asString("").strip().replace(",", "");
        if (raw.isEmpty() || "-".equals(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code account_id} <b>완전일치</b>. 부분일치는 정반대 계정을 문다. */
    private static JsonNode find(JsonNode list, Target target) {
        for (JsonNode row : list) {
            if (target.accountIds().contains(row.path("account_id").asString(""))) {
                return row;
            }
        }
        return null;
    }
}
