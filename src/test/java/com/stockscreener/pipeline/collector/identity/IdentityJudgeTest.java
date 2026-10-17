package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.collector.identity.IdentityJudge.Ambiguous;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.OpenRow;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.Rename;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.Result;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.Reuse;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.SplitCandidate;
import com.stockscreener.pipeline.datasource.IdentitySource.EntityBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 신원 판정의 갈래(ADR-0180 · 계획 v3.1 §4 시험 D2). 봉은 손으로 짓는다 — 같은 실체는 (종가 · 거래량 · 체결 수)가 같은 계열이다.
 */
class IdentityJudgeTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);

    private final List<OpenRow> rows = new ArrayList<>();
    private final Map<Long, List<EntityBar>> anchor = new HashMap<>();
    private final Map<String, List<EntityBar>> today = new HashMap<>();
    private final Map<String, List<EntityBar>> literal = new HashMap<>();
    private final Map<String, Long> file = new HashMap<>();

    @Test
    @DisplayName("같은 등록인의 개명 — 표지를 바꾼다(행 수 그대로) · 개명일은 새 기호 문자열의 첫 봉")
    void renameWithinRegistrant() {
        row(1, "HLX", 866829L, true, series(1, 0, 30));
        today.put("HOS", series(1, 0, 30));
        literal.put("HOS", series(1, 10, 30));
        file.put("HOS", 866829L);

        Result result = judge("HOS");

        assertThat(result.verdicts()).singleElement().isInstanceOfSatisfying(Rename.class, r -> {
            assertThat(r.stockId()).isEqualTo(1);
            assertThat(r.newSymbol()).isEqualTo("HOS");
            assertThat(r.renamedOn()).isEqualTo(START.plusDays(10));
        });
        assertThat(result.blockedSymbols()).isEmpty();
    }

    @Test
    @DisplayName("재무 0행인 행(등록인 미결합)의 개명 — 섞일 판이 없으니 개명")
    void renameWithoutFinancials() {
        row(1, "JAB", null, false, series(2, 0, 30));
        today.put("ATLQ", series(2, 0, 30));
        literal.put("ATLQ", series(2, 20, 30));

        assertThat(judge("ATLQ").verdicts()).singleElement().isInstanceOf(Rename.class);
    }

    @Test
    @DisplayName("재무가 있는데 등록인이 안 묶인 행의 개명 — 보류하고 새 기호를 등록하지 않는다(sec-cik-bind 전)")
    void unboundRowWithFinancialsIsAmbiguous() {
        row(1, "HLX", null, true, series(1, 0, 30));
        today.put("HOS", series(1, 0, 30));
        literal.put("HOS", series(1, 10, 30));

        Result result = judge("HOS");

        assertThat(result.verdicts()).singleElement().isInstanceOf(Ambiguous.class);
        assertThat(result.blockedSymbols()).containsExactly("HOS");
    }

    @Test
    @DisplayName("다른 등록인 — 끊기 후보(전제는 SplitEvidence가 판정)")
    void registrantChangeIsASplitCandidate() {
        row(1, "COLA", 2028201L, true, series(3, 0, 30));
        today.put("SAIQ", series(3, 0, 30));
        literal.put("SAIQ", series(3, 25, 30));
        file.put("SAIQ", 2101411L);

        assertThat(judge("SAIQ").verdicts()).singleElement().isInstanceOfSatisfying(SplitCandidate.class, c -> {
            assertThat(c.sameSymbol()).isFalse();
            assertThat(c.newCik()).isEqualTo(2101411L);
            assertThat(c.renamedOn()).isEqualTo(START.plusDays(25));
        });
    }

    @Test
    @DisplayName("겹침 19는 일치가 아니다 — 새 행(판정 없음)")
    void overlapNineteenIsNoMatch() {
        row(1, "OLD", 1L, true, series(4, 0, 19));
        today.put("NEW", series(4, 0, 30));

        assertThat(judge("NEW").verdicts()).isEmpty();
    }

    @Test
    @DisplayName("공허한 참의 대조군 — 겹침 0인 신규 상장은 일치가 아니다")
    void freshListingDoesNotMatchAnything() {
        row(1, "OLD", 1L, true, series(5, 0, 10));
        today.put("IPO", series(6, 15, 45));

        assertThat(judge("IPO").verdicts()).isEmpty();
    }

    @Test
    @DisplayName("JAB꼴 — 옛 행이 오늘 세션에 거래가 없어도 일치한다(v2 「오늘 거래」 조건은 넷을 놓쳤다)")
    void oldRowWithoutTodaysSessionStillMatches() {
        row(1, "JAB", 1L, true, series(7, 0, 25));
        today.put("ATLQ", series(7, 0, 30));
        literal.put("ATLQ", series(7, 26, 30));
        file.put("ATLQ", 1L);

        assertThat(judge("ATLQ").verdicts()).singleElement().isInstanceOf(Rename.class);
    }

    @Test
    @DisplayName("일치하는 행이 둘 — 그 기호 하나만 멈춘다")
    void twoMatchesBlockOnlyThatSymbol() {
        row(1, "A", 1L, true, series(8, 0, 30));
        row(2, "B", 1L, true, series(8, 0, 30));
        today.put("C", series(8, 0, 30));
        today.put("D", series(9, 0, 30));
        row(3, "D", 2L, true, series(9, 0, 30));

        Result result = judge("C", "D");

        assertThat(result.verdicts()).singleElement().isInstanceOf(Ambiguous.class);
        assertThat(result.blockedSymbols()).containsExactly("C");
    }

    @Test
    @DisplayName("기호 그대로 등록인 교체 — 닻 = 오늘이고 오늘 파일 CIK ≠ 행 CIK면 끊기 후보(REAX꼴)")
    void sameSymbolRegistrantChange() {
        row(1, "REAX", 1862461L, true, series(10, 0, 30));
        today.put("REAX", series(10, 0, 30));
        file.put("REAX", 2136387L);

        assertThat(judge("REAX").verdicts()).singleElement().isInstanceOfSatisfying(SplitCandidate.class,
                c -> assertThat(c.sameSymbol()).isTrue());
    }

    @Test
    @DisplayName("등록인 미결합 행의 재사용 — 고리 2가 sec_cik 없이도 잡는다")
    void reuseOfUnboundRow() {
        row(1, "VG", null, false, series(11, 0, 20));
        today.put("VG", series(12, 22, 30));

        assertThat(judge("VG").verdicts()).singleElement().isInstanceOfSatisfying(Reuse.class,
                r -> assertThat(r.lastBar()).isEqualTo(START.plusDays(19)));
    }

    @Test
    @DisplayName("재사용된 기호의 옛 실체가 다른 기호로 갔으면 개명이 먼저다 — 재사용으로 비우지 않는다")
    void displacedRowThatRenamedIsARename() {
        row(1, "VG", 5L, true, series(13, 0, 30));
        today.put("VG", series(14, 5, 30));
        today.put("VGX", series(13, 0, 30));
        literal.put("VGX", series(13, 5, 30));
        file.put("VGX", 5L);

        Result result = judge("VG", "VGX");

        assertThat(result.verdicts()).singleElement().isInstanceOfSatisfying(Rename.class,
                r -> assertThat(r.newSymbol()).isEqualTo("VGX"));
    }

    @Test
    @DisplayName("거래 드문 증권 — 같은 기호의 닻·오늘이 겹친 날이 전부 같으면 겹침이 적어도 같은 실체")
    void thinTraderIsSameEntity() {
        row(1, "WSO.B", 1L, true, series(15, 0, 3));
        today.put("WSO.B", series(15, 0, 3));

        Result result = judge("WSO.B");

        assertThat(result.verdicts()).isEmpty();
        assertThat(result.counts().sameEntity()).isEqualTo(1);
    }

    @Test
    @DisplayName("문자열 봉 재료가 없는 개명 후보는 판정을 안 내고 돌려준다 — 호출부가 그 기호만 받아 다시 판정한다")
    void renameWithoutLiteralAsksForIt() {
        row(1, "HLX", 866829L, true, series(1, 0, 30));
        today.put("HOS", series(1, 0, 30));

        Result result = judge("HOS");

        assertThat(result.verdicts()).isEmpty();
        assertThat(result.needsLiteral()).containsExactly("HOS");
    }

    @Test
    @DisplayName("옛 기호가 아직 목록에 활성인 개명(HKIT·BDAI꼴) — 새 기호를 등록하지 않고 멈춘다(같은 실체의 두 기호)")
    void secondSymbolOfAListedEntityIsBlocked() {
        row(1, "HKIT", 1742341L, true, series(16, 0, 30));
        today.put("HKIT", series(16, 0, 30));
        today.put("BDAI", series(16, 0, 30));
        literal.put("BDAI", series(16, 20, 30));

        Result result = judge("HKIT", "BDAI");

        assertThat(result.verdicts()).singleElement().isInstanceOfSatisfying(Ambiguous.class,
                a -> assertThat(a.evidence()).contains("두 기호"));
        assertThat(result.blockedSymbols()).containsExactly("BDAI");
    }

    private Result judge(String... listed) {
        return IdentityJudge.judge(rows, Set.of(listed), anchor, today, literal, s -> Optional.ofNullable(file.get(s)));
    }

    private void row(long id, String ticker, Long cik, boolean financials, List<EntityBar> anchorBars) {
        rows.add(new OpenRow(id, ticker, cik, financials));
        anchor.put(id, anchorBars);
    }

    /** 실체 {@code entity}의 봉 — {@code from}일째부터 {@code to}일째 전까지(하루 하나). 실체마다 거래량이 다르다. */
    private static List<EntityBar> series(int entity, int from, int to) {
        List<EntityBar> bars = new ArrayList<>();
        for (int day = from; day < to; day++) {
            bars.add(new EntityBar(START.plusDays(day), BigDecimal.valueOf(10 + day), 1_000L * entity + day, 10L * entity));
        }
        return bars;
    }
}
