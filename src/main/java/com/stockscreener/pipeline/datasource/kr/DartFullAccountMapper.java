package com.stockscreener.pipeline.datasource.kr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 전체 재무제표({@code fnlttSinglAcntAll}) 응답을 우리 필드로 옮긴다.
 *
 * <p>{@link DartAccountMapper}와 나뉜 이유가 셋이다.
 * <ol>
 *   <li><b>{@code account_id}가 있다.</b> 실측에서 영업활동현금흐름이 회사마다
 *       {@code 영업활동현금흐름}·{@code 영업활동으로 인한 현금흐름}·
 *       {@code 영업활동으로부터의 현금흐름} 셋으로 왔는데 {@code account_id}는 하나였다
 *       (§7). 계정명 후보군을 늘리는 방식은 업종이 늘수록 반드시 새어 나간다.
 *   <li><b>{@code sj_div}가 다섯이다</b>({@code BS}·{@code IS}·{@code CIS}·{@code CF}·
 *       {@code SCE}). 요약본은 {@code BS}/{@code IS} 둘로 정규화해 줬지만 여기서는
 *       <b>표본 4사 중 3사에 {@code IS}가 아예 없다</b> — 손익이 {@code CIS}에만 있다.
 *   <li>요약본에는 <b>현금흐름표가 없다</b>. 그래서 이 매퍼에만 OCF 항목이 있다.
 * </ol>
 *
 * <p>IMPORTANT — 표준코드를 쓰지 않는 행은 {@code null}이 아니라
 * {@code "-표준계정코드 미사용-"}이라는 <b>문자열</b>로 온다(전체의 2~9%). 아래는 후보 ID와
 * <b>완전일치</b>로만 맞추므로 그 파수꾼이 우연히 걸리는 일이 없다.
 *
 * <p>MUST NOT: {@code account_id}를 부분일치(contains)로 맞추지 않는다. {@code ifrs-full_Equity}가
 * {@code ifrs-full_EquityAttributableToOwnersOfParent}에 걸려 <b>지배주주지분을 자본총계로</b>
 * 읽는다 — 값이 그럴듯해서 드러나지 않는다.
 */
final class DartFullAccountMapper {

    private static final Logger log = LoggerFactory.getLogger(DartFullAccountMapper.class);

    static final String SJ_BALANCE = "BS";
    static final String SJ_INCOME = "IS";
    static final String SJ_COMPREHENSIVE_INCOME = "CIS";
    static final String SJ_CASH_FLOW = "CF";
    static final String SJ_EQUITY_CHANGES = "SCE";

    /** 표준계정코드를 쓰지 않는 행이 들고 오는 값. null이 아니라 이 문자열이다. */
    static final String NO_STANDARD_CODE = "-표준계정코드 미사용-";

    // ── 계정 ID 후보군 ────────────────────────────────────────────────────────
    // 접두어 둘을 함께 두는 이유: DART XBRL이 예전 택사노미에서 `ifrs_`를 썼고 지금은
    // `ifrs-full_`이다. 우리가 읽는 FY2021 이후는 후자일 것으로 보이나, 틀렸을 때의 증상이
    // "그 종목만 값이 조용히 빈다"라 확인 비용보다 후보 하나의 비용이 싸다.

    static final Set<String> ID_REVENUE = ordered("ifrs-full_Revenue", "ifrs_Revenue");
    static final Set<String> ID_OPERATING_INCOME = ordered(
            "dart_OperatingIncomeLoss",
            "ifrs-full_ProfitLossFromOperatingActivities",
            "ifrs_ProfitLossFromOperatingActivities");
    static final Set<String> ID_NET_INCOME = ordered("ifrs-full_ProfitLoss", "ifrs_ProfitLoss");

    static final Set<String> ID_TOTAL_ASSETS = ordered("ifrs-full_Assets", "ifrs_Assets");
    static final Set<String> ID_TOTAL_LIABILITIES = ordered("ifrs-full_Liabilities", "ifrs_Liabilities");
    static final Set<String> ID_TOTAL_EQUITY = ordered("ifrs-full_Equity", "ifrs_Equity");
    static final Set<String> ID_CURRENT_ASSETS = ordered("ifrs-full_CurrentAssets", "ifrs_CurrentAssets");
    static final Set<String> ID_CURRENT_LIABILITIES =
            ordered("ifrs-full_CurrentLiabilities", "ifrs_CurrentLiabilities");
    static final Set<String> ID_RETAINED_EARNINGS =
            ordered("ifrs-full_RetainedEarnings", "ifrs_RetainedEarnings");

    /** 표본 4/4에서 확인된 유일한 항목이다(§7). 이 전환으로 실제로 열리는 것이 이것 하나다. */
    static final Set<String> ID_OPERATING_CASH_FLOW = ordered(
            "ifrs-full_CashFlowsFromUsedInOperatingActivities",
            "ifrs_CashFlowsFromUsedInOperatingActivities");

