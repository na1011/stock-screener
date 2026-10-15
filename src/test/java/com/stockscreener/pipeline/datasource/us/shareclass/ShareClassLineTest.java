package com.stockscreener.pipeline.datasource.us.shareclass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShareClassLineTest {

    private static final BigDecimal CURRENT = new BigDecimal("900");

    private static ShareClassLine.Verdict anchor(String accession, LocalDate filed, String lineShares) {
        return new ShareClassLine.Verdict(accession, filed, 'T', 'O', false, false, new BigDecimal(lineShares),
                new BigDecimal("950"));
    }

    private static ShareClassLine.Verdict hidden(String accession, LocalDate filed) {
        return new ShareClassLine.Verdict(accession, filed, 'T', 'X', false, true, null, new BigDecimal("950"));
    }

    private static ShareClassLine.Verdict cover(String accession, LocalDate filed) {
        return new ShareClassLine.Verdict(accession, filed, 'd', 'O', true, false, new BigDecimal("800"),
                new BigDecimal("790"));
    }

    private static final LocalDate Q1 = LocalDate.of(2025, 5, 1);
    private static final LocalDate Q2 = LocalDate.of(2025, 8, 1);
    private static final LocalDate Q3 = LocalDate.of(2025, 11, 1);

    @Test
    @DisplayName("자기 공시의 판정이 행을 정한다 — 클래스 축은 N_L, 지금 경로는 행 값, 숨김은 상태로 막고 분모는 보인 판만")
    void ownVerdictDecides() {
        ShareClassLine line = new ShareClassLine(List.of(cover("A1", Q1), anchor("A2", Q2, "1000"), hidden("A3", Q3)),
                List.of());

        assertThat(line.read("10-Q:A1", Q1, CURRENT)).isEqualTo(
                new ShareClassLine.Read(CURRENT, new BigDecimal("790"), ShareClassLine.State.SHOWN, "지금 경로"));
        assertThat(line.read("10-Q:A2", Q2, CURRENT).shares()).isEqualByComparingTo("1000");
        ShareClassLine.Read hiddenRead = line.read("10-Q:A3", Q3, CURRENT);
        assertThat(hiddenRead.state()).isEqualTo(ShareClassLine.State.HIDDEN);
        assertThat(hiddenRead.shares()).isNull();
        assertThat(hiddenRead.denominator()).isNull();
    }

    @Test
    @DisplayName("클래스 축에 든 뒤의 판정 없는 정기 공시는 미판정 = 숨김 — 옛 N_L로 조용히 돌아가지 않는다")
    void unjudgedPeriodicAfterTheAnchorIsHidden() {
        ShareClassLine line = new ShareClassLine(List.of(anchor("A2", Q2, "1000")), List.of());

        assertThat(line.read("10-Q:NEW", Q3, null).state()).isEqualTo(ShareClassLine.State.HIDDEN);
        assertThat(line.read("10-Q:OLD", Q1, CURRENT))
                .as("앵커 앞의 판정 없는 정기 공시는 지금 경로").extracting(ShareClassLine.Read::shares).isEqualTo(CURRENT);
        assertThat(line.read("10-Q:PRE", LocalDate.of(2020, 8, 1), CURRENT).state())
                .as("인스턴스 구간 앞").isEqualTo(ShareClassLine.State.SHOWN);
    }

    @Test
    @DisplayName("정기 공시가 아닌 행(정정)은 그날 서 있던 판정을 따르고, N_L은 그 사이 분할만큼 옮긴다")
    void amendmentsFollowTheStandingVerdict() {
        LocalDate split = Q2.plusDays(30);
        ShareClassLine line = new ShareClassLine(List.of(anchor("A2", Q2, "1000"), hidden("A3", Q3)),
                List.of(new ShareClassLine.Split(split, new BigDecimal("2"))));

        assertThat(line.read("10-Q/A:X", split.plusDays(1), CURRENT).shares()).isEqualByComparingTo("2000");
        assertThat(line.read("10-K/A:Y", Q3.plusDays(5), CURRENT).state()).isEqualTo(ShareClassLine.State.HIDDEN);
        assertThat(line.read("8-K:Z", Q1, CURRENT)).as("판정 앞").extracting(ShareClassLine.Read::shares).isEqualTo(CURRENT);
    }

    @Test
    @DisplayName("판정이 하나도 없는 줄은 모든 행이 지금 경로다(분모 없음)")
    void noVerdicts() {
        ShareClassLine.Read read = ShareClassLine.none().read("10-Q:A", Q3, CURRENT);

        assertThat(read.shares()).isEqualTo(CURRENT);
        assertThat(read.state()).isEqualTo(ShareClassLine.State.SHOWN);
        assertThat(read.denominator()).isNull();
    }
}
