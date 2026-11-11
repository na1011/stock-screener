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

    // ── 계속 · 중단영업(F2b · ADR-0184 · 국내 관문 뒤) ────────────────────────────────────────
    // K-IFRS 1105는 중단영업이 있을 때만 계속 · 중단을 갈라 표시한다 — 손익 행이 온 보고서에 이 ID들이 없으면 「중단영업 없음」이다
    // (2026-10-23 전수: 편입 350 · 2025 사업 + 2026 반기 696보고서 중 줄이 있는 72종목 · 그중 ≠ 0이 40). 분 · 반기 보고서의
    // thstrm_amount는 3개월이고 4분기는 FY − Q3 보고서 누계(thstrm_add_amount)로 짓는다.

    /** 중단영업 손익(연결). */
    static final Set<String> ID_DISCONTINUED = ordered(
            "ifrs-full_ProfitLossFromDiscontinuedOperations", "ifrs_ProfitLossFromDiscontinuedOperations");

    /** 중단영업 손익의 지배기업 소유주 몫 — 같은 뜻의 이름 둘(60보고서 중 직접 21). */
    static final Set<String> ID_DISCONTINUED_CONTROLLING = ordered(
            "ifrs-full_IncomeFromDiscontinuedOperationsAttributableToOwnersOfParent",
            "ifrs-full_ProfitLossFromDiscontinuedOperationsAttributableToOrdinaryEquityHoldersOfParentEntity");

    /** 중단영업 손익의 비지배 몫. */
    static final Set<String> ID_DISCONTINUED_NONCONTROLLING = ordered(
            "ifrs-full_ProfitLossFromDiscontinuedOperationsAttributableToNoncontrollingInterests");

    /** 기본주당이익(보통주)과 계속영업 기본주당이익 — 지배 계속영업을 비로 짓는 재료(국내만 · {@link #discontinued}). */
    static final Set<String> ID_BASIC_EPS = ordered("ifrs-full_BasicEarningsLossPerShare");
    static final Set<String> ID_BASIC_EPS_CONTINUING = ordered("ifrs-full_BasicEarningsLossPerShareFromContinuingOperations");
    static final Set<String> ID_BASIC_EPS_DISCONTINUED = ordered("ifrs-full_BasicEarningsLossPerShareFromDiscontinuedOperations");

    /**
     * 주당이익 비로 지을 때 받는 반올림 오차 상한. 원화 주당이익은 원 단위 정수라 오차 상한은 {@code 0.5/|EPS| + 0.5/|계속 EPS|}다.
     */
    private static final BigDecimal EPS_RATIO_BOUND = new BigDecimal("0.005");

    /** 계속영업 손익의 지배기업 소유주 몫 — 같은 뜻의 이름 둘. */
    static final Set<String> ID_CONTINUING_CONTROLLING = ordered(
            "ifrs-full_IncomeFromContinuingOperationsAttributableToOwnersOfParent",
            "ifrs-full_ProfitLossFromContinuingOperationsAttributableToOrdinaryEquityHoldersOfParentEntity");

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
     * <p><b>MUST NOT: 「비는 종목은 4뿐」을 근거로 쓰지 않는다 — 2026-09-22에 재니 22다</b>
     * (KOSDAQ 18 · KOSPI 4 · 편입 · {@code is_latest} · {@code period_end >= 2024-01-01}에서
     * 창 전체가 빈 종목). 4는 KOSPI만 센 수였다. <b>크기가 커져도 처방은 안 바뀐다</b> —
     * 이 함수가 보고서 하나만 보는 한 게이트는 못 선다.
     *
     * <p><b>손익 축은 그 한계를 넘었다</b>({@link #controllingIncome}) — 호출부가 여러 판을
     * 모아 종목 단위 증거를 만들도록 구조를 바꿨기 때문이다. <b>자본 축에 같은 것을 하려면
     * 같은 구조 변경이 먼저다.</b> 위 수를 「작으니 그냥 두자」로 읽지 않는다.
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

    /**
     * <b>지배기업 소유주 귀속 순이익.</b> 네 갈래다 —
     * 직접 태그 → {@code 연결총액 − 비지배손익} → <b>비지배 전무 게이트</b> → {@code null}.
     *
     * <p>미국 {@code SecEdgarFacts.consolidatedIncome}과 <b>같은 축</b>이다
     * (ADR-0159 §결정은 두 컬럼의 뜻이 두 시장 공통이라 정했다).
     *
     * <p><b>MUST: 게이트 판정을 이 함수가 하지 않는다 — 호출부가 준다.</b> 그 판정은
     * <b>종목 단위 증거</b>(국내 관문 켬은 판 전체의 흔적 — {@link #hasNoncontrollingTrace} · ADR-0209)를 요구하는데 이 함수는
     * 보고서 하나만 보기 때문이다. {@link #controllingEquity}가 같은 이유로 게이트를 아예
     * 안 두는데, 이쪽은 <b>호출부가 여러 판을 모아 판정하도록 구조를 바꿔</b> 연다.
     *
     * <p><b>MUST NOT: 재무상태표 비지배지분으로 게이트를 켜지 않는다.</b> 답을 아는 칸에
     * 그 산수를 태워 봤다(2026-09-22 · 편입 · {@code is_latest} ·
     * {@code period_end >= 2024-01-01} · 네 컬럼이 다 있는 행):
     * {@code total_equity_controlling = total_equity}로 발화한 <b>KOSPI 315 중 11 · KOSDAQ 315 중 11</b>에서
     * {@code nic <> ni}였다(3.5%). 상대오차 {@code abs(1 - teqc/te) < 0.0001}로 느슨하게 잡으면
     * <b>KOSPI 403 중 94(23%)</b>로 더 나빠진다 — 비지배 자본이 <b>작지만 0은 아닌</b> 회사가
     * 그 차이를 만든다. 잔액은 손익을 말해 주지 않는다.
     *
     * @param gateOpen 호출부의 종목 단위 판정. <b>연결재무제표(CFS) 응답에서만 참일 수 있다</b> —
     *                 별도재무제표(OFS)에는 비지배 행이 원리적으로 없어 그 부재가 증거가 아니다
     */
    static BigDecimal controllingIncome(List<JsonNode> income, boolean gateOpen) {
        BigDecimal direct = find(income, ID_NET_INCOME_CONTROLLING, null);
        if (direct != null) {
            return direct;
        }
        BigDecimal total = find(income, ID_NET_INCOME, DartAccountMapper.NET_INCOME);
        if (total == null) {
            return null;
        }
        BigDecimal noncontrolling = find(income, ID_NET_INCOME_NONCONTROLLING, null);
        if (noncontrolling != null) {
            return total.subtract(noncontrolling);
        }
        return gateOpen ? total : null;
    }

    /**
     * <b>한 보고서의 중단영업 판정</b>(연결 · 지배). {@code cumulative}면 누계 칸({@code thstrm_add_amount} — Q3 보고서의
     * 9개월)을, 아니면 당기 칸을 읽는다.
     *
     * <p>지배 몫은 항등식만이다 — 직접 → 연결 − 비지배 몫 → 지배주주 순이익 − 계속영업 지배 몫 → 같은 보고서에서
     * 지배주주 순이익 = 연결 순이익(그 기간 비지배 0)이면 연결 몫 → {@code null}. 「비지배 몫 0」 가정은 두지 않는다
     * (미국 답을 아는 칸에서 원장이 자기정합한데 그 가정만 틀린 것 104/144 — 탐색 F2B-PLAN-1023.md 1b).
     *
     * <p>지배주주 순이익은 보고서와 같은 해석을 쓴다 — 표준 줄이 없으면 연결 응답의 비표준 지배 줄을 같은 항등식으로
     * ({@link #controllingIncomeFromNonstandardRows}). 안 쓰면 보고서는 지배주주 순이익을 갖는데 중단 지배 몫만 비어 TTM이 빈다.
     *
     * @param consolidatedStatement 연결(CFS) 응답인가
     * @return 두 값 배열 {연결, 지배}. 중단 줄이 하나도 없으면 {0, 0}
     */
    static BigDecimal[] discontinued(List<JsonNode> income, boolean cumulative, boolean consolidatedStatement) {
        return discontinued(income, cumulative, consolidatedStatement, null);
    }

    /** @param confirmedControlling {@link #confirmedControlling}이 고른 지배주주 순이익(그 칸) — 없으면 {@code null}(표준 줄) */
    static BigDecimal[] discontinued(List<JsonNode> income, boolean cumulative, boolean consolidatedStatement,
                                     BigDecimal confirmedControlling) {
        return discontinued(income, cumulative, consolidatedStatement, confirmedControlling, false);
    }

    /**
     * @param noNoncontrolling 국내 관문 켬 — 그 판에 비지배 흔적이 없다({@link #hasNoncontrollingTrace} · 연결 응답). 그러면 지배 중단 = 연결 중단이다 —
     *                         지배주주 순이익을 연결 순이익으로 여는 게이트와 같은 증거 · 같은 항등식이다(한쪽만 두면 그 판이 「비지배 없음」과 「지배
     *                         중단 모름」을 함께 말한다). 위의 「비지배 몫 0 가정 금지」는 판 안 증거 없이 줄 부재로 가정하는 것에 선다. 답을 아는 판
     *                         (녹화본 · 흔적 없음 · 표준 지배 중단 줄 있음) 6/6이 같았다
     */
    static BigDecimal[] discontinued(List<JsonNode> income, boolean cumulative, boolean consolidatedStatement,
                                     BigDecimal confirmedControlling, boolean noNoncontrolling) {
        String field = cumulative ? "thstrm_add_amount" : "thstrm_amount";
        BigDecimal total = findBy(income, ID_DISCONTINUED, "account_id", field);
        BigDecimal parent = findBy(income, ID_DISCONTINUED_CONTROLLING, "account_id", field);
        BigDecimal noncontrolling = findBy(income, ID_DISCONTINUED_NONCONTROLLING, "account_id", field);
        if (total == null && parent == null && noncontrolling == null) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
        }
        BigDecimal net = findBy(income, ID_NET_INCOME, "account_id", field);
        BigDecimal controllingNet = confirmedControlling != null ? confirmedControlling
                : findBy(income, ID_NET_INCOME_CONTROLLING, "account_id", field);
        if (controllingNet == null && consolidatedStatement) {
            controllingNet = controllingIncomeFromNonstandardRows(income, net, field);
        }
        BigDecimal consolidated = total;
        if (consolidated == null && parent != null && noncontrolling != null) {
            consolidated = parent.add(noncontrolling);
        }
        if (consolidated == null && parent != null && parent.signum() == 0 && noncontrolling == null) {
            consolidated = BigDecimal.ZERO;
        }
        BigDecimal controlling;
        if (consolidated != null && consolidated.signum() == 0) {
            controlling = BigDecimal.ZERO;
        } else if (parent != null) {
            controlling = parent;
        } else if (consolidated != null && noncontrolling != null) {
            controlling = consolidated.subtract(noncontrolling);
        } else {
            BigDecimal continuing = findBy(income, ID_CONTINUING_CONTROLLING, "account_id", field);
            if (controllingNet != null && continuing != null) {
                controlling = controllingNet.subtract(continuing);
            } else if (consolidated != null && controllingNet != null && net != null
                    && controllingNet.compareTo(net) == 0) {
                controlling = consolidated;
            } else if (consolidated != null && noNoncontrolling) {
                controlling = consolidated;
            } else {
                controlling = controllingNet == null ? null : byEpsRatio(income, field, controllingNet);
            }
        }
        return new BigDecimal[]{consolidated, controlling};
    }

    /**
     * <b>지배 중단 = 지배 순이익 × (1 − 계속 EPS / EPS)</b> — 원장이 지배 몫 줄을 안 줄 때의 마지막 갈래(국내만).
     *
     * <p>근거: 답을 아는 보고서(지배 계속영업 직접 · 중단 ≠ 0 · 두 EPS 있음) 13 전부에서 이 비로 지은 지배 계속영업이 0.04% 안으로
     * 맞았다(2026-10-23 · 2025 사업 + 2026 반기 원응답). 원화 주당이익이 큰 정수라 반올림이 작다. <b>미국에는 옮기지 않는다</b> —
     * 같은 산수가 센트 반올림 · 우선주 배분으로 0.5% 안이 1,901/2,784(68%)였다(폴백 근거의 이식 금지).
     * 반올림 오차 상한이 {@link #EPS_RATIO_BOUND}를 넘으면(주당이익이 작은 회사) 받지 않는다.
     *
     * <p>EPS가 없으면 계속 EPS + 중단 EPS로 둔다(IAS 33 — 둘을 함께 표시한다 · 반올림 둘이라 오차 상한 1). 답을 아는 연결 보고서(지배 중단
     * 직접 · 두 EPS)에서 이 합으로 지은 값이 반올림 상한 안 40/42 · 밖 둘은 LG전자 2024(우선주 배분 · 표준 EPS로도 같은 값 · 지배 계속영업의
     * 0.25% · 0.56%)다(2026-11-01 · 녹화본 0콜).
     */
    private static BigDecimal byEpsRatio(List<JsonNode> income, String field, BigDecimal controllingNet) {
        BigDecimal eps = findBy(income, ID_BASIC_EPS, "account_id", field);
        BigDecimal continuingEps = findBy(income, ID_BASIC_EPS_CONTINUING, "account_id", field);
        BigDecimal epsHalf = new BigDecimal("0.5");
        if (eps == null && continuingEps != null) {
            BigDecimal discontinuedEps = findBy(income, ID_BASIC_EPS_DISCONTINUED, "account_id", field);
            if (discontinuedEps != null) {
                eps = continuingEps.add(discontinuedEps);
                epsHalf = BigDecimal.ONE;
            }
        }
        if (eps == null || continuingEps == null || eps.signum() == 0 || continuingEps.signum() == 0) {
            return null;
        }
        BigDecimal half = new BigDecimal("0.5");
        BigDecimal bound = epsHalf.divide(eps.abs(), 12, java.math.RoundingMode.HALF_UP)
                .add(half.divide(continuingEps.abs(), 12, java.math.RoundingMode.HALF_UP));
        if (bound.compareTo(EPS_RATIO_BOUND) > 0) {
            return null;
        }
        BigDecimal continuing = controllingNet.multiply(continuingEps).divide(eps, 0, java.math.RoundingMode.HALF_UP);
        return controllingNet.subtract(continuing);
    }

    /** 중단 줄(연결 · 지배 · 비지배 몫 중 하나)이 이 응답에 있는가 — 누계 칸 기준. */
    static boolean hasDiscontinuedLine(List<JsonNode> income) {
        for (JsonNode row : income) {
            String id = row.path("account_id").asString("");
            if (ID_DISCONTINUED.contains(id) || ID_DISCONTINUED_CONTROLLING.contains(id)
                    || ID_DISCONTINUED_NONCONTROLLING.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 지배주주 순이익 줄 없이 <b>계속 · 중단영업 지배 몫만</b> 준 보고서의 지배주주 순이익 — 둘의 합(F2b · 국내 관문 뒤).
     * 2025 사업 · 2026 반기 696보고서 중 5(036540 · 솔브레인홀딩스 · 스피어 — `reload-gate.md` 국내 「계속+중단영업으로 나뉜
     * 값」 행의 그 꼴). 둘 중 하나라도 없으면 {@code null}이다.
     */
    static BigDecimal controllingIncomeFromParts(List<JsonNode> income) {
        BigDecimal continuing = find(income, ID_CONTINUING_CONTROLLING, null);
        BigDecimal discontinued = find(income, ID_DISCONTINUED_CONTROLLING, null);
        return continuing != null && discontinued != null ? continuing.add(discontinued) : null;
    }

    /**
     * <b>표준계정 ID 없이 실린 지배주주 순이익</b>(국내 관문 켬) — 손익 행 중 {@code account_id}가 표준계정코드를 안 쓴 행에서 이름이 지배 몫(지배 ·
     * 비지배 아님)인 값을, <b>같은 보고서의 비표준 비지배 몫과 더해 당기순이익과 같을 때만</b>(비지배 행이 없으면 그 값이 당기순이익과 같을 때만)
     * 받는다. 이름만으로 받지 않는 것은 같은 이름이 총포괄이익의 귀속에도 실려서다 — 항등식이 당기순이익의 귀속을 가른다.
     *
     * <p>근거(국내 주요계정 스윕 · 편입 350 × 2019~ 녹화 · 2026-11-01): 지배주주 순이익이 빈 판 1,439 중 손익 섹션에 비표준 지배 행이 있는 512 ·
     * 그중 항등식이 서는 509(유한양행 2019 FY 연결 36.6B = 지배 40.0B + 비지배 −3.4B · 펌텍코리아 「-표준계정코드 미사용-」 지배기업소유주지분).
     *
     * <p>연결(CFS) 응답에서만 부른다 — 지배 · 비지배 귀속은 연결의 개념이다(별도 응답의 같은 이름 줄은 받지 않는다).
     *
     * @param consolidated 같은 보고서 · 같은 칸의 당기순이익(연결) — 없으면 받지 않는다
     * @param field        읽을 칸 — 당기({@code thstrm_amount}) · 누계({@code thstrm_add_amount})
     */
    static BigDecimal controllingIncomeFromNonstandardRows(List<JsonNode> income, BigDecimal consolidated, String field) {
        if (consolidated == null) {
            return null;
        }
        List<BigDecimal> controlling = new ArrayList<>();
        List<BigDecimal> noncontrolling = new ArrayList<>();
        for (JsonNode row : income) {
            if (!row.path("account_id").asString("").contains("표준계정코드")) {
                continue;
            }
            String name = row.path("account_nm").asString("");
            BigDecimal value = DartAccountMapper.parseAmount(row.path(field).asString(""));
            if (value == null) {
                continue;
            }
            if (name.contains("비지배")) {
                noncontrolling.add(value);
            } else if (name.contains("지배")) {
                controlling.add(value);
            }
        }
        for (BigDecimal c : controlling) {
            if (noncontrolling.isEmpty() ? c.compareTo(consolidated) == 0
                    : noncontrolling.stream().anyMatch(n -> c.add(n).compareTo(consolidated) == 0)) {
                return c;
            }
        }
        return null;
    }

    /**
     * <b>표준 지배 줄을 원장 둘이 반박할 때의 지배주주 순이익</b>(국내 관문 켬 · 계획 K19). 표준 {@code ProfitLossAttributableToOwnersOfParent}가
     * 같은 보고서의 손익 산수(지배 + 비지배 = 당기순이익 — 비지배 후보는 표준 · 비표준 줄)에도, 자본변동표 지배 열 당기순이익에도 어긋나고, 다른 손익
     * 줄(당기순이익 − 비지배 · 비표준 지배 줄 · 그 칸에 비지배 흔적이 없으면 당기순이익 — K9b) 하나가 자본변동표 지배 열과 같으면 그 줄의 값. 아니면 {@code null}(표준 줄 그대로).
     *
     * <p>자본변동표는 누계라 누계 칸으로 줄을 고르고 그 줄의 {@code field} 값을 낸다(반기 · 3분기 3개월 칸). 실측(녹화본 연결 8,462판 · 2026-11-10):
     * 자본변동표 지배 열이 실린 판에서 표준 지배 줄과 같은 것 6,262 · 다른 것 113 — 그중 이 규칙이 바꾸는 것 27(삼성전기 2019~2023 · LG이노텍
     * 2020 FY 330.9B → 236.1B — 표준 줄이 계속영업 몫이었다). 한쪽만 믿지 않는다 — 산수만 어긋나면 비지배 줄 쪽 결함(코미코)이고, 자본변동표만
     * 다르면 그 표의 사정일 수 있다.
     *
     * @param equityChanges   같은 응답의 자본변동표 줄({@link #SJ_EQUITY_CHANGES})
     * @param cumulativeField 그 보고서의 누계 칸 — 반기 · 3분기는 {@code thstrm_add_amount}, 1분기 · 연간은 {@code thstrm_amount}
     */
    static BigDecimal confirmedControlling(List<JsonNode> income, List<JsonNode> equityChanges, String field, String cumulativeField) {
        return confirmedControlling(income, equityChanges, field, cumulativeField, "thstrm_amount");
    }

    /**
     * @param equityField 자본변동표에서 읽을 칸 — 당기 {@code thstrm_amount} · 비교 열은 사업보고서 {@code frmtrm_amount} ·
     *                    {@code bfefrmtrm_amount}, 분기 보고서 {@code frmtrm_q_amount}(전년 누계 — 녹화본 분기 판 1,378 전부 이 이름)
     */
    static BigDecimal confirmedControlling(List<JsonNode> income, List<JsonNode> equityChanges, String field, String cumulativeField,
                                           String equityField) {
        BigDecimal owners = findBy(income, ID_NET_INCOME_CONTROLLING, "account_id", cumulativeField);
        BigDecimal net = findBy(income, ID_NET_INCOME, "account_id", cumulativeField);
        BigDecimal statement = equityControllingProfit(equityChanges, equityField);
        if (owners == null || net == null || statement == null || near(owners, statement)) {
            return null;
        }
        BigDecimal minority = findBy(income, ID_NET_INCOME_NONCONTROLLING, "account_id", cumulativeField);
        List<BigDecimal> minorities = new ArrayList<>();
        if (minority != null) {
            minorities.add(minority);
        }
        List<JsonNode> nonstandardOwners = new ArrayList<>();
        for (JsonNode row : income) {
            String name = row.path("account_nm").asString("");
            if (!row.path("account_id").asString("").contains("표준계정코드") || name.contains("포괄")) {
                continue;
            }
            if (name.contains("비지배")) {
                BigDecimal v = DartAccountMapper.parseAmount(row.path(cumulativeField).asString(""));
                if (v != null) {
                    minorities.add(v);
                }
            } else if (name.contains("지배")) {
                nonstandardOwners.add(row);
            }
        }
        if (minorities.isEmpty() ? near(owners, net) : minorities.stream().anyMatch(n -> near(owners.add(n), net))) {
            return null;
        }
        // 원장이 직접 적은 지배 줄을 앞에 — 순이익 − 비지배와 천 원 안이면 같은 값이고(반올림 · 삼성전기 2022 반기 1원) 그때는 적힌 줄을 쓴다.
        List<BigDecimal> confirmed = new ArrayList<>();
        for (JsonNode row : nonstandardOwners) {
            BigDecimal cumulative = DartAccountMapper.parseAmount(row.path(cumulativeField).asString(""));
            if (cumulative != null && near(cumulative, statement)) {
                BigDecimal v = DartAccountMapper.parseAmount(row.path(field).asString(""));
                if (v == null) {
                    return null;
                }
                confirmed.add(v.stripTrailingZeros());
            }
        }
        if (minority != null && near(net.subtract(minority), statement)) {
            BigDecimal periodNet = findBy(income, ID_NET_INCOME, "account_id", field);
            BigDecimal periodMinority = findBy(income, ID_NET_INCOME_NONCONTROLLING, "account_id", field);
            if (periodNet == null || periodMinority == null) {
                return null;
            }
            confirmed.add(periodNet.subtract(periodMinority).stripTrailingZeros());
        }
        // 그 칸에 비지배 흔적이 없으면(비지배 손익 줄이 비었거나 0 · 다른 표준 비지배 손익 줄 0 · 자본변동표 비지배 열 0) 당기순이익이 곧 지배 몫이다 —
        // 판 단독 게이트(hasNoncontrollingTrace)와 같은 증거. LG이노텍 2022(표준 지배 줄 976.9B = 계속영업 몫 · 비지배 줄 값 없음 · 자본변동표 지배 열
        // 979.8B = 당기순이익): 녹화본 흔적 없는 판 중 표준 지배 줄 ≠ 당기순이익 19판 · 자본변동표 지배 열 = 당기순이익 13판.
        if (minority == null && minorities.stream().allMatch(n -> n.signum() == 0) && near(net, statement)
                && !noncontrollingIn(income, cumulativeField) && !equityNoncontrolling(equityChanges, equityField)) {
            BigDecimal periodNet = findBy(income, ID_NET_INCOME, "account_id", field);
            if (periodNet == null) {
                return null;
            }
            confirmed.add(periodNet.stripTrailingZeros());
        }
        if (confirmed.isEmpty()) {
            return null;
        }
        BigDecimal first = confirmed.get(0);
        return confirmed.stream().allMatch(v -> near(v, first)) ? first : null;
    }

    /** 그 칸에 값이 0이 아닌 표준 비지배 손익 줄(귀속 · 포괄 · 계속 · 중단 몫)이 있는가. */
    private static boolean noncontrollingIn(List<JsonNode> income, String field) {
        return income.stream().anyMatch(row -> row.path("account_id").asString("").toLowerCase(java.util.Locale.ROOT).contains("noncontrolling")
                && nonzero(row, field));
    }

    /** 자본변동표 비지배 열(구성요소 축)에 그 칸 값이 0이 아닌 줄이 있는가. */
    private static boolean equityNoncontrolling(List<JsonNode> equityChanges, String equityField) {
        return equityChanges.stream().anyMatch(row -> row.path("account_detail").asString("").contains("비지배") && nonzero(row, equityField));
    }

    /** 자본변동표 지배 열(구성 열이 아닌 합계 열 하나)의 당기순이익 — 값이 하나가 아니면 {@code null}. */
    private static BigDecimal equityControllingProfit(List<JsonNode> equityChanges, String equityField) {
        java.util.Set<BigDecimal> values = new java.util.LinkedHashSet<>();
        for (JsonNode row : equityChanges) {
            if (!"ifrs-full_ProfitLoss".equals(row.path("account_id").asString("")) && !nonstandardProfitRow(row)) {
                continue;
            }
            String[] segments = row.path("account_detail").asString("").split("\\|");
            boolean owners = false;
            boolean minority = false;
            for (String segment : segments) {
                minority |= segment.contains("비지배");
                owners |= segment.contains("지배");
            }
            if (!owners || minority || segments.length > 2) {
                continue;
            }
            BigDecimal v = DartAccountMapper.parseAmount(row.path(equityField).asString(""));
            if (v != null) {
                values.add(v.stripTrailingZeros());
            }
        }
        return values.size() == 1 ? values.iterator().next() : null;
    }

    /** 자본변동표 비지배 열(구성 열 하나)의 당기순이익 — 값이 하나가 아니면 {@code null}. */
    private static BigDecimal equityMinorityProfit(List<JsonNode> equityChanges, String equityField) {
        java.util.Set<BigDecimal> values = new java.util.LinkedHashSet<>();
        for (JsonNode row : equityChanges) {
            if (!"ifrs-full_ProfitLoss".equals(row.path("account_id").asString("")) && !nonstandardProfitRow(row)) {
                continue;
            }
            String[] segments = row.path("account_detail").asString("").split("\\|");
            if (segments.length > 2 || java.util.Arrays.stream(segments).noneMatch(segment -> segment.contains("비지배"))) {
                continue;
            }
            BigDecimal v = DartAccountMapper.parseAmount(row.path(equityField).asString(""));
            if (v != null) {
                values.add(v.stripTrailingZeros());
            }
        }
        return values.size() == 1 ? values.iterator().next() : null;
    }

    /**
     * <b>「당기순이익 − 비지배」로 지은 지배 몫을 자본변동표가 반박하는가</b>(국내 관문 켬 · 계획 K8). 표준 지배 줄이 없고, 자본변동표 지배 · 비지배 열의
     * 당기순이익이 합해 당기순이익으로 닫히는데 나눔이 손익 비지배 줄과 다르면 참이다 — 같은 판 두 표가 지배 몫을 각자 말하고 셋째 증인이 없으니 그
     * 몫은 모른다(K19의 「한쪽만 믿지 않는다」와 같은 축 · 원장 둘이 같은 줄을 확인할 때만 고른다).
     *
     * <p>실측(녹화본 연결 8,462판 · 2026-11-11): 표준 지배 줄 없이 이 꼴인 판은 043260 2023 · 2024 FY 둘이다(손익 비지배 줄 410.7M은 비지배
     * 총포괄이익과 같고 자본변동표 비지배 열 114.8M · 지배 7,961.9M 대 산수 7,666.1M). 2023은 주당이익 364원 × 명부 주식수가 자본변동표를 지지하고
     * 2024는 명부 구간이 두 값을 다 품는다.
     */
    static boolean splitContradicted(List<JsonNode> income, List<JsonNode> equityChanges, String cumulativeField) {
        if (findBy(income, ID_NET_INCOME_CONTROLLING, "account_id", cumulativeField) != null) {
            return false;
        }
        BigDecimal net = findBy(income, ID_NET_INCOME, "account_id", cumulativeField);
        BigDecimal minority = findBy(income, ID_NET_INCOME_NONCONTROLLING, "account_id", cumulativeField);
        BigDecimal owners = equityControllingProfit(equityChanges, "thstrm_amount");
        BigDecimal minorityColumn = equityMinorityProfit(equityChanges, "thstrm_amount");
        if (net == null || minority == null || owners == null || minorityColumn == null) {
            return false;
        }
        return near(owners.add(minorityColumn), net) && !near(net.subtract(minority), owners);
    }

    /**
     * 표준계정코드 없이 실린 자본변동표 순이익 줄 — 이름(공백 뺀)이 순이익 · 순손익 · 순손실이고 포괄 · 평가 줄이 아니다(녹화본 연결 판의 지배 열에서 센
     * 이름: 당기순이익 160 · 당기순이익(손실) 127 · 분기순이익 106 · 반기순이익 51 · 연결당기순이익 36 · 「딩기순이익(손실)」 오타 13 …). 이름만으로 받는
     * 것이 아니다 — 그 값이 손익의 다른 줄과 같을 때만 쓰인다({@link #confirmedControlling}).
     */
    private static boolean nonstandardProfitRow(JsonNode row) {
        if (!row.path("account_id").asString("").contains("표준계정코드")) {
            return false;
        }
        String name = row.path("account_nm").asString("").replace(" ", "");
        return (name.contains("순이익") || name.contains("순손익") || name.contains("순손실")) && !name.contains("포괄") && !name.contains("평가");
    }

    /** 1천 원 반올림 안(천 원 단위로 낸 판). */
    private static boolean near(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(THOUSAND) <= 0;
    }

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    /** 같은 보고서의 당기순이익(연결) — {@link #controllingIncome}이 피감수로 쓰는 것과 같은 찾기. */
    static BigDecimal consolidatedIncome(List<JsonNode> income) {
        return find(income, ID_NET_INCOME, DartAccountMapper.NET_INCOME);
    }

    /**
     * <b>손익 비지배 행이 이 응답에 있는가.</b> 값이 아니라 <b>행의 존재</b>를 묻는다 —
     * 비지배가 정확히 0인 회사도 행은 내고, 그것이 「비지배를 낸 적 있다」의 증거다.
     */
    /**
     * <b>이 보고서가 비지배를 냈는가</b>(국내 관문 켬의 「비지배를 낸 적 있다」 증거). 당기순이익 비지배 줄은 원래대로 존재만으로(0이어도 그 줄을
     * 내는 것이 비지배가 있다는 표시다), 그 밖의 표준 비지배 귀속 줄(계속 · 중단영업 몫 · 총포괄이익 귀속)은 값이 0이 아닐 때. 당기순이익 귀속 줄만
     * 보면 비지배를 계속 몫으로만 싣는 회사(솔브레인홀딩스 2025 3분기 비지배 계속 1.87B)에서 게이트가 열려 연결 총액이 지배주주 순이익이 된다 ·
     * 존재만 보면 비지배가 없는데 총포괄 비지배 줄을 0으로 싣는 회사(031330 · 지배 자본 = 총자본)에서 게이트가 닫힌다. 표준계정코드 없는 줄의 이름은
     * 증거로 안 쓴다 — 089970 2025 3분기는 「비지배지분」 이름 줄에 당기순이익과 같은 값을 실었고(지배 자본 = 총자본) 그 한 줄이 앞 기간 전부의
     * 게이트를 닫았다(녹화본 스윕 30칸).
     */
    static boolean hasAnyNoncontrollingIncome(List<JsonNode> income) {
        if (hasNoncontrollingIncome(income)) {
            return true;
        }
        for (JsonNode row : income) {
            String id = row.path("account_id").asString("");
            boolean line = id.contains("NoncontrollingInterests");
            BigDecimal value = line ? DartAccountMapper.parseAmount(row.path("thstrm_amount").asString("")) : null;
            if (value != null && value.signum() != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>이 판 하나에 비지배의 흔적이 있는가</b>(국내 관문 켬 · 계획 K9 · K9b · K10) — 표준 비지배 계정(손익 · 포괄 · 재무상태표 · 현금흐름 어디든)이나
     * 자본변동표 비지배 열(구성요소 축)의 당기 값이 0이 아니거나, 재무상태표 비지배지분 전기말이 0이 아니면 참이다. 게이트를 이 판 하나로 연다 —
     * 「그 기간 뒤 비지배」로 물으면 뒤에 생긴 진짜 비지배가 앞 기간 전부를 닫고(033790 · 253590 · 319660 · K9b) 답이 호출이 받은 기간 집합에
     * 따라 갈린다(FY만 부른 관문 FSR과 전 분기 정기 FSR · K10).
     *
     * <p>「비지배 줄을 생략하는 회사가 있다」는 옛 사유를 녹화본(11-01 · 연결 판 8,462)으로 쟀다: 게이트가 물리는 판(지배 · 비지배 손익 표준 줄
     * 없음) 1,032 중 이 술어로 새로 열리는 169판에서 다른 판 재무상태표가 그 기간 비지배 ≠ 0을 실은 것 0 · 뒤 해 같은 보고서 전기 열이 비지배 ≠ 0을
     * 실은 것 0/161 · 같은 해 FY 손익만 비지배를 실은 13판은 전부 그 해 중간에 비지배가 처음 생긴 회사였다(FY 자본변동표 비지배 기초 0 · 취득 ·
     * 종속기업 증자). 값이 0인 줄은 증거가 아니다(171090 · K9). 표준계정코드 없는 줄의 이름은 증거로 안 쓴다 — 089970 2025 3분기는 「비지배지분」
     * 이름 줄에 당기순이익과 같은 값을 실었다(지배 자본 = 총자본).
     */
    static boolean hasNoncontrollingTrace(JsonNode list) {
        for (JsonNode row : list) {
            String sj = row.path("sj_div").asString("");
            boolean marked = row.path("account_id").asString("").toLowerCase(java.util.Locale.ROOT).contains("noncontrolling")
                    || SJ_EQUITY_CHANGES.equals(sj) && row.path("account_detail").asString("").contains("비지배");
            if (marked && (nonzero(row, "thstrm_amount") || SJ_BALANCE.equals(sj) && nonzero(row, "frmtrm_amount"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean nonzero(JsonNode row, String field) {
        BigDecimal value = DartAccountMapper.parseAmount(row.path(field).asString(""));
        return value != null && value.signum() != 0;
    }

    static boolean hasNoncontrollingIncome(List<JsonNode> income) {
        for (JsonNode row : income) {
            if (ID_NET_INCOME_NONCONTROLLING.contains(row.path("account_id").asString(""))) {
                return true;
            }
        }
        return false;
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
        return findBy(rows, candidates, field, "thstrm_amount");
    }

    /** {@code amountField}로 금액 칸을 고르는 판 — 누계({@code thstrm_add_amount})를 읽는 자리(F2b Q4)가 쓴다. */
    static BigDecimal findBy(List<JsonNode> rows, Set<String> candidates, String field, String amountField) {
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
                    matches.get(0).path(amountField).asString(""));
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
