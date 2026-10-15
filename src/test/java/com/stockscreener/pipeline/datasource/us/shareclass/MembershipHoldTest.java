package com.stockscreener.pipeline.datasource.us.shareclass;

import com.stockscreener.pipeline.datasource.us.shareclass.MembershipHold.State;
import com.stockscreener.pipeline.datasource.us.shareclass.MembershipHold.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MembershipHoldTest {

    private static final LocalDate D0 = LocalDate.of(2026, 5, 1);

    private static Verdict shown(LocalDate d) {
        return new Verdict(d, false, false, 100.0);
    }

    private static Verdict hidden(LocalDate d) {
        return new Verdict(d, true, false, null);
    }

    private static Verdict contradiction(LocalDate d) {
        return new Verdict(d, true, true, null);
    }

    @Test
    @DisplayName("보인 판 뒤 숨김은 공시일에서 119일까지 보류, 120일째는 상한을 넘는다")
    void holdEndsAtTheCap() {
        List<Verdict> h = List.of(shown(D0), hidden(D0.plusDays(90)));

        assertThat(MembershipHold.stateAt(h, D0.plusDays(89))).isEqualTo(State.SHOWN);
        assertThat(MembershipHold.stateAt(h, D0.plusDays(90 + 119))).isEqualTo(State.HELD);
        assertThat(MembershipHold.stateAt(h, D0.plusDays(90 + 120))).isEqualTo(State.EXPIRED);
    }

    @Test
    @DisplayName("숨김이 두 판 이어지면 보류 끝 · 숨긴 판 하나뿐이면 후보 불가 · 판정이 없으면 지금 경로")
    void consecutiveFirstAndUnjudged() {
        assertThat(MembershipHold.stateAt(List.of(shown(D0), hidden(D0.plusDays(90)), hidden(D0.plusDays(180))),
                D0.plusDays(181))).isEqualTo(State.CONSECUTIVE);
        assertThat(MembershipHold.stateAt(List.of(hidden(D0)), D0.plusDays(1))).isEqualTo(State.FIRST_HIDDEN);
        assertThat(MembershipHold.stateAt(List.of(shown(D0)), D0.minusDays(1))).isNull();
    }

    @Test
    @DisplayName("원장 어긋남 구간이 371일 안에 두 번 시작하면 둘째 시작부터 첫 시작 + 371일까지 제외 — 대용 실패는 안 센다")
    void twoLedgerContradictionsWithinTheWindowExclude() {
        LocalDate second = D0.plusDays(200);
        List<Verdict> h = List.of(shown(D0.minusDays(90)), contradiction(D0), shown(D0.plusDays(100)),
                contradiction(second), shown(D0.plusDays(290)));
        List<Verdict> proxy = List.of(shown(D0.minusDays(90)), hidden(D0), shown(D0.plusDays(100)),
                hidden(second), shown(D0.plusDays(290)));

        assertThat(MembershipHold.stateAt(h, second.minusDays(1))).isEqualTo(State.SHOWN);
        assertThat(MembershipHold.stateAt(h, D0.plusDays(300))).as("보인 판이 와도 창 안이면 제외").isEqualTo(State.EXCLUDED);
        assertThat(MembershipHold.stateAt(h, D0.plusDays(371))).isEqualTo(State.SHOWN);
        assertThat(MembershipHold.stateAt(proxy, D0.plusDays(300))).isEqualTo(State.SHOWN);
    }

    @Test
    @DisplayName("보류된 앞 편입은 마지막 보인 판의 값으로 순위를 다툰다 — 자리를 미리 떼지 않고, 숨긴 비편입은 못 들며, 판정 없는 후보는 그대로 든다")
    void heldMembersCompeteWithTheirLastShownValue() {
        Map<String, State> state = Map.of("H", State.HELD, "L", State.HELD, "N", State.HELD, "X", State.CONSECUTIVE);
        Map<String, Double> lastShown = Map.of("H", 95.0, "L", 10.0, "N", 500.0);
        List<MembershipHold.Candidate> ranked = List.of(new MembershipHold.Candidate("A", 100), new MembershipHold.Candidate("H", 99),
                new MembershipHold.Candidate("N", 98), new MembershipHold.Candidate("X", 97), new MembershipHold.Candidate("L", 96),
                new MembershipHold.Candidate("B", 90), new MembershipHold.Candidate("C", 80));

        MembershipHold.Selection s = MembershipHold.select(ranked, Set.of("A", "H", "L", "X"), state::get, lastShown::get, 3);

        assertThat(s.held()).as("L은 마지막 보인 값이 정원 밖이라 보류가 안 된다").containsExactly("H");
        assertThat(s.members()).containsExactlyInAnyOrder("A", "H", "B");
    }

    @Test
    @DisplayName("마지막으로 보인 판은 숨긴 판을 건너 고른다")
    void lastShownSkipsHiddenVintages() {
        List<Verdict> h = List.of(shown(D0), hidden(D0.plusDays(90)));

        assertThat(MembershipHold.lastShown(h, D0.plusDays(100)).filed()).isEqualTo(D0);
        assertThat(MembershipHold.lastShown(h, D0.minusDays(1))).isNull();
    }
}
