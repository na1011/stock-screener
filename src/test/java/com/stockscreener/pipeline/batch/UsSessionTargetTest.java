package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.MarketSessionCalendar;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미국 일일 갱신의 대상 세션 판정({@link UsSessionTarget}) — 장 마감 + 14시간이 지난 마지막 세션, 달력이 낡았으면 정하지 않는다.
 *
 * <p>달력 행은 실제 표에 넣고 판정은 실제 질의로 묻는다({@code core_end}가 timestamptz라 시간대가 틀리면 하루가 밀린다). 읽기라
 * {@code @Transactional} 롤백으로 격리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
@Transactional
class UsSessionTargetTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MarketSessionCalendar calendar;

    private UsSessionTarget target;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM market_calendar WHERE exchange_code = 'NYSE'");
        target = new UsSessionTarget(calendar);
        // 2026-10-26(월) ~ 10-30(금) 다섯 세션. 10-27(화)은 합성 조기폐장(13:00) — 앞당겨진 종료가 그대로 쓰이는지 본다.
        for (String day : new String[] {"2026-10-26", "2026-10-28", "2026-10-29", "2026-10-30"}) {
            session(day, "16:00");
        }
        session("2026-10-27", "13:00");
    }

    @Test
    @DisplayName("평일 21:00 KST는 뉴욕 전날 세션을 고른다 — 그날 세션은 아직 장 전이다")
    void eveningFirePicksThePreviousSession() {
        assertThat(target.resolve(kst("2026-10-29T21:00"))).isEqualTo(new UsSessionTarget.Resolved(LocalDate.of(2026, 10, 28)));
    }

    @Test
    @DisplayName("IMPORTANT — 늦은 발화(자정을 넘겨 뉴욕 장중)도 장중 세션을 안 잡는다 — 「직전 평일」이면 잠정 봉을 받는다")
    void lateFireDoesNotPickTheSessionInProgress() {
        // 10-30 02:00 KST = 10-29 13:00 뉴욕(EDT) — 10-29 세션이 열려 있다. 국내 규칙(직전 평일)이면 10-29다.
        assertThat(DailyUpdateJob.targetDateFor(LocalDate.of(2026, 10, 30))).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(target.resolve(kst("2026-10-30T02:00"))).isEqualTo(new UsSessionTarget.Resolved(LocalDate.of(2026, 10, 28)));
        // 장 마감(16:00 뉴욕) 뒤라도 14시간 전이면 아직 거래량이 잠정이다 — 10-30 06:00 KST = 10-29 17:00 뉴욕.
        assertThat(target.resolve(kst("2026-10-30T06:00"))).isEqualTo(new UsSessionTarget.Resolved(LocalDate.of(2026, 10, 28)));
    }

    @Test
    @DisplayName("확정 여유는 core_end에서 잰다 — 조기폐장일은 앞당겨진 종료 + 14시간이다")
    void earlyCloseCountsFromItsOwnEnd() {
        // 10-27 13:00 뉴욕 종료 + 14h = 10-28 03:00 뉴욕 = 10-28 16:00 KST.
        assertThat(target.resolve(kst("2026-10-28T16:00"))).isEqualTo(new UsSessionTarget.Resolved(LocalDate.of(2026, 10, 27)));
        assertThat(target.resolve(kst("2026-10-28T15:59"))).isEqualTo(new UsSessionTarget.Resolved(LocalDate.of(2026, 10, 26)));
    }

    @Test
    @DisplayName("휴장일 다음 저녁은 휴장 앞 세션을 고르고 낡음으로 안 읽는다")
    void holidayIsSkippedWithoutStaleness() {
        jdbcTemplate.update("DELETE FROM market_calendar WHERE exchange_code = 'NYSE' AND session_date = '2026-10-28'");

        // 10-29 21:00 KST — 기대 세션(평일 규칙)은 10-28인데 그날이 휴장이다. 달력은 10-30까지 실려 있다.
        assertThat(target.resolve(kst("2026-10-29T21:00"))).isEqualTo(new UsSessionTarget.Resolved(LocalDate.of(2026, 10, 27)));
    }

    @Test
    @DisplayName("IMPORTANT — 달력이 낡았으면 정하지 않는다 — 낡은 달력으로 고르면 옛 세션이 대상이 되어 새 봉이 0인데 실행은 초록이다")
    void staleCalendarIsUnresolved() {
        jdbcTemplate.update("DELETE FROM market_calendar WHERE exchange_code = 'NYSE' AND session_date > '2026-10-27'");

        UsSessionTarget.Resolution resolution = target.resolve(kst("2026-10-30T21:00"));

        assertThat(resolution).isInstanceOf(UsSessionTarget.Unresolved.class);
        assertThat(((UsSessionTarget.Unresolved) resolution).expected()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(((UsSessionTarget.Unresolved) resolution).reason()).contains("낡았다");
    }

    @Test
    @DisplayName("대조군: 달력이 하루 덜 받아도(어제 받은 오늘 세션이 있다) 낡음이 아니다 — 달력 수집 실패 하루로 미국 봉을 멈추지 않는다")
    void oneMissedCalendarLoadIsNotStale() {
        // 10-30 21:00 KST — 오늘 달력 수집이 실패해 10-30이 없다고 쳐도 어제(10-29 21:00) 받은 10-29가 있다.
        jdbcTemplate.update("DELETE FROM market_calendar WHERE exchange_code = 'NYSE' AND session_date = '2026-10-30'");

        assertThat(target.resolve(kst("2026-10-30T21:00"))).isEqualTo(new UsSessionTarget.Resolved(LocalDate.of(2026, 10, 29)));
    }

    @Test
    @DisplayName("달력이 비면 정하지 않는다")
    void emptyCalendarIsUnresolved() {
        assertThat(UsSessionTarget.judge(kst("2026-10-30T21:00"), Optional.empty(), Optional.empty()))
                .isInstanceOf(UsSessionTarget.Unresolved.class);
    }

    @Test
    @DisplayName("기대 세션은 평일 16:00(뉴욕) + 14시간 규칙이고 두 철 모두 21:00 KST에 전날이다")
    void expectedSessionAcrossDaylightSaving() {
        // EDT: 21:00 KST = 08:00 뉴욕. EST: 21:00 KST = 07:00 뉴욕. 둘 다 전날 16:00 + 14h(= 06:00) 뒤다.
        assertThat(UsSessionTarget.expectedSession(kst("2026-10-29T21:00"))).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(UsSessionTarget.expectedSession(kst("2026-12-03T21:00"))).isEqualTo(LocalDate.of(2026, 12, 2));
        // 월요일 저녁은 금요일이다.
        assertThat(UsSessionTarget.expectedSession(kst("2026-11-02T21:00"))).isEqualTo(LocalDate.of(2026, 10, 30));
    }

    private void session(String day, String closeNewYork) {
        jdbcTemplate.update("""
                INSERT INTO market_calendar (exchange_code, session_date, core_start, core_end)
                VALUES ('NYSE', ?::date, (?::date + time '09:30') AT TIME ZONE 'America/New_York',
                        (?::date + ?::time) AT TIME ZONE 'America/New_York')
                """, day, day, day, closeNewYork);
    }

    private static Instant kst(String localDateTime) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(localDateTime), SEOUL).toInstant();
    }
}
