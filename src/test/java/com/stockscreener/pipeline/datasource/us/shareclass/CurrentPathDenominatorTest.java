package com.stockscreener.pipeline.datasource.us.shareclass;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.us.TaxonomyFrame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 지금 경로(축 없는 표지가 있는 정기 공시 · 판정 {@code d}와 보충 공시의 {@code cur})의 EPS 분모 n(ADR-0189 · 탐색
 * {@code D-DENOM-PLAN-1024.md} v1.1). 픽스처는 실제 companyfacts와 인스턴스를 분모 · EPS · 분자 · 표지 개념과 사례 기간으로 가지친 것이다.
 *
 * <p>무너지는 방식이 조용하다 — n이 빈 끝은 주식수 비 지표 셋이 앞 판의 n으로 내려가고(BKR · KKR · PAA는 전 판이 비어 값 없음),
 * 단위가 틀린 n(MCD 711 · BRKR 147)은 그대로 증가율에 실리며, 전년 비교열이 그 공시의 n으로 나가도(CHRD 회생 전 315M) 값은 그럴듯하다.
 */
class CurrentPathDenominatorTest {

    private static final Path DIR = Path.of("src/test/resources/fixtures/sec/denominator");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Case(String name, boolean frame, Map<String, IssuerFacts.Filing> supplements,
                        List<IssuerTimeline.Split> splits) {
        static Case of(String name) {
            return new Case(name, false, Map.of(), List.of());
        }

        Case framed() {
            return new Case(name, true, supplements, splits);
        }

        Case supplement(String accession, String form, String filed) {
            return new Case(name, frame, Map.of(accession, new IssuerFacts.Filing(form, LocalDate.parse(filed))), splits);
        }

        Case split(String date, double ratio) {
            List<IssuerTimeline.Split> s = new java.util.ArrayList<>(splits);
            s.add(new IssuerTimeline.Split(LocalDate.parse(date), ratio));
            return new Case(name, frame, supplements, s);
        }
    }

