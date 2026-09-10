package com.stockscreener.indicator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 지표가 실제로 존재하는 거래일의 범위 — <b>시장별로</b>.
 *
 * <p>스크리닝은 언제나 <b>한 일자를 고정하고</b> 그날의 지표를 읽는다(ADR-0011). 그런데
 * "그 일자"를 정하는 코드가 지금까지 없었다. 호출자가 {@code LocalDate.now()}를 쓰면
 * 배치가 아직 돌지 않았거나 오늘이 휴장일인 날 전 종목이 "판정 불가"로 나오고,
 * 그것은 오류가 아니라 <b>빈 결과</b>로 보인다.
 *
 * <p>IMPORTANT — 기준을 {@code price_daily}가 아니라 지표로 잡는다. 시세는 들어왔는데 지표
 * 배치가 실패한 날이 실제로 있을 수 있고, 그날을 기준 일자로 고르면 시세는 최신인데 조건은
 * 하나도 판정되지 않는 상태가 된다.
 *
 * <h2>왜 시장 인자를 받는가 (ADR-0095)</h2>
 *
 * <p><b>국내 휴장·미국 개장일이 실재한다</b> — 보존창 안 국내 휴장 평일 18일 중 미국 개장이
 * 14일이다(2026-09-08 실측 · 창이 미끄러지므로 그날의 수다). 시장을 안 가르면 그날 기준일이 미국 날짜가 되고, 그 날짜에는 국내 지표가
 * 한 행도 없어 <b>국내 전 프리셋이 「조건에 맞는 종목이 없다」</b>가 된다.
 *
 * <p><b>MUST: 호출부가 시장을 반드시 고른다.</b> 무인자 갈래를 두지 않는 이유는
 * {@code FinancialVisibility}와 같다 — 기본값을 주면 새 호출부가 전 시장을 골라도
 * <b>아무 증상이 없다.</b> 값이 나오고, 대개 맞다. 틀리는 날은 시장 달력이 갈리는 날뿐이다.
 *
 * <p><b>읽는 것은 {@code indicator_value}가 아니라 파생 색인 {@code indicator_trade_date}다.</b>
 * 원본에 {@code stock}을 조인하면 이 세 질의가 각각 <b>1,333ms</b>가 되는데(실측 7,179,721행)
 * 요청마다 돈다. 색인은 시장 수 × 180행이라 인덱스 끝을 한 번 훑고 끝난다.
 * 색인을 짓는 것은 {@link IndicatorTradeDateIndex}이고, <b>그것을 안 부르면 여기가 조용히
 * 낡는다.</b>
 */
@Component
public class IndicatorCalendar {

    private final JdbcTemplate jdbcTemplate;

