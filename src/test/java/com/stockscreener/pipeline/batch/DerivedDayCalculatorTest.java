package com.stockscreener.pipeline.batch;

import com.stockscreener.indicator.LayerChange;
import com.stockscreener.indicator.PercentileCalculator;
import com.stockscreener.indicator.SectorRelativeCalculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 한 거래일 파생층의 재시도 — 일시적 동시성 실패만 상한까지 다시 하고, 비일시적 실패는 바로 던진다.
 *
 * <p>실행 318(2026-09-08)이 업종 상대값 SQL의 교착 하나로 FAILED였고 같은 모양의 319가 2분 뒤 성공했다 —
 * 그 교착을 {@link PessimisticLockingFailureException}으로 꾸민다.
 */
class DerivedDayCalculatorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 8);

    private final PercentileCalculator percentile = mock(PercentileCalculator.class);
    private final SectorRelativeCalculator sector = mock(SectorRelativeCalculator.class);
    private final List<Long> waits = new ArrayList<>();
    private final DerivedDayCalculator calculator = new DerivedDayCalculator(percentile, sector) {
        @Override
        void pause(long millis) {
            waits.add(millis);
        }
    };

    @Test
    @DisplayName("IMPORTANT — 교착 한 번 뒤 다시 하면 그날을 쓴다 — 건너뛰지 않는다")
    void retriesATransientFailure() {
        // 실제 계산기는 바뀐 행만 센다 — 다시 할 때 앞 시도에서 이미 쓴 백분위는 (0, 0)으로 돌아온다.
        when(percentile.calculate(DAY, null)).thenReturn(new LayerChange(5, 1), new LayerChange(0, 0));
        when(sector.calculate(DAY, null))
                .thenThrow(new PessimisticLockingFailureException("deadlock detected"))
                .thenReturn(new LayerChange(2, 0));

        LayerChange result = calculator.calculate(DAY, null);

        assertThat(result).as("앞 시도에서 쓴 백분위가 집계에서 빠지면 note의 「쓴」이 줄어 보인다")
                .isEqualTo(new LayerChange(7, 1));
        assertThat(waits).hasSize(1);
    }

    @Test
    @DisplayName("상한까지 일시적 실패가 이어지면 마지막 것을 던진다 — 호출부가 그 날짜를 가둔다")
    void givesUpAfterTheLimit() {
        when(percentile.calculate(DAY, null)).thenThrow(new PessimisticLockingFailureException("deadlock"));

        assertThatThrownBy(() -> calculator.calculate(DAY, null))
                .isInstanceOf(PessimisticLockingFailureException.class);
        verify(percentile, times(DerivedDayCalculator.MAX_ATTEMPTS)).calculate(DAY, null);
        assertThat(waits).hasSize(DerivedDayCalculator.MAX_ATTEMPTS - 1);
    }

    @Test
    @DisplayName("비일시적 실패는 다시 하지 않는다 — 다시 해도 같은 실패다(스키마 · cardinality 위반)")
    void doesNotRetryANonTransientFailure() {
        when(percentile.calculate(DAY, null)).thenThrow(new DataIntegrityViolationException("cardinality"));

        assertThatThrownBy(() -> calculator.calculate(DAY, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(percentile, times(1)).calculate(DAY, null);
        assertThat(waits).isEmpty();
    }
}
