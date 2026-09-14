package com.stockscreener.indicator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 「소스가 그 회사에 대해 그 금액 항목을 안 낸다」를 읽는다.
 *
 * <p><b>MUST: {@code ABSENT}만 읽는다.</b> {@code FILTERED}는 단위·폼·필드 필터가 걸러낸
 * 것이라 <b>우리 쪽 한계</b>이고, 그것으로 사유를 지으면 {@link IndicatorUnavailabilityConfig}가
 * 금지한 <b>그럴듯한데 틀린 사유</b>가 된다.
 *
 * <p><b>MUST NOT: 빈 집합을 「부재가 없다」로 읽지 않는다.</b> 그 종목을 이 축으로 아직 안
 * 훑었을 수도 있고, 그 시장의 소스가 이 축을 안 볼 수도 있다(지금 DART가 그렇다).
 * <b>빈 집합은 「모른다」이고 그때 화면은 침묵한다.</b>
 *
 * <p><b>이 값은 「오늘의 관측」이다 — 과거 기준일 조회에 쓰지 않는다.</b> 표가
 * {@code (종목, 컬럼)} 하나로 현재 상태만 들고 있어서, 과거 as-of로 종목 상세를 열면
 * <b>그때는 값이 있던 종목에도 오늘의 판정이 붙는다.</b> 그래서 호출부가 <b>최신 일자일 때만</b>
 * 이것을 싣는다({@code StockController}).
 */
@Component
public class FinancialColumnAbsenceReader {

    private final JdbcTemplate jdbcTemplate;

    public FinancialColumnAbsenceReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    /**
     * <b>소스가 부분 개념만 내는 컬럼.</b> 「안 낸다」와 갈라야 화면이 거짓을 안 말한다 —
     * 실측으로 D&amp;A 제외 원가만 내는 20종목이 「공시에 내지 않습니다」를 받고 있었다.
     */
    public Set<String> partialOnlyColumnsOf(long stockId) {
        return Set.copyOf(jdbcTemplate.queryForList(
                "SELECT column_name FROM financial_column_absence "
                        + "WHERE stock_id = ? AND state = 'PARTIAL_ONLY'",
                String.class, stockId));
    }

    public Set<String> absentColumnsOf(long stockId) {
        return Set.copyOf(jdbcTemplate.queryForList(
                "SELECT column_name FROM financial_column_absence "
                        + "WHERE stock_id = ? AND state = 'ABSENT'",
                String.class, stockId));
    }
}
