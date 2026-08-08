package com.stockscreener.pipeline.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.lang.reflect.Method;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스케줄이 <b>다섯 거래일을 모두</b> 덮는지 본다.
 *
 * <p>이 테스트가 있는 이유는 실제로 하나가 새고 있었기 때문이다. 예전 구성은 평일 새벽 4시에
 * <b>전일</b>을 대상으로 돌았는데, 그러면 금요일 종가를 받는 실행이 어느 요일에도 없다 —
 * 월요일 새벽 실행의 대상은 일요일이라 주말로 걸러지고, 토요일에는 실행이 없다.
 * 컴파일도 되고 테스트도 통과하며, 알림도 오지 않는다. 매주 하루치가 조용히 빈다.
 *
 * <p>크론 문자열을 애너테이션에서 <b>직접 읽는다.</b> 테스트에 상수로 옮겨 적으면 그 상수와
 * 실제 스케줄이 갈릴 수 있고, 그러면 이 테스트는 자기 자신을 검사하는 것이 된다.
 */
class PipelineSchedulerTest {

    private static final Set<DayOfWeek> TRADING_DAYS = Set.of(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    @Test
    @DisplayName("일일 갱신이 월~금 다섯 거래일을 모두 덮는다 — 금요일 종가가 새던 자리다")
    void dailyUpdateCoversEveryTradingDay() {
        CronExpression cron = cronOf("runDailyUpdate");

        // IMPORTANT — 크론만 보면 안 된다. 어느 거래일이 덮이는가는 **실행 요일과 대상 일자
        // 규칙의 조합**이 정하고, 구멍은 정확히 그 조합에서 생겼다. 옛 구성(평일 04시 +
        // 전일 대상)도 크론만 보면 월~금 다섯 번 돌아 멀쩡해 보인다.
        Set<DayOfWeek> covered = new HashSet<>();
        LocalDateTime cursor = LocalDate.of(2026, 8, 10).atStartOfDay(); // 월요일 00:00
        for (int i = 0; i < 10; i++) {
            LocalDateTime next = cron.next(cursor);
            assertThat(next).as("%d번째 실행이 없다", i).isNotNull();
            cursor = next;

            LocalDate target = PipelineScheduler.targetDateFor(next.toLocalDate());
            // 주말이 대상이면 DailyUpdateJob이 아무것도 하지 않고 돌아간다.
            if (!TRADING_DAYS.contains(target.getDayOfWeek())) {
                continue;
            }
            covered.add(target.getDayOfWeek());
        }

        assertThat(covered)
                .as("덮이지 않는 거래일이 있으면 매주 그 하루가 조용히 빈다")
                .isEqualTo(TRADING_DAYS);
    }

    @Test
    @DisplayName("일일 갱신은 국내 장 마감(15:30) 이후에 돈다")
    void dailyUpdateRunsAfterMarketClose() {
        LocalDateTime next = cronOf("runDailyUpdate").next(LocalDate.of(2026, 8, 10).atStartOfDay());

        assertThat(next).isNotNull();
        assertThat(next.toLocalTime())
                .as("당일 종가를 대상으로 하므로 마감 전에 돌면 그날 데이터가 없다")
                .isAfter(java.time.LocalTime.of(15, 30));
    }

    @Test
    @DisplayName("리밸런싱은 토요일에만 돌아 일일 갱신과 겹치지 않는다")
    void rebalanceDoesNotCollideWithDailyUpdate() {
        LocalDateTime next = cronOf("runUniverseRebalance").next(LocalDate.of(2026, 8, 10).atStartOfDay());

        assertThat(next).isNotNull();
        assertThat(next.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
        assertThat(TRADING_DAYS).doesNotContain(next.getDayOfWeek());
    }

    private static CronExpression cronOf(String methodName) {
        for (Method method : PipelineScheduler.class.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            assertThat(scheduled).as("%s에 @Scheduled가 없다", methodName).isNotNull();
            return CronExpression.parse(scheduled.cron());
        }
        throw new AssertionError(methodName + " 메서드를 찾을 수 없다");
    }
}