    /**
     * 영업활동현금흐름을 계정명으로 찾는다. 표준코드가 없을 때만 쓴다.
     *
     * <p>후보군을 열거하지 않고 패턴으로 거르는 이유는 실측이 그렇게 시켰기 때문이다 —
     * 350종목을 돌려 보니 총계 행이 <b>표준코드 미사용</b>으로 오는 회사가 있고
     * (삼성전기 {@code 영업활동순현금흐름 합계}, 현대글로비스 {@code 영업활동으로 인한 순현금흐름}),
     * 표준코드를 쓰되 <b>다른 ID</b>를 쓰는 회사도 있다
     * (코오롱인더 {@code ifrs-full_CashFlowsFromUsedInOperations} / {@code I. 영업활동으로인한현금흐름}).
     * 이름을 하나씩 더하는 방식은 업종이 늘 때마다 새어 나간다.
     *
     * <p>IMPORTANT — <b>{@code 창출}을 반드시 뺀다.</b> {@code 영업활동에서 창출된 현금}은
     * 이자·법인세를 빼기 <b>전</b> 소계이고 총계보다 20% 안팎 크다. 삼성전기 2022가 실제로
     * 그 둘을 나란히 갖고 있었다(창출 1,882,897,371,147 / 총계 1,575,355,826,731).
     * 이것을 안 빼면 값이 그럴듯하게 틀리고, H-05는 5년 합이라 오차가 누적된다.
     */
    static boolean looksLikeOperatingCashFlowTotal(String accountName) {
        if (accountName == null) {
            return false;
        }
        // 번호 매김("I.", "Ⅰ.", "1.")과 공백을 걷어낸다. 회사마다 다르고 뜻은 같다.
        String normalized = accountName.replaceAll("[\\s.\\u2160-\\u217F0-9IVXivx]+", "");
        // '현금흐름'만 보면 새어 나간다 — 이마트가 '영업활동으로부터의 순현금유입'이었다.
        boolean namesTheFlow = normalized.contains("현금흐름") || normalized.contains("순현금");
        return normalized.contains("영업활동")
                && namesTheFlow
                && !normalized.contains("창출")
                && !normalized.contains("투자활동")
                && !normalized.contains("재무활동")
                && !normalized.contains("기초")
                && !normalized.contains("기말");
    }

    private DartFullAccountMapper() {
    }

