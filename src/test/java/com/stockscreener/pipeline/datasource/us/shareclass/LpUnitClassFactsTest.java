package com.stockscreener.pipeline.datasource.us.shareclass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LP 단위 발행사의 클래스 축 재료(ADR-0187 · 탐색 {@code LP-UNIT-PLAN-1023.md}). 값은 실제 공시에서 옮겼다 — SUN 2026-08-06 10-Q
 * (표지 보통 단위는 클래스 축, 가중평균은 LP 클래스 축의 같은 구성원) · KNOP(LP 클래스 축에 거래 구성원) · CAPL 2016 · JSPR 2022
 * (LP+GP 합계 단위가 다른 가중평균과 함께 실려 뜻이 갈림) · REG(축 없는 LP 단위가 운영 파트너십 외부 조합원 단위).
 *
 * <p>무너지는 방식이 조용하다 — LP 클래스 축을 버리면 SUN이 「가중평균 안 줌」으로 숨고, 합계 단위나 넓힌 태그를 확인 없이 받으면
 * 값 없음이던 분모가 그럴듯한 틀린 값이 된다.
 */
class LpUnitClassFactsTest {

    private static final ClassFacts.Period Q = new ClassFacts.Period(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30));

    private static String instance(String body) {
        return """
                <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance" xmlns:xbrldi="http://xbrl.org/2006/xbrldi"
                    xmlns:us-gaap="http://fasb.org/us-gaap/2025" xmlns:dei="http://xbrl.sec.gov/dei/2025">
                  <xbrli:context id="q"><xbrli:entity><xbrli:identifier scheme="x">0001552275</xbrli:identifier></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2026-04-01</xbrli:startDate><xbrli:endDate>2026-06-30</xbrli:endDate></xbrli:period></xbrli:context>
                  <xbrli:context id="qLpCommon"><xbrli:entity><xbrli:identifier scheme="x">0001552275</xbrli:identifier>
                    <xbrli:segment><xbrldi:explicitMember dimension="us-gaap:LimitedPartnersCapitalAccountByClassAxis">sun:CommonUnitsMember</xbrldi:explicitMember></xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2026-04-01</xbrli:startDate><xbrli:endDate>2026-06-30</xbrli:endDate></xbrli:period></xbrli:context>
                  <xbrli:context id="qLpSale"><xbrli:entity><xbrli:identifier scheme="x">0001552275</xbrli:identifier>
                    <xbrli:segment><xbrldi:explicitMember dimension="us-gaap:LimitedPartnersCapitalAccountByClassAxis">sun:PartnersCapitalAccountSaleOfUnitsMember</xbrldi:explicitMember></xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2026-04-01</xbrli:startDate><xbrli:endDate>2026-06-30</xbrli:endDate></xbrli:period></xbrli:context>
                  <xbrli:context id="cCommon"><xbrli:entity><xbrli:identifier scheme="x">0001552275</xbrli:identifier>
                    <xbrli:segment><xbrldi:explicitMember dimension="us-gaap:StatementClassOfStockAxis">sun:CommonUnitsMember</xbrldi:explicitMember></xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:instant>2026-07-31</xbrli:instant></xbrli:period></xbrli:context>
                  <xbrli:context id="cClassD"><xbrli:entity><xbrli:identifier scheme="x">0001552275</xbrli:identifier>
                    <xbrli:segment><xbrldi:explicitMember dimension="us-gaap:StatementClassOfStockAxis">sun:CommonClassDUnitsMember</xbrldi:explicitMember></xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:instant>2026-07-31</xbrli:instant></xbrli:period></xbrli:context>
                  <dei:DocumentType contextRef="q">10-Q</dei:DocumentType>
                  <dei:EntityCommonStockSharesOutstanding contextRef="cCommon" unitRef="s" decimals="INF">136896651</dei:EntityCommonStockSharesOutstanding>
                  <dei:EntityCommonStockSharesOutstanding contextRef="cClassD" unitRef="s" decimals="INF">51517198</dei:EntityCommonStockSharesOutstanding>
                %s
                </xbrli:xbrl>
                """.formatted(body);
    }

    private static ClassFacts read(String body) throws Exception {
        return ClassFactReader.read(new ByteArrayInputStream(instance(body).getBytes(StandardCharsets.UTF_8)), 1552275, "test")
                .facts();
    }

    private static ClassFacts listing(String body) throws Exception {
        return ClassFactReader.readListingCover(new ByteArrayInputStream(instance(body).getBytes(StandardCharsets.UTF_8)), 1552275,
                "test");
    }

    @Test
    @DisplayName("LP 단위 가중평균은 LP 클래스 축에서도 받는다 — 구성원이 그 공시 표지의 클래스 구성원일 때만(SUN · KNOP 거래 구성원)")
    void lpClassAxisCountsOnlyForCoverClassMembers() throws Exception {
        ClassFacts f = read("""
                <us-gaap:WeightedAverageLimitedPartnershipUnitsOutstanding contextRef="qLpCommon" unitRef="s" decimals="INF">136895211</us-gaap:WeightedAverageLimitedPartnershipUnitsOutstanding>
                <us-gaap:WeightedAverageLimitedPartnershipUnitsOutstanding contextRef="qLpSale" unitRef="s" decimals="INF">252000</us-gaap:WeightedAverageLimitedPartnershipUnitsOutstanding>
                <us-gaap:WeightedAverageNumberOfSharesOutstandingBasic contextRef="qLpCommon" unitRef="s" decimals="INF">7</us-gaap:WeightedAverageNumberOfSharesOutstandingBasic>
                """);

        var w = f.of("WeightedAverageLimitedPartnershipUnitsOutstanding");
        assertThat(w.get(new ClassFacts.MemberPeriod("sun:CommonUnitsMember", Q)).value()).isEqualTo(136895211);
        assertThat(w).as("표지에 없는 구성원(거래 · 0인 종속 단위)은 안 싣는다").hasSize(1);
        assertThat(f.of("WeightedAverageNumberOfSharesOutstandingBasic"))
                .as("법인 태그는 LP 축으로 안 넓힌다 — REIT 운영 파트너십 단위가 그 축에 실린다").isEmpty();
        assertThat(f.isClassAnchor()).isTrue();
    }

    @Test
    @DisplayName("LP+GP 합계 단위는 같은 (구성원, 기간)에 다른 가중평균이 없을 때만 싣는다 — 함께 실린 공시에서 뜻이 갈린다(JSPR · CAPL)")
    void lpPlusGpUnitsOnlyFillAnEmptySlot() throws Exception {
        ClassFacts both = read("""
                <us-gaap:WeightedAverageNumberOfLimitedPartnershipAndGeneralPartnershipUnitOutstandingBasicAndDiluted contextRef="q" unitRef="s" decimals="INF">3028277</us-gaap:WeightedAverageNumberOfLimitedPartnershipAndGeneralPartnershipUnitOutstandingBasicAndDiluted>
                <us-gaap:WeightedAverageNumberOfSharesOutstandingBasic contextRef="q" unitRef="s" decimals="INF">36425000</us-gaap:WeightedAverageNumberOfSharesOutstandingBasic>
                """);
        ClassFacts alone = read("""
                <us-gaap:WeightedAverageNumberOfLimitedPartnershipAndGeneralPartnershipUnitOutstandingBasicAndDiluted contextRef="q" unitRef="s" decimals="INF">122579000</us-gaap:WeightedAverageNumberOfLimitedPartnershipAndGeneralPartnershipUnitOutstandingBasicAndDiluted>
                """);

        String lpGp = "WeightedAverageNumberOfLimitedPartnershipAndGeneralPartnershipUnitOutstandingBasicAndDiluted";
        assertThat(both.of(lpGp)).as("기본 태그가 있으면 판정기의 어느 자리(앞이 이김 · 뒤가 이김)에도 안 닿는다").isEmpty();
        assertThat(DenominatorJudge.periodValues(both, ClassFactReader.WEIGHTED_TAGS, Q)).containsEntry(ClassFacts.AXISLESS, 36425000.0);
        assertThat(alone.of(lpGp).get(new ClassFacts.MemberPeriod(ClassFacts.AXISLESS, Q)).value()).as("GEL 2021").isEqualTo(122579000);
    }

    @Test
    @DisplayName("상장 판정 표지 판독은 LP 재료를 안 읽는다 — 그 판(예탁증권 표지 저장)이 바이트까지 그대로다")
    void listingCoverIgnoresLpMaterial() throws Exception {
        String body = """
                <us-gaap:WeightedAverageNumberOfLimitedPartnershipAndGeneralPartnershipUnitOutstandingBasicAndDiluted contextRef="q" unitRef="s" decimals="INF">122579000</us-gaap:WeightedAverageNumberOfLimitedPartnershipAndGeneralPartnershipUnitOutstandingBasicAndDiluted>
                <us-gaap:WeightedAverageLimitedPartnershipUnitsOutstanding contextRef="qLpCommon" unitRef="s" decimals="INF">136895211</us-gaap:WeightedAverageLimitedPartnershipUnitsOutstanding>
                """;

        assertThat(listing(body).toJson()).isEqualTo(listing("").toJson());
    }

    @Test
    @DisplayName("companyfacts의 넓힌 가중평균 태그는 그 공시 표지 주식수로 확인될 때만 분모가 된다(REG 운영 파트너십 단위 · EPD LP 단위)")
    void widenedCompanyFactsTagsNeedTheCover() throws Exception {
        String json = """
                {"facts": {
                  "dei": {"EntityCommonStockSharesOutstanding": {"units": {"shares": [
                    {"end": "2026-07-31", "val": 181538000, "accn": "reg-q2", "form": "10-Q", "filed": "2026-08-05"},
                    {"end": "2026-07-31", "val": 2164990000, "accn": "epd-q2", "form": "10-Q", "filed": "2026-08-06"}]}}},
                  "us-gaap": {"WeightedAverageLimitedPartnershipUnitsOutstanding": {"units": {"shares": [
                    {"start": "2026-04-01", "end": "2026-06-30", "val": 1101104, "accn": "reg-q2", "form": "10-Q", "filed": "2026-08-05"},
                    {"start": "2026-04-01", "end": "2026-06-30", "val": 2163000000, "accn": "epd-q2", "form": "10-Q", "filed": "2026-08-06"}]}}}}}
                """;

        IssuerFacts cf = IssuerFacts.fromCompanyFacts(new ObjectMapper().readTree(json));

        assertThat(cf.weighted()).containsEntry(new IssuerFacts.Key("epd-q2", Q), 2163000000.0);
        assertThat(cf.weighted()).as("표지의 0.6% — 그 태그는 상장 증권을 센 것이 아니다").doesNotContainKey(new IssuerFacts.Key("reg-q2", Q));
    }
}
