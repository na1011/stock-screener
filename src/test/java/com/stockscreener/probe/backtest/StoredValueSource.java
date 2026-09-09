package com.stockscreener.probe.backtest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 저장된 지표를 <b>그 날짜·그 모집단만큼</b> 읽는다 — 스윕의 값 출처다(ADR-0131).
 *
 * <p><b>코드를 안 좁힌다.</b> 참조 코드로 좁히고 싶어지지만, {@code screenAllFromValues}가
 * 읽기 시점에 업종 상대값·유니버스 백분위를 <b>원천 코드에서 재구성</b>하므로 참조 집합만
 * 주면 그 재구성이 조용히 빈다 — 값이 안 나오는 것이 아니라 <b>동료 집합이 작아진 값</b>이
 * 나오고, 그것은 표에서 그럴듯한 숫자다. 좁히려면 원천을 유도해야 하는데 그 유도가 새
 * 규약이고, ADR-0123 §규칙이 *"담기로 선언한 코드 집합을 레지스트리에서 만든다"*로 같은
 * 부류를 이미 막아 뒀다.
 *
 * <p><b>모집단은 통화 전체여야 한다.</b> 대상으로 미리 좁힌 것을 주면 업종 백분위의 동료 수가
 * 달라져 조용히 다른 값이 나온다({@code screenAllFromValues} javadoc이 그 근거를 든다).
 *
 * <p><b>라이브와 같은 표를 읽는다</b> — {@code ScreeningRepository.loadIndicatorValues}와
 * 같은 술어이고 다른 것은 <b>코드를 안 좁힌다</b>는 것뿐이다. 갈리면 화면과 스윕이 다른 값을
 * 보는데 어느 쪽도 에러를 내지 않는다.
 */
@Component
public class StoredValueSource {

    private final JdbcTemplate jdbcTemplate;

    public StoredValueSource(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param stockIds 그 통화의 값 계산 모집단 전체
     * @param asOf     지표 거래일
     * @return 종목 → (지표 코드 → 값). 값이 없는 종목은 <b>키가 없다</b>
     */
    public Map<Long, Map<String, BigDecimal>> read(List<Long> stockIds, LocalDate asOf) {
        Map<Long, Map<String, BigDecimal>> out = new LinkedHashMap<>();
        if (stockIds.isEmpty()) {
            return out;
        }
        jdbcTemplate.query("""
                SELECT stock_id, indicator_code, value FROM indicator_value
                 WHERE trade_date = ? AND stock_id = ANY (?)
                """,
                ps -> {
                    ps.setObject(1, asOf);
                    ps.setArray(2, ps.getConnection()
                            .createArrayOf("bigint", stockIds.toArray()));
                },
                rs -> {
                    out.computeIfAbsent(rs.getLong(1), id -> new LinkedHashMap<>())
                            .put(rs.getString(2), rs.getBigDecimal(3));
                });
        return out;
    }
}
