package com.stockscreener.pipeline.datasource.us.shareclass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClassFactReaderTest {

    private static final ClassFacts.Period Q = new ClassFacts.Period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));

    private static String instance(String body) {
        return """
                <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance" xmlns:xbrldi="http://xbrl.org/2006/xbrldi"
                    xmlns:us-gaap="http://fasb.org/us-gaap/2025" xmlns:dei="http://xbrl.sec.gov/dei/2025">
                  <xbrli:context id="q"><xbrli:entity><xbrli:identifier scheme="x">0000000042</xbrli:identifier></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2026-01-01</xbrli:startDate><xbrli:endDate>2026-03-31</xbrli:endDate></xbrli:period></xbrli:context>
                  <xbrli:context id="qA"><xbrli:entity><xbrli:identifier scheme="x">0000000042</xbrli:identifier>
                    <xbrli:segment><xbrldi:explicitMember dimension="us-gaap:StatementClassOfStockAxis">us-gaap:CommonClassAMember</xbrldi:explicitMember></xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2026-01-01</xbrli:startDate><xbrli:endDate>2026-03-31</xbrli:endDate></xbrli:period></xbrli:context>
                  <xbrli:context id="qAeq"><xbrli:entity><xbrli:identifier scheme="x">0000000042</xbrli:identifier>
                    <xbrli:segment><xbrldi:explicitMember dimension="us-gaap:StatementClassOfStockAxis">us-gaap:CommonClassAMember</xbrldi:explicitMember>
                    <xbrldi:explicitMember dimension="us-gaap:StatementEquityComponentsAxis">us-gaap:CommonStockMember</xbrldi:explicitMember></xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2026-01-01</xbrli:startDate><xbrli:endDate>2026-03-31</xbrli:endDate></xbrli:period></xbrli:context>
                  <xbrli:context id="qBeq"><xbrli:entity><xbrli:identifier scheme="x">0000000042</xbrli:identifier>
                    <xbrli:segment><xbrldi:explicitMember dimension="us-gaap:StatementClassOfStockAxis">us-gaap:CommonClassBMember</xbrldi:explicitMember>
                    <xbrldi:explicitMember dimension="us-gaap:StatementEquityComponentsAxis">us-gaap:CommonStockMember</xbrldi:explicitMember></xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2026-01-01</xbrli:startDate><xbrli:endDate>2026-03-31</xbrli:endDate></xbrli:period></xbrli:context>
                  <xbrli:context id="qRestated"><xbrli:entity><xbrli:identifier scheme="x">0000000042</xbrli:identifier>
                    <xbrli:segment><xbrldi:explicitMember dimension="us-gaap:StatementClassOfStockAxis">us-gaap:CommonClassBMember</xbrldi:explicitMember>
                    <xbrldi:explicitMember dimension="us-gaap:RestatementAxis">srt:ScenarioPreviouslyReportedMember</xbrldi:explicitMember></xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2026-01-01</xbrli:startDate><xbrli:endDate>2026-03-31</xbrli:endDate></xbrli:period></xbrli:context>
                  <xbrli:context id="other"><xbrli:entity><xbrli:identifier scheme="x">0000000099</xbrli:identifier></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2026-01-01</xbrli:startDate><xbrli:endDate>2026-03-31</xbrli:endDate></xbrli:period></xbrli:context>
                  <xbrli:context id="cA"><xbrli:entity><xbrli:identifier scheme="x">0000000042</xbrli:identifier>
                    <xbrli:segment><xbrldi:explicitMember dimension="us-gaap:StatementClassOfStockAxis">us-gaap:CommonClassAMember</xbrldi:explicitMember></xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:instant>2026-04-30</xbrli:instant></xbrli:period></xbrli:context>
                  <xbrli:context id="cBsub"><xbrli:entity><xbrli:identifier scheme="x">0000000042</xbrli:identifier>
                    <xbrli:segment><xbrldi:explicitMember dimension="us-gaap:StatementClassOfStockAxis">us-gaap:CommonClassBMember</xbrldi:explicitMember>
                    <xbrldi:explicitMember dimension="us-gaap:SubsequentEventTypeAxis">us-gaap:SubsequentEventMember</xbrldi:explicitMember></xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:instant>2026-04-30</xbrli:instant></xbrli:period></xbrli:context>
                  <dei:DocumentType contextRef="q">10-Q</dei:DocumentType>
                %s
                </xbrli:xbrl>
                """.formatted(body);
    }

    private static ClassFactReader.Result read(String body) throws Exception {
        return ClassFactReader.read(new ByteArrayInputStream(instance(body).getBytes(StandardCharsets.UTF_8)), 42, "test");
    }

    @Test
    @DisplayName("클래스 축 + 보통주 자본 구성요소 축은 클래스만 단 사실이 없을 때만 그 클래스 사실이 된다 · 재작성 축은 버린다")
    void equityComponentsAxisFillsOnlyMissingClassFacts() throws Exception {
        ClassFacts f = read("""
                <us-gaap:WeightedAverageNumberOfSharesOutstandingBasic contextRef="qAeq" unitRef="s" decimals="-3">999000</us-gaap:WeightedAverageNumberOfSharesOutstandingBasic>
                <us-gaap:WeightedAverageNumberOfSharesOutstandingBasic contextRef="qA" unitRef="s" decimals="-3">206528000</us-gaap:WeightedAverageNumberOfSharesOutstandingBasic>
                <us-gaap:WeightedAverageNumberOfSharesOutstandingBasic contextRef="qBeq" unitRef="s" decimals="-3">95161000</us-gaap:WeightedAverageNumberOfSharesOutstandingBasic>
                <us-gaap:WeightedAverageNumberOfSharesOutstandingBasic contextRef="qRestated" unitRef="s" decimals="-3">1</us-gaap:WeightedAverageNumberOfSharesOutstandingBasic>
                <us-gaap:WeightedAverageNumberOfSharesOutstandingBasic contextRef="other" unitRef="s" decimals="-3">7</us-gaap:WeightedAverageNumberOfSharesOutstandingBasic>
                """).facts();

        var w = f.of("WeightedAverageNumberOfSharesOutstandingBasic");
        assertThat(w.get(new ClassFacts.MemberPeriod("us-gaap:CommonClassAMember", Q)).value())
                .as("클래스만 단 맥락이 문서 순서와 무관하게 먼저다").isEqualTo(206528000);
        assertThat(w.get(new ClassFacts.MemberPeriod("us-gaap:CommonClassBMember", Q)).value()).isEqualTo(95161000);
        assertThat(w.get(new ClassFacts.MemberPeriod("us-gaap:CommonClassBMember", Q)).halfWidth()).isEqualTo(500.0);
        assertThat(w).as("재작성 축 · 남의 CIK는 안 싣는다").hasSize(2);
    }

    @Test
    @DisplayName("표지 주식수는 후속사건 축도 받아 클래스 축 앵커가 된다")
    void subsequentEventCoverMakesAnAnchor() throws Exception {
        ClassFacts f = read("""
                <dei:EntityCommonStockSharesOutstanding contextRef="cA" unitRef="s" decimals="INF">3915329785</dei:EntityCommonStockSharesOutstanding>
                <dei:EntityCommonStockSharesOutstanding contextRef="cBsub" unitRef="s" decimals="INF">70852076</dei:EntityCommonStockSharesOutstanding>
                """).facts();

        assertThat(f.coverOf(ClassFactReader.SHARES_OUTSTANDING))
                .containsEntry("us-gaap:CommonClassAMember", "3915329785")
                .containsEntry("us-gaap:CommonClassBMember", "70852076");
        assertThat(f.isClassAnchor()).isTrue();
        assertThat(f.documentType()).isEqualTo("10-Q");
    }

    @Test
    @DisplayName("수로 안 읽히는 값은 건너뛰고 센다 — 조용히 0이 되지 않는다")
    void unreadableValuesAreCounted() throws Exception {
        ClassFactReader.Result r = read("""
                <us-gaap:EarningsPerShareBasic contextRef="q" unitRef="u" decimals="2">n/a</us-gaap:EarningsPerShareBasic>
                <us-gaap:EarningsPerShareBasic contextRef="qA" unitRef="u" decimals="x">1.00</us-gaap:EarningsPerShareBasic>
                """);

        assertThat(r.unreadableValues()).isEqualTo(2);
        assertThat(r.facts().of("EarningsPerShareBasic")).isEmpty();
    }

    @Test
    @DisplayName("사실이 맥락보다 앞에 나와도 읽는다 — 구 생성기 인스턴스(JOBY 2021-03)")
    void factsBeforeContextsAreRead() throws Exception {
        String xml = """
                <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance" xmlns:xbrldi="http://xbrl.org/2006/xbrldi"
                    xmlns:dei="http://xbrl.sec.gov/dei/2019-01-31">
                  <dei:EntityCommonStockSharesOutstanding contextRef="cA" unitRef="s" decimals="INF">69000000</dei:EntityCommonStockSharesOutstanding>
                  <xbrli:context id="cA"><xbrli:entity><xbrli:identifier scheme="x">0000000042</xbrli:identifier>
                    <xbrli:segment><xbrldi:explicitMember dimension="us-gaap:StatementClassOfStockAxis">us-gaap:CommonClassAMember</xbrldi:explicitMember></xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:instant>2021-02-26</xbrli:instant></xbrli:period></xbrli:context>
                </xbrli:xbrl>
                """;

        ClassFacts f = ClassFactReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), 42, "test").facts();

        assertThat(f.isClassAnchor()).isTrue();
    }

    /** 저장 판독(COVER)으로 상장 판정 픽스처를 읽는다 — 같은 표지를 두 판독이 어떻게 받는지 맞댄다. */
    private static ClassFacts stored(String name, long cik) throws Exception {
        try (var in = ClassFactReaderTest.class.getResourceAsStream("/fixtures/sec-edgar/listing/" + name)) {
            return ClassFactReader.read(in, cik, name).facts();
        }
    }

    @Test
    @DisplayName("저장 표지의 기호는 거래소 축 꼴도 받아 상장 클래스로 잇는다 — 같은 기호로 시작하는 우선주 줄이 있어도(Two Harbors)")
    void exchangeAxisCoverMapsTheListedClass() throws Exception {
        ClassFacts two = stored("two-10q-2026-07-29-cover.xml", 1465740);

        assertThat(two.coverOf(ClassFactReader.TRADING_SYMBOL))
                .containsEntry("us-gaap:CommonClassAMember", "TWO")
                .containsEntry("us-gaap:SeriesAPreferredStockMember", "TWO PRA");
        assertThat(two.coverOf(ClassFactReader.SECURITY_TITLE))
                .containsEntry("us-gaap:CommonClassAMember", "Common Stock, par value $0.01 per share");
        assertThat(DenominatorJudge.listedMember("TWO", two,
                List.of("us-gaap:CommonClassAMember", "us-gaap:SeriesAPreferredStockMember")))
                .containsExactly("us-gaap:CommonClassAMember", "direct");
    }

    @Test
    @DisplayName("이중 상장 표지(클래스 축 없이 거래소 축 + 보통주 구성요소 축)는 한 증권의 기호를 잇고 이름은 첫 값이다(SunOpta)")
    void dualListedCoverJoinsSymbols() throws Exception {
        ClassFacts stkl = stored("stkl-10k-2026-03-04-cover.xml", 351834);

        assertThat(stkl.coverOf(ClassFactReader.TRADING_SYMBOL)).containsExactly(Map.entry(ClassFacts.AXISLESS, "STKL, SOY"));
        assertThat(stkl.coverOf(ClassFactReader.SECURITY_TITLE)).containsExactly(Map.entry(ClassFacts.AXISLESS, "Common Shares"));
        assertThat(DenominatorJudge.splitSymbols(stkl.coverOf(ClassFactReader.TRADING_SYMBOL).get(ClassFacts.AXISLESS)))
                .containsExactly("STKL", "SOY");
    }

    @Test
    @DisplayName("넓힌 꼴은 축 없는 값을 못 덮고, 표지 주식수는 넓히지 않는다 · 다른 자본 구성요소(유닛)는 안 받는다")
    void widenedCoverOnlyFillsGaps() throws Exception {
        String ex = """
                <xbrli:context id="%s"><xbrli:entity><xbrli:identifier scheme="x">0000000042</xbrli:identifier>
                  <xbrli:segment>%s</xbrli:segment></xbrli:entity><xbrli:period><xbrli:instant>2026-04-30</xbrli:instant></xbrli:period></xbrli:context>
                """;
        String member = "<xbrldi:explicitMember dimension=\"%s\">%s</xbrldi:explicitMember>";
        String body = ex.formatted("aX", member.formatted("us-gaap:StatementClassOfStockAxis", "us-gaap:CommonClassAMember")
                + member.formatted("dei:EntityListingsExchangeAxis", "exch:XNYS"))
                + ex.formatted("bX", member.formatted("us-gaap:StatementClassOfStockAxis", "us-gaap:CommonClassBMember")
                + member.formatted("dei:EntityListingsExchangeAxis", "exch:XNYS"))
                + ex.formatted("units", member.formatted("us-gaap:StatementEquityComponentsAxis", "us-gaap:UnitsMember"))
                + """
                <dei:TradingSymbol contextRef="aX">WRONG</dei:TradingSymbol>
                <dei:TradingSymbol contextRef="cA">AAA</dei:TradingSymbol>
                <dei:TradingSymbol contextRef="bX">BBB</dei:TradingSymbol>
                <dei:TradingSymbol contextRef="units">AAA.U</dei:TradingSymbol>
                <dei:EntityCommonStockSharesOutstanding contextRef="bX" unitRef="s" decimals="INF">5</dei:EntityCommonStockSharesOutstanding>
                """;

        ClassFacts f = read(body).facts();

        assertThat(f.coverOf(ClassFactReader.TRADING_SYMBOL)).containsExactly(
                Map.entry("us-gaap:CommonClassAMember", "AAA"), Map.entry("us-gaap:CommonClassBMember", "BBB"));
        assertThat(f.coverOf(ClassFactReader.SHARES_OUTSTANDING)).as("표지 주식수의 뜻은 그대로다").isEmpty();
    }

    @Test
    @DisplayName("표지 글의 줄바꿈 없는 공백 · 너비 0 공백은 공백으로, 방향 표시는 지운다 — 그래야 「Class A」로 상장 클래스를 잇는다(ERIE 꼴)")
    void coverTextIsPlainSpaced() throws Exception {
        String body = """
                <dei:TradingSymbol contextRef="q">CLSE</dei:TradingSymbol>
                <dei:Security12bTitle contextRef="q">Class&#160;A common&#x200B;stock,&#x200E;&#160;</dei:Security12bTitle>
                <us-gaap:EarningsPerShareBasic contextRef="qA" unitRef="u" decimals="2">3.87</us-gaap:EarningsPerShareBasic>
                <us-gaap:EarningsPerShareBasic contextRef="qBeq" unitRef="u" decimals="0">581</us-gaap:EarningsPerShareBasic>
                """;

        ClassFacts f = read(body).facts();

        assertThat(f.coverOf(ClassFactReader.SECURITY_TITLE)).containsExactly(Map.entry(ClassFacts.AXISLESS, "Class A common stock,"));
        assertThat(DenominatorJudge.listedMember("CLSE", f, List.of("us-gaap:CommonClassAMember", "us-gaap:CommonClassBMember")))
                .containsExactly("us-gaap:CommonClassAMember", "title");
        assertThat(ClassFactReader.plainText("Class\u00a0A\u2007Units")).isEqualTo("Class A Units");
    }
}
