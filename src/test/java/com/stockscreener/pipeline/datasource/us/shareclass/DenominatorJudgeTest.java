package com.stockscreener.pipeline.datasource.us.shareclass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts.AXISLESS;
import static org.assertj.core.api.Assertions.assertThat;

class DenominatorJudgeTest {

    private static final ClassFacts.Period Q = new ClassFacts.Period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
    private static final String W = "WeightedAverageNumberOfSharesOutstandingBasic";
    private static final String EPS = "EarningsPerShareBasic";
    private final DenominatorJudge judge = new DenominatorJudge(0.054);

    /** 개념 → (구성원 → 값 · 반폭). */
    private static ClassFacts facts(Map<String, Map<String, double[]>> durations, Map<String, String> coverShares,
                                    Map<String, String> symbols, Map<String, String> titles) {
        Map<String, LinkedHashMap<ClassFacts.MemberPeriod, ClassFacts.Measured>> d = new LinkedHashMap<>();
        durations.forEach((c, ms) -> {
            LinkedHashMap<ClassFacts.MemberPeriod, ClassFacts.Measured> m = new LinkedHashMap<>();
            ms.forEach((member, v) -> m.put(new ClassFacts.MemberPeriod(member, Q), new ClassFacts.Measured(v[0], v[1])));
            d.put(c, m);
        });
        Map<String, LinkedHashMap<String, String>> cover = new LinkedHashMap<>();
        cover.put(ClassFactReader.SHARES_OUTSTANDING, new LinkedHashMap<>(coverShares));
        cover.put(ClassFactReader.TRADING_SYMBOL, new LinkedHashMap<>(symbols));
        cover.put(ClassFactReader.SECURITY_TITLE, new LinkedHashMap<>(titles));
        return new ClassFacts(d, cover, "10-Q");
    }

    @Test
    @DisplayName("원장이 밝힌 분자로 닫히면 그 분모를 받는다 — 선언 정밀도만 허용")
    void declaredNumeratorClosesTheDenominator() {
        ClassFacts f = facts(Map.of(
                W, Map.of(AXISLESS, new double[]{100_000_000, 500}),
                EPS, Map.of(AXISLESS, new double[]{1.50, 0.005}),
                ClassFactReader.DECLARED_NUMERATOR, Map.of(AXISLESS, new double[]{150_000_000, 500})),
                Map.of("us-gaap:CommonClassAMember", "100000000"), Map.of(), Map.of());

        DenominatorJudge.Result r = judge.judge("ZZ", f, DenominatorJudge.Form.QUARTER);

        assertThat(r.n()).isEqualTo(100_000_000);
        assertThat(r.branch()).isEqualTo("A-ok");
    }

    @Test
    @DisplayName("분모가 천 단위로 잘못 실린 공시는 배율을 찾아 고치지 않는다 — 표지 총량이 자릿수를 말해 줘도 원장 어긋남 실패다(TEM)")
    void wrongUnitIsNotRestored() {
        Map<String, Map<String, double[]>> d = Map.of(
                W, Map.of(AXISLESS, new double[]{178_880, 0.5}),
                EPS, Map.of(AXISLESS, new double[]{-0.50, 0.005}),
                ClassFactReader.DECLARED_NUMERATOR, Map.of(AXISLESS, new double[]{-89_440_000, 500}));
        ClassFacts withCover = facts(d, Map.of("us-gaap:CommonClassAMember", "170000000",
                "us-gaap:CommonClassBMember", "9000000"), Map.of(), Map.of());
        ClassFacts withoutCover = facts(d, Map.of(), Map.of(), Map.of());

        DenominatorJudge.Result withTotal = judge.judge("TEM", withCover, DenominatorJudge.Form.QUARTER);
        DenominatorJudge.Result withoutTotal = judge.judge("TEM", withoutCover, DenominatorJudge.Form.QUARTER);

        assertThat(withTotal.n()).as("옛 판은 ×1000을 받아 178,880,000(A-ok+w+3)").isNull();
        assertThat(withTotal.failureSource()).isEqualTo("원장");
        assertThat(withoutTotal.n()).isNull();
        assertThat(withoutTotal.failureSource()).isEqualTo("원장");
    }

    @Test
    @DisplayName("EPS와 분자의 부호가 어긋나면 폐합으로 검사할 수 없다 — 값을 받고 실패로 세지 않는다")
    void signMismatchIsUnverifiable() {
        ClassFacts f = facts(Map.of(
                W, Map.of(AXISLESS, new double[]{810_000_000, 500}),
                EPS, Map.of(AXISLESS, new double[]{-0.10, 0.005}),
                "NetIncomeLoss", Map.of(AXISLESS, new double[]{50_000_000, 500})),
                Map.of("us-gaap:CommonClassAMember", "810000000"), Map.of(), Map.of());

        DenominatorJudge.Result r = judge.judge("MDLN", f, DenominatorJudge.Form.QUARTER);

        assertThat(r.n()).isEqualTo(810_000_000);
        assertThat(r.branch()).isEqualTo("A-unverifiable-sign");
    }

    @Test
    @DisplayName("대용 분자로만 실패하면 실패 출처가 대용이다 — 발행사 제외의 원장 어긋남으로 안 센다")
    void proxyFailureIsNotALedgerContradiction() {
        ClassFacts f = facts(Map.of(
                W, Map.of(AXISLESS, new double[]{100_000_000, 500}),
                EPS, Map.of(AXISLESS, new double[]{1.00, 0.005}),
                "NetIncomeLoss", Map.of(AXISLESS, new double[]{130_000_000, 500})),
                Map.of("us-gaap:CommonClassAMember", "100000000"), Map.of(), Map.of());

        DenominatorJudge.Result r = judge.judge("ZZ", f, DenominatorJudge.Form.QUARTER);

        assertThat(r.n()).isNull();
        assertThat(r.failureSource()).isEqualTo("대용");
    }

