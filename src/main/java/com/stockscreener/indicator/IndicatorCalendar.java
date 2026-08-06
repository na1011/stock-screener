package com.stockscreener.indicator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 지표가 실제로 존재하는 거래일의 범위.
 *
 * <p>스크리닝은 언제나 <b>한 일자를 고정하고</b> 그날의 지표를 읽는다(ADR-0011). 그런데
 * "그 일자"를 정하는 코드가 지금까지 없었다. 호출자가 {@code LocalDate.now()}를 쓰면
 * 배치가 아직 돌지 않았거나 오늘이 휴장일인 날 전 종목이 "판정 불가"로 나오고,
 * 그것은 오류가 아니라 <b>빈 결과</b>로 보인다.
 *
 * <p>IMPORTANT — 기준을 {@code price_daily}가 아니라 {@code indicator_value}로 잡는다.
 * 시세는 들어왔는데 지표 배치가 실패한 날이 실제로 있을 수 있고, 그날을 기준 일자로
 * 고르면 시세는 최신인데 조건은 하나도 판정되지 않는 상태가 된다.
 *
 * <p>세 쿼리 모두 PK({@code trade_date, indicator_code, stock_id})의 선두 컬럼만 쓰므로
 * 인덱스 끝을 한 번 훑는 것으로 끝난다.
 */
@Component
public class IndicatorCalendar {

    private final JdbcTemplate jdbcTemplate;

    public IndicatorCalendar(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 지표가 있는 가장 최근 거래일.
     *
     * @return 지표가 하나도 없으면 비어 있음. 그것은 "오늘"이 아니라 <b>준비되지 않음</b>이다.
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> latestIndicatorDate() {
        return single("SELECT max(trade_date) FROM indicator_value");
    }

    /**
     * 지표가 남아 있는 가장 이른 거래일 — 보존 구간의 하한.
     *
     * <p>{@link IndicatorRetentionPolicy}의 상수(180거래일)가 아니라 <b>실제로 남아 있는
     * 값</b>을 쓴다. 보존 정리가 아직 돌지 않았으면 더 옛날 데이터가 있고, 그것을 조회할 수
     * 없다고 거절하는 것은 사실이 아니다.
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> earliestIndicatorDate() {
        return single("SELECT min(trade_date) FROM indicator_value");
    }

    /**
     * 주어진 일자 이하에서 지표가 있는 가장 가까운 거래일.
     *
     * <p>주말·공휴일을 요청했을 때 오류로 만들지 않기 위해서다. 사용자가 거래일 달력을
     * 알아야 조회할 수 있는 API는 쓰기 어렵다.
     *
     * @return 그 일자 이하에 지표가 없으면 비어 있음(보존 구간보다 이전이다)
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> nearestOnOrBefore(LocalDate date) {
        return single("SELECT max(trade_date) FROM indicator_value WHERE trade_date <= ?", date);
    }

    private Optional<LocalDate> single(String sql, Object... args) {
        // max/min은 행이 없어도 NULL 한 행을 돌려주므로 queryForObject가 EmptyResultDataAccess를
        // 던지지 않는다. 그래도 queryForList로 받는 이유는 그 성질에 기대지 않기 위해서다 —
        // 쿼리를 조금만 바꿔도 조용히 예외 경로가 달라진다.
        List<LocalDate> rows = jdbcTemplate.queryForList(sql, LocalDate.class, args);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }
}
