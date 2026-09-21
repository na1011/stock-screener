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
 *       (dart-financial.md의 「전체 재무제표」 절). 계정명 후보군을 늘리는 방식은 업종이 늘수록 반드시 새어 나간다.
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

    /**
     * 법인세비용. 세후 투하자본이익률의 유효세율에서 <b>분자</b>다.
     *
     * <p><b>표준 ID가 「계속영업」이다.</b> 그런데 {@link #ID_PROFIT_BEFORE_TAX}는 <b>총액</b>이라
     * (계속영업 세전이익은 표본 0/8로 안 온다) <b>중단영업이 있는 해에는 분자와 분모의 기저가
     * 어긋난다.</b> 그 빈도는 전수가 답한다.
     *
     * <p><b>MUST NOT: 음수를 크기(abs)로 읽지 않는다.</b> 자본적지출과 다르다 — 그쪽은
     * 「취득」이라 한 방향이지만 <b>법인세는 환입이 실재한다.</b> 실측(8종목 × 5년)에서 음수
     * 여섯 중 <b>넷이 진짜 환입이고 둘이 차감 항목 표기</b>였다. 크기로 읽으면 진짜 환입의
     * 부호가 뒤집힌다. <b>가르는 것은 {@code 세전이익 − 순이익}과의 부호 대조</b>이고 그 판단은
     * 지표 계층이 한다 — 적재 계층은 원자료를 그대로 담는다(ADR-0067 §규칙).
     */
    static final Set<String> ID_INCOME_TAX_EXPENSE = ordered(
            "ifrs-full_IncomeTaxExpenseContinuingOperations",
            "dart_IncomeTaxExpenseContinuingOperations");

    /**
     * 세전이익(법인세비용차감전순이익). 유효세율의 <b>분모</b>다.
     *
     * <p><b>계정명 폴백을 두지 않는다.</b> 실측에서 이름이 넷으로 갈렸고
     * ({@code 법인세비용차감전순이익} · {@code …(손실)} · {@code 법인세비용차감전당기순이익} ·
     * {@code 법인세차감전순이익}), 포괄손익계산서에는 <b>「법인세비용차감전기타포괄손익」</b>이
     * 따로 있어 낱말을 공유한다. 감가상각비가 정확히 그 형태로 3/8 → 2/8 오진을 냈다.
     */
    static final Set<String> ID_PROFIT_BEFORE_TAX = ordered(
            "ifrs-full_ProfitLossBeforeTax", "dart_ProfitLossBeforeTax");

    /**
     * <b>지배기업 소유주 귀속</b> 당기순이익. 주당 지표의 분자다(K-IFRS 1033).
     *
     * <p>MUST NOT: 계정명 폴백을 두지 않는다. 실측에서 <b>한 계정을 여덟 회사가 일곱 가지
     * 이름으로 부른다</b> — {@code 지배기업소유주지분} · {@code 지배주주} ·
     * {@code 지배기업소유주지분순이익} · {@code 지배기업 소유주지분} ·
     * {@code 지배회사지분 반기순이익} · {@code 지배기업주주지분순이익} ·
     * {@code 지배기업의 소유주에게 귀속되는 당기순이익(손실)}. 어느 이름을 후보로 넣어도
     * 새어 나가고, 새면 <b>연결 총액이 그 자리에 앉아 값이 그럴듯하게 틀린다.</b>
     *
     * <p>IMPORTANT — {@link #ID_NET_INCOME}과 <b>부분일치로 겹친다.</b>
     * {@code ifrs-full_ProfitLoss}는 이 ID의 접두사이므로, {@link #findBy}가 완전일치가
     * 아니었다면 연결 총액을 물었을 것이다. 이 파일 머리의 MUST NOT이 지키는 것이 그것이다.
     *
     * <p>실측 <b>연간 8/8 · 분기 8/8</b>이고 {@code sj_div}가 {@code IS}와 {@code CIS}로
     * 갈린다(dart-financial.md의 「계정별 가용성」 절). 호출부가 두 절을 함께 모으므로 걸리지 않는다.
     */
    static final Set<String> ID_NET_INCOME_CONTROLLING = ordered(
            "ifrs-full_ProfitLossAttributableToOwnersOfParent",
            "ifrs_ProfitLossAttributableToOwnersOfParent");
    /**
     * 매출원가. <b>없는 것이 결손이 아닌 계정</b>이다 — 금융업·서비스업은 개념 자체가 없어
     * 원래 오지 않는다(실측: 8개사 중 KB금융·NAVER 둘이 그렇고 제조·유통·물류·제약 여섯은
     * 전부 표준코드로 왔다).
     *
     * <p><b>이 문장의 범위는 국내다</b> — 미국 서비스업({@code SIC:73})은 실측에서
     * <b>131종목 중 108이 값을 낸다</b>(2026-09-14 · ADR-0147).
     *
     * <p><b>둘째 사례가 {@link #ID_INVENTORIES}다</b>(dart-financial.md의 「계정별 가용성」 절). 그쪽은 금융업
     * 하나만 없다 —
     * <b>업종 부재의 범위가 계정마다 다르므로 한 계정의 결손 사유를 다른 계정에 복사하지
     * 않는다.</b>
     */
    static final Set<String> ID_COST_OF_SALES = ordered("ifrs-full_CostOfSales", "ifrs_CostOfSales");

    /**
     * 자본적지출(유형자산의 취득). <b>현금흐름표에서만 읽는다.</b>
     *
     * <p>계정명 폴백을 두지 않는다. {@code 취득}이 든 계정이 회사마다 열 개 넘게 오고
     * (자기주식의 취득·장기금융상품의 취득·관계기업투자의 취득·투자부동산의 취득…)
     * 부분일치는 그중 아무거나 집는다 — {@code DartMissingAccountProbeLiveTest}에서 {@code 감가상각}이 오리온의
     * {@code 투자부동산감가상각비 조정}을 집어 3/8을 2/8로 정정하게 만든 것과 같은 병이고,
     * <b>이쪽은 틀린 값이 훨씬 커서 잉여현금흐름을 통째로 뒤집는다.</b>
     *
     * <p>표준코드로 8종목 8/8이 왔고 계정명도 여덟이 전부 {@code 유형자산의 취득}으로
     * 같았다(dart-financial.md의 「계정별 가용성」 절). 폴백이 메울 것이 관측되지 않았다.
     */
    static final Set<String> ID_CAPEX = ordered(
            "ifrs-full_PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities",
            "dart_PurchaseOfPropertyPlantAndEquipment",
            "ifrs-full_PurchaseOfPropertyPlantAndEquipment");

    /**
     * 재고자산. <b>재무상태표에서만 읽는다.</b>
     *
     * <p>계정명 폴백을 두지 않는다. 좁히지 않으면 무는 것이 실측으로 셋 나왔다(dart-financial.md의 「계정별
     * 가용성」 절) —
     * 현금흐름표의 {@code 재고자산의 감소(증가)}는 <b>증감분</b>이라 잔액으로 저장하면
     * 「재고 증가율」이 <i>증감의 증감</i>이 되고, 같은 표의 {@code 재고자산평가손실}은 손익
     * 조정이며, 재무상태표의 {@code 상품권}(이마트 1.7조)은 재고와 무관한 항목인데 계정명
     * 부분일치에 걸린다.
     *
     * <p>표준코드로 8종목 중 <b>7/8</b>이 왔고 일곱이 전부 {@code ifrs-full_Inventories}
     * 하나였다 — 구접두어도 {@code dart_} 확장도 관측되지 않았다. <b>빠진 하나는 금융업이고
     * 그 회사 재무상태표에는 재고 계열 행이 아예 없다</b>(결손이 아니라 업종이다).
     * 계정명은 갈린다({@code 재고자산}·{@code 유동재고자산}).
     */
    static final Set<String> ID_INVENTORIES = ordered("ifrs-full_Inventories", "ifrs_Inventories");

    static final Set<String> ID_TOTAL_ASSETS = ordered("ifrs-full_Assets", "ifrs_Assets");
    static final Set<String> ID_TOTAL_LIABILITIES = ordered("ifrs-full_Liabilities", "ifrs_Liabilities");
    static final Set<String> ID_TOTAL_EQUITY = ordered("ifrs-full_Equity", "ifrs_Equity");

    /**
     * <b>지배기업 소유주 귀속 자본.</b> {@code PBR}의 분모가 이것이어야 한다 — 분자인
     * 시가총액은 <b>상장주식에만</b> 값을 매기므로 연결자본을 쓰면 비지배지분이 분모에만
     * 들어간다(ADR-0159 §규칙).
     *
     * <p><b>실측(2026-09-21 · 편입 국내 전수 350콜 ·
     * {@code DartControllingEquityProbeLiveTest})</b>: 응답 <b>326/350</b>(나머지 24는 전부
     * {@code status 013}) 중 <b>직접 태그 321 = 98.5%</b>, 항등식
     * {@code 자본총계 = 지배주주 + 비지배}가 <b>294/294 = 100%</b>, {@code sj_div}는 <b>BS 하나</b>.
     * 지분 구분이 아예 없는 4종목은 비지배지분이 없는 회사이고 그때는 자본총계가 곧 이 값이다.
     *
     * <p><b>MUST NOT: 계정명으로 찾지 않는다 — 실측 계정명이 28가지다.</b>
     * {@code 지배기업의 소유주지분} · {@code 지배기업소유주지분} · {@code 지배주주지분} ·
     * {@code 지배기업 소유지본}(오타까지) … 지배주주 <b>순이익</b>이 일곱으로 갈린 것보다 넓다.
     * 이름 폴백을 두면 어느 이름을 넣어도 새어 나간다.
     *
     * <p><b>MUST NOT: 부분일치로 맞추지 않는다</b> — {@code ifrs-full_Equity}가 이 ID의
     * <b>접두사</b>다. {@link #findBy}가 완전일치라 지금은 안 걸리지만, 그 함수를 고치면 걸린다.
     */
    static final Set<String> ID_TOTAL_EQUITY_CONTROLLING = ordered(
            "ifrs-full_EquityAttributableToOwnersOfParent",
            "ifrs_EquityAttributableToOwnersOfParent");

    /**
     * <b>비지배지분(자본).</b> 유도와 항등식 검산의 재료다 — 지배주주 자본이 없어도
     * {@code 자본총계 − 비지배}로 만들 수 있고, 둘 다 있으면 항등식이 선다.
     *
     * <p>이것이 <b>있는데</b> 지배주주 자본이 없으면 「계정 후보군이 좁다」는 신호이고,
     * <b>둘 다 없으면</b> 비지배지분이 없는 회사라 자본총계가 곧 지배주주 자본이다.
     * <b>그 둘을 가르는 것이 이 매핑의 목적이다</b> — NULL 하나로는 못 가른다.
     */
    static final Set<String> ID_NONCONTROLLING_INTERESTS = ordered(
            "ifrs-full_NoncontrollingInterests", "ifrs_NoncontrollingInterests");

    /**
     * <b>비지배지분 귀속 순이익.</b> 실측 <b>연간 8/8 · 분기 8/8</b>이고 항등식
     * {@code 연결총액 = 지배주주 + 비지배}가 <b>16/16</b>에서 맞았다
     * ({@code docs/api-spec/dart-financial.md} 「계정별 가용성」).
     *
     * <p>2026-09-21 이전에는 <b>매퍼에 비지배 매핑이 0곳</b>이라 국내에서
     * 「지배주주 자본이 없다」와 「비지배가 없는 회사다」를 가를 수단이 없었다.
     */
    static final Set<String> ID_NET_INCOME_NONCONTROLLING = ordered(
            "ifrs-full_ProfitLossAttributableToNoncontrollingInterests",
            "ifrs_ProfitLossAttributableToNoncontrollingInterests");
    static final Set<String> ID_CURRENT_ASSETS = ordered("ifrs-full_CurrentAssets", "ifrs_CurrentAssets");
    /**
     * 현금및현금성자산. <b>재무상태표에서만 읽는다</b> — 현금흐름표에도 같은 이름의 계정이
     * 있고(기초·기말 잔액) 그쪽이 먼저 잡히면 <b>기초 잔액을 현재 보유 현금으로 저장</b>하게
     * 된다. 실측에서 KB금융이 정확히 그랬다.
     */
    static final Set<String> ID_CASH_AND_EQUIVALENTS =
            ordered("ifrs-full_CashAndCashEquivalents", "ifrs_CashAndCashEquivalents");
    static final Set<String> ID_CURRENT_LIABILITIES =
            ordered("ifrs-full_CurrentLiabilities", "ifrs_CurrentLiabilities");
    static final Set<String> ID_RETAINED_EARNINGS =
            ordered("ifrs-full_RetainedEarnings", "ifrs_RetainedEarnings");

    /** 표본 4/4에서 확인된 유일한 항목이다(dart-financial.md의 「전체 재무제표」 절). 이 전환으로 실제로 열리는 것이 이것 하나다. */
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
    /**
     * <b>지배기업 소유주 귀속 자본.</b> 직접 태그 → {@code 자본총계 − 비지배지분} → {@code null}.
     *
     * <p><b>MUST NOT: 「비지배 행이 없으니 자본총계가 곧 지배주주」로 폴백하지 않는다.</b>
     * 그 판정은 <b>종목 단위 증거</b>를 요구하는데(ADR-0159 §결정 — *"그 종목이 비지배를
     * 한 번도 안 내면"*) 이 함수는 보고서 하나만 본다. 행 단위로 폴백하면
     * <b>어느 분기에 그 행이 없다는 것만으로 연결자본에 지배주주 이름표가 붙는다.</b>
     * 실측(2026-09-21 전수 탐침)으로 그렇게 비는 종목은 <b>4</b>뿐이고, 그 넷을 NULL로 두는
     * 것이 지어내는 것보다 정직하다.
     *
     * <p><b>MUST NOT: 계정명 후보를 주지 않는다</b> — 실측 계정명이 <b>28가지</b>다
     * ({@link #ID_TOTAL_EQUITY_CONTROLLING}). 그래서 {@code find}의 셋째 인자가 {@code null}이다.
     */
    static BigDecimal controllingEquity(List<JsonNode> balance) {
        BigDecimal direct = find(balance, ID_TOTAL_EQUITY_CONTROLLING, null);
        if (direct != null) {
            return direct;
        }
        BigDecimal total = find(balance, ID_TOTAL_EQUITY, DartAccountMapper.TOTAL_EQUITY);
        BigDecimal noncontrolling = find(balance, ID_NONCONTROLLING_INTERESTS, null);
        return total != null && noncontrolling != null ? total.subtract(noncontrolling) : null;
    }

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
     * <p>dart-financial.md의 「계정 집합이 회사마다 다르다」 절의 세 종목(오리온·오리온홀딩스·에스티큐브)은
     * 요약본에도 전체 재무제표의
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
