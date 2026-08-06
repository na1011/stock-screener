package com.stockscreener.indicator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 보존 기간을 넘긴 지표 값을 지운다(ADR-0011).
 *
 * <p>스크리닝은 언제나 최신 일자 한 장만 읽고 지표 시계열 차트는 v1 범위 밖이므로, 오래된
 * 지표 행은 저장 비용만 발생시키고 아무도 읽지 않는다.
 *
 * <p>MUST NOT: 원자료({@code price_daily}·{@code financial_statement})는 지우지 않는다.
 * 지표는 원자료에서 재생성되지만 그 역은 불가능하고, 외부 API가 과거 구간을 계속 제공한다는
 * 보장도 없다. 이 클래스가 {@code indicator_value}만 건드리는 것이 그 비대칭의 이행이다.
 */
@Component
public class IndicatorRetentionPolicy {

    private static final Logger log = LoggerFactory.getLogger(IndicatorRetentionPolicy.class);

    /**
     * 보존할 거래일 수(ADR-0011).
     *
     * <p>200일 이동평균의 워밍업(200거래일)보다 짧아도 되는 이유는, 계산의 입력이
     * {@code price_daily}이지 {@code indicator_value}가 아니기 때문이다. 지표를 지워도
     * 다시 계산할 수 있다.
     */
    static final int RETAINED_TRADING_DAYS = 180;

    private final JdbcTemplate jdbcTemplate;

    public IndicatorRetentionPolicy(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 보존 경계보다 오래된 지표 값을 지운다.
     *
     * <p>경계를 달력이 아니라 <b>실제 거래일</b>로 센다. 달력으로 180일 전을 잡으면 휴장일이
     * 포함되어 실제 보존되는 거래일이 그보다 적어지고, 그 차이는 연휴가 긴 해에 커진다.
     *
     * @return 지운 행 수
     */
    public int purge() {
        List<LocalDate> boundary = jdbcTemplate.queryForList("""
                SELECT DISTINCT trade_date FROM indicator_value
                ORDER BY trade_date DESC
                OFFSET ?
                LIMIT 1
                """, LocalDate.class, RETAINED_TRADING_DAYS);

        if (boundary.isEmpty()) {
            // 보존 기간보다 적게 쌓여 있다. 지울 것이 없다.
            return 0;
        }

        LocalDate cutoff = boundary.get(0);
        int deleted = jdbcTemplate.update(
                "DELETE FROM indicator_value WHERE trade_date <= ?", cutoff);
        log.info("보존 기간을 넘긴 지표를 정리했다. 기준일={} 삭제={}행 (보존 {}거래일)",
                cutoff, deleted, RETAINED_TRADING_DAYS);
        return deleted;
    }
}
