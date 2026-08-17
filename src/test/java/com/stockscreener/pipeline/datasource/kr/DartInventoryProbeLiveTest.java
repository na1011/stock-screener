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
 * 전체 재무제표가 <b>재고자산</b>을 주는지 실측한다. §13이 묶어 세었던 무형자산·영업권을 함께 잰다.
 *
 * <h2>왜 지금 재는가 — 이 계정은 한 번도 재본 적이 없다</h2>
 *
 * <p>탐침 표 §7(4개사)·§12(8개사)·§13(8개사) <b>어디에도 재고자산이 없다.</b> 그런데
 * {@code docs/phase-criteria.md:449}가 <i>"재고 대 매출 증가율은 {@code IndicatorRef}로 쓸 수
 * 있지만 재고자산이 없어 값이 안 난다"</i>라고 적어 <b>원자료의 부재로 읽힌다.</b> 같은 파일
 * 448행이 바로 그 구분을 요구한다 — <i>"도구의 한계인지 원자료의 부재인지 갈렸다. 둘은 다르다
 * — 앞은 우리에게 열리고 뒤는 안 열린다."</i>
 *
 * <p>ADR-0067이 자본적지출에서 같은 자리를 뒤집으며 MUST를 남겼다 — <i>"결손을 「소스가 주지
 * 않는다」로 적기 전에 재보지 않은 것이 아닌지 확인한다."</i> <b>재고자산이 그 MUST에 걸리는
 * 남아 있는 사례다.</b>
 *
 * <p><b>IMPORTANT — 판정은 비대칭이다. "닫혔다"는 결론만 낼 수 있고 "열렸다"는 못 낸다</b>
 * (§12·§13과 같다). §8이 반례다 — 이익잉여금은 4개사에서 전부 왔는데 350종목에서 3종목이 비었다.
 * <b>8/8이 나와도 350종목 재적재로 재기 전에는 커버리지를 한 줄도 주장하지 않는다.</b>
 *
 * <h2>MUST — 분모는 8이 아니다</h2>
 *
 * <p><b>금융업(KB금융)은 재고자산이 원리적으로 없다.</b> K-IFRS 금융업 재무상태표에 그 계정
 * 자체가 없다. 서비스업(NAVER)도 희박하다(원리적 부재인지 회사 사정인지는 <b>미확인</b>이며
 * 결과로 확정한다). 선례가 코드에 있다 — {@link DartFullAccountMapper} {@code ID_COST_OF_SALES}
 * javadoc이 <i>"없는 것이 결손이 아닌 유일한 계정이다 — 금융업·서비스업은 개념 자체가 없어
 * 원래 오지 않는다"</i>라고 적고, §12 표의 매출원가 행도 <i>"6/8 … 결손이 아니라 업종이다"</i>로
 * 같은 형식을 쓴다.
 *
 * <p>MUST NOT: {@code 6/8}처럼 <b>분모만</b> 적지 않는다. 적으려면 같은 칸에 빠진 회사 이름과
 * 사유를 붙인다.
 *
 * <h2>이 탐침이 capex 탐침과 다르게 하는 것 셋</h2>
 *
 * <p><b>① {@code sj_div}를 {@code BS}로 좁힌다.</b> 재고자산은 <b>저량(잔액)</b>인데 같은 이름이
 * 두 곳에 더 있다 — {@code CF}의 {@code 재고자산의 감소(증가)}는 <b>증감분</b>이라 잔액으로
 * 저장하면 「재고 증가율」이 <i>증감의 증감</i>이 되고, {@code IS}/{@code CIS}에
 * {@code 재고자산평가손실}과 {@code 제품 및 재공품의 변동}이 있다. §7이 현금및현금성자산에서
 * 같은 함정을 적었다 — KB금융은 CF의 {@code 기초 현금 및 현금성자산}이 먼저 잡혔다.
 *
 * <p><b>② 부호의 뜻이 capex와 반대다.</b> capex는 유출의 표기 관례라 음수가 정상일 수 있었지만
 * (§13에서 350종목 중 22행), <b>재고자산은 저량이라 음수가 원리적으로 없다.</b> 나오면 그것
 * 자체가 새 사실이므로 부호를 함께 찍는다.
 *
 * <p><b>③ CFS가 비면 OFS로 다시 부른다.</b> capex 탐침은 {@code CFS} 한 번만 부르고 빈 응답이면
 * 건너뛰었다 — <b>「연결재무제표가 없는 회사」와 「그 계정이 없는 회사」가 구별되지 않는다.</b>
 * 운영 어댑터 {@link DartFullStatementAdapter}는 그 폴백을 갖고 있고, 여기서 빠뜨리면
 * 커버리지 분모가 오염된다.
 *
 * <h2>무형자산을 함께 재는 이유 — §13이 스스로 남긴 MUST의 이행</h2>
 *
 * <p>§13이 표준코드 <b>둘을 한 칸에 묶어</b> 세어 무형자산 8/8이라는 <b>과대 판정</b>을 냈고,
 * <i>"MUST: 3.6(유형자본 대비 수익률)을 열려면 두 표준코드를 갈라 다시 잰다"</i>가 미이행으로
 * 남아 있다. {@code IntangibleAssetsOtherThanGoodwill}은 영업권을 <b>빼고</b>
 * {@code IntangibleAssetsAndGoodwill}은 <b>포함한다.</b> 셋 다 재고자산과 같은 {@code BS}
 * 구간·같은 응답이라 <b>추가 API 콜이 0이다.</b>
 *
 * <p>MUST NOT: 뜻이 다른 표준코드를 한 칸에 묶어 세지 않는다. §12의 오진은 계정명 부분일치였고
 * §13의 오진은 표준코드 묶음이었다 — <b>표준코드를 쓴다고 오진이 사라지지 않는다.</b>
 *
 * <p><b>정본은 {@code dart-open-api.md} §14 하나다.</b> 이 javadoc과 실행 출력은 그리로 가는
 * 포인터이며, 같은 문장을 여러 곳에 두면 {@code check-doc-staleness.sh}의 거울 표에 등록해야
 * 한다(등록하지 않은 사본은 갈려도 아무도 모른다).
 *
 * <p>호출 수는 <b>회사당 1~2콜(8개사 = 8~16콜)</b>이다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartInventoryProbeLiveTest {

    /** 사업보고서. 연간만 본다 — 분기를 함께 부르면 콜이 4배다. */
    private static final String ANNUAL_REPORT = "11011";

    /** 결산이 끝나 사업보고서가 확실히 있는 연도. */
    private static final String SETTLED_YEAR = "2025";

    /** §12·§13과 같은 여덟. 중분류가 겹치지 않는다. */
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
     * @param sjDiv      이 계정이 있어야 하는 재무제표 구분. 다른 구분의 동명 계정을 잡지 않는다
     * @param accountIds 완전일치로 <b>세는</b> 표준계정ID. 계정명 폴백을 두지 않는다
     * @param opens      이 계정이 여는 것
     */
    private record Target(String label, String sjDiv, List<String> accountIds, String opens) {
    }

    /**
     * 표준계정ID 후보. <b>T2·T3은 요소 존재 자체가 미확인이라 0/8이 정상일 수 있다.</b>
     *
     * <p>구성 계정(상품·제품·재공품·원재료)은 <b>후보에 넣지 않는다</b> — 총계 없이 구성만 내는
     * 회사가 "열렸다"로 잡히면 합산 규칙이 회사마다 갈린다. 대신 아래 나열이 그것을 보여 준다.
     */
    private static final List<Target> TARGETS = List.of(
            new Target("재고자산(총계)", "BS",
                    List.of("ifrs-full_Inventories",
                            "ifrs_Inventories",
                            "dart_Inventories"),
                    "린치 3.14 재고 증가율 대 매출 증가율 — 이 탐침의 목적"),
            new Target("유동 재고자산", "BS",
                    List.of("ifrs-full_CurrentInventories"),
                    "총계와 뜻이 다르므로 별도 칸. 요소 존재 미확인"),
            new Target("비유동 재고자산", "BS",
                    List.of("ifrs-full_NoncurrentInventories"),
                    "총계와 뜻이 다르므로 별도 칸. 요소 존재 미확인"),
            new Target("무형자산(영업권 제외)", "BS",
                    List.of("ifrs-full_IntangibleAssetsOtherThanGoodwill"),
                    "3.6 유형자본 — §13이 아래와 묶어 세어 과대 판정했다"),
            new Target("무형자산 및 영업권(포함)", "BS",
                    List.of("ifrs-full_IntangibleAssetsAndGoodwill"),
                    "3.6 — 위와 뜻이 다르다. 영업권을 포함한다"),
            new Target("영업권", "BS",
                    List.of("ifrs-full_Goodwill"),
                    "3.6 — 1/8이어도 '영업권이 없다'가 아니다(무형자산에 합쳐 내는 회사가 있다)"));

    /**
     * 세지 않고 나열만 하는 <b>이름 축</b>. {@code sj_div}를 좁히지 않고 다섯 구분 전부를 훑는다.
     *
     * <p>MUST NOT: 이 목록에 걸린 것을 발견 수에 더하지 않는다. §12의 오리온
     * {@code 투자부동산감가상각비 조정}이 정확히 그렇게 3/8이라는 거짓 판정을 만들었다.
     * {@code 상품}은 {@code 상품권}을, {@code 제품}은 {@code 제품보증충당부채}를 문다.
     */
    private static final List<String> NAME_HINTS =
            List.of("재고", "상품", "제품", "원재료", "재공품", "저장품", "미착");

    /**
     * 세지 않고 나열만 하는 <b>코드 축</b>. 한글명이 이름 축에 안 걸리는 표준코드를 잡는다.
     *
     * <p>축이 둘인 이유는 한쪽만 보면 한국어 이름이나 표준코드 중 하나에 눈이 멀기 때문이다.
     */
    private static final List<String> ID_HINTS = List.of("Inventor", "Intangible", "Goodwill");

    @Test
    @DisplayName("[live][probe] 전체 재무제표가 재고자산을 주는지 재고, 무형자산을 갈라 다시 잰다")
    void probeInventories() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        System.out.println("\n===== 재고자산·무형자산 실측 (" + SETTLED_YEAR + " 사업보고서) =====");
        System.out.println("판정은 비대칭이다 — 0/8은 닫혔다는 결론이 되지만 8/8은 '유망'까지다.");
        System.out.println("MUST: 분모는 8이 아니다. 금융업은 재고자산이 원리적으로 없다.");
        System.out.println("MUST: 아래 「판정」보다 그 위의 나열을 먼저 읽는다 —");
        System.out.println("      나열이 비었으면 업종 부재이고, 나열은 있는데 안 걸렸으면 후보 부족이다.");

        Map<String, Integer> hits = new LinkedHashMap<>();
        Map<String, Set<String>> foundNames = new LinkedHashMap<>();
        Map<String, Set<String>> signs = new LinkedHashMap<>();
        for (Target target : TARGETS) {
            hits.put(target.label(), 0);
            foundNames.put(target.label(), new LinkedHashSet<>());
            signs.put(target.label(), new LinkedHashSet<>());
        }
        int answered = 0;
        int ofsFallbacks = 0;

        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElse(null);
            if (corpCode == null) {
                System.out.printf("%n--- %s(%s): corp_code를 찾지 못했다 — 상장사 매핑을 확인한다%n",
                        sample.getValue(), sample.getKey());
                continue;
            }

            String usedDiv = "CFS";
            JsonNode body = call(client, apiKey, corpCode, "CFS");
            JsonNode list = body == null ? null : body.get("list");
            if (list == null || list.isEmpty()) {
                // 연결재무제표가 없는 회사가 있다. 폴백이 없으면 그것이 '계정 없음'으로 섞인다.
                JsonNode separate = call(client, apiKey, corpCode, "OFS");
                JsonNode separateList = separate == null ? null : separate.get("list");
                if (separateList != null && !separateList.isEmpty()) {
                    body = separate;
                    list = separateList;
                    usedDiv = "OFS";
                    ofsFallbacks++;
                }
            }

            System.out.printf("%n--- %s(%s) [%s] %s%n", sample.getValue(), sample.getKey(),
                    usedDiv, DartProbeSupport.describeStatus(body));
            if (list == null || list.isEmpty()) {
                // 013(데이터 없음)과 100(요청이 틀림)을 구분하지 않으면 탐침이 뜻을 잃는다(§4).
                System.out.println("    CFS·OFS 둘 다 행이 없다 — 회사 사정이므로 분모에서 뺀다.");
                continue;
            }
            answered++;

            for (Target target : TARGETS) {
                JsonNode row = findRow(list, target);
                if (row == null) {
                    System.out.printf("    %-22s 없음%n", target.label());
                    continue;
                }
                hits.merge(target.label(), 1, Integer::sum);
                String name = row.path("account_nm").asString("?");
                String amount = row.path("thstrm_amount").asString("");
                foundNames.get(target.label()).add(name);
                signs.get(target.label()).add(signOf(amount));
                System.out.printf("    %-22s → %s = %s  [부호 %s]%n", target.label(), name,
                        amount.isBlank() ? "(빈 값)" : amount, signOf(amount));
            }

            System.out.println("    나열 ① 이름 축(세지 않는다 — 전 sj_div):");
            listRows(list, true).forEach(r -> System.out.println("        " + r));
            System.out.println("    나열 ② 코드 축(세지 않는다 — BS의 표준코드):");
            listRows(list, false).forEach(r -> System.out.println("        " + r));
        }

        System.out.println("\n===== 판정 =====");
        System.out.println("응답한 회사: " + answered + "/" + SAMPLES.size()
                + " (OFS 폴백 " + ofsFallbacks + "건)");
        for (Target target : TARGETS) {
            int count = hits.get(target.label());
            String verdict = count == 0 ? "닫혔다(또는 후보 부족 — 위 나열을 본다)"
                    : count == answered ? "유망 — 커버리지는 재적재로만 안다"
                    : "혼재 — 어느 업종에서 깨지는지가 소득이다";
            System.out.printf("%-22s %d/%d  %-40s (%s)%n",
                    target.label(), count, answered, verdict, target.opens());
            if (!foundNames.get(target.label()).isEmpty()) {
                System.out.println("                       회사별 계정명: " + foundNames.get(target.label()));
                System.out.println("                       관측된 부호: " + signs.get(target.label())
                        + "  ← 재고자산은 저량이라 음수가 원리적으로 없다");
            }
        }
        System.out.println("\n호출 수: 회사당 1~2콜 = " + (SAMPLES.size() + ofsFallbacks) + "콜.");
        System.out.println("MUST: 이 출력을 dart-open-api.md §14로 옮긴다. §7 표에는 섞지 않는다");
        System.out.println("      (그 절이 머리에 4개사 표본을 못박고 있어 섞으면 거짓이 된다).");
        System.out.println("MUST: 8/8이어도 350종목 재적재 전에는 커버리지를 주장하지 않는다.");

        assertThat(answered)
                .as("여덟 중 하나도 응답하지 않았다. 키·엔드포인트·연도를 먼저 의심한다")
                .isPositive();
    }

    private static JsonNode call(RestClient client, String apiKey, String corpCode, String fsDiv) {
        ResponseEntity<JsonNode> response = client.get().uri(uri -> uri
                        .path("/fnlttSinglAcntAll.json")
                        .queryParam("crtfc_key", apiKey)
                        .queryParam("corp_code", corpCode)
                        .queryParam("bsns_year", SETTLED_YEAR)
                        .queryParam("reprt_code", ANNUAL_REPORT)
                        .queryParam("fs_div", fsDiv)
                        .build())
                .retrieve()
                .toEntity(JsonNode.class);
        return response.getBody();
    }

    /**
     * 세지 않는 나열. {@code account_id}가 모자랐는지 사람이 판정하는 재료다.
     *
     * @param byName true면 이름 축(전 {@code sj_div}), false면 코드 축({@code BS}만)
     */
    private static List<String> listRows(JsonNode list, boolean byName) {
        List<String> collected = new ArrayList<>();
        for (JsonNode row : list) {
            String sjDiv = row.path("sj_div").asString("");
            String name = row.path("account_nm").asString("");
            String id = row.path("account_id").asString("?");
            boolean matched = byName
                    ? NAME_HINTS.stream().anyMatch(name::contains)
                    : DartFullAccountMapper.SJ_BALANCE.equals(sjDiv)
                            && ID_HINTS.stream().anyMatch(id::contains);
            if (matched) {
                collected.add("[" + sjDiv + "] " + name + "=" + row.path("thstrm_amount").asString("")
                        + " [" + id + "]");
            }
        }
        return collected;
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