    private static Set<String> ordered(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    /** {@code sj_div}가 주어진 값들 중 하나인 행을, <b>준 순서대로</b> 모은다. */
    static List<JsonNode> sections(JsonNode list, String... sjDivs) {
        List<JsonNode> collected = new ArrayList<>();
        for (String sjDiv : sjDivs) {
            for (JsonNode row : list) {
                if (sjDiv.equals(row.path("sj_div").asString(""))) {
                    collected.add(row);
                }
            }
        }
        return collected;
    }

    /**
     * {@code account_id} 완전일치를 먼저 보고, 없으면 계정명으로 폴백한다.
     *
     * <p>폴백을 남겨 두는 이유는 표준코드 미사용 행이 2~9% 있기 때문이다. 다만 <b>순서를
     * 뒤집으면 안 된다</b> — 계정명이 먼저면 회사마다 다른 이름 때문에 표준코드가 있는 행도
     * 놓친다.
     *
     * @param nameCandidates null이면 계정명 폴백을 하지 않는다
     */
    static BigDecimal find(List<JsonNode> rows, Set<String> idCandidates, Set<String> nameCandidates) {
        BigDecimal byId = findBy(rows, idCandidates, "account_id");
        if (byId != null) {
            return byId;
        }
        if (nameCandidates == null) {
            return null;
        }
        return findBy(rows, nameCandidates, "account_nm");
    }

    private static BigDecimal findBy(List<JsonNode> rows, Set<String> candidates, String field) {
        for (String candidate : candidates) {
            List<JsonNode> matches = new ArrayList<>();
            for (JsonNode row : rows) {
                if (candidate.equals(row.path(field).asString(""))) {
                    matches.add(row);
                }
            }
            if (matches.isEmpty()) {
                continue;
            }
            if (matches.size() > 2) {
                log.warn("{} '{}'이 {}번 중복된다 — 첫 행을 쓴다. 매핑 규칙 재확인이 필요하다.",
                        field, candidate, matches.size());
            }
            // 당기 금액만 쓴다. 분/반기 (포괄)손익계산서의 thstrm_amount는 [3개월] 금액이고
            // 그것이 우리가 Q1~Q3에 저장하는 값이다. 누적 컬럼은 쓰지 않는다.
            BigDecimal value = DartAccountMapper.parseAmount(
                    matches.get(0).path("thstrm_amount").asString(""));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 영업활동현금흐름. 표준계정 ID를 먼저 보고, 없으면 계정명 패턴으로 찾는다.
     *
     * @return 찾지 못하면 null. 그때는 결손이 결손으로 남는 것이 옳다
     */
    static BigDecimal operatingCashFlow(List<JsonNode> cashFlowRows) {
        BigDecimal byId = find(cashFlowRows, ID_OPERATING_CASH_FLOW, null);
        if (byId != null) {
            return byId;
        }
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode row : cashFlowRows) {
            if (looksLikeOperatingCashFlowTotal(row.path("account_nm").asString(""))) {
                matches.add(row);
            }
        }
        for (JsonNode match : matches) {
            BigDecimal value = DartAccountMapper.parseAmount(
                    match.path("thstrm_amount").asString(""));
            if (value != null) {
                if (matches.size() > 2) {
                    log.warn("영업활동현금흐름 후보가 {}건이다 — '{}'를 쓴다. 매핑 규칙 재확인이 필요하다.",
                            matches.size(), match.path("account_nm").asString(""));
                }
                return value;
            }
        }
        return null;
    }

    /**
     * 재무상태표에 이익잉여금이 없을 때 <b>자본변동표의 기말 잔액</b>으로 대신한다.
     *
     * <p>§8의 세 종목(오리온·오리온홀딩스·에스티큐브)은 요약본에도 전체 재무제표의
     * 재무상태표에도 <b>행 자체가 없다.</b> 이름이 다른 것이 아니므로 계정명 후보군을 늘려도
     * 열리지 않는다. 남는 자리가 자본변동표다.
     *
     * <p>규칙은 <b>대조군으로 확인했다.</b> 자본변동표에서 {@code account_id}가
     * {@code ifrs-full_Equity}이고 {@code account_detail}이 이익잉여금 구성요소를 가리키는 행이
     * 그 계정의 <b>기말 잔액</b>이다. 근거 둘:
     * <ul>
     *   <li><b>둘 다 가진 회사에서 두 값이 같다.</b> 삼성전자 2025 사업보고서의 재무상태표
     *       {@code ifrs-full_RetainedEarnings}와 이 규칙이 고른 행이 402,135,600,000,000으로
     *       일치했고, 매칭된 자본변동표 행은 하나였다.
     *   <li><b>해가 이어진다.</b> 오리온 2024가 이 규칙으로 낸 값이 2025의 기초자본
     *       ({@code dart_EquityAtBeginningOfPeriod})과 같았다.
     * </ul>
     *
     * <p>MUST NOT: 기초 잔액이나 증감 행을 고르지 않는다. 자본변동표는 한 계정이 기초·증감·기말로
     * 흩어져 있어 {@code account_id}를 안 보면 <b>기초 잔액을 이익잉여금으로 저장</b>하게 되는데,
     * 그 값은 한 해 늦은 것일 뿐 그럴듯해서 드러나지 않는다.
     *
     * @return 찾지 못하면 null. 그때는 결손이 결손으로 남는 것이 옳다
     */
    static BigDecimal retainedEarningsFromEquityChanges(JsonNode list, String ticker,
                                                        int year, String period) {
        List<JsonNode> candidates = new ArrayList<>();
        for (JsonNode row : sections(list, SJ_EQUITY_CHANGES)) {
            if (!"ifrs-full_Equity".equals(row.path("account_id").asString(""))) {
                continue;
            }
            if (row.path("account_detail").asString("").contains("이익잉여금")) {
                candidates.add(row);
            }
        }
        if (candidates.isEmpty()) {
            log.warn("이익잉여금이 재무상태표에도 자본변동표에도 없다. 결손으로 남긴다. "
                    + "ticker={} {}/{}", ticker, year, period);
            return null;
        }
        // 둘 이상이면 지배기업 소유주 귀속분을 고른다. 재무상태표의 이익잉여금이 가리키는
        // 것이 그것이고(비지배지분은 별도 항목이다), 표본에서는 애초에 하나만 걸렸다.
        JsonNode chosen = candidates.stream()
                .filter(row -> row.path("account_detail").asString("").contains("지배기업"))
                .findFirst()
                .orElse(candidates.get(0));
        if (candidates.size() > 1) {
            log.warn("자본변동표의 이익잉여금 후보가 {}건이다 — '{}'를 쓴다. 매핑 규칙 재확인이 "
                            + "필요하다. ticker={} {}/{}", candidates.size(),
                    chosen.path("account_detail").asString(""), ticker, year, period);
        }
        return DartAccountMapper.parseAmount(chosen.path("thstrm_amount").asString(""));
    }
}
