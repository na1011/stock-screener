package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.MarketSessionCalendar;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 미국 일일 갱신({@code us-daily-update})의 대상 세션을 세션 달력으로 정한다.
 *
 * <p><b>규칙</b>: 거래소 {@link #EXCHANGE}의 세션 중 {@code core_end + }{@link #SETTLE_AFTER_CLOSE}가 지금 이전인 마지막 세션.
 * 소스는 일봉 거래량을 다음 날 뉴욕 새벽에 고친다 — 실측한 늦은 끝이 뉴욕 05:40 = 장 마감 + 13:40이라 14시간을 둔다
 * ({@code docs/api-spec/alpaca-bars.md} 「일봉 거래량은 다음 날 뉴욕 새벽까지 잠정이다」). 그래서 21:00 발화가 기계 수면으로
 * 자정을 넘겨 늦어도 <b>장중 세션을 대상으로 잡지 않는다</b> — 국내의 「직전 평일」({@link DailyUpdateJob#targetDateFor})을 쓰면
 * 뉴욕 장중에 그날 세션의 잠정 봉을 받는다.
 *
 * <p><b>정하지 못하는 두 경우</b>는 「0건 성공」이 아니라 실패다({@link DailyUpdateJob#runUs}가 FAILED 행 · ERROR 알림으로 끝낸다):
 * <ul>
 *   <li><b>달력이 낡았다</b> — 적재된 최대 세션일이 {@link #expectedSession 휴장을 모를 때 기대하는 세션}보다 앞이다. 그대로 두면
 *       규칙이 오래된 세션을 골라 15일 창이 같은 날들을 매일 다시 받고 새 봉은 하나도 안 들어오는데 실행은 초록이다. 달력은 같은 사슬
 *       맨 앞에서 오늘(KST)까지 받으므로 하루 이틀 실패해도 어제 받은 오늘 세션이 남아 이 판정에 안 걸린다 — 걸리는 것은 그보다
 *       오래 멈춘 경우다.</li>
 *   <li><b>확정된 세션이 없다</b> — 달력이 비었다.</li>
 * </ul>
 *
 * <p>사정거리: 기대 세션은 평일 16:00(뉴욕) 마감으로 센다. 그 평일이 휴장이어도 달력은 대개 그 뒤 세션(오늘 예정 세션)까지 실려 있어
 * 거짓 낡음이 안 난다. 평일 이틀 넘게 이어지는 비정기 휴장(2012-10 허리케인 샌디 꼴)의 둘째 날에는 낡음으로 알린다 — 드문 일이고 그날
 * 실제로 받을 봉이 없다.
 */
@Component
public class UsSessionTarget {

    /**
     * 대상 세션을 묻는 거래소. 일일 갱신의 대상일은 시장(US) 하나라 거래소 하나로 묻는다 — 두 거래소의 세션이 같다는 것은 2024년 한 해의
     * 실측이다({@code alpaca-calendar.md}). 갈리는 날이 생기면 NASDAQ 종목의 그날 봉은 다음 실행의 15일 창이 받는다.
     */
    public static final String EXCHANGE = "NYSE";

    /** 장 마감 뒤 거래량이 확정될 때까지의 여유. 실측의 늦은 끝(장 마감 + 13:40) 위다. */
    public static final Duration SETTLE_AFTER_CLOSE = Duration.ofHours(14);

    static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime NOMINAL_CLOSE = LocalTime.of(16, 0);

    private final MarketSessionCalendar calendar;

    public UsSessionTarget(MarketSessionCalendar calendar) {
        this.calendar = calendar;
    }

    /** 판정 — 정했으면 {@link Resolved}, 못 정했으면 {@link Unresolved}(사유와 기대 세션). */
    public sealed interface Resolution permits Resolved, Unresolved {
    }

    /** @param session 대상 세션(뉴욕 날짜) */
    public record Resolved(LocalDate session) implements Resolution {
    }

    /**
     * @param reason   운영자에게 보낼 사유
     * @param expected 휴장을 모를 때 기대하는 세션 — 실패 행의 기준일로 쓴다
     */
    public record Unresolved(String reason, LocalDate expected) implements Resolution {
    }

    public Resolution resolve(Instant now) {
        return judge(now, calendar.latestSessionEndedBy(EXCHANGE, now.minus(SETTLE_AFTER_CLOSE)),
                calendar.latestSession(EXCHANGE));
    }

    /**
     * 순수 판정. {@code public}인 이유는 {@code PipelineSchedulerTest}가 크론 · 이 규칙 · 조회 창 셋을 합쳐 「모든 세션이 어느 실행엔가
     * 덮이는가」를 묻기 때문이다 — 시험에 규칙을 옮겨 적으면 그 시험이 자기 자신을 검사한다.
     *
     * @param settled     {@code core_end + 14h <= now}인 마지막 세션
     * @param calendarMax 달력에 적재된 마지막 세션
     */
    public static Resolution judge(Instant now, Optional<LocalDate> settled, Optional<LocalDate> calendarMax) {
        LocalDate expected = expectedSession(now);
        if (calendarMax.isEmpty()) {
            return new Unresolved("세션 달력(" + EXCHANGE + ")이 비어 있어 미국 대상 세션을 정하지 못했다 — "
                    + "--job=market-calendar로 달력을 받는다.", expected);
        }
        if (calendarMax.get().isBefore(expected)) {
            return new Unresolved(("세션 달력(%s)이 낡았다 — 적재된 마지막 세션 %s가 기대 세션 %s보다 앞이다. 낡은 달력으로 고르면 "
                    + "오래된 세션이 대상이 되어 새 봉이 안 들어오는데 실행은 초록이다. 같은 사슬 맨 앞의 세션 달력 수집이 "
                    + "실패했는지 본다.").formatted(EXCHANGE, calendarMax.get(), expected), expected);
        }
        if (settled.isEmpty()) {
            return new Unresolved("세션 달력(" + EXCHANGE + ")에 거래량이 확정된 세션(장 마감 + "
                    + SETTLE_AFTER_CLOSE.toHours() + "시간 경과)이 없다.", expected);
        }
        return new Resolved(settled.get());
    }

    /**
     * 휴장을 모를 때 기대하는 마지막 확정 세션 — 평일 16:00(뉴욕) 마감 + {@link #SETTLE_AFTER_CLOSE}가 지금 이전인 가장 늦은 평일.
     */
    static LocalDate expectedSession(Instant now) {
        LocalDate day = now.atZone(NEW_YORK).toLocalDate();
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY
                || day.atTime(NOMINAL_CLOSE).atZone(NEW_YORK).toInstant().plus(SETTLE_AFTER_CLOSE).isAfter(now)) {
            day = day.minusDays(1);
        }
        return day;
    }
}