    @Test
    @DisplayName("상장 기호는 전체 일치로만 줄을 집고, 제목 낱말이 구성원 이름 낱말과 하나로 맞으면 잇는다(JEF)")
    void listedClassByTitleTokens() {
        ClassFacts f = facts(Map.of(), Map.of(), new LinkedHashMap<>(Map.of(
                        "us-gaap:CommonStockMember", "JEF",
                        "jef:FourPointEightFivePercentageSeniorNotesDue2027Member", "JEF 27A")),
                Map.of("us-gaap:CommonStockMember", "Voting Common Shares, par value $1 per share"));

        String[] mapped = DenominatorJudge.listedMember("JEF", f,
                List.of("jef:VotingCommonStockMember", "jef:NonvotingCommonStockMember"));

        assertThat(mapped).containsExactly("jef:VotingCommonStockMember", "title-tokens");
    }

    @Test
    @DisplayName("기호가 통째로 같은 줄이 먼저다 — 워런트 줄 「HIMS WS」가 같은 기호로 시작해도 하나로 잇는다")
    void wholeSymbolBeforeSplitSymbols() {
        ClassFacts f = facts(Map.of(), Map.of(), new LinkedHashMap<>(Map.of(
                        "hims:ClassACommonMember", "HIMS", "us-gaap:WarrantMember", "HIMS WS")), Map.of());

        assertThat(DenominatorJudge.listedMember("HIMS", f, List.of("us-gaap:CommonClassAMember", "us-gaap:CommonClassVMember")))
                .containsExactly("us-gaap:CommonClassAMember", "direct-letter");
    }

    @Test
    @DisplayName("여러 클래스를 묶은 구성원은 글자로 잇지 않는다 — SPAC은 상환 Class A로 가고, 묶음뿐이면 잇지 않는다")
    void groupedMembersAreNotALetterLine() {
        ClassFacts spac = facts(Map.of(), Map.of(), Map.of("x:ClassAOrdinarySharesMember", "ZEO"), Map.of());
        ClassFacts dell = facts(Map.of(), Map.of(), Map.of("us-gaap:CommonClassCMember", "DELL"), Map.of());

        assertThat(DenominatorJudge.listedMember("ZEO", spac,
                List.of("x:RedeemableClassAOrdinarySharesMember", "x:NonredeemableClassAAndClassBOrdinarySharesMember")))
                .containsExactly("x:RedeemableClassAOrdinarySharesMember", "direct-letter");
        assertThat(DenominatorJudge.listedMember("ZEO", spac, List.of("x:NonRedeemableClassAAndBOrdinarySharesMember"))[0]).isNull();
        assertThat(DenominatorJudge.listedMember("DELL", dell,
                List.of("dell:CommonClassVMember", "dell:CommonClassACommonClassBCommonClassCAndCommonClassDMember"))[0]).isNull();
    }

    @Test
    @DisplayName("구성원 이름의 소문자 클래스 글자도 그 클래스다(ALDF 「CommonClassaSubjectToRedemption」) · 「Classes」는 글자가 아니다")
    void lowercaseClassLetterInMemberName() {
        ClassFacts f = facts(Map.of(), Map.of(), Map.of(AXISLESS, "ALDF"), Map.of(AXISLESS, "Class A Ordinary Share"));

        assertThat(DenominatorJudge.listedMember("ALDF", f,
                List.of("aldf:CommonClassaSubjectToRedemptionMember", "aldf:CommonClassbMember", "x:ClassesOfStockMember")))
                .containsExactly("aldf:CommonClassaSubjectToRedemptionMember", "title");
    }

    @Test
    @DisplayName("기호의 줄이 유닛 · 워런트 · 권리면 그 이름의 「Class A」로 보통주 구성원에 잇지 않는다")
    void unitAndWarrantLinesAreNotMapped() {
        ClassFacts f = facts(Map.of(), Map.of(), Map.of(
                        "x:UnitsEachConsistingOfOneClassAOrdinaryShareAndOneHalfOfOneRedeemableWarrantMember", "BACCU",
                        "x:ClassAOrdinarySharesMember", "BACC",
                        "x:RedeemableWarrantsMember", "BACCW"),
                Map.of("x:UnitsEachConsistingOfOneClassAOrdinaryShareAndOneHalfOfOneRedeemableWarrantMember",
                        "Units, each consisting of one Class A ordinary share and one-half of one redeemable warrant",
                        "x:ClassAOrdinarySharesMember", "Class A ordinary shares, par value $0.0001 per share",
                        "x:RedeemableWarrantsMember", "Redeemable warrants, each whole warrant exercisable for one Class A ordinary share"));
        List<String> members = List.of("x:RedeemableClassAOrdinarySharesMember", "x:ClassBOrdinarySharesMember");

        assertThat(DenominatorJudge.listedMember("BACCU", f, members)).containsExactly(null, "not-common");
        assertThat(DenominatorJudge.listedMember("BACCW", f, members)).containsExactly(null, "not-common");
        assertThat(DenominatorJudge.listedMember("BACC", f, members))
                .containsExactly("x:RedeemableClassAOrdinarySharesMember", "direct-letter");
    }
}
