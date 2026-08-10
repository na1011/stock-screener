package com.stockscreener.pipeline.collector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 시세가 실제로 적재된 거래일에 묻는다.
 *
 * <p>{@link MarketCalendar}와 짝이지만 답하는 것이 다르다. 그쪽은 <b>달력만으로 확실한 것</b>
 * (주말)만 알고, 이쪽은 <b>적재된 데이터</b>에 묻는다. 공휴일 달력을 두지 않겠다는 결정이
 * 성립하는 이유가 이 둘의 조합이다 — 휴장이 어디 있는지 몰라도 그날 봉이 없다는 것은 알 수 있다.
 *
 * <p>IMPORTANT — 기준이 {@code indicator_value}가 아니라 {@code price_daily}다.
 * {@link com.stockscreener.indicator.IndicatorCalendar}는 <b>조회 화면</b>의 기준 일자를
 * 정하려고 지표에 묻지만, 여기서는 <b>방금 수집한 것</b>을 묻는다. 지표는 이 뒤에 계산되므로
 * 그쪽에 물으면 아직 없는 것을 보게 된다.
 *
 * <p>두 쿼리 모두 {@code idx_price_date}(V1)를 타므로 인덱스 끝을 한 번 훑는 것으로 끝난다.
 */
@Component
public class PriceCalendar {

    private final JdbcTemplate jdbcTemplate;

    public PriceCalendar(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 주어진 일자 이하에서 봉이 실제로 적재된 가장 가까운 거래일.
     *
     * <p>수집 대상일이 휴장일일 수 있기 때문에 필요하다. 대상일을 그대로 지표·백분위의
     * 기준으로 쓰면 그날 봉이 없어 백분위가 <b>0행</b>이 되는데, 지표는 봉의 날짜로 저장되어
     * 값이 있다. 그러면 지표는 있고 백분위만 없는 날이 화면의 기준 일자가 되어 백분위 조건이
     * 전부 판정 불가가 된다 — <b>결과가 비는 것이 아니라 그럴듯하게 좁아진다.</b>
     *
     * @return 그 일자 이하에 봉이 하나도 없으면 비어 있음
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> latestTradeDateOnOrBefore(LocalDate date) {
        // max는 행이 없어도 NULL 한 행을 돌려주므로 queryForObject가 예외를 던지지 않는다.
        // 그래도 queryForList로 받는 이유는 그 성질에 기대지 않기 위해서다.
        List<LocalDate> rows = jdbcTemplate.queryForList(
                "SELECT max(trade_date) FROM price_daily WHERE trade_date <= ?",
                LocalDate.class, date);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /**
     * 그 일자에 적재된 봉의 수. 0이면 대상일이 비어 있다.
     *
     * <p>IMPORTANT — 결손 판정이 <b>이 값</b>이어야 하고 실행이 쓴 행 수가 아니다. 수집이
     * 하루가 아니라 창으로 도는 이상, 대상일이 비어 있어도 창 안의 과거 날짜 재적재가 쓰기
     * 행 수를 채운다. 그러면 알림이 원리적으로 발화하지 않는다.
     */
    @Transactional(readOnly = true)
    public long countOn(LocalDate tradeDate) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM price_daily WHERE trade_date = ?", Long.class, tradeDate);
        return count == null ? 0L : count;
    }
}
