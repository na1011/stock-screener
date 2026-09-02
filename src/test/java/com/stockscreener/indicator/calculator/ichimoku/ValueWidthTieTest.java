package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.calculator.WeeklyBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 값폭관측론의 <b>동점 처리</b>. 규칙은 "더 과거(인덱스 큰 쪽) 채택"이다.
 *
 * <p>MUST: VW3·VW4에만 건다. <b>VW2는 제외한다</b> — {@code pivot_a}는 인덱스를 쓰지 않고 값만
 * 쓰므로 {@code <=} ↔ {@code <}가 출력에 흔적을 남기지 않는다. 거기에 테스트를 걸면 항등식이
 * 되고, 초록이라는 사실로 <b>거짓 안심</b>만 준다(D-13·D-14는 무의미한 패치였다).
 *
 * <p>각 테스트가 <b>뒤집힌 규칙이 냈을 값</b>을 함께 단언한다. 그것이 없으면 "지금 값이 이렇다"는
 * 사실만 남고, 부등호를 뒤집어도 깨지지 않는 단언이 될 수 있다.
 */
class ValueWidthTieTest {

    private static final LocalDate START = LocalDate.of(2020, 1, 6);

    @Test
    @DisplayName("VW3 과거 방향은 >= 라 동점이면 그 후보가 탈락한다 — 더 과거 쪽이 피벗 B가 된다")
    void pivotBRejectsTieTowardThePast() {
        // 두 주가 같은 고가로 붙어 있다. 과거 방향 점검이 `>=`이므로 더 최근 쪽(인덱스 작은 쪽)이
        // 먼저 후보가 되지만 동점에 걸려 탈락하고, 더 과거 쪽이 채택된다.
        List<WeeklyBar> weeks = flatWeeks(260);
        int tie = 40;
        setHigh(weeks, tie, 200.0);
        setHigh(weeks, tie - 6, 200.0);

        ValueWidth vw = identifyMature(weeks, 60);

        assertThat(indexOf(weeks, vw.pivotBIndex()))
                .as("동점이면 더 과거(인덱스 큰 쪽)가 피벗 B다. 과거 방향을 `>`로 뒤집으면 "
                        + "더 최근 쪽 %d이 먼저 통과해 여기가 바뀐다", tie - 6)
                .isEqualTo(tie);
        assertThat(vw.pivotB()).isEqualTo(200.0);
    }

    @Test
    @DisplayName("VW3 과거 방향의 동점은 후보를 탈락시킨다 — 대조군이 그 자리를 못박는다")
    void pivotBIsRejectedOnlyBecauseOfTheTieTowardThePast() {
        // 대조군: 탐색 시작점(t_break)에 단독 최고값이 있으면 그 자리가 피벗 B다.
        List<WeeklyBar> control = flatWeeks(260);
        setHigh(control, 60, 200.0);
        assertThat(identifyMature(control, 60).pivotBIndex())
                .as("동점이 없으면 t_break 자체가 피벗 B다 — 아래 단언이 동점 때문임을 보인다")
                .isEqualTo(60);

        // 같은 배치에 과거 방향(인덱스 큰 쪽) 9주 안의 동점만 더한다.
        List<WeeklyBar> tied = flatWeeks(260);
        setHigh(tied, 60, 200.0);
        setHigh(tied, 66, 200.0);

        assertThat(identifyMature(tied, 60).pivotBIndex())
                .as("과거 방향이 `>=`라 동점에 걸려 60이 탈락한다. `>`로 뒤집으면 대조군과 같은 "
                        + "60이 나와 이 단언이 깨진다")
                .isEqualTo(-1);
    }

    @Test
    @DisplayName("VW4는 strict < 라 동점이면 먼저 본 쪽(더 과거)이 피벗 C로 남는다")
    void pivotCKeepsTheEarlierOfTiedLows() {
        List<WeeklyBar> weeks = flatWeeks(260);
        int pivotB = 40;
        setHigh(weeks, pivotB, 200.0);
        // 피벗 B 이후(=더 최근) 구간에 같은 저가가 둘. 더 과거 쪽이 남아야 한다.
        setLow(weeks, 30, 50.0);
        setLow(weeks, 10, 50.0);

        ValueWidth vw = identifyMature(weeks, 60);

        assertThat(indexOf(weeks, vw.pivotCIndex()))
                .as("동점이면 더 과거(인덱스 30)가 피벗 C다. `<=`로 바꾸면 더 최근인 10이 남는다")
                .isEqualTo(30);
        assertThat(vw.pivotC()).isEqualTo(50.0);
    }

    /**
     * 진행 단계({@code drv_51 >= 9})로 고정해 VW1의 폴백 경로를 피한다.
     *
     * <p>폴백이면 피벗 B 탐색 자체가 돌지 않아({@code !is_fallback} 가드) 동점 규칙이
     * 출력에 흔적을 남기지 않는다 — 그러면 이 테스트가 항등식이 된다.
     */
    private static ValueWidth identifyMature(List<WeeklyBar> weeks, int t3yeok) {
        WeeklySeries series = WeeklySeries.of(weeks);
        int current = weeks.size() - 1;
        WeeklyWindow window = new WeeklyWindow(series, current, weeks.get(current));
        return ValueWidth.identify(window, t3yeok, false, true, false);
    }

    /** 창의 인덱스(0 = 현재)를 주봉 목록의 인덱스로 옮긴다. 테스트가 읽기 쉬우라고 둔다. */
    private static int indexOf(List<WeeklyBar> weeks, int windowIndex) {
        return windowIndex;
    }

    /** 전부 같은 값인 주봉. 여기에 고가·저가를 하나씩 심어 동점만 남긴다. */
    private static List<WeeklyBar> flatWeeks(int count) {
        List<WeeklyBar> weeks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            weeks.add(new WeeklyBar(START.plusWeeks(i), 100, 110, 90, 100, 1000, 5));
        }
        return weeks;
    }

    /** 창 인덱스로 심는다 — 0이 가장 최근이므로 목록에서는 뒤에서부터다. */
    private static void setHigh(List<WeeklyBar> weeks, int windowIndex, double high) {
        int j = weeks.size() - 1 - windowIndex;
        WeeklyBar bar = weeks.get(j);
        weeks.set(j, new WeeklyBar(bar.endDate(), bar.open(), high, bar.low(), bar.close(),
                bar.volume(), bar.days()));
    }

    private static void setLow(List<WeeklyBar> weeks, int windowIndex, double low) {
        int j = weeks.size() - 1 - windowIndex;
        WeeklyBar bar = weeks.get(j);
        weeks.set(j, new WeeklyBar(bar.endDate(), bar.open(), bar.high(), low, bar.close(),
                bar.volume(), bar.days()));
    }
}
