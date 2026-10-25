package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.us.shareclass.CoverUnitJudge;
import com.stockscreener.pipeline.datasource.us.shareclass.IssuerFacts;
import com.stockscreener.pipeline.datasource.us.shareclass.IssuerTimeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 표지 단위 판정(ADR-0193) — 운영 입구({@link ShareClassCollector#judgeCovers(JsonNode, List)})를 실제 companyfacts를 판정 재료 개념과
 * 사례 기간으로 가지친 픽스처에 태운다. 무너지는 방식이 조용하다 — 단위가 틀린 표지(PKG 89,213,394,000)는 시총을 1000배로 올리고, 거꾸로
 * 정직한 표지를 버리면 사다리 다음 칸이 대신 나가 값이 그럴듯하다.
 */
class CoverUnitJudgeTest {

    private static final Path DIR = Path.of("src/test/resources/fixtures/sec/cover");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CoverUnitJudge.Result judge(String name, List<IssuerTimeline.Split> splits) throws IOException {
        JsonNode root = MAPPER.readTree(DIR.resolve("companyfacts-" + name + "-cover.json").toFile());
        return ShareClassCollector.judgeCovers(root.path("facts"), splits);
    }

    @Test
    @DisplayName("EPS로 확인된 가중평균에서 1000배 갈린 표지는 사다리 다음 칸(기본 가중평균)으로 바뀐다 — PKG")
    void unitMistaggedCoverTakesTheNextRung() throws IOException {
        CoverUnitJudge.Result r = judge("pkg", List.of());

        assertThat(r.original()).containsEntry("0000950170-23-016955", 89_932_185_000d)
                .containsEntry("0001193125-26-074129", 89_213_394_000d);
        assertThat(r.replaced()).containsEntry("0000950170-23-016955", 89_000_000d)
                .containsEntry("0001193125-26-074129", 89_300_000d);
        assertThat(r.absent()).isEmpty();
    }

    @Test
    @DisplayName("다음 칸 CSO가 같은 배수로 틀리면 그 칸을 건너뛰고, 맞는 칸이 없으면 없음이다 — CB 2010")
    void aNextRungInTheSameWrongUnitIsSkipped() throws IOException {
        CoverUnitJudge.Result r = judge("cb", List.of());

        assertThat(r.absent()).containsExactly("0001193125-10-111376");
        assertThat(r.replaced()).containsEntry("0001193125-10-180546", 339_975_261d);
    }

    @Test
    @DisplayName("n과 EPS가 함께 반대로 틀려 폐합이 맞아도 앞 표지와 맞는 표지는 둔다 — HL 2017-05(n 395,370 × EPS 70)")
    void aCoverTheHistoryConfirmsIsKeptAgainstAFooledClosure() throws IOException {
        CoverUnitJudge.Result r = judge("hl2017", List.of());

        assertThat(r.judged("0001437749-17-008250")).isFalse();
        assertThat(r.witnessed()).contains("0001437749-17-008250");
    }

    @Test
    @DisplayName("연속 오태깅 뒤의 정직한 표지는 바뀐 값을 증인으로 둔다 — GRMN 2016~2017")
    void replacedValuesWitnessTheHonestCoverThatFollows() throws IOException {
        CoverUnitJudge.Result r = judge("grmn", List.of());

        assertThat(r.replaced()).containsEntry("0001615774-16-005099", 189_193_000d);
        assertThat(r.original().keySet()).noneMatch(a -> a.startsWith("0001615774-17"));
    }

    @Test
    @DisplayName("증인 둘 다에서 0.08 갈린 오태깅도 띠 안이다 — HBAN 2011(다음 칸 CSO = 표지 ÷ 10^3)")
    void aMistagBeyondTheCoverToleranceIsStillUnitShifted() throws IOException {
        CoverUnitJudge.Result r = judge("hban", List.of());

        assertThat(r.replaced()).containsEntry("0000950123-11-015766", 863_319_435d);
    }

    @Test
    @DisplayName("앞 표지들이 첫 공시의 오태깅으로 오염돼도 확인된 가중평균과 맞는 정직한 표지는 둔다 — TFC 2009-11")
    void anHonestCoverMatchingTheConfirmedWeightedIsKept() throws IOException {
        CoverUnitJudge.Result r = judge("tfc", List.of());

        assertThat(r.judged("0001193125-09-229012")).isFalse();
    }

    @Test
    @DisplayName("재편으로 n · 앞 표지가 600배 작아도 단위 계단이 아니면 둔다 — QXO 2024(2.79)")
    void aGenuineRecapitalizationIsNotAUnitStep() throws IOException {
        CoverUnitJudge.Result r = judge("qxo", List.of());

        assertThat(r.original()).isEmpty();
    }

    @Test
    @DisplayName("1:800 역분할 뒤 표지는 분할을 반영한 앞 표지와 맞는다 — 반영하지 않으면 −2.90이라 띠 안에 든다")
    void priorCoversAreSplitAdjusted() throws IOException {
        JsonNode facts = synthetic();
        List<IssuerTimeline.Split> splits = List.of(new IssuerTimeline.Split(LocalDate.of(2025, 2, 1), 1.0 / 800));

        assertThat(ShareClassCollector.judgeCovers(facts, splits).original()).isEmpty();
        // 대조: 분할을 안 주면 같은 표지가 단위 오태깅으로 버려진다.
        assertThat(ShareClassCollector.judgeCovers(facts, List.of()).original()).containsKey("0000000001-25-000005");
    }

    /** 엔진이 어댑터의 결정을 입고 판정한다 — 운영과 같은 길(클래스 축 입력에 결정을 싣는다). */
    private static List<IssuerTimeline.Verdict> verdicts(String name) throws IOException {
        JsonNode raw = MAPPER.readTree(DIR.resolve("companyfacts-" + name + "-cover.json").toFile());
        ObjectNode root = MAPPER.createObjectNode();
        root.set("facts", TaxonomyFrame.shareClassFacts(raw.path("facts")));
        CoverUnitJudge.attach(root, ShareClassCollector.judgeCovers(raw.path("facts"), List.of()));
        IssuerFacts cf = IssuerFacts.fromCompanyFacts(root);
        return IssuerTimeline.standard().judge(name, cf, cf.anchorCandidates(), accession -> null, List.of(),
                LocalDate.of(2015, 1, 1), IssuerFacts.INSTANCE_FROM);
    }

    private static IssuerTimeline.Verdict verdict(List<IssuerTimeline.Verdict> all, String accession) {
        return all.stream().filter(v -> v.accession().equals(accession)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("엔진: 맞는 칸이 없는 표지의 공시는 숨김(원장 어긋남) — 원장이 그 공시의 주식수를 확인해 주지 않았다(CB 2010-05)")
    void noRungMeansTheFilingIsHidden() throws IOException {
        IssuerTimeline.Verdict v = verdict(verdicts("cb"), "0001193125-10-111376");

        assertThat(v.status()).isEqualTo('X');
        assertThat(v.branch()).isEqualTo("cover-unit");
        assertThat(v.category()).isEqualTo("어긋남");
    }

    @Test
    @DisplayName("엔진: 표지가 앞 표지와 맞는 공시에서 표지와 1000배 갈린 n은 폐합이 참이어도 n이 아니다(HL 2017-05 395,370)")
    void aWitnessedCoverOverrulesAFooledClosure() throws IOException {
        IssuerTimeline.Verdict v = verdict(verdicts("hl2017"), "0001437749-17-008250");

        assertThat(v.status()).isEqualTo('O');
        assertThat(v.denominator()).as("395,370은 n이 아니다 — 다음 태그 대체도 없어 값 없음").isNull();
    }

    @Test
    @DisplayName("엔진: 바뀐 표지가 d 판정의 표지다 — 단위가 틀린 표지는 판정 재료에 안 남는다(PKG 2025 FY)")
    void theEngineSeesTheReplacedCover() throws IOException {
        JsonNode raw = MAPPER.readTree(DIR.resolve("companyfacts-pkg-cover.json").toFile());
        ObjectNode root = MAPPER.createObjectNode();
        root.set("facts", TaxonomyFrame.shareClassFacts(raw.path("facts")));
        CoverUnitJudge.attach(root, ShareClassCollector.judgeCovers(raw.path("facts"), List.of()));

        assertThat(IssuerFacts.fromCompanyFacts(root).coverShares()).containsEntry("0001193125-26-074129", 89_300_000d);
    }

    /** 앞 정기 공시 넷의 표지 800M → 역분할 → 표지 1M(가중평균 없음 — 앞 표지만이 증인). */
    private static JsonNode synthetic() {
        ObjectNode facts = MAPPER.createObjectNode();
        ArrayNode cover = facts.putObject("dei").putObject("EntityCommonStockSharesOutstanding").putObject("units").putArray("shares");
        ArrayNode ni = facts.putObject("us-gaap").putObject("NetIncomeLoss").putObject("units").putArray("USD");
        String[][] filings = {{"0000000001-24-000001", "2024-02-20", "10-K", "2023-12-31", "800000000"},
                {"0000000001-24-000002", "2024-05-01", "10-Q", "2024-03-31", "800000000"},
                {"0000000001-24-000003", "2024-08-01", "10-Q", "2024-06-30", "800000000"},
                {"0000000001-24-000004", "2024-11-01", "10-Q", "2024-09-30", "800000000"},
                {"0000000001-25-000005", "2025-02-20", "10-K", "2024-12-31", "1000000"}};
        for (String[] f : filings) {
            cover.addObject().put("end", f[1]).put("val", Long.parseLong(f[4])).put("accn", f[0]).put("form", f[2]).put("filed", f[1]);
            ni.addObject().put("start", f[3].substring(0, 4) + "-01-01").put("end", f[3]).put("val", 1_000_000).put("accn", f[0])
                    .put("form", f[2]).put("filed", f[1]);
        }
        return facts;
    }
}
