package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.indicator.GovernanceMetric;
import com.stockscreener.pipeline.datasource.GovernanceEndpoint;
import com.stockscreener.pipeline.datasource.GovernanceFact;
import com.stockscreener.pipeline.datasource.GovernanceReportRow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유도 규칙을 <b>실측 응답</b>으로 검증한다.
 *
 * <p>값이 전부 {@code docs/api-spec/dart-open-api.md} §16-A·§16-B·§16-C에서 온 것이다.
 * 합성 데이터를 쓰지 않는 이유는 그것이 <b>계산 구조만 검증하고 소스 해석의 오류는 못 잡기</b>
 * 때문이다 — 이 매퍼의 위험은 산수가 아니라 「어느 행을 고르는가」에 있다.
 */
class DartGovernanceFactMapperTest {

    private static final LocalDate PUBLISHED = LocalDate.of(2026, 3, 10);
    private static final String RECEIPT = "20260310002820";

    private final DartGovernanceFactMapper mapper = new DartGovernanceFactMapper();

    @Test
    @DisplayName("주식의 총수 — 보통주 행만 쓰고 합계·비고는 버린다 (삼성전자 2025 실측)")
    void shareTotalsUsesCommonRowOnly() {
        List<GovernanceReportRow> rows = List.of(
                shareRow("보통주", "7,780,466,850", "1,860,828,928", "5,919,637,922",
                        "91,828,987", "1,860,828,928"),
                shareRow("우선주", "1,194,671,350", "378,696,686", "815,974,664",
                        "13,603,461", "378,696,686"),
                shareRow("합계", "8,975,138,200", "2,239,525,614", "6,735,612,586",
                        "105,432,448", "2,239,525,614"),
                // 비고 행은 숫자 자리에 문자열을 싣고 온다. 파서에 그대로 넘기면 그 행에서 터진다.
                shareRow("비고", "-", "-", "-", "-", "자사주소각"));

        Map<GovernanceMetric, BigDecimal> facts = index(
                mapper.derive(GovernanceEndpoint.SHARE_TOTALS, rows));

        assertThat(facts.get(GovernanceMetric.ISSUED_SHARES_COMMON))
                .as("합계 6,735,612,586이 아니라 보통주 5,919,637,922여야 한다 — 합계를 쓰면 "
                        + "listed_shares 기반 기존 지표와 분모가 갈린다")
                .isEqualByComparingTo("5919637922");
        assertThat(facts.get(GovernanceMetric.TREASURY_SHARES_COMMON))
                .isEqualByComparingTo("91828987");
        assertThat(facts.get(GovernanceMetric.PROFIT_RETIRED_CUM))
                .as("상장 이래 누적이다. 차분은 지표가 낸다")
                .isEqualByComparingTo("1860828928");
    }

    @Test
    @DisplayName("배당 — 3개년이 열로 오고 총액은 백만원을 원으로 맞춘다 (삼성전자 2025 실측)")
    void dividendSpreadsColumnsAndNormalizesUnit() {
        List<GovernanceReportRow> rows = List.of(
                dividendRow("주당 현금배당금(원)", "보통주", "1,668", "1,446", "1,444"),
                dividendRow("주당 현금배당금(원)", "우선주", "1,669", "1,447", "1,445"),
                dividendRow("현금배당금총액(백만원)", "-", "11,107,906", "9,810,767", "9,809,438"),
                dividendRow("(연결)현금배당성향(%)", "-", "25.10", "29.20", "67.80"));

        List<GovernanceFact> facts = mapper.derive(GovernanceEndpoint.DIVIDEND, rows);

        assertThat(pick(facts, 2025, GovernanceMetric.DPS_COMMON))
                .as("우선주 1,669가 아니라 보통주 1,668이어야 한다")
                .isEqualByComparingTo("1668");
        assertThat(pick(facts, 2024, GovernanceMetric.DPS_COMMON)).isEqualByComparingTo("1446");
        assertThat(pick(facts, 2023, GovernanceMetric.DPS_COMMON)).isEqualByComparingTo("1444");
        assertThat(pick(facts, 2025, GovernanceMetric.CASH_DIVIDEND_TOTAL))
                .as("소스는 백만원이다. 원으로 안 맞추면 배당성향이 100만분의 1이 된다")
                .isEqualByComparingTo("11107906000000");
        assertThat(facts).as("현금배당성향은 소스가 계산한 값이라 담지 않는다(spec §7)")
                .noneMatch(fact -> fact.value().compareTo(new BigDecimal("25.10")) == 0);
    }

