package com.stockscreener.pipeline.batch;

import com.stockscreener.indicator.LayerChange;
import com.stockscreener.indicator.PercentileCalculator;
import com.stockscreener.indicator.SectorRelativeCalculator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 한 거래일의 파생층(백분위 · 업종 상대값)을 낸다. <b>일시적 동시성 실패는 상한을 두고 그 날짜를 다시 계산한다.</b>
 *
 * <p>세 호출부(일일 갱신의 시장별 기준일 · 날짜 루프 백필 · 매 평일 화해 ②)가 같은 두 계산을 같은 순서로 부르므로
 * 한 자리에 모은다. 갈리면 한쪽만 재시도하고 다른 쪽은 교착 하나에 그날을 통째로 잃는다.
 *
 * <h2>왜 다시 계산하는가 — 건너뛰지 않고</h2>
 * <p>교착({@code PessimisticLockingFailureException})은 {@link TransientDataAccessException} — 다시 하면 값이 나오는
 * 실패다. 실행 318(2026-09-08)이 업종 상대값 SQL의 교착 하나로 FAILED였고, 같은 모양의 319가 2분 뒤 189/189로 성공했다.
 * 날짜를 건너뛰기만 하면 그날의 나머지 지표와 업종 상대값까지 다음 평일로 미뤄진다.
 *
 * <p><b>다시 해도 되는 이유</b>: 두 계산기는 지표마다 UPSERT → 안 쓴 자리 DELETE를 자동 커밋으로 낸다 — 같은 날짜를 다시
 * 돌리면 같은 값이 된다(멱등). 비일시적 실패(스키마 · 닫힌 풀 · cardinality 위반)는 다시 해도 같으므로 바로 던진다.
 */
@Component
public class DerivedDayCalculator {

    /** 한 날짜를 계산하는 최대 횟수. 교착 희생자는 대개 곧바로 다시 하면 된다(319). */
    static final int MAX_ATTEMPTS = 3;

    private static final long[] BACKOFF_MILLIS = {2_000, 5_000};

    private static final Logger log = LoggerFactory.getLogger(DerivedDayCalculator.class);

    private final PercentileCalculator percentileCalculator;
    private final SectorRelativeCalculator sectorRelativeCalculator;

    public DerivedDayCalculator(PercentileCalculator percentileCalculator,
                                SectorRelativeCalculator sectorRelativeCalculator) {
        this.percentileCalculator = percentileCalculator;
        this.sectorRelativeCalculator = sectorRelativeCalculator;
    }

    /**
     * @param backfillUniverseCode 모집단에 더할 비활성 유니버스 — 두 계산기의 인자 그대로({@code null}이면 없음)
     * @throws TransientDataAccessException 상한까지 다시 해도 일시적 실패가 이어지면 마지막 것을
     * @throws BatchShutdownException       재시도 대기 중 인터럽트(종료 신호)면
     */
    public LayerChange calculate(LocalDate tradeDate, String backfillUniverseCode) {
        // 시도마다 끝난 계산의 수를 누적한다 — 다시 할 때 앞 시도에서 이미 쓴 백분위는 「바뀐 행 0」으로 돌아오므로,
        // 마지막 시도의 반환만 쓰면 쓴 수가 줄어 보인다. 던진 계산 안에서 이미 커밋된 문장의 수는 못 센다.
        LayerChange done = LayerChange.NONE;
        for (int attempt = 1; ; attempt++) {
            try {
                done = done.plus(percentileCalculator.calculate(tradeDate, backfillUniverseCode));
                return done.plus(sectorRelativeCalculator.calculate(tradeDate, backfillUniverseCode));
            } catch (TransientDataAccessException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                long wait = BACKOFF_MILLIS[attempt - 1];
                log.warn("파생층 계산이 일시적으로 실패해 {}ms 뒤 다시 한다. tradeDate={} 시도={}/{} 원인={}",
                        wait, tradeDate, attempt, MAX_ATTEMPTS, e.getMessage());
                pause(wait);
            }
        }
    }

    /**
     * 한 거래일을 계산하되 실패를 그 날짜에 가둔다. 실패하면 {@code null}을 돌려주고 {@code failures}에 남긴다.
     *
     * <p>세 호출부(일일 갱신 · 날짜 루프 백필 · 화해 ②)가 이 한 자리를 쓴다 — 격리와 종료 신호 변환이 갈리면
     * 한쪽만 종료를 늦춘다. 날짜끼리 독립이고 계산기는 지표마다 자동 커밋이라 날짜를 건넌 섞임이 없다.
     *
     * <p>MUST: 종료 신호는 되던진다(ADR-0078 · {@code data-pipeline.md}) — 종료 중 풀 닫힘을 「그 날짜의 실패」로
     * 삼키면 남은 날짜만큼 종료가 늦어진다. 삼키지 않는 대가는 ERROR 로그와 호출부의 실패 수 · note · 알림이다.
     */
    LayerChange calculateIsolated(BatchExecutionContext execution, LocalDate tradeDate,
                                  String backfillUniverseCode, Failures failures) {
        try {
            return calculate(tradeDate, backfillUniverseCode);
        } catch (RuntimeException e) {
            RuntimeException signal = execution.asShutdownSignalIfAbandoned(e);
            if (signal instanceof BatchShutdownException) {
                throw signal;
            }
            log.error("파생층 한 거래일이 실패했다 — 나머지 날짜는 계속 돈다. tradeDate={}", tradeDate, e);
            failures.add(tradeDate, e);
            return null;
        }
    }

    /** 실패한 거래일. note와 알림 본문에 앞 다섯과 첫 원인의 종류를 싣는다. */
    static final class Failures {
        private final List<LocalDate> dates = new ArrayList<>();
        private RuntimeException first;

        void add(LocalDate tradeDate, RuntimeException cause) {
            dates.add(tradeDate);
            if (first == null) {
                first = cause;
            }
        }

        boolean isEmpty() {
            return dates.isEmpty();
        }

        int size() {
            return dates.size();
        }

        RuntimeException first() {
            return first;
        }

        String describe() {
            return "실패 거래일 %d(%s%s · 첫 원인 %s)".formatted(dates.size(),
                    dates.stream().limit(5).map(LocalDate::toString).collect(Collectors.joining(", ")),
                    dates.size() > 5 ? " …" : "", first.getClass().getSimpleName());
        }
    }

    /** 재시도 사이 대기. 시험이 기다리지 않게 덮어쓸 수 있도록 따로 둔다. */
    void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BatchShutdownException("파생층 재시도를 기다리는 중에 인터럽트됐다", e);
        }
    }
}
