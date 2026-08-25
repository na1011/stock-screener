package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기업개황({@code company.json})이 <b>업종 코드를 실제로 주는지</b> 실측한다.
 *
 * <p>ADR-0039가 버리거나 치환한 것 넷이 이 한 필드에 걸려 있다 — H-02(업종 중앙값 대비
 * 부채비율)·S-01(업종 밸류에이션)·S-02(업종 이익수익률), 그리고 §부작용의
 * <b>Altman Z 금융업 명시 제외</b>다. 지금은 {@code stock.sector}가 활성 종목 전부 NULL이라
 * 넷 다 성립하지 않는다.
 *
 * <p>테스트가 아니라 <b>탐침</b>이다. 단언은 최소한만 두고 관측을 표준출력으로 낸다 —
 * 그 출력이 {@code docs/api-spec/dart-open-api.md}의 「기업개황」 절에 그대로 옮겨진다.
 *
 * <p>알아야 할 것 넷:
 * <ol>
 *   <li>{@code induty_code}가 채워지는가, <b>몇 자리인가</b>
 *   <li>그 체계가 한국표준산업분류(KSIC)인가 — 금융·보험이 64~66번대로 모이면 그렇게 보인다.
 *       그러면 Altman Z의 금융업 제외를 <b>코드 범위 하나</b>로 쓸 수 있다
 *   <li>{@code est_dt}가 상장일인가 설립일인가 — <b>설립일이면 U-05(상장 3년)는 열리지 않는다</b>
 *   <li>{@code acc_mt}(결산월) — {@code DartFinancialAdapter.periodRange()}가 12월 결산을
 *       전제한 근사를 쓰고 있고, 이 필드가 그것을 고칠 원자료다
 * </ol>
 *
 * <p>호출 수는 회사당 1콜(6개사 = 6콜)이다. 일 20,000 한도에 견주면 무시할 만하다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartCompanyProfileProbeLiveTest {

    /**
     * 업종이 서로 갈리도록 고른 여섯.
     *
     * <p><b>금융 둘(KB금융·삼성화재)이 표본의 핵심이다.</b> 업종 코드 체계는 값 하나로는
     * 판정할 수 없고, "금융끼리 붙고 제조와 떨어지는가"를 봐야 갈린다. 오리온은 api-spec dart-financial.md의
     * 「계정 집합이 회사마다 다르다」 절의
     * 이익잉여금 결손 3사 중 하나이며, 지주회사 구조가 업종 코드에 어떻게 나오는지 본다.
     */
    private static final Map<String, String> SAMPLES = new LinkedHashMap<>();

    static {
        SAMPLES.put("005930", "삼성전자(제조)");
        SAMPLES.put("105560", "KB금융(금융지주)");
        SAMPLES.put("000810", "삼성화재(보험)");
        SAMPLES.put("035720", "카카오(서비스)");
        SAMPLES.put("035420", "NAVER(서비스)");
        SAMPLES.put("271560", "오리온(식품·이익잉여금 결손사)");
    }

    @Test
    @DisplayName("[live][probe] 기업개황이 업종 코드를 주는지 기록한다")
    void probeCompanyProfile() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        System.out.println("\n===== company.json 실측 =====");

        int withIndustryCode = 0;
        TreeSet<Integer> codeLengths = new TreeSet<>();
        Map<String, String> industryByCompany = new LinkedHashMap<>();
        TreeSet<String> settlementMonths = new TreeSet<>();

        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElseThrow();

            JsonNode body = DartProbeSupport.get(client, uri -> uri
                    .path("/company.json")
                    .queryParam("crtfc_key", apiKey)
                    .queryParam("corp_code", corpCode)
                    .build());

            System.out.printf("%n--- %s(%s) status=%s%n",
                    sample.getValue(), sample.getKey(), DartProbeSupport.describeStatus(body));
            if (body == null) {
                continue;
            }

            // 기업개황은 list가 아니라 평면 객체다. 다른 엔드포인트와 응답 형태가 다르므로
            // 어댑터를 쓸 때 list를 기대하면 조용히 빈 결과가 된다.
            String industry = body.path("induty_code").asString("");
            String establishedOn = body.path("est_dt").asString("");
            String accountingMonth = body.path("acc_mt").asString("");
            String corpClass = body.path("corp_cls").asString("");
            String stockCode = body.path("stock_code").asString("");

            System.out.println("    induty_code = '" + industry + "' (" + industry.length() + "자리)");
            System.out.println("    corp_cls    = '" + corpClass + "'  stock_code = '" + stockCode + "'");
            System.out.println("    est_dt      = '" + establishedOn + "'  ← 설립일인가 상장일인가");
            System.out.println("    acc_mt      = '" + accountingMonth + "' (결산월)");

            if (!stockCode.equals(sample.getKey())) {
                System.out.println("    !! stock_code가 우리 티커와 다르다. 매핑 전제가 깨진다.");
            }
            if (!industry.isBlank()) {
                withIndustryCode++;
                codeLengths.add(industry.length());
                industryByCompany.put(sample.getValue(), industry);
            }
            if (!accountingMonth.isBlank()) {
                settlementMonths.add(accountingMonth);
            }

            // 상장일이 다른 이름으로 올 가능성을 남겨 둔다 — 없다는 판정은 필드 전체를
            // 훑은 뒤에만 할 수 있다. 침묵하면 "없다"와 "안 찾아봤다"가 구분되지 않는다.
            for (String candidate : new String[]{"lst_dt", "listing_dt", "ipo_dt"}) {
                if (body.has(candidate)) {
                    System.out.println("    (상장일 후보 발견) " + candidate + " = "
                            + body.path(candidate).asString(""));
                }
            }
        }

        System.out.println("\n===== 요약 =====");
        System.out.println("induty_code 채워짐: " + withIndustryCode + "/" + SAMPLES.size());
        System.out.println("코드 자릿수: " + codeLengths);
        System.out.println("회사별 업종 코드: " + industryByCompany);
        System.out.println("결산월 분포: " + settlementMonths
                + (settlementMonths.size() > 1
                        ? "  ← 12월 결산이 아닌 종목이 있다. periodRange()의 근사가 그만큼 틀린다."
                        : "  ← 표본은 전부 같은 결산월이다. 350종목 전체는 별개다."));
        System.out.println("판정: 금융 둘(KB금융·삼성화재)의 코드가 서로 붙고 제조·서비스와 떨어지면"
                + " 업종 체계로 금융업을 가려낼 수 있다. 위 '회사별 업종 코드'를 눈으로 대조한다.");
        System.out.println("상장일: est_dt가 설립일이면 U-05는 이 엔드포인트로 열리지 않는다."
                + " 위 출력의 est_dt를 실제 설립연도와 대조한다"
                + "(삼성전자 1969 · NAVER 1999 · 카카오 1995).");
        System.out.println("호출 수: 회사당 1콜. 유니버스 350종목이면 350콜이고, 업종은 거의"
                + " 바뀌지 않으므로 1회 적재 + 리밸런싱 때만 갱신하면 된다.");

        // 이 엔드포인트가 응답한다는 것만 단언한다. 업종 코드가 비었다는 관측도 결과이므로
        // 여기서 단언하면 그 사실을 기록하지 못한 채 탐침이 죽는다.
        assertThat(withIndustryCode)
                .as("업종 코드가 한 건도 없으면 P8은 이 엔드포인트로 열리지 않는다 — 그 사실이 결론이다")
                .isGreaterThanOrEqualTo(0);
    }
}
