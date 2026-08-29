package com.stockscreener.web;

import com.stockscreener.indicator.IndicatorCalendar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 세 경계가 전부 "결과 0건"으로 보이는 문제를 각각 다른 응답으로 가르는 것이 이 클래스의 전부다.
 * 달력은 손으로 만든 스텁을 쓴다 — 여기서 검증할 것은 SQL이 아니라 <b>정책</b>이다.
 */
class AsOfResolverTest {

    /** 시장은 이 테스트의 축이 아니다 — 정책만 본다. 시장 분리는 IndicatorCalendarTest가 잰다. */
    private static final java.util.Set<String> MARKET = java.util.Set.of("KOSPI");

    private static final LocalDate LATEST = LocalDate.of(2026, 8, 5);
    private static final LocalDate EARLIEST = LocalDate.of(2025, 11, 3);

    @Test
    @DisplayName("일자를 비우면 최신 지표 일자로 정해진다")
    void resolvesToLatestWhenUnspecified() {
        assertThat(resolver(LATEST, EARLIEST).resolve(null, MARKET)).isEqualTo(LATEST);
    }

    @Test
    @DisplayName("IMPORTANT — 배치가 밀려 오늘 지표가 없어도 거절하지 않고 최신으로 내린다")
    void clampsFutureRequestToLatest() {
        // 실제로 겪는 상황이다(오늘 08-06, 최신 지표 08-05). 거절하면 매일 아침 화면이
        // 깨지고, 응답의 asOf 에코가 밀렸다는 사실을 이미 드러내 준다.
        assertThat(resolver(LATEST, EARLIEST).resolve(LocalDate.of(2026, 8, 6), MARKET)).isEqualTo(LATEST);
    }

    @Test
    @DisplayName("휴장일은 그 이하 가장 가까운 거래일로 해석된다")
    void resolvesNonTradingDayDownwards() {
        AsOfResolver resolver = new AsOfResolver(new StubCalendar(LATEST, EARLIEST) {
            @Override
            public Optional<LocalDate> nearestOnOrBefore(LocalDate date, java.util.Collection<String> markets) {
                return Optional.of(LocalDate.of(2026, 7, 31));
            }
        });

        assertThat(resolver.resolve(LocalDate.of(2026, 8, 2), MARKET)).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    @DisplayName("IMPORTANT — 보존 구간 밖은 빈 결과가 아니라 400이고, 조회 가능한 구간을 알려준다")
    void rejectsDatesOutsideRetention() {
        AsOfResolver resolver = new AsOfResolver(new StubCalendar(LATEST, EARLIEST) {
            @Override
            public Optional<LocalDate> nearestOnOrBefore(LocalDate date, java.util.Collection<String> markets) {
                return Optional.empty();
            }
        });

        assertThatThrownBy(() -> resolver.resolve(LocalDate.of(2020, 1, 2), MARKET))
                .isInstanceOfSatisfying(ApiExceptions.IndicatorHistoryUnavailableException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(400);
                    assertThat(e.retryable()).isFalse();
                    // 구간 없이 거절만 하면 사용자는 날짜를 하나씩 시도하게 된다.
                    assertThat(e.properties())
                            .containsEntry("retentionFrom", EARLIEST)
                            .containsEntry("retentionTo", LATEST);
                });
    }

    @Test
    @DisplayName("IMPORTANT — 지표가 하나도 없으면 503이고 재시도 대상이다")
    void reportsNotReadyWhenNoIndicatorsAtAll() {
        AsOfResolver resolver = new AsOfResolver(new StubCalendar(null, null));

        assertThatThrownBy(() -> resolver.resolve(null, MARKET))
                .isInstanceOfSatisfying(ApiExceptions.IndicatorDataNotReadyException.class, e -> {
                    // 사용자가 고칠 수 있는 것이 없고 배치가 돌면 해결된다 —
                    // frontend.md의 "자동 짧은 재시도"가 겨냥하는 경우다.
                    assertThat(e.status().value()).isEqualTo(503);
                    assertThat(e.retryable()).isTrue();
                });
    }

    private static AsOfResolver resolver(LocalDate latest, LocalDate earliest) {
        return new AsOfResolver(new StubCalendar(latest, earliest));
    }

    /** DB 없이 정책만 검증하기 위한 스텁. */
    private static class StubCalendar extends IndicatorCalendar {
        private final LocalDate latest;
        private final LocalDate earliest;

        StubCalendar(LocalDate latest, LocalDate earliest) {
            super(null);
            this.latest = latest;
            this.earliest = earliest;
        }

        @Override
        public Optional<LocalDate> latestIndicatorDate(java.util.Collection<String> markets) {
            return Optional.ofNullable(latest);
        }

        @Override
        public Optional<LocalDate> earliestIndicatorDate(java.util.Collection<String> markets) {
            return Optional.ofNullable(earliest);
        }

        @Override
        public Optional<LocalDate> nearestOnOrBefore(LocalDate date, java.util.Collection<String> markets) {
            return Optional.ofNullable(latest);
        }
    }
}
