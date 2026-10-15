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

        DenominatorJudge.Result r = judge.judge("ZZ", f, DenominatorJudge.Form.QUARTER, null);

        assertThat(r.n()).isEqualTo(100_000_000);
        assertThat(r.branch()).isEqualTo("A-ok");
    }

    @Test
    @DisplayName("분모가 천 단위로 잘못 실린 공시는 표지 총량과 자릿수가 맞는 후보 하나로만 복원한다 — 대수로 같은 EPS ×1000은 기각")
    void unitRestorationNeedsTheCoverTotal() {
        Map<String, Map<String, double[]>> d = Map.of(
                W, Map.of(AXISLESS, new double[]{178_880, 0.5}),
                EPS, Map.of(AXISLESS, new double[]{-0.50, 0.005}),
                ClassFactReader.DECLARED_NUMERATOR, Map.of(AXISLESS, new double[]{-89_440_000, 500}));
        ClassFacts withCover = facts(d, Map.of("us-gaap:CommonClassAMember", "170000000",
                "us-gaap:CommonClassBMember", "9000000"), Map.of(), Map.of());
        ClassFacts withoutCover = facts(d, Map.of(), Map.of(), Map.of());

        DenominatorJudge.Result restored = judge.judge("TEM", withCover, DenominatorJudge.Form.QUARTER, null);
        DenominatorJudge.Result refused = judge.judge("TEM", withoutCover, DenominatorJudge.Form.QUARTER, null);

        assertThat(restored.n()).isEqualTo(178_880_000);
        assertThat(restored.branch()).isEqualTo("A-ok+w+3");
        assertThat(refused.n()).as("자릿수를 가를 시점 총량이 없으면 복원하지 않는다").isNull();
        assertThat(refused.failureSource()).isEqualTo("원장");
    }

    @Test
    @DisplayName("EPS와 분자의 부호가 어긋나면 폐합으로 검사할 수 없다 — 값을 받고 실패로 세지 않는다")
    void signMismatchIsUnverifiable() {
        ClassFacts f = facts(Map.of(
                W, Map.of(AXISLESS, new double[]{810_000_000, 500}),
                EPS, Map.of(AXISLESS, new double[]{-0.10, 0.005}),
                "NetIncomeLoss", Map.of(AXISLESS, new double[]{50_000_000, 500})),
                Map.of("us-gaap:CommonClassAMember", "810000000"), Map.of(), Map.of());

        DenominatorJudge.Result r = judge.judge("MDLN", f, DenominatorJudge.Form.QUARTER, null);

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

        DenominatorJudge.Result r = judge.judge("ZZ", f, DenominatorJudge.Form.QUARTER, null);

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
}