    @Test
    @DisplayName("최대주주 — 이름에 '계'가 든 주주를 버리지 않는다 (삼성전자 2025 실측)")
    void largestHolderKeepsNamesContainingTheTotalWord() {
        List<GovernanceReportRow> rows = List.of(
                holderRow("삼성생명보험㈜", "최대주주 본인", "보통주", "503,904,843"),
                holderRow("홍라희", "최대주주의 특수관계인", "보통주", "87,978,700"),
                holderRow("홍라희", "최대주주의 특수관계인", "우선주", "206,633"),
                holderRow("이재용", "최대주주의 특수관계인", "보통주", "97,414,196"),
                holderRow("이재용", "최대주주의 특수관계인", "우선주", "137,757"),
                holderRow("이부진", "계열회사 임원", "보통주", "41,745,681"),
                holderRow("이서현", "계열회사 임원", "보통주", "45,574,190"),
                holderRow("전영현", "계열회사 임원", "보통주", "17,000"),
                holderRow("노태문", "계열회사 임원", "보통주", "50,679"),
                holderRow("송재혁", "계열회사 임원", "보통주", "13,373"),
                holderRow("조혜경", "계열회사 임원", "보통주", "500"),
                holderRow("이혁재", "계열회사 임원", "보통주", "200"),
                holderRow("한종희", "계열회사 임원", "보통주", "0"),
                // IMPORTANT — 이름 안에 '계'가 있다(특별계정). contains로 '계' 행을 고르면
                // 이 주주가 통째로 빠지고 합이 4,424,328주 모자란다.
                holderRow("삼성생명보험㈜ (특별계정)", "최대주주 본인", "보통주", "4,424,328"),
                holderRow("삼성물산㈜", "계열회사", "보통주", "298,818,100"),
                holderRow("삼성화재해상보험㈜", "계열회사", "보통주", "88,058,948"),
                holderRow("삼성복지재단", "출연 재단", "보통주", "4,484,150"),
                holderRow("삼성문화재단", "출연 재단", "보통주", "1,880,750"),
                holderRow("삼성생명공익재단", "출연 재단", "보통주", "1,250"),
                holderRow("계", "", "보통주", "1,174,366,888"),
                holderRow("계", "", "우선주", "823,776"));

        Map<GovernanceMetric, BigDecimal> facts = index(
                mapper.derive(GovernanceEndpoint.LARGEST_HOLDER, rows));

        assertThat(facts.get(GovernanceMetric.LARGEST_HOLDER_SHARES_COMMON))
                .as("소스의 '계' 보통주 행과 정확히 같아야 한다. 계 행을 안 빼면 두 배가 되고, "
                        + "contains로 빼면 특별계정 4,424,328주가 사라진다")
                .isEqualByComparingTo("1174366888");
    }

    @Test
    @DisplayName("최대주주 — 회계연도를 갈라 센다 (안 가르면 두 해가 합쳐져 지분이 두 배가 된다)")
    void largestHolderSplitsByFiscalYear() {
        List<GovernanceReportRow> rows = List.of(
                holderRow(2025, "정용진", "최대주주 본인", "보통주", "7,960,493"),
                holderRow(2025, "한채양", "대표이사", "보통주", "400"),
                holderRow(2025, "계", "", "보통주", "7,960,893"),
                holderRow(2024, "정용진", "최대주주 본인", "보통주", "5,000,000"),
                holderRow(2024, "계", "", "보통주", "5,000,000"));

        List<GovernanceFact> facts = mapper.derive(GovernanceEndpoint.LARGEST_HOLDER, rows);

        assertThat(pick(facts, 2025, GovernanceMetric.LARGEST_HOLDER_SHARES_COMMON))
                .as("두 해를 한 목록으로 받는다. 안 가르면 12,960,893이 되고 값이 그럴듯해 안 드러난다")
                .isEqualByComparingTo("7960893");
        assertThat(pick(facts, 2024, GovernanceMetric.LARGEST_HOLDER_SHARES_COMMON))
                .isEqualByComparingTo("5000000");
    }

