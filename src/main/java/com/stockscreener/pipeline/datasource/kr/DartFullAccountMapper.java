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

    /** 현금흐름표의 계정명 폴백. 요약본에는 이 항목이 없어 {@link DartAccountMapper}에도 없다. */
    static final Set<String> NAME_OPERATING_CASH_FLOW = ordered(
            "영업활동현금흐름",
            "영업활동으로 인한 현금흐름",
            "영업활동으로부터의 현금흐름",
            "영업활동 현금흐름");

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
     * 자본변동표에서 이익잉여금으로 보이는 행을 진단용으로 뽑는다.
     *
     * <p>§8의 세 종목(오리온·오리온홀딩스·에스티큐브)은 요약본에 <b>행 자체가 없어</b>
     * 이익잉여금이 빈다. 전체 재무제표의 재무상태표가 그것을 주는지는 <b>실측되지 않았고</b>,
     * 안 준다면 남는 자리가 자본변동표다.
     *
     * <p>MUST NOT: 이 결과를 값으로 쓰지 않는다. 자본변동표는 한 계정이 기초·증감·기말로
     * 여러 행에 흩어지고 {@code account_detail}의 member 표기가 회사마다 갈려, <b>어느 행이
     * 기말 잔액인지 실측 없이 정할 수 없다.</b> 추측으로 고르면 기초 잔액을 이익잉여금으로
     * 저장하는데 값이 그럴듯해 드러나지 않는다. 지금은 <b>무엇이 있는지 로그로 남기는 것</b>이
     * 이 메서드의 전부이고, 표본 실행이 답을 준 뒤에 값 경로를 연다.
     */
    static void logEquityChangeCandidates(JsonNode list, String ticker, int year, String period) {
        List<JsonNode> rows = sections(list, SJ_EQUITY_CHANGES);
        List<String> found = new ArrayList<>();
        for (JsonNode row : rows) {
            String detail = row.path("account_detail").asString("");
            String name = row.path("account_nm").asString("");
            if (detail.contains("이익잉여금") || name.contains("이익잉여금")) {
                found.add("ord=" + row.path("ord").asString("")
                        + " id=" + row.path("account_id").asString("")
                        + " nm=" + name
                        + " detail=" + detail
                        + " amt=" + row.path("thstrm_amount").asString(""));
            }
        }
        if (found.isEmpty()) {
            log.warn("이익잉여금이 재무상태표에 없고 자본변동표에도 후보가 없다. "
                    + "ticker={} {}/{} SCE={}행", ticker, year, period, rows.size());
            return;
        }
        log.warn("이익잉여금이 재무상태표에 없다. 자본변동표 후보 {}건을 남긴다(값으로 쓰지 않는다). "
                + "ticker={} {}/{} — {}", found.size(), ticker, year, period, found);
    }
}
