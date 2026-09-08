package com.stockscreener.pipeline.collector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 시세가 실제로 적재된 거래일에 묻는다. <b>언제나 시장 하나에 대해 묻는다.</b>
 *
 * <p>{@link MarketCalendar}와 짝이지만 답하는 것이 다르다. 그쪽은 <b>달력만으로 확실한 것</b>
 * (주말)만 알고, 이쪽은 <b>적재된 데이터</b>에 묻는다. 공휴일 달력을 두지 않겠다는 결정이
 * 성립하는 이유가 이 둘의 조합이다 — 휴장이 어디 있는지 몰라도 그날 봉이 없다는 것은 알 수 있다.
 *
 * <p><b>MUST: 시장 인자를 없애지 않는다.</b> 두 시장의 휴장일이 다르므로 전 시장 합계로 세면
 * <b>국내가 휴장이고 미국이 개장한 날</b>이 전역 「거래일」이 된다. 그러면 지표 기준일·보존
 * 경계·결손 판정이 함께 밀리는데, <b>값은 전부 그럴듯하고 아무 에러도 나지 않는다.</b>
 * 실측으로 보존창 안의 국내 휴장 평일 18일 중 미국 개장이 14일이다(2026-09-08).
 *
 * <p>IMPORTANT — 기준이 {@code indicator_value}가 아니라 {@code price_daily}다.
 * {@link com.stockscreener.indicator.IndicatorCalendar}는 <b>조회 화면</b>의 기준 일자를
 * 정하려고 지표에 묻지만, 여기서는 <b>방금 수집한 것</b>을 묻는다. 지표는 이 뒤에 계산되므로
 * 그쪽에 물으면 아직 없는 것을 보게 된다.
 *
 * <p>비용은 실측했다(2026-08-30, 542,289봉): {@link #countOn}이 0.7ms,
 * {@link #earliestTradeDate}가 38ms, {@link #latestTradeDateOnOrBefore}가 209ms다.
 * 셋 다 <b>배치 실행당 한 번</b> 불리므로 이 비용이 성립한다 —
 * <b>종목 루프 안으로 옮기지 마라.</b>
 */
@Component
public class PriceCalendar {

    private final JdbcTemplate jdbcTemplate;

    public PriceCalendar(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 그 시장에서, 주어진 일자 이하에 봉이 실제로 적재된 가장 가까운 거래일.
     *
     * <p>수집 대상일이 휴장일일 수 있기 때문에 필요하다. 대상일을 그대로 지표·백분위의
     * 기준으로 쓰면 그날 봉이 없어 백분위가 <b>0행</b>이 되는데, 지표는 봉의 날짜로 저장되어
     * 값이 있다. 그러면 지표는 있고 백분위만 없는 날이 화면의 기준 일자가 되어 백분위 조건이
     * 전부 판정 불가가 된다 — <b>결과가 비는 것이 아니라 그럴듯하게 좁아진다.</b>
     *
     * @return 그 시장에 그 일자 이하 봉이 하나도 없으면 비어 있음
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> latestTradeDateOnOrBefore(String marketCode, LocalDate date) {
        // max는 행이 없어도 NULL 한 행을 돌려주므로 queryForObject가 예외를 던지지 않는다.
        // 그래도 queryForList로 받는 이유는 그 성질에 기대지 않기 위해서다.
        List<LocalDate> rows = jdbcTemplate.queryForList("""
                SELECT max(p.trade_date) FROM price_daily p
                JOIN stock s ON s.id = p.stock_id
                WHERE s.market_code = ? AND p.trade_date <= ?
                """, LocalDate.class, marketCode, date);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /**
     * 그 시장에서 봉이 적재된 가장 이른 거래일. 하나도 없으면 비어 있음.
     *
     * <p>기업 행위 조회 창의 기본 시작점이다. <b>상수로 박으면 안 되는 이유가 실측으로
     * 있다</b> — {@code InitialLoadJob.PRICE_HISTORY_YEARS}는 2인데 실제 적재는 7년이라,
     * 그 상수를 거울 삼으면 2020~2024의 기업 행위가 통째로 창 밖으로 나간다. 조정할 봉이
     * 있는 구간이 곧 조회할 구간이므로 여기에 묻는 것이 정본이다.
     *
     * <p><b>시장을 가르는 것이 이 메서드의 일부다.</b> 전 시장 최솟값을 쓰면 국내가 정한
     * 2020-01-02가 미국의 기본 창이 되는데, 미국 봉이 없는 구간에서는 원장의 근접중복 창이
     * <b>두 날짜 사이의 봉 수</b>로 5거래일을 세므로 45일 안의 두 사건이 무조건 한 사건으로
     * 붙는다 — 그리고 알림 없이 {@code log.info}로 끝난다(ADR-0090).
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> earliestTradeDate(String marketCode) {
        List<LocalDate> rows = jdbcTemplate.queryForList("""
                SELECT min(p.trade_date) FROM price_daily p
                JOIN stock s ON s.id = p.stock_id
                WHERE s.market_code = ?
                """, LocalDate.class, marketCode);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /**
     * 그 종목에 봉이 하나라도 있는가.
     *
     * <p>기업 행위 소스는 <b>우리가 저장한 시계열을 근거로 일자와 비율을 유도하므로</b>
     * (`CorporateActionDataSource`) 봉이 없는 종목에 물으면 유도가 조용히 실패하고 로그 한
     * 줄만 남는다. 신규 편입 직후에 정상적으로 생기는 상태이며, 미리 걸러 콜도 아낀다.
     *
     * <p>시장 인자가 없는 것이 정상이다 — 종목이 곧 시장이라 이미 시장 하나로 좁혀져 있다.
     */
    @Transactional(readOnly = true)
    public boolean hasBars(long stockId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM (SELECT 1 FROM price_daily WHERE stock_id = ? LIMIT 1) t",
                Long.class, stockId);
        return count != null && count > 0;
    }

    /**
     * 그 시장에서 그 일자에 적재된 봉의 수. 0이면 그 시장의 대상일이 비어 있다.
     *
     * <p>IMPORTANT — 결손 판정이 <b>이 값</b>이어야 하고 실행이 쓴 행 수가 아니다. 수집이
     * 하루가 아니라 창으로 도는 이상, 대상일이 비어 있어도 창 안의 과거 날짜 재적재가 쓰기
     * 행 수를 채운다. 그러면 알림이 원리적으로 발화하지 않는다.
     *
     * <p><b>MUST: 시장으로 좁힌 값으로 판정한다.</b> 전 시장 합계로 물으면 국내 휴장·미국
     * 개장일에 <b>국내 결손 알림이 원리적으로 발화하지 않고</b>(거짓 음성), 같은 값을 쓰는
     * 지수 결손 알림은 반대로 <b>상시 오발화</b>한다(거짓 양성).
     */
    @Transactional(readOnly = true)
    public long countOn(String marketCode, LocalDate tradeDate) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM price_daily p
                JOIN stock s ON s.id = p.stock_id
                WHERE s.market_code = ? AND p.trade_date = ?
                """, Long.class, marketCode, tradeDate);
        return count == null ? 0L : count;
    }
}
