package com.stockscreener.indicator;

import com.stockscreener.pipeline.collector.IndicatorInvalidationPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 원자료가 소급 변경되면 그것에서 계산된 지표를 지운다.
 *
 * <p>{@code @Component}로 등록되는 순간 {@code NoOpIndicatorInvalidationPort}가 물러난다
 * ({@code @ConditionalOnMissingBean}). 호출 지점은 Phase 2가 이미 만들어 두었다.
 *
 * <p><b>지우기만 하고 다시 계산하지 않는다.</b> 재계산은 배치가 담당한다. 무효화는
 * 수집 트랜잭션 안에서 일어나는데 거기서 지표까지 다시 계산하면, 850종목 수집 중
 * 한 종목의 분할이 그 트랜잭션을 몇 배로 늘리고 실패 시 시세 저장까지 함께 롤백된다.
 * 지워두면 다음 배치가 그 구간을 자연히 다시 채운다 — 그 사이 조건 판정에서 해당 종목이
 * 빠지는 것은 <b>틀린 값으로 판정되는 것보다 낫다</b>.
 */
@Component
public class StoredIndicatorInvalidationPort implements IndicatorInvalidationPort {

    private static final Logger log = LoggerFactory.getLogger(StoredIndicatorInvalidationPort.class);

    /**
     * IMPORTANT — {@code >=}이고 {@code =}가 아니다. 분할은 그날 하루가 아니라 그 이후 전체의
     * 수정주가를 바꾸며, 이동평균처럼 창을 쓰는 지표는 창 길이만큼 더 오래 영향을 받는다.
     * 호출자가 그 구간의 시작일을 넘겨준다.
     */
    private static final String DELETE_FROM = """
            DELETE FROM indicator_value
            WHERE stock_id = ? AND trade_date >= ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public StoredIndicatorInvalidationPort(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void invalidate(long stockId, LocalDate fromDate, InvalidationReason reason) {
        int deleted = jdbcTemplate.update(DELETE_FROM, stockId, fromDate);

        // 0건도 남긴다 — 무효화가 호출되었는데 지울 것이 없었다는 사실 자체가,
        // 나중에 "왜 지표가 낡았는가"를 볼 때 구별해야 하는 정보다.
        log.info("지표를 무효화했다. stockId={} fromDate={} reason={} 삭제={}행",
                stockId, fromDate, reason, deleted);
    }
}