    public IndicatorCalendar(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 그 시장들에 지표가 있는 가장 최근 거래일.
     *
     * <p>시장이 여럿이면 <b>최댓값</b>이다. 그 날짜에 값이 없는 시장의 종목은 판정불가가 되므로,
     * 여러 시장을 한 기준일로 묶는 호출부는 <b>그 시장의 종목을 판정하지 않아야 한다</b>
     * (ADR-0093이 워치리스트에 세운 것과 같은 형태).
     *
     * @return 지표가 하나도 없으면 비어 있음. 그것은 "오늘"이 아니라 <b>준비되지 않음</b>이다
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> latestIndicatorDate(Collection<String> marketCodes) {
        return single("SELECT max(trade_date) FROM indicator_trade_date", marketCodes, null);
    }

    /**
     * 그 시장들에 지표가 남아 있는 가장 이른 거래일 — 보존 구간의 하한.
     *
     * <p>{@link IndicatorRetentionPolicy}의 상수가 아니라 <b>실제로 남아 있는
     * 값</b>을 쓴다. 보존 정리가 아직 돌지 않았으면 더 옛날 데이터가 있고, 그것을 조회할 수
     * 없다고 거절하는 것은 사실이 아니다.
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> earliestIndicatorDate(Collection<String> marketCodes) {
        return single("SELECT min(trade_date) FROM indicator_trade_date", marketCodes, null);
    }

    /**
     * 주어진 일자 이하에서 그 시장들에 지표가 있는 가장 가까운 거래일.
     *
     * <p>주말·공휴일을 요청했을 때 오류로 만들지 않기 위해서다. 사용자가 거래일 달력을
     * 알아야 조회할 수 있는 API는 쓰기 어렵다.
     *
     * @return 그 일자 이하에 지표가 없으면 비어 있음(보존 구간보다 이전이다)
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> nearestOnOrBefore(LocalDate date, Collection<String> marketCodes) {
        return single("SELECT max(trade_date) FROM indicator_trade_date", marketCodes, date);
    }

    /**
     * {@code from}부터 {@code to}까지 그 시장들에 지표가 있는 <b>모든 거래일</b>.
     *
     * <p>범위를 묻는 위의 셋과 <b>같은 표·같은 시장 술어</b>여야 해서 여기 둔다. 부르는 쪽이
     * 스스로 {@code indicator_trade_date}를 훑으면 시장 한정이 조용히 갈리고, 그러면
     * <b>국내 휴장·미국 개장일이 국내 as-of로 들어간다</b> — 그 실패가 값이 나오는 형태라
     * 드러나지 않는 것이 이 클래스가 존재하는 이유 그대로다.
     *
     * <p><b>시세({@code price_daily})가 아니라 지표를 기준으로 잡는 것이 요점이다.</b> 시세는
     * 들어왔는데 지표 배치가 아직 안 돈 날이 실재한다 — 일일 갱신과 지표 사슬이 다른 시각에
     * 돌기 때문이고, 그 날짜를 기준일로 고르면 <b>전 조건이 판정불가인데 에러가 없다.</b>
     *
     * @return 오름차순. 그 구간에 지표가 없으면 빈 목록
     */
    @Transactional(readOnly = true)
    public List<LocalDate> everyIndicatorDate(LocalDate from, LocalDate to,
                                              Collection<String> marketCodes) {
        if (marketCodes == null || marketCodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "거래일을 열거하려면 시장을 골라야 한다 — 빈 집합은 「전 시장」이 아니다");
        }
        List<Object> args = new ArrayList<>();
        args.add(from);
        args.add(to);
        args.addAll(marketCodes);
        String sql = ("SELECT DISTINCT trade_date FROM indicator_trade_date"
                + " WHERE trade_date BETWEEN ? AND ? AND market_code IN (%s)"
                + " ORDER BY trade_date")
                .formatted(String.join(", ",
                        java.util.Collections.nCopies(marketCodes.size(), "?")));
        return jdbcTemplate.queryForList(sql, LocalDate.class, args.toArray());
    }

    /**
     * 시장 조건을 붙여 한 값을 읽는다.
     *
     * <p><b>빈 시장 집합은 예외다.</b> 「전 시장」으로 접으면 이 클래스가 존재하는 이유가
     * 사라지고, 그 접힘은 값이 나오는 형태라 드러나지 않는다.
     */
    private Optional<LocalDate> single(String select, Collection<String> marketCodes,
                                       LocalDate onOrBefore) {
        if (marketCodes == null || marketCodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "기준일을 물으려면 시장을 골라야 한다 — 빈 집합은 「전 시장」이 아니다");
        }
        List<Object> args = new ArrayList<>(marketCodes);
        String sql = select + " WHERE market_code IN (%s)"
                .formatted(String.join(", ", java.util.Collections.nCopies(args.size(), "?")));
        if (onOrBefore != null) {
            sql += " AND trade_date <= ?";
            args.add(onOrBefore);
        }
        // max/min은 행이 없어도 NULL 한 행을 돌려주므로 queryForObject가 EmptyResultDataAccess를
        // 던지지 않는다. 그래도 queryForList로 받는 이유는 그 성질에 기대지 않기 위해서다 —
        // 쿼리를 조금만 바꿔도 조용히 예외 경로가 달라진다.
        List<LocalDate> rows = jdbcTemplate.queryForList(sql, LocalDate.class, args.toArray());
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }
}
