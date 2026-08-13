package com.stockscreener.indicator;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

/**
 * 원본 값이 사라진 파생 지표 행을 지운다.
 *
 * <p>{@link PercentileCalculator}와 {@link SectorRelativeCalculator}는 저장된
 * {@code indicator_value}를 <b>다시 읽어</b> 파생 값을 쓴다. 둘 다 {@code INSERT … SELECT …
 * ON CONFLICT}뿐이라 <b>원본이 사라져도 파생 행은 그대로 남는다</b> — SELECT가 그 종목을 안
 * 뽑을 뿐이고, 남은 파생 값은 여전히 그럴듯한 숫자다.
 *
 * <p>원본이 사라지는 경로는 둘이다. 하나는 미보정 기업 행위로 값을 내지 않기로 한 것
 * (ADR-0055)이고, 다른 하나는 원래부터 있던 자리 — 모집단이 1이거나 업종 표본이 임계에 못
 * 미쳐 그날 파생 값을 쓰지 않는 경우다. 후자에서도 <b>어제 쓴 행이 오늘 값처럼 남는다.</b>
 *
 * <p>지금 실제로 걸리는 규모는 작다({@code MACD_V_PCTL} 수십 행). 그것은 백분위·업종 상대값의
 * 원본 아홉 중 가격 기반이 {@code MACD_V} 하나뿐이고 나머지가 재무 지표
 * ({@code effectiveLookback} 1~2)라 원리적으로 억제되지 않기 때문이며, <b>구조가 아니라
 * 우연이다.</b> 가격 기반 백분위를 하나만 더 선언하면 바로 커진다.
 */
final class DerivedIndicatorCleanup {

    /**
     * IMPORTANT — 판정 기준이 "파생 값이 있는데 원본이 없다"이지 "이번에 안 썼다"가 아니다.
     * 후자로 쓰면 값이 안 바뀌어 UPSERT가 0행을 낸 날에도 지우게 된다
     * ({@code IS DISTINCT FROM} 때문에 그런 날이 대부분이다).
     */
    private static final String DELETE_ORPHANS = """
            DELETE FROM indicator_value derived
            WHERE derived.indicator_code = ?
              AND derived.trade_date = ?
              AND NOT EXISTS (
                  SELECT 1 FROM indicator_value source
                  WHERE source.stock_id = derived.stock_id
                    AND source.trade_date = derived.trade_date
                    AND source.indicator_code = ?
              )
            """;

    private DerivedIndicatorCleanup() {
    }

    /** @return 지운 행 수 */
    static int removeOrphans(JdbcTemplate jdbcTemplate, String derivedCode, String sourceCode,
                             LocalDate tradeDate) {
        return jdbcTemplate.update(DELETE_ORPHANS, derivedCode, tradeDate, sourceCode);
    }
}
