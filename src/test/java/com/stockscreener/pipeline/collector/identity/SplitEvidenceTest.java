package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.datasource.IdentitySource.IdentityEvent;
import com.stockscreener.pipeline.datasource.IdentitySource.RegistrantFiling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 행 끊기의 전제 셋(ADR-0180) — 하나라도 안 서면 끊지 않는다. */
class SplitEvidenceTest {

    private static final LocalDate REORG = LocalDate.of(2026, 8, 25);

    @Test
    @DisplayName("접수번호 공유 0 + Alpaca 재편 → 그 효력일에 끊는다(REAX꼴)")
    void splitsOnAlpacaEvent() {
        SplitEvidence.Decision d = SplitEvidence.decide(Set.of("a-1", "a-2"), Set.of("b-1"),
                List.of(new IdentityEvent("reorganization", REORG, Set.of("REAX"), "REAX")),
                List.of(), List.of(new RegistrantFiling("8-K12B", REORG.plusDays(1))));

        assertThat(d.switchDate()).contains(REORG);
        assertThat(d.evidence()).contains("reorganization").contains("8-K12B");
    }

    @Test
    @DisplayName("Alpaca 사건이 없으면 SEC 날짜 박힌 양식의 제출일 — 앞 등록인 25-NSE")
    void splitsOnSecFormWhenNoAlpacaEvent() {
        LocalDate delisted = LocalDate.of(2026, 10, 9);
        SplitEvidence.Decision d = SplitEvidence.decide(Set.of("a-1"), Set.of(), List.of(),
                List.of(new RegistrantFiling("10-Q", delisted.minusDays(30)), new RegistrantFiling("25-NSE", delisted)),
                List.of());

        assertThat(d.switchDate()).contains(delisted);
    }

    @Test
    @DisplayName("저장 접수번호가 새 CIK에 하나라도 있으면 끊지 않는다 — 같은 등록인의 표기 변경일 수 있다")
    void sharedAccessionStops() {
        SplitEvidence.Decision d = SplitEvidence.decide(Set.of("a-1", "a-2"), Set.of("a-2", "b-1"),
                List.of(new IdentityEvent("name_change", REORG, Set.of("X"), "X")), List.of(), List.of());

        assertThat(d.switchDate()).isEmpty();
        assertThat(d.evidence()).contains("2 중 1");
    }

    @Test
    @DisplayName("끝의 증거가 없으면 끊지 않는다 — 정기 공시만 있는 앞 등록인")
    void noEndEvidenceStops() {
        SplitEvidence.Decision d = SplitEvidence.decide(Set.of("a-1"), Set.of("b-1"), List.of(),
                List.of(new RegistrantFiling("10-Q", REORG)), List.of(new RegistrantFiling("10-K", REORG)));

        assertThat(d.switchDate()).isEmpty();
        assertThat(d.evidence()).contains("끝의 증거 없음");
    }
}
