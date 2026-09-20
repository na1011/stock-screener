package com.stockscreener.pipeline.scheduler;

import com.stockscreener.pipeline.batch.DailyUpdateJob;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.lang.reflect.Method;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스케줄이 <b>거래일을 모두</b> 덮는지 본다.
 *
 * <p>이 테스트가 있는 이유는 실제로 하나가 새고 있었기 때문이다. 예전 구성은 평일 새벽 4시에
 * <b>전일</b>을 대상으로 돌았는데, 그러면 금요일 종가를 받는 실행이 어느 요일에도 없다 —
 * 월요일 새벽 실행의 대상은 일요일이라 주말로 걸러지고, 토요일에는 실행이 없다.
 * 컴파일도 되고 테스트도 통과하며, 알림도 오지 않는다. 매주 하루치가 조용히 빈다.
 *
 * <p>IMPORTANT — 이제 요소가 <b>셋</b>이다: 크론 · {@code targetDateFor} · 조회 창. 어느 거래일이
 * 덮이는가는 셋을 합쳐야 나오고, 둘만 보면 공휴일 앞 거래일이 새는 것을 놓친다(ADR-0042).
 *
 * <p>크론 문자열을 애너테이션에서, 창 폭을 {@link DailyUpdateJob}에서 <b>직접 읽는다.</b>
 * 테스트에 상수로 옮겨 적으면 그 상수와 실제 구성이 갈릴 수 있고, 그러면 이 테스트는 자기
 * 자신을 검사하는 것이 된다.
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

            LocalDate target = DailyUpdateJob.targetDateFor(next.toLocalDate());
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
    @DisplayName("일일 갱신은 소스 공개 시각(13시) 이후에 돌고, 대상은 실행일보다 앞이다")
    void dailyUpdateRunsAfterSourcePublication() {
        LocalDateTime next = cronOf("runDailyUpdate").next(LocalDate.of(2026, 8, 10).atStartOfDay());

        assertThat(next).isNotNull();
        // 소스는 거래일 T의 종가를 T의 다음 영업일 **13시 이후**에 낸다. 지켜야 할 불변식은
        // 그 **하한 13시**이지 지금 고른 시각이 아니다.
        //
        // MUST NOT: 이 단언을 실제 크론 시각(13:30)으로 못박지 않는다. 그러면 이 테스트가
        // 애너테이션의 거울이 되어 **자기 자신을 검사한다** — 크론을 어디로 옮기든 함께
        // 따라가므로 아무것도 막지 못한다. ADR-0042가 창 폭에 대해 경고하는 것과 같은 형태다.
        //
        // 실제 여유는 30분뿐이다. "이후"는 하한만 보증하므로 늦게 나오는 날이 있을 수 있는데,
        // 그날은 15일 창이 메우고 반복되면 0건 경고가 매일 떠 시각이 이르다는 것이
        // 드러난다(ADR-0042 §결정).
        assertThat(next.toLocalTime())
                .as("13시 전에 돌면 그날 아직 나오지 않은 것을 긁는다")
                .isAfterOrEqualTo(LocalTime.of(13, 0));

        // IMPORTANT — 시각 단언만으로는 부족하다. **옛 구성(20시 + 당일 대상)도 이 단언은
        // 통과하면서** 매일 0건으로 끝났다. 대상이 실행일보다 앞이라는 것이 D+1을 실제로
        // 인코딩하는 단언이다.
        assertThat(DailyUpdateJob.targetDateFor(next.toLocalDate()))
                .as("당일을 대상으로 하면 그날 종가는 자정까지도 소스에 없다")
                .isBefore(next.toLocalDate());
    }

    @Test
    @DisplayName("공휴일이 낀 주에도 모든 거래일이 어느 실행엔가 덮인다 — 공휴일 앞 거래일이 새던 자리다")
    void noTradingDayIsMissedAroundHolidays() {
        // 합성 휴장일. 실제 공휴일 달력이 아니라 "휴장이 낀 주"를 만드는 것이 요점이라
        // 역사적 사실에 기대지 않는다. 화요일 하나, 그리고 수~목 이틀 연속.
        Set<LocalDate> closed = Set.of(
                LocalDate.of(2026, 8, 11),                                  // 화
                LocalDate.of(2026, 8, 26), LocalDate.of(2026, 8, 27));      // 수·목

        LocalDate from = LocalDate.of(2026, 8, 3);
        LocalDate tradingUntil = LocalDate.of(2026, 9, 4);
        // 실행은 대상 구간보다 뒤까지 본다. 구간 끝의 거래일도 그 뒤 실행이 받기 때문이다.
        LocalDate runsUntil = LocalDate.of(2026, 9, 30);

        List<LocalDate> runs = weekdaysBetween(from, runsUntil);

        for (LocalDate tradingDay : openDaysBetween(from, tradingUntil, closed)) {
            // 소스는 거래일 T를 T의 다음 **영업일**에 낸다 — 휴장을 건너뛴다. 이 한 줄이
            // 결손의 발생 기제 자체이므로 테스트가 스스로 모형을 갖는다.
            LocalDate publishedOn = nextOpenDay(tradingDay, closed);

            boolean covered = runs.stream().anyMatch(run -> {
                if (run.isBefore(publishedOn)) {
                    return false;                       // 그 실행 시점엔 아직 안 나왔다
                }
                LocalDate to = DailyUpdateJob.targetDateFor(run);
                return !tradingDay.isBefore(DailyUpdateJob.priceWindowStart(to))
                        && !tradingDay.isAfter(to);
            });

            assertThat(covered)
                    .as("%s(%s) 종가를 받을 수 있는 실행이 하나도 없다", tradingDay,
                            tradingDay.getDayOfWeek())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("조회 창이 장기 연휴를 건너뛴다 — 좁으면 연휴 앞 거래일이 영구히 샌다")
    void priceWindowCoversLongClosure() {
        // 11일 연속 휴장(9/12 토 ~ 9/22 화)을 합성한다. 앞 거래일은 9/11(금), 그것을 받을 수
        // 있는 첫 실행은 9/23(수)이고 그날의 대상일은 9/22(화)다. 창이 9/11에 못 닿으면
        // 첫 기회를 놓치는 것이 아니라 **기회 자체가 없다** — 이후 실행은 대상일만 더 멀어진다.
        LocalDate lastBeforeClosure = LocalDate.of(2026, 9, 11);
        LocalDate firstRunAfter = LocalDate.of(2026, 9, 23);

        LocalDate to = DailyUpdateJob.targetDateFor(firstRunAfter);

        assertThat(DailyUpdateJob.priceWindowStart(to))
                .as("창이 연휴를 건너뛰지 못하면 %s 종가를 받을 실행이 없다", lastBeforeClosure)
                .isBeforeOrEqualTo(lastBeforeClosure);
    }

    @Test
    @DisplayName("리밸런싱은 토요일에만 돌아 일일 갱신과 겹치지 않는다")
    void rebalanceDoesNotCollideWithDailyUpdate() {
        LocalDateTime next = cronOf("runUniverseRebalance").next(LocalDate.of(2026, 8, 10).atStartOfDay());

        assertThat(next).isNotNull();
        assertThat(next.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
        assertThat(TRADING_DAYS).doesNotContain(next.getDayOfWeek());
    }

    @Test
    @DisplayName("기업 행위 사슬이 일일 갱신 뒤에 돈다 — 앞이 끝나기 전에 시작하면 같은 봉을 함께 쓴다")
    void corporateActionChainRunsAfterDailyUpdate() {
        LocalDateTime base = LocalDate.of(2026, 9, 2).atStartOfDay();
        LocalDateTime daily = cronOf("runDailyUpdate").next(base);
        LocalDateTime chain = cronOf("runCorporateActionChain").next(base);

        assertThat(daily).isNotNull();
        assertThat(chain).isNotNull();
        assertThat(chain.toLocalDate()).isEqualTo(daily.toLocalDate());
        assertThat(chain.toLocalTime()).isAfter(daily.toLocalTime());
        // 일일 갱신이 실측 13분 36초다. 여유가 그보다 넉넉해야 밀리지 않는다.
        assertThat(java.time.Duration.between(daily, chain).toMinutes())
                .isGreaterThanOrEqualTo(15);
    }

    @Test
    @DisplayName("무거운 잡과 신선도 점검이 사슬 뒤에 온다 — 순서가 뒤집히면 그날의 판정이 낡는다")
    void heavyJobAndFreshnessComeAfterTheChain() {
        LocalDateTime base = LocalDate.of(2026, 9, 2).atStartOfDay();
        LocalTime chain = cronOf("runCorporateActionChain").next(base).toLocalTime();
        LocalTime heavy = cronOf("runHeavyJobOfTheDay").next(base).toLocalTime();
        LocalTime freshness = cronOf("checkJobFreshness").next(base).toLocalTime();

        assertThat(heavy).isAfter(chain);
        // governance-load의 실측이 **54분 58초**다(2026-09-20 · 1,350종목 12,050콜 = 국내 8,050 +
        // 미국 4,000). 스레드 풀이 1이라 그 전에는 신선도 점검이 밀리므로 여유를 그보다 크게 둔다.
        //
        // **소요는 콜 수가 아니라 소스별 리미터가 정한다**(dart 100ms · alpaca 600ms). 그 모델이
        // 두 실측을 다 재현한다 — 11,050콜에 43분 25초(실측 44분 52초) · 12,050콜에 53분 25초
        // (실측 54분 58초)로 오차 3% 안이다.
        //
        // **이 수는 코드 변경 없이도 자란다** — 21분 30초 → 44분 52초 → 54분 58초. 14시 30분이면
        // 여유가 5분뿐이라 무거운 잡을 **14시 15분**으로 당겼고, 그래서 간격이 75분이다.
        //
        // 여기 있던 22는 「1,290초(21분 30초)」가 근거였는데 그 수는 `--governance-endpoints=DIVIDEND`로
        // **좁힌 판**의 것이었다. 정기 실행은 표 여덟을 다 부르고, 2026-09-20에 표별 기본 해 수가
        // 회복되면서(그전에는 span=2가 깎고 있었다) 소요가 배로 늘었다. **간격 40분으로는
        // 부족해져 점검 시각을 15:30으로 옮겼다** — 시험이 초록인 채로 운영만 밀리던 자리다.
        assertThat(java.time.Duration.between(heavy, freshness).toMinutes())
                .isGreaterThanOrEqualTo(70);
    }

    @Test
    @DisplayName("새 스케줄 셋이 전부 평일에만 돈다 — 주말에 돌면 대상일이 비어 헛돈다")
    void newSchedulesRunOnWeekdaysOnly() {
        for (String method : List.of("runCorporateActionChain", "runHeavyJobOfTheDay",
                "checkJobFreshness")) {
            CronExpression cron = cronOf(method);
            LocalDateTime at = LocalDate.of(2026, 9, 4).atStartOfDay();  // 금요일
            for (int i = 0; i < 10; i++) {
                at = cron.next(at);
                assertThat(at).isNotNull();
                assertThat(TRADING_DAYS)
                        .as("%s의 %s", method, at)
                        .contains(at.getDayOfWeek());
            }
        }
    }

    /** 주어진 일자 다음의 첫 개장일 — 주말도 휴장일도 아닌 날. */
    private static LocalDate nextOpenDay(LocalDate date, Set<LocalDate> closed) {
        LocalDate next = date.plusDays(1);
        while (!isOpen(next, closed)) {
            next = next.plusDays(1);
        }
        return next;
    }

    private static boolean isOpen(LocalDate date, Set<LocalDate> closed) {
        return TRADING_DAYS.contains(date.getDayOfWeek()) && !closed.contains(date);
    }

    /** 크론이 {@code MON-FRI}라 실행은 휴장일에도 있다 — 그날 대상이 비어 있을 뿐이다. */
    private static List<LocalDate> weekdaysBetween(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (TRADING_DAYS.contains(d.getDayOfWeek())) {
                days.add(d);
            }
        }
        return days;
    }

    private static List<LocalDate> openDaysBetween(LocalDate from, LocalDate to,
                                                   Set<LocalDate> closed) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (isOpen(d, closed)) {
                days.add(d);
            }
        }
        return days;
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