    private static List<IssuerTimeline.Verdict> judge(Case c) throws IOException {
        JsonNode root = MAPPER.readTree(DIR.resolve("companyfacts-" + c.name() + "-denominator.json").toFile());
        long cik = root.path("cik").asLong();
        if (c.frame()) {
            ObjectNode framed = MAPPER.createObjectNode();
            framed.set("facts", TaxonomyFrame.shareClassFacts(root.path("facts")));
            root = framed;
        }
        IssuerFacts cf = IssuerFacts.fromCompanyFacts(root).withFilings(c.supplements());
        Set<String> anchors = new HashSet<>(cf.anchorCandidates());
        IssuerTimeline.Instances instances = accession -> {
            Path xml = DIR.resolve("instance-" + c.name() + "-" + cik + "_" + accession + ".xml");
            if (!Files.exists(xml)) {
                return null;
            }
            try (InputStream in = Files.newInputStream(xml)) {
                return ClassFactReader.read(in, cik, c.name() + " " + accession).facts();
            } catch (IOException e) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, "test", e.getMessage(), e);
            }
        };
        return IssuerTimeline.standard().judge(c.name().toUpperCase(), cf, anchors, instances, c.splits(),
                LocalDate.of(2015, 1, 1), IssuerFacts.INSTANCE_FROM);
    }

    private static IssuerTimeline.Verdict at(List<IssuerTimeline.Verdict> verdicts, String filed) {
        return verdicts.stream().filter(v -> v.filed().equals(LocalDate.parse(filed))).findFirst()
                .orElseThrow(() -> new AssertionError(filed + " 판정이 없다"));
    }

    @Test
    @DisplayName("companyfacts가 버린 축 맥락의 n을 인스턴스에서 읽는다 — 클래스 축 구성원 하나(BKR 2026 Q2) · EPS 표시 축 기본/희석 같은 값(CBSH)")
    void readsTheDimensionedDenominatorFromTheInstance() throws IOException {
        assertThat(at(judge(Case.of("bkr")), "2026-07-27").denominator()).isEqualTo(992_000_000d);
        assertThat(at(judge(Case.of("cbsh").split("2025-12-02", 1.05)), "2026-08-05").denominator()).isEqualTo(144_215_000d);
    }

    @Test
    @DisplayName("뜻이 다른 축(기업인수)의 n은 인스턴스 선언 정밀도로 그 공시의 EPS · 분자와 엄격히 닫힐 때만 받는다(SSD FY2024 10-K)")
    void otherAxisNeedsTheStrictInstanceClosure() throws IOException {
        assertThat(at(judge(Case.of("ssd")), "2025-03-03").denominator()).as("Q4 유도값").isCloseTo(41_967_565d, within(1d));
    }

    @Test
    @DisplayName("16주 1분기(111일)도 그 10-Q의 분기다(KR 2021 Q1)")
    void sixteenWeekFirstQuarter() throws IOException {
        assertThat(at(judge(Case.of("kr")), "2021-06-25").denominator()).isEqualTo(752_000_000d);
    }

    @Test
    @DisplayName("9개월 입력이 선언 분자 반올림으로 폐합에 실패해도 Q4가 표지 · 직전 분기로 확인되면 받는다(ABT FY2025)")
    void fourthQuarterIsConfirmedByTheOutput() throws IOException {
        assertThat(at(judge(Case.of("abt")), "2026-02-20").denominator()).as("Q4 유도값").isCloseTo(1_739_169_120d, within(1d));
    }

    @Test
    @DisplayName("단위가 틀린 n(백만 단위 711)은 같은 기간의 다음 값이나 단위 복원으로 바꾼다 — 폐합과 표지 자릿수가 함께 맞을 때(MCD 2026 Q1)")
    void wrongUnitIsRepaired() throws IOException {
        assertThat(at(judge(Case.of("mcd")), "2026-05-07").denominator()).isEqualTo(710_700_000d);
    }

    @Test
    @DisplayName("앞 태그 값이 단위 오태깅이면 같은 기간 다음 태그(SharesIssuedBasic) 값이다(AMCR 2026 Q2 · 1:5 병합 뒤)")
    void laterTagReplacesAMistaggedValue() throws IOException {
        assertThat(at(judge(Case.of("amcr").split("2026-01-15", 0.2)), "2026-02-04").denominator()).isEqualTo(463_800_000d);
    }

    @Test
    @DisplayName("n과 EPS가 서로 반대로 틀려 폐합만 맞는 입력으로 낸 Q4는 확인이 안 서 값 없음이다(COHR 2020 Q3 9개월 82,615 · EPS −1430)")
    void inverselyWrongInputsDoNotPass() throws IOException {
        List<IssuerTimeline.Verdict> v = judge(Case.of("cohr"));
        assertThat(at(v, "2020-05-11").denominator()).as("3개월 91,081(천 단위) · EPS ×100 — 복원이 안 선다").isNull();
        assertThat(at(v, "2020-08-26").denominator()).as("옛 판정은 340,926,691(표지 103.7M의 3.3배)").isNull();
        assertThat(at(v, "2020-02-10").denominator()).isEqualTo(90_886_000d);
    }

    @Test
    @DisplayName("폐합을 물을 수 없으면 표지가 틀린 공시의 n을 지우지 않는다(CRM 2011 Q1 · 표지 133.9 · EPS 0)")
    void unknownClosureKeepsTheValue() throws IOException {
        assertThat(at(judge(Case.of("crm")), "2011-05-26").denominator()).isEqualTo(133_454_000d);
    }

    @Test
    @DisplayName("원장이 기본 = 희석을 선언하면(EPS를 「기본 및 희석」으로만) 희석 가중평균이 n이다(TPL 2021 Q1)")
    void declaredBasicEqualsDiluted() throws IOException {
        assertThat(at(judge(Case.of("tpl")), "2021-05-06").denominator()).isEqualTo(7_756_156d);
    }

    @Test
    @DisplayName("당기 값이 없으면 같은 공시의 전년 비교열은 n이 아니다(CHRD FY2020 10-K · 회생 전 FY2019 315,414,467)")
    void comparativeColumnIsNotTheFilingsDenominator() throws IOException {
        assertThat(at(judge(Case.of("chrd")), "2021-03-08").denominator())
                .as("승계 기간(41일)은 연간 창 밖 — 원장이 당기 연간 분모를 안 준다").isNull();
    }

    @Test
    @DisplayName("companyfacts가 빠뜨린 보충 공시(표지가 클래스에 없음 · cur)는 받은 인스턴스의 축 없는 가중평균이 n이다(ABT 2026 Q2)")
    void supplementedFilingReadsItsInstance() throws IOException {
        IssuerTimeline.Verdict v = at(judge(Case.of("abt").supplement("0001628280-26-050134", "10-Q", "2026-07-28")), "2026-07-28");
        assertThat(v.branch()).isEqualTo("cur");
        assertThat(v.denominator()).isEqualTo(1_740_420_000d);
    }

    @Test
    @DisplayName("판독기의 지금 경로 칸은 축 하나짜리 가중평균을 싣되 재작성 · 법인 축은 안 싣는다 — 앵커 판정이 읽는 개념 칸은 그대로다")
    void readerKeepsSingleAxisWeightedOutsideOtherVersions() throws Exception {
        String xml = """
                <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance" xmlns:xbrldi="http://xbrl.org/2006/xbrldi"
                    xmlns:us-gaap="http://fasb.org/us-gaap/2025" xmlns:dei="http://xbrl.sec.gov/dei/2025">
                  <xbrli:context id="eps"><xbrli:entity><xbrli:identifier scheme="x">0000022356</xbrli:identifier>
                    <xbrli:segment><xbrldi:explicitMember dimension="us-gaap:IncomePerCommonShareAxis">x:BasicIncomePerCommonShareMember</xbrldi:explicitMember></xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2026-04-01</xbrli:startDate><xbrli:endDate>2026-06-30</xbrli:endDate></xbrli:period></xbrli:context>
                  <xbrli:context id="restated"><xbrli:entity><xbrli:identifier scheme="x">0000022356</xbrli:identifier>
                    <xbrli:segment><xbrldi:explicitMember dimension="srt:RestatementAxis">srt:ScenarioPreviouslyReportedMember</xbrldi:explicitMember></xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2026-04-01</xbrli:startDate><xbrli:endDate>2026-06-30</xbrli:endDate></xbrli:period></xbrli:context>
                  <xbrli:context id="entity"><xbrli:entity><xbrli:identifier scheme="x">0000022356</xbrli:identifier>
                    <xbrli:segment><xbrldi:explicitMember dimension="dei:LegalEntityAxis">x:SubsidiaryMember</xbrldi:explicitMember></xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2026-04-01</xbrli:startDate><xbrli:endDate>2026-06-30</xbrli:endDate></xbrli:period></xbrli:context>
                  <us-gaap:WeightedAverageNumberOfSharesOutstandingBasic contextRef="eps" unitRef="s" decimals="-3">144215000</us-gaap:WeightedAverageNumberOfSharesOutstandingBasic>
                  <us-gaap:WeightedAverageNumberOfSharesOutstandingBasic contextRef="restated" unitRef="s" decimals="-3">150000000</us-gaap:WeightedAverageNumberOfSharesOutstandingBasic>
                  <us-gaap:WeightedAverageNumberOfSharesOutstandingBasic contextRef="entity" unitRef="s" decimals="-3">1000</us-gaap:WeightedAverageNumberOfSharesOutstandingBasic>
                </xbrli:xbrl>
                """;
        ClassFacts facts = ClassFactReader.read(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                22356, "test").facts();

        assertThat(facts.of(ClassFactReader.DIMENSIONED + "WeightedAverageNumberOfSharesOutstandingBasic").keySet())
                .extracting(ClassFacts.MemberPeriod::member)
                .containsExactly("IncomePerCommonShareAxis=x:BasicIncomePerCommonShareMember");
        assertThat(facts.of("WeightedAverageNumberOfSharesOutstandingBasic")).as("앵커가 읽는 칸 — 축 맥락은 안 든다").isEmpty();
    }

    @Test
    @DisplayName("IFRS 발행사는 클래스 축 입력의 틀이 실은 기본 가중평균(WeightedAverageShares)이 n이다 — 별칭 없는 틀은 값 없음(AEM 2025 40-F)")
    void ifrsWeightedAverageThroughTheShareClassFrame() throws IOException {
        assertThat(at(judge(Case.of("aem").framed()), "2026-03-19").denominator()).isEqualTo(501_993_000d);
    }
}