    @Test
    @DisplayName("최대주주 — 주식 종류가 '의결권 있는 주식'인 회사도 보통주 축이다 (오리온 2025 실측)")
    void largestHolderAcceptsVotingShareVocabulary() {
        List<GovernanceReportRow> rows = List.of(
                holderRow("(주)오리온홀딩스", "본인", "의결권 있는 주식", "14,775,139"),
                holderRow("김영훈", "임원", "의결권 있는 주식", "2,500"),
                holderRow("이화경", "임원", "의결권 있는 주식", "1,613,553"),
                holderRow("담철곤", "임원", "의결권 있는 주식", "177,670"),
                holderRow("담경선", "특수관계인", "의결권 있는 주식", "238,997"),
                holderRow("담서원", "계열회사임원", "의결권 있는 주식", "486,909"),
                holderRow("이원우", "특수관계인", "의결권 있는 주식", "14,000"),
                holderRow("허인철", "임원", "의결권 있는 주식", "6,400"),
                holderRow("신호정", "계열회사임원", "의결권 있는 주식", "65"),
                holderRow("이서연", "특수관계인", "의결권 있는 주식", "6,000"),
                holderRow("계", "", "의결권 있는 주식", "17,321,233"),
                holderRow("계", "", "-", "-"));

        Map<GovernanceMetric, BigDecimal> facts = index(
                mapper.derive(GovernanceEndpoint.LARGEST_HOLDER, rows));

        assertThat(facts.get(GovernanceMetric.LARGEST_HOLDER_SHARES_COMMON))
                .as("'보통주'만 받으면 이 회사가 통째로 빠진다 — 어휘가 넷이다")
                .isEqualByComparingTo("17321233");
    }

