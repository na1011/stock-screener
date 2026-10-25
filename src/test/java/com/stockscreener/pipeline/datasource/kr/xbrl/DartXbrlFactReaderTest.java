package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Basis;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Fact;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Member;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Source;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DartXbrlFactReaderTest {

    /** 2021-12 뒤 판의 꼴 — {@code xbrli:} 접두 · 주석 태그(가중평균 · 우선주 EPS) · 회사 구성원. */
    private static final String CURRENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance" xmlns:xbrldi="http://xbrl.org/2006/xbrldi"
                xmlns:ifrs-full="https://xbrl.ifrs.org/taxonomy/2023-03-23/ifrs-full"
                xmlns:dart="http://dart.fss.or.kr/taxonomy/2024-06-30/ifrs/dart"
                xmlns:entity00164742="http://dart.fss.or.kr/taxonomy/2026-06-30/entity00164742"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <xbrli:context id="Q2c">
                <xbrli:entity><xbrli:identifier scheme="x">00164742</xbrli:identifier>
                  <xbrli:segment><xbrldi:explicitMember dimension="ifrs-full:ConsolidatedAndSeparateFinancialStatementsAxis">ifrs-full:ConsolidatedMember</xbrldi:explicitMember></xbrli:segment>
                </xbrli:entity>
                <xbrli:period><xbrli:startDate>2026-04-01</xbrli:startDate><xbrli:endDate>2026-06-30</xbrli:endDate></xbrli:period>
              </xbrli:context>
              <xbrli:context id="Q2cOrd">
                <xbrli:entity><xbrli:identifier scheme="x">00164742</xbrli:identifier>
                  <xbrli:segment>
                    <xbrldi:explicitMember dimension="ifrs-full:ConsolidatedAndSeparateFinancialStatementsAxis">ifrs-full:ConsolidatedMember</xbrldi:explicitMember>
                    <xbrldi:explicitMember dimension="ifrs-full:ClassesOfShareCapitalAxis">ifrs-full:OrdinarySharesMember</xbrldi:explicitMember>
                  </xbrli:segment>
                </xbrli:entity>
                <xbrli:period><xbrli:startDate>2026-04-01</xbrli:startDate><xbrli:endDate>2026-06-30</xbrli:endDate></xbrli:period>
              </xbrli:context>
              <xbrli:context id="Q2cPref1">
                <xbrli:entity><xbrli:identifier scheme="x">00164742</xbrli:identifier>
                  <xbrli:segment>
                    <xbrldi:explicitMember dimension="ifrs-full:ConsolidatedAndSeparateFinancialStatementsAxis">ifrs-full:ConsolidatedMember</xbrldi:explicitMember>
                    <xbrldi:explicitMember dimension="ifrs-full:ClassesOfShareCapitalAxis">entity00164742:FirstPreferredSharesMember</xbrldi:explicitMember>
                  </xbrli:segment>
                </xbrli:entity>
                <xbrli:period><xbrli:startDate>2026-04-01</xbrli:startDate><xbrli:endDate>2026-06-30</xbrli:endDate></xbrli:period>
              </xbrli:context>
              <xbrli:context id="EndC">
                <xbrli:entity><xbrli:identifier scheme="x">00164742</xbrli:identifier>
                  <xbrli:segment><xbrldi:explicitMember dimension="ifrs-full:ConsolidatedAndSeparateFinancialStatementsAxis">ifrs-full:ConsolidatedMember</xbrldi:explicitMember></xbrli:segment>
                </xbrli:entity>
                <xbrli:period><xbrli:instant>2026-06-30</xbrli:instant></xbrli:period>
              </xbrli:context>
              <ifrs-full:ProfitLossAttributableToOwnersOfParent contextRef="Q2c" decimals="-6" unitRef="KRW">3254000000000</ifrs-full:ProfitLossAttributableToOwnersOfParent>
              <ifrs-full:BasicEarningsLossPerShare contextRef="Q2cOrd" decimals="0" unitRef="KRWS">9621</ifrs-full:BasicEarningsLossPerShare>
              <ifrs-full:WeightedAverageShares contextRef="Q2cOrd" decimals="0" unitRef="SHR">261013584</ifrs-full:WeightedAverageShares>
              <dart:BasicEarningsLossPerSharePreferredStock contextRef="Q2cPref1" decimals="0" unitRef="KRWS">9671</dart:BasicEarningsLossPerSharePreferredStock>
              <dart:HybridBonds contextRef="EndC" decimals="-6" unitRef="KRW">299230000000</dart:HybridBonds>
              <dart:IssueDateHybridBonds contextRef="EndC">2024년 3월</dart:IssueDateHybridBonds>
              <entity00164742:udf_BasicEarningsLossPerShare contextRef="Q2c" decimals="0" unitRef="KRWS">1</entity00164742:udf_BasicEarningsLossPerShare>
              <ifrs-full:Revenue contextRef="Q2c" decimals="-6" unitRef="KRW">48000000000000</ifrs-full:Revenue>
              <ifrs-full:WeightedAverageShares contextRef="Q2c" xsi:nil="true" unitRef="SHR"/>
            </xbrli:xbrl>
            """;

    /** 2021-12 앞 판의 꼴 — 인스턴스 이름공간이 기본(접두 없음) · 2019 분류체계 · {@code scenario} 안 차원. */
    private static final String LEGACY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <xbrl xmlns="http://www.xbrl.org/2003/instance" xmlns:xbrldi="http://xbrl.org/2006/xbrldi"
                xmlns:ifrs-full="http://xbrl.ifrs.org/taxonomy/2019-03-27/ifrs-full"
                xmlns:dart="http://dart.fss.or.kr/taxonomy/2019-10-01/ifrs/dart">
              <context id="FY2020s">
                <entity><identifier scheme="http://dart.fss.or.kr/ifrs/CIK">00106641</identifier></entity>
                <period><startDate>2020-01-01</startDate><endDate>2020-12-31</endDate></period>
                <scenario><xbrldi:explicitMember dimension="ifrs-full:ConsolidatedAndSeparateFinancialStatementsAxis">ifrs-full:SeparateMember</xbrldi:explicitMember></scenario>
              </context>
              <context id="FY2020">
                <entity><identifier scheme="http://dart.fss.or.kr/ifrs/CIK">00106641</identifier></entity>
                <period><startDate>2020-01-01</startDate><endDate>2020-12-31</endDate></period>
              </context>
              <ifrs-full:ProfitLoss contextRef="FY2020s" decimals="-6" unitRef="KRW">1,487,617,000,000</ifrs-full:ProfitLoss>
              <ifrs-full:BasicEarningsLossPerShare contextRef="FY2020" decimals="0" unitRef="KRWS">3,672</ifrs-full:BasicEarningsLossPerShare>
            </xbrl>
            """;

    @Test
    void readsTheCurrentFormatAndKeepsOnlyMaterialStandardConcepts() throws DataSourceException {
        DartXbrlFactReader.Result result = read(CURRENT);

        assertThat(result.facts()).extracting(Fact::concept).containsExactly(
                "ProfitLossAttributableToOwnersOfParent", "BasicEarningsLossPerShare", "WeightedAverageShares",
                "BasicEarningsLossPerSharePreferredStock", "HybridBonds");
        // 발행일 문구는 이름에 Hybrid가 든 DART 개념이지만 수가 아니다 — 버리되 센다(조용히 삼키지 않는다).
        assertThat(result.unreadableValues()).isEqualTo(1);

        Fact eps = result.facts().get(1);
        assertThat(eps.source()).isEqualTo(Source.IFRS);
        assertThat(eps.basis()).isEqualTo(Basis.CONSOLIDATED);
        assertThat(eps.start()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(eps.end()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(eps.members()).containsExactly(new Member("ClassesOfShareCapitalAxis", "OrdinarySharesMember", Source.IFRS));
        assertThat(eps.value()).isEqualByComparingTo("9621");

        Fact pref = result.facts().get(3);
        assertThat(pref.source()).isEqualTo(Source.DART);
        // 회사 구성원은 맥락에 남는다 — 이름공간으로 회사 확장임을 안다(판정이 축마다 거른다).
        assertThat(pref.members()).containsExactly(
                new Member("ClassesOfShareCapitalAxis", "FirstPreferredSharesMember", Source.ENTITY));

        Fact hybrid = result.facts().get(4);
        assertThat(hybrid.instant()).isTrue();
        assertThat(hybrid.end()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void readsTheLegacyFormatThroughTheDefaultNamespace() throws DataSourceException {
        List<Fact> facts = read(LEGACY).facts();

        assertThat(facts).hasSize(2);
        assertThat(facts.get(0).basis()).isEqualTo(Basis.SEPARATE);
        // 천 단위 쉼표가 든 값도 수로 읽는다.
        assertThat(facts.get(0).value()).isEqualByComparingTo(new BigDecimal("1487617000000"));
        assertThat(facts.get(1).basis()).isEqualTo(Basis.NONE);
        assertThat(facts.get(1).members()).isEmpty();
    }

    @Test
    void anUnknownConsolidationMemberIsMalformedNotSilentlyAxisless() {
        String odd = CURRENT.replaceFirst("(id=\"EndC\">[\\s\\S]*?)ifrs-full:ConsolidatedMember", "$1ifrs-full:CombinedMember");
        assertThat(odd).isNotEqualTo(CURRENT);

        assertThatThrownBy(() -> read(odd))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("연결 축 구성원을 모른다");
    }

    @Test
    void namespacesAreClassifiedByUriNotByPrefix() {
        assertThat(Source.of("http://xbrl.ifrs.org/taxonomy/2019-03-27/ifrs-full")).isEqualTo(Source.IFRS);
        assertThat(Source.of("https://xbrl.ifrs.org/taxonomy/2023-03-23/ifrs-full")).isEqualTo(Source.IFRS);
        // 2018년 앞 판(삼성전자 FY2018 사업보고서 20190401004781) — IASB 시절 URI도 IFRS 표준이다.
        assertThat(Source.of("http://xbrl.iasb.org/taxonomy/2010-04-30/ifrs")).isEqualTo(Source.IFRS);
        assertThat(Source.of("http://xbrl.iasb.org/info")).isEqualTo(Source.ENTITY);
        assertThat(Source.of("http://dart.fss.or.kr/taxonomy/2019-10-01/ifrs/dart")).isEqualTo(Source.DART);
        assertThat(Source.of("http://dart.fss.or.kr/taxonomy/2026-06-30/entity00164742")).isEqualTo(Source.ENTITY);
        assertThat(Source.of("http://dart.fss.or.kr/taxonomy/2014-03-31/entity00106641")).isEqualTo(Source.ENTITY);
        assertThat(Source.of(null)).isEqualTo(Source.ENTITY);
    }

    private static DartXbrlFactReader.Result read(String xml) throws DataSourceException {
        return DartXbrlFactReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "test");
    }
}
