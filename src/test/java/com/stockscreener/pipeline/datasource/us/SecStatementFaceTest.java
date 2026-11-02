package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.stockscreener.pipeline.datasource.DataSourceException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    @DisplayName("MetaLinks.json이 JSON으로 안 읽히면 같은 폴더의 링크베이스로 읽는다 — 종목 전체가 던지지 않는다(LOOP · BBAR 2026-11-02)")
    void brokenMetaLinksFallsBackToLinkbase(@TempDir Path cache) throws Exception {
        SecFilingClient client = mock(SecFilingClient.class);
        var meta = new SecFilingClient.FolderItem("MetaLinks.json", "1");
        var summary = new SecFilingClient.FolderItem("FilingSummary.xml", "1");
        var pre = new SecFilingClient.FolderItem("x_pre.xml", "1");
        when(client.folder(1L, "a")).thenReturn(List.of(meta, summary, pre));
        when(client.instance(1L, "a", meta)).thenReturn("{\"instance\": \"줄\n바꿈\"}".getBytes(StandardCharsets.UTF_8));
        when(client.instance(1L, "a", summary)).thenReturn(("<FilingSummary><MyReports><Report><MenuCategory>Statements</MenuCategory>"
                + "<Role>http://x/role/StatementOfIncome</Role></Report></MyReports></FilingSummary>").getBytes(StandardCharsets.UTF_8));
        when(client.instance(1L, "a", pre)).thenReturn("""
                <link:linkbase xmlns:link="http://www.xbrl.org/2003/linkbase" xmlns:xlink="http://www.w3.org/1999/xlink">
                  <link:presentationLink xlink:type="extended" xlink:role="http://x/role/StatementOfIncome">
                    <link:loc xlink:type="locator" xlink:href="us-gaap.xsd#us-gaap_NetIncomeLoss" xlink:label="a"/>
                  </link:presentationLink>
                </link:linkbase>""".getBytes(StandardCharsets.UTF_8));
        var faces = new SecStatementFaceCollector(client, cache.toString()).faces(1L, Set.of("a"), "t");
        assertThat(faces.carries("a", Set.of("us-gaap_NetIncomeLoss"))).isTrue();
    }

    @Test
    @DisplayName("공시 폴더가 없으면(404) 그 공시는 본문 모름 · 캐시하지 않는다 — 일시 실패는 종목 실패로 던진다(VOD 2026-11-02)")
    void missingFolderIsUnknownButTransientThrows(@TempDir Path cache) throws Exception {
        SecFilingClient client = mock(SecFilingClient.class);
        when(client.folder(1L, "gone")).thenThrow(new DataSourceException(DataSourceException.Kind.NOT_FOUND, "sec-edgar", "HTTP 404"));
        when(client.folder(1L, "busy")).thenThrow(new DataSourceException(DataSourceException.Kind.TRANSIENT, "sec-edgar", "HTTP 503"));
        var collector = new SecStatementFaceCollector(client, cache.toString());
        assertThat(collector.faces(1L, Set.of("gone"), "t").carries("gone", Set.of("us-gaap_NetIncomeLoss"))).isNull();
        collector.faces(1L, Set.of("gone"), "t");
        verify(client, times(2)).folder(1L, "gone");
        assertThatThrownBy(() -> collector.faces(1L, Set.of("busy"), "t")).isInstanceOf(DataSourceException.class);
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

    @Test
    @DisplayName("데이터셋 기말은 가장 가까운 월말이다 — 52/53주 회계연도의 사실 기말을 그 날로 맞춰 찾는다")
    void dataSetDateRoundsToTheNearestMonthEnd() {
        assertThat(SecStatementFaceCollector.SecStatementFaces.dataSetDate("20180929")).as("SNA 2018 3분기").isEqualTo("20180930");
        assertThat(SecStatementFaceCollector.SecStatementFaces.dataSetDate("20220128")).as("NTAP").isEqualTo("20220131");
        assertThat(SecStatementFaceCollector.SecStatementFaces.dataSetDate("20190102")).as("1월 초 기말은 앞 달 말").isEqualTo("20181231");
        assertThat(SecStatementFaceCollector.SecStatementFaces.dataSetDate("20171231")).isEqualTo("20171231");
    }
}