    @Test
    @DisplayName("사외이사 — 값이 있는 행의 '-'는 0명이다 (삼성전자 2025 실측)")
    void outsideDirectorTreatsDashAsZeroWhenRowIsPopulated() {
        List<GovernanceReportRow> rows = List.of(new GovernanceReportRow(
                GovernanceEndpoint.OUTSIDE_DIRECTOR, 2025, "11011", RECEIPT, PUBLISHED, 0,
                fields("drctr_co", "9", "otcmp_drctr_co", "6", "apnt", "4",
                        "rlsofc", "-", "mdstrm_resig", "2")));

        Map<GovernanceMetric, BigDecimal> facts = index(
                mapper.derive(GovernanceEndpoint.OUTSIDE_DIRECTOR, rows));

        assertThat(facts.get(GovernanceMetric.DIRECTOR_COUNT)).isEqualByComparingTo("9");
        assertThat(facts.get(GovernanceMetric.OUTSIDE_DIRECTOR_COUNT)).isEqualByComparingTo("6");
        assertThat(facts.get(GovernanceMetric.DIRECTOR_DISMISSED))
                .as("해임이 8/8에서 '-'인데 이사 수는 다 있다 — 결손으로 세면 축이 통째로 판정불가가 된다")
                .isEqualByComparingTo("0");
        assertThat(facts.get(GovernanceMetric.DIRECTOR_RESIGNED)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("소액주주 — 분자와 분모를 같은 행에서 가져온다 (삼성전자 2025 실측)")
    void minorityHolderTakesBothFromSameRow() {
        List<GovernanceReportRow> rows = List.of(new GovernanceReportRow(
                GovernanceEndpoint.MINORITY_HOLDER, 2025, "11011", RECEIPT, PUBLISHED, 0,
                fields("se", "소액주주", "shrholdr_co", "4,195,927",
                        "hold_stock_co", "3,909,148,867", "stock_tot_co", "5,919,637,922")));

        Map<GovernanceMetric, BigDecimal> facts = index(
                mapper.derive(GovernanceEndpoint.MINORITY_HOLDER, rows));

        assertThat(facts.get(GovernanceMetric.MINORITY_SHARES))
                .isEqualByComparingTo("3909148867");
        assertThat(facts.get(GovernanceMetric.MINORITY_TOTAL_SHARES))
                .as("보통주 축이다 — 합계 67.4억이 아니다")
                .isEqualByComparingTo("5919637922");
    }

    @Test
    @DisplayName("최대주주 변동 — 행 수가 아니라 이름이 바뀐 횟수를 센다 (KB금융 2025 실측)")
    void controlChangeCountsNameTransitionsNotRows() {
        List<GovernanceReportRow> rows = List.of(
                changeRow("2023년 12월 31일", "국민연금공단", "장내매매"),
                changeRow("2024년 06월 30일", "국민연금공단", "장내매매"),
                changeRow("2025년 03월 31일", "국민연금공단", "장내매매"),
                changeRow("2025년 12월 31일", "국민연금공단", "장내매매"));

        Map<GovernanceMetric, BigDecimal> facts = index(
                mapper.derive(GovernanceEndpoint.CONTROL_CHANGE, rows));

        assertThat(facts.get(GovernanceMetric.CONTROL_TRANSFERS_3Y))
                .as("28행이 전부 같은 최대주주였다. 행 수를 세면 연기금이 매 분기 사고파는 회사가 "
                        + "「자주 바뀌는 회사」가 된다")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("최대주주 변동 — 창 밖 전이는 안 세고 창 첫 전이는 센다")
    void controlChangeRespectsTheWindow() {
        List<GovernanceReportRow> rows = List.of(
                changeRow("2019년 05월 01일", "갑"),
                // 창(2023-01-01) 밖의 전이다. 세면 3년 창이 뜻을 잃는다.
                changeRow("2020년 05월 01일", "을"),
                // 창 첫 사건이지만 직전 이름이 '을'이라 전이다. 기준선을 창 안에서 잡으면 놓친다.
                changeRow("2024년 05월 01일", "병"),
                changeRow("2025년 05월 01일", "정"));

        Map<GovernanceMetric, BigDecimal> facts = index(
                mapper.derive(GovernanceEndpoint.CONTROL_CHANGE, rows));

        assertThat(facts.get(GovernanceMetric.CONTROL_TRANSFERS_3Y)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("최대주주 변동 — 데이터 없음 행도 「변동 0」이라는 사실이다")
    void controlChangeTreatsNoDataAsZero() {
        List<GovernanceReportRow> rows = List.of(
                GovernanceReportRow.noData(GovernanceEndpoint.CONTROL_CHANGE, 2025, "11011")
                        .stampedWith(RECEIPT, PUBLISHED));

        Map<GovernanceMetric, BigDecimal> facts = index(
                mapper.derive(GovernanceEndpoint.CONTROL_CHANGE, rows));

        assertThat(facts.get(GovernanceMetric.CONTROL_TRANSFERS_3Y))
                .as("표본 여덟 중 넷이 이 형태다. 값을 안 내면 그 넷이 조건에서 통째로 탈락한다")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("감사의견 — 기수를 회계연도로 옮기고 강조사항을 OR로 접는다 (오리온 2025 실측)")
    void auditOpinionMapsTermsAndFoldsEmphasis() {
        List<GovernanceReportRow> rows = List.of(
                auditRow("제9기 \n(당기)", "삼일회계법인", "해당사항 없음", "종속기업투자인 Orion"),
                auditRow("제9기\n(당기)", "삼일회계법인", "해당사항 없음", "Orion Nutritionals"),
                auditRow("제8기\n (전기)", "삼정회계법인", "재무제표 재작성", "재화의 판매"),
                auditRow("제8기\n(전기)", "삼정회계법인", "해당사항 없음", "재화의 판매"),
                auditRow("제7기\n(전전기)", "삼정회계법인", "해당사항 없음", "재화의 판매"),
                auditRow("제7기\n(전전기)", "삼정회계법인", "해당사항 없음", "재화의 판매"));

        List<GovernanceFact> facts = mapper.derive(GovernanceEndpoint.AUDIT_OPINION, rows);

        assertThat(pick(facts, 2024, GovernanceMetric.AUDIT_EMPHASIS_PRESENT))
                .as("한 해의 두 행 중 하나만 '재무제표 재작성'이다 — OR로 접지 않으면 놓친다")
                .isEqualByComparingTo("1");
        assertThat(pick(facts, 2025, GovernanceMetric.AUDIT_EMPHASIS_PRESENT))
                .as("핵심감사사항은 48/48이 값을 가져 세면 전 종목 1인 상수가 된다")
                .isEqualByComparingTo("0");
        assertThat(pick(facts, 2025, GovernanceMetric.AUDITOR_CHANGED))
                .as("삼정 → 삼일이다")
                .isEqualByComparingTo("1");
        assertThat(pick(facts, 2024, GovernanceMetric.AUDITOR_CHANGED))
                .as("전기와 전전기가 둘 다 삼정이다")
                .isEqualByComparingTo("0");
        assertThat(facts)
                .as("전전기는 그 앞을 모르므로 「안 바뀌었다」가 아니라 값을 내지 않는다")
                .noneMatch(fact -> fact.fiscalYear() == 2023
                        && fact.metric() == GovernanceMetric.AUDITOR_CHANGED);
    }

    @Test
    @DisplayName("감사의견 — 관측된 비적정을 잡고, 목록 밖은 「적정」이 아니라 판정불가다")
    void auditOpinionUsesAnAllowlistInBothDirections() {
        // 실측: 044490 태웅 FY2020.
        assertThat(opinionOf("감사범위 제한으로\n인한 한정"))
                .as("활성 유니버스 안에 실재하는 비적정이다")
                .isEqualByComparingTo("1");
        assertThat(opinionOf("적정의견")).isEqualByComparingTo("0");
        assertThat(opinionOf("적 정"))
                .as("공백이 낀 표기가 15행 있다. 정규화하지 않으면 판정불가가 된다")
                .isEqualByComparingTo("0");

        assertThat(opinionOf("부적정의견"))
                .as("IMPORTANT — contains(\"적정\")로 물으면 이것이 「적정」으로 분류된다. "
                        + "한 글자가 가장 위험한 종목을 통과시킨다")
                .isNull();
        assertThat(opinionOf("(주1)"))
                .as("각주 기호가 실제로 온다. 「적정」으로 접지 않는다")
                .isNull();
        assertThat(opinionOf(""))
                .as("빈 문자열이 185행이다. 없는 값을 0으로 만들지 않는다")
                .isNull();
    }

    @Test
    @DisplayName("감사의견 — 한 해의 두 행 중 하나만 비적정이어도 비적정이다")
    void auditOpinionFoldsTwoRowsWithOr() {
        List<GovernanceReportRow> rows = List.of(
                auditOpinionRow("제10기\n(당기)", "적정의견"),
                auditOpinionRow("제10기\n(당기)", "감사범위 제한으로 인한 한정"));

        List<GovernanceFact> facts = mapper.derive(GovernanceEndpoint.AUDIT_OPINION, rows);

        assertThat(pick(facts, 2025, GovernanceMetric.AUDIT_OPINION_ADVERSE))
                .as("두 행이 연결·별도로 보이나 필드로 구분되지 않는다 — 안전한 쪽으로 접는다")
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("강조사항 — '-'로 시작하는 글머리표는 센티널이 아니다")
    void emphasisBulletIsNotASentinel() {
        List<GovernanceReportRow> rows = List.of(
                auditRow("제10기\n(당기)", "삼정회계법인",
                        "- 증권선물위원회의 조치에 대한 행정소송", "핵심감사사항"));

        List<GovernanceFact> facts = mapper.derive(GovernanceEndpoint.AUDIT_OPINION, rows);

        assertThat(pick(facts, 2025, GovernanceMetric.AUDIT_EMPHASIS_PRESENT))
                .as("실측에 이 표기가 6행 있다. 앞 글자만 보고 센티널로 접으면 강조사항이 사라진다")
                .isEqualByComparingTo("1");
    }

    // --- 픽스처 ------------------------------------------------------------

    private static GovernanceReportRow shareRow(String se, String issuedCum, String decreased,
                                                String issued, String treasury, String retired) {
        return new GovernanceReportRow(GovernanceEndpoint.SHARE_TOTALS, 2025, "11011",
                RECEIPT, PUBLISHED, 0,
                fields("se", se, "now_to_isu_stock_totqy", issuedCum,
                        "now_to_dcrs_stock_totqy", decreased, "istc_totqy", issued,
                        "tesstk_co", treasury, "profit_incnr", retired));
    }

    private static GovernanceReportRow dividendRow(String se, String kind, String thstrm,
                                                   String frmtrm, String lwfr) {
        return new GovernanceReportRow(GovernanceEndpoint.DIVIDEND, 2025, "11011",
                RECEIPT, PUBLISHED, 0,
                fields("se", se, "stock_knd", kind, "thstrm", thstrm, "frmtrm", frmtrm,
                        "lwfr", lwfr));
    }

    private static GovernanceReportRow holderRow(String nm, String relate, String kind,
                                                 String shares) {
        return holderRow(2025, nm, relate, kind, shares);
    }

    private static GovernanceReportRow holderRow(int bsnsYear, String nm, String relate,
                                                 String kind, String shares) {
        return new GovernanceReportRow(GovernanceEndpoint.LARGEST_HOLDER, bsnsYear, "11011",
                RECEIPT, PUBLISHED, 0,
                fields("nm", nm, "relate", relate, "stock_knd", kind,
                        "trmend_posesn_stock_co", shares));
    }

    private static GovernanceReportRow changeRow(String changedOn, String holder) {
        return changeRow(changedOn, holder, "장내매매");
    }

    private static GovernanceReportRow changeRow(String changedOn, String holder, String cause) {
        return new GovernanceReportRow(GovernanceEndpoint.CONTROL_CHANGE, 2025, "11011",
                RECEIPT, PUBLISHED, 0,
                fields("change_on", changedOn, "mxmm_shrholdr_nm", holder,
                        "change_cause", cause));
    }

    private static GovernanceReportRow auditRow(String term, String auditor, String emphasis,
                                                String keyMatter) {
        return new GovernanceReportRow(GovernanceEndpoint.AUDIT_OPINION, 2025, "11011",
                RECEIPT, PUBLISHED, 0,
                fields("bsns_year", term, "adtor", auditor, "adt_opinion", "적정의견",
                        "emphs_matter", emphasis, "core_adt_matter", keyMatter));
    }

    private static BigDecimal opinionOf(String opinion) {
        return pick(mapper().derive(GovernanceEndpoint.AUDIT_OPINION,
                        List.of(auditOpinionRow("제10기\n(당기)", opinion))),
                2025, GovernanceMetric.AUDIT_OPINION_ADVERSE);
    }

    private static DartGovernanceFactMapper mapper() {
        return new DartGovernanceFactMapper();
    }

    private static GovernanceReportRow auditOpinionRow(String term, String opinion) {
        return new GovernanceReportRow(GovernanceEndpoint.AUDIT_OPINION, 2025, "11011",
                RECEIPT, PUBLISHED, 0,
                fields("bsns_year", term, "adtor", "삼정회계법인", "adt_opinion", opinion,
                        "emphs_matter", "해당사항 없음", "core_adt_matter", "핵심감사사항"));
    }

    private static Map<String, String> fields(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<GovernanceMetric, BigDecimal> index(List<GovernanceFact> facts) {
        Map<GovernanceMetric, BigDecimal> byMetric = new LinkedHashMap<>();
        facts.forEach(fact -> byMetric.put(fact.metric(), fact.value()));
        return byMetric;
    }

    private static BigDecimal pick(List<GovernanceFact> facts, int fiscalYear,
                                   GovernanceMetric metric) {
        return facts.stream()
                .filter(fact -> fact.fiscalYear() == fiscalYear && fact.metric() == metric)
                .map(GovernanceFact::value)
                .findFirst()
                .orElse(null);
    }
}
