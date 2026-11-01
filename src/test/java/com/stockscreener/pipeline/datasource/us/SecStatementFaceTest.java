package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공시 본문 개념(재고 「부문 합계가 영업이익 자리에 앉음」) — 모양은 2026-11-01 탐침에서 옮겼다: SHW는 영업이익이 부문 주석 역할에만 · MSFT는
 * 손익계산서 역할에 · 옛 판(MSFT 2015)은 FilingSummary에 분류가 없고 역할 정의가 「- Statement -」다.
 */
class SecStatementFaceTest {

    @Test
    @DisplayName("MetaLinks — statement 보고서 역할에 실린 개념만 · 부문 주석(disclosure)에만 있으면 본문에 없다")
    void metaLinksKeepsStatementRolesOnly() throws Exception {
        var meta = new ObjectMapper().readTree("""
                {"instance": {"x.htm": {
                  "report": {"R4": {"role": "http://x/role/IncomeStatement", "groupType": "statement"},
                             "R40": {"role": "http://x/role/SegmentDetails", "groupType": "disclosure"}},
                  "tag": {"us-gaap_Revenues": {"presentation": ["http://x/role/IncomeStatement", "http://x/role/SegmentDetails"]},
                          "us-gaap_OperatingIncomeLoss": {"presentation": ["http://x/role/SegmentDetails"]}}}}}""");
        assertThat(SecStatementFace.fromMetaLinks(meta)).containsExactly("us-gaap_Revenues");
    }

    @Test
    @DisplayName("링크베이스 — FilingSummary의 MenuCategory가 있으면 그것이 · 없는 옛 판은 역할 정의(「- Statement -」)가 본문을 정한다")
    void linkbaseUsesMenuCategoryOrRoleDefinitions() {
        String presentation = """
                <link:linkbase xmlns:link="http://www.xbrl.org/2003/linkbase" xmlns:xlink="http://www.w3.org/1999/xlink">
                  <link:presentationLink xlink:type="extended" xlink:role="http://x/role/StatementOfIncome"
                      xlink:title="124000 - Statement - Statement of Income">
                    <link:loc xlink:type="locator" xlink:href="us-gaap.xsd#us-gaap_OperatingIncomeLoss" xlink:label="a"/>
                  </link:presentationLink>
                  <link:presentationLink xlink:type="extended" xlink:role="http://x/role/Segments"
                      xlink:title="400000 - Disclosure - Segments (Details)">
                    <link:loc xlink:type="locator" xlink:href="us-gaap.xsd#us-gaap_Revenues" xlink:label="b"/>
                  </link:presentationLink>
                </link:linkbase>""";
        String oldSummary = "<FilingSummary><MyReports><Report><Role>http://x/role/StatementOfIncome</Role></Report></MyReports></FilingSummary>";
        assertThat(SecStatementFace.fromLinkbase(oldSummary, presentation, null)).containsExactly("us-gaap_OperatingIncomeLoss");

        String categorised = """
                <FilingSummary><MyReports>
                  <Report><MenuCategory>Details</MenuCategory><Role>http://x/role/StatementOfIncome</Role></Report>
                  <Report><MenuCategory>Statements</MenuCategory><Role>http://x/role/Segments</Role></Report>
                </MyReports></FilingSummary>""";
        assertThat(SecStatementFace.fromLinkbase(categorised, presentation, null)).as("분류가 있으면 정의보다 분류").containsExactly("us-gaap_Revenues");
    }

    @Test
    @DisplayName("본문 재료 — 아는 공시만 답한다(모르면 null)")
    void facesAnswerOnlyForKnownFilings() {
        var faces = new SecStatementFaceCollector.SecStatementFaces(java.util.Map.of("a", new SecStatementFaceCollector.Face(
                java.util.Set.of("us-gaap_Revenues"),
                java.util.Set.of(SecStatementFaceCollector.Face.key("20220331", 1, new java.math.BigDecimal("124437000.0000"))))));
        assertThat(faces.carries("a", SecEdgarFacts.OPERATING_INCOME_ON_FACE)).isFalse();
        assertThat(faces.onIncomeStatement("a", "10-Q", java.util.Set.of("us-gaap_Revenues"), "20220331", 1, new java.math.BigDecimal("5")))
                .as("값 재료가 있으면 개념 이름이 본문에 있어도 값이 본문 줄 값과 달라 본문 줄이 아니다(GE 주석의 3개월 Revenues)").isFalse();
        assertThat(faces.carries("b", SecEdgarFacts.OPERATING_INCOME_ON_FACE)).isNull();
        assertThat(faces.onIncomeStatement("a", "10-Q", SecEdgarFacts.OPERATING_INCOME_ON_FACE, "20220331", 1, new java.math.BigDecimal("124437000")))
                .as("다른 개념으로 단 본문 줄과 값이 같으면 본문의 줄이다(SSD 「Income from operations」)").isTrue();
        assertThat(faces.onIncomeStatement("a", "10-Q", SecEdgarFacts.OPERATING_INCOME_ON_FACE, "20220331", 1, new java.math.BigDecimal("130000000")))
                .isFalse();
        assertThat(faces.onIncomeStatement("a", "10-Q", SecEdgarFacts.OPERATING_INCOME_ON_FACE, "20211231", 1, new java.math.BigDecimal("1")))
                .as("본문이 싣지 않는 기간(그 공시 본문 값에 없는 기간)은 판정하지 않는다").isNull();
    }

    @Test
    @DisplayName("데이터셋 pre.txt — 표준 택소노미만 접두를 붙여 내고 회사 확장 · 표지(CP)는 안 낸다")
    void dataSetConceptNames() {
        assertThat(SecStatementFace.dataSetConcept("us-gaap/2022", "OperatingIncomeLoss", "IS")).isEqualTo("us-gaap_OperatingIncomeLoss");
        assertThat(SecStatementFace.dataSetConcept("ifrs/2022", "ProfitLossFromOperatingActivities", "IS"))
                .isEqualTo("ifrs-full_ProfitLossFromOperatingActivities");
        assertThat(SecStatementFace.dataSetConcept("0000089800-23-000007", "SegmentProfit", "IS")).isNull();
        assertThat(SecStatementFace.dataSetConcept("dei/2022", "EntityCommonStockSharesOutstanding", "CP")).isNull();
    }

    @Test
    @DisplayName("손익계산서가 재료에 있는가 — IS의 순이익 · 주당이익 · 합본(CI · UN)의 주당이익 · 기타포괄손익표의 순이익은 아니다")
    void finalIncomeLines() {
        assertThat(SecStatementFace.finalIncomeLine("IS", "NetIncomeLoss")).isTrue();
        assertThat(SecStatementFace.finalIncomeLine("CI", "EarningsPerShareBasic")).isTrue();
        assertThat(SecStatementFace.finalIncomeLine("CI", "NetIncomeLoss")).as("INTU FY2024: CI만").isFalse();
        assertThat(SecStatementFace.finalIncomeLine("IS", "GoodwillAndIntangibleAssetImpairment")).as("CCL FY2019: IS 한 줄").isFalse();
    }
}
