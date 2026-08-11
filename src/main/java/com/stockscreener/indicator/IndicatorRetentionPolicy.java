package com.stockscreener.indicator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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
     * 앞으로 쓸 지표의 보존 경계. 이 날짜 <b>이하</b>는 애초에 쓰지 않는다.
     *
     * <p>{@link #purge()}가 이미 쌓인 것을 지운다면 이쪽은 쌓이지 않게 한다. 둘 다 필요한
     * 이유는 러너가 <b>읽기 창 전체</b>에 대해 값을 내기 때문이다 — 전역 최대가 751봉이라
     * 이 경계가 없으면 매 실행이 최대 571거래일치를 보존 밖에 쓰고 다음 정리가 그것을 지운다.
     * 값은 언제나 옳고 화면도 정상이라 드러나는 것은 몇 달 뒤 인덱스 크기뿐이다.
     *
     * <p>경계를 {@code price_daily}에서 세는 것이 {@link #purge()}와 다른 점이다. 지표 날짜는
     * 시세 날짜의 부분집합이므로 이쪽 경계가 언제나 같거나 더 최근이고, 따라서 <b>이 필터를
     * 통과한 값은 정리 대상이 되지 않는다.</b> 반대로 {@code indicator_value}를 기준으로 삼으면
     * 지표가 아직 보존 기간만큼 쌓이지 않은 동안 경계가 비어, 초기 적재가 전 구간을 쓴다.
     *
     * @return 경계 거래일. 시세가 보존 기간보다 적게 쌓여 있으면 비어 있고, 그때는 거를 것이 없다
     */
    public Optional<LocalDate> writeCutoff() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT trade_date FROM price_daily
                ORDER BY trade_date DESC
                OFFSET ?
                LIMIT 1
                """, LocalDate.class, RETAINED_TRADING_DAYS)
                .stream()
                .findFirst();
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
