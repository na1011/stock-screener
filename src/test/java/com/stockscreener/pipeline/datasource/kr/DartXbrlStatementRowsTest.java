package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.StatementPeriod;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인스턴스 → 전체 재무제표 응답 행(국내 관문 켬 · 재고 「얼린 비최신 판」). 대응 규칙은 녹화본 9,110 보고서 대조({@code DartXbrlStatementSweepTest})에서
 * 옮겼다 — 재무상태표 기말 · 손익 3개월과 누계 · 현금흐름 누계 · 연결 구성원 · 응답이 그 표에 실은 줄만.
 */
class DartXbrlStatementRowsTest {

    private static final String CONS = "ifrs-full:ConsolidatedAndSeparateFinancialStatementsAxis";

    private static String context(String id, String start, String end, String member) {
        String period = start == null ? "<xbrli:instant>" + end + "</xbrli:instant>"
                : "<xbrli:startDate>" + start + "</xbrli:startDate><xbrli:endDate>" + end + "</xbrli:endDate>";
        String scenario = member == null ? "" : "<xbrli:scenario><xbrldi:explicitMember dimension=\"" + CONS + "\">ifrs-full:" + member
                + "</xbrldi:explicitMember></xbrli:scenario>";
        return "<xbrli:context id=\"" + id + "\"><xbrli:entity><xbrli:identifier scheme=\"x\">1</xbrli:identifier>" + scenario
                + "</xbrli:entity><xbrli:period>" + period + "</xbrli:period></xbrli:context>";
    }

    private static String fact(String concept, String context, String value) {
        return "<ifrs-full:" + concept + " contextRef=\"" + context + "\" unitRef=\"KRW\" decimals=\"0\">" + value + "</ifrs-full:" + concept + ">";
    }

    /** 2025 반기 · 연결 · 3개월 / 누계 / 기말 / 전기말. 중단영업은 누계만 · 주석 개념 하나 · 같은 칸에 값이 갈린 개념 하나. */
    private static List<DartXbrlFactReader.StatementFact> halfYear() throws Exception {
        String xml = "<xbrli:xbrl xmlns:xbrli=\"http://www.xbrl.org/2003/instance\" xmlns:xbrldi=\"http://xbrl.org/2006/xbrldi\""
                + " xmlns:ifrs-full=\"http://xbrl.ifrs.org/taxonomy/2023-03-23/ifrs-full\">"
                + "<xbrli:unit id=\"KRW\"><xbrli:measure>iso4217:KRW</xbrli:measure></xbrli:unit>"
                + context("E", null, "2025-06-30", "ConsolidatedMember") + context("P", null, "2024-12-31", "ConsolidatedMember")
                + context("S", null, "2025-06-30", "SeparateMember")
                + context("Q", "2025-04-01", "2025-06-30", "ConsolidatedMember") + context("Q2", "2025-04-01", "2025-06-30", "ConsolidatedMember")
                + context("A", "2025-01-01", "2025-06-30", "ConsolidatedMember")
                + fact("Assets", "E", "900") + fact("Assets", "P", "800") + fact("Assets", "S", "500")
                + fact("Revenue", "Q", "100") + fact("Revenue", "A", "190")
                + fact("ProfitLoss", "Q", "10") + fact("ProfitLoss", "A", "19")
                + fact("ProfitLossAttributableToOwnersOfParent", "Q", "8") + fact("ProfitLossAttributableToOwnersOfParent", "Q2", "9")
                + fact("ProfitLossFromDiscontinuedOperations", "A", "3")
                + fact("ProfitLossFromDiscontinuedOperationsAttributableToOrdinaryEquityHoldersOfParentEntity", "Q", "1")
                + fact("CashFlowsFromUsedInOperatingActivities", "A", "40")
                + "</xbrli:xbrl>";
        return DartXbrlFactReader.readStatements(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "test").facts();
    }

    private static DartXbrlStatementRows.Face face() throws Exception {
        String rows = """
                [{"sj_div":"BS","account_id":"ifrs-full_Assets"},
                 {"sj_div":"CIS","account_id":"ifrs-full_Revenue"},
                 {"sj_div":"CIS","account_id":"ifrs-full_ProfitLoss"},
                 {"sj_div":"CIS","account_id":"ifrs-full_ProfitLossAttributableToOwnersOfParent"},
                 {"sj_div":"CIS","account_id":"ifrs-full_ProfitLossFromDiscontinuedOperations"},
                 {"sj_div":"CF","account_id":"ifrs-full_CashFlowsFromUsedInOperatingActivities"}]""";
        return DartXbrlStatementRows.Face.of(new ObjectMapper().readTree(rows));
    }

    private static List<String> lines(JsonNode list) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : list) {
            out.add(r.path("sj_div").asString() + " " + r.path("account_id").asString().replace("ifrs-full_", "") + " "
                    + r.path("thstrm_amount").asString("-") + " " + r.path("thstrm_add_amount").asString("-"));
        }
        return out;
    }

    @Test
    @DisplayName("기말 · 3개월 · 누계 · 현금흐름 누계로 옮긴다 — 응답이 실은 줄만 · 연결 구성원만 · 값이 갈린 칸은 버린다")
    void mapsContextsToResponseColumns() throws Exception {
        DartXbrlStatementRows.Built built = DartXbrlStatementRows.rows(halfYear(), 2025, StatementPeriod.Q2, "20250814000001", "11012", face());

        assertThat(built.consolidated()).isTrue();
        assertThat(lines(built.list())).containsExactlyInAnyOrder(
                "BS Assets 900 -",
                "CIS Revenue 100 190",
                "CIS ProfitLoss 10 19",
                "CF CashFlowsFromUsedInOperatingActivities 40 -");
        assertThat(built.unknownIncome()).as("3개월 사실 없이 누계만 · 본문 줄 없음 · 값 갈림 — 그 값은 모른다").contains(
                "ifrs-full_ProfitLossFromDiscontinuedOperations",
                "ifrs-full_ProfitLossFromDiscontinuedOperationsAttributableToOrdinaryEquityHoldersOfParentEntity",
                "ifrs-full_ProfitLossAttributableToOwnersOfParent");
        assertThat(built.list().get(0).path("rcept_no").asString()).isEqualTo("20250814000001");
        assertThat(built.list().get(0).path("currency").asString()).isEqualTo("KRW");
    }

    @Test
    @DisplayName("인스턴스의 가장 늦은 기말이 그 기간 말일이 아니면 그 보고서가 아니다(비12월 결산의 달력 근사)")
    void rejectsAnInstanceOfAnotherPeriod() throws Exception {
        assertThat(DartXbrlStatementRows.rows(halfYear(), 2025, StatementPeriod.Q1, "x", "11013", face())).isNull();
    }

    @Test
    @DisplayName("옛 IASB 이름공간 판 — 응답이 쓴 접두어(ifrs_)로 줄을 낸다")
    void usesTheResponsePrefix() throws Exception {
        DartXbrlStatementRows.Face old = DartXbrlStatementRows.Face.of(new ObjectMapper().readTree("""
                [{"sj_div":"BS","account_id":"ifrs_Assets"}]"""));
        DartXbrlStatementRows.Built built = DartXbrlStatementRows.rows(halfYear(), 2025, StatementPeriod.Q2, "x", "11012", old);
        assertThat(lines(built.list())).containsExactly("BS ifrs_Assets 900 -");
    }

    @Test
    @DisplayName("회사 확장 개념 — 그 판의 이름표가 응답의 같은 표 비표준 줄 이름과 같으면 그 이름으로 싣는다 · 표준 줄은 응답의 계정명")
    void entityConceptsTravelByTheirLabel() throws Exception {
        String xml = "<xbrli:xbrl xmlns:xbrli=\"http://www.xbrl.org/2003/instance\" xmlns:xbrldi=\"http://xbrl.org/2006/xbrldi\""
                + " xmlns:ifrs-full=\"http://xbrl.ifrs.org/taxonomy/2023-03-23/ifrs-full\""
                + " xmlns:entity001=\"http://dart.fss.or.kr/taxonomy/2025-06-30/entity001\">"
                + "<xbrli:unit id=\"KRW\"><xbrli:measure>iso4217:KRW</xbrli:measure></xbrli:unit>"
                + context("E", null, "2025-06-30", "ConsolidatedMember") + context("Q", "2025-04-01", "2025-06-30", "ConsolidatedMember")
                + fact("Assets", "E", "900")
                + "<entity001:OwnersShare contextRef=\"Q\" unitRef=\"KRW\" decimals=\"0\">7</entity001:OwnersShare>"
                + "<entity001:Unlisted contextRef=\"Q\" unitRef=\"KRW\" decimals=\"0\">3</entity001:Unlisted>"
                + "</xbrli:xbrl>";
        var facts = DartXbrlFactReader.readStatements(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "t").facts();
        var face = DartXbrlStatementRows.Face.of(new ObjectMapper().readTree("""
                [{"sj_div":"BS","account_id":"ifrs-full_Assets","account_nm":"자산총계"},
                 {"sj_div":"CIS","account_id":"-표준계정코드 미사용-","account_nm":"지배기업 소유주지분"}]"""));
        String labels = "<link:linkbase xmlns:link=\"http://www.xbrl.org/2003/linkbase\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
                + "<link:labelLink><link:loc xlink:href=\"e.xsd#entity001_OwnersShare\" xlink:label=\"l1\"/>"
                + "<link:label xlink:label=\"t1\" xlink:role=\"http://www.xbrl.org/2003/role/label\" xml:lang=\"ko\">지배기업 소유주지분</link:label>"
                + "<link:labelArc xlink:from=\"l1\" xlink:to=\"t1\"/>"
                + "<link:loc xlink:href=\"e.xsd#entity001_Unlisted\" xlink:label=\"l2\"/>"
                + "<link:label xlink:label=\"t2\" xlink:role=\"http://www.xbrl.org/2003/role/label\" xml:lang=\"ko\">본문에 없는 줄</link:label>"
                + "<link:labelArc xlink:from=\"l2\" xlink:to=\"t2\"/></link:labelLink></link:linkbase>";
        var entityLabels = com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlLabels.entityLabels(
                labels.getBytes(StandardCharsets.UTF_8), "t");

        DartXbrlStatementRows.Built built = DartXbrlStatementRows.rows(facts, 2025, StatementPeriod.Q2, "x", "11012", face, entityLabels);

        List<String> named = new ArrayList<>();
        for (JsonNode r : built.list()) {
            named.add(r.path("account_id").asString() + "|" + r.path("account_nm").asString() + "|" + r.path("thstrm_amount").asString());
        }
        assertThat(named).containsExactlyInAnyOrder("ifrs-full_Assets|자산총계|900", "-표준계정코드 미사용-|지배기업 소유주지분|7");
        assertThat(DartXbrlStatementRows.rows(facts, 2025, StatementPeriod.Q2, "x", "11012", face).list())
                .as("이름표가 없으면 비표준 줄을 못 옮긴다").hasSize(1);
    }

    @Test
    @DisplayName("원본 ZIP의 한국어 이름표 — 새 판 _lab-ko.xml · 옛 판 labels\\lab_<회사>-ko_<날짜>.xml")
    void recognisesBothLabelEntryNames() {
        assertThat(DartXbrlClient.koreanLabels("entity00544577_2025-09-30_lab-ko.xml")).isTrue();
        assertThat(DartXbrlClient.koreanLabels("labels\\lab_00102858-ko_2017-11-10.xml")).isTrue();
        assertThat(DartXbrlClient.koreanLabels("entity00544577_2025-09-30_lab-en.xml")).isFalse();
        assertThat(DartXbrlClient.koreanLabels("entity00544577_2025-09-30_pre.xml")).isFalse();
    }
}
