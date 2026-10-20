package com.stockscreener.indicator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 계산된 지표 값을 {@code indicator_value}에 쓴다.
 *
 * <p>{@code ON CONFLICT}로 갱신하는 이유는 배치가 멱등해야 하기 때문이다. 같은 날짜로 두 번
 * 돌려도 행이 늘지 않고, 원자료가 정정되어 다시 계산한 값이 반영된다.
 *
 * <p>MUST NOT: 계산할 수 없는 날짜에 행을 만들지 않는다(ADR-0002). NULL 행을 쓰면 조건 판정의
 * {@code EXISTS}가 참이 되어 "값이 없는 종목"과 "조건을 만족하지 않는 종목"이 구별되지 않는다.
 * 계산기가 빈 목록을 주면 여기서도 아무것도 쓰지 않는 것이 그 계약의 이행이다.
 */
@Component
public class IndicatorValueWriter {

    /**
     * IMPORTANT — {@code ON CONFLICT}의 컬럼 순서는 PK 순서
     * {@code (trade_date, indicator_code, stock_id)}와 같아야 한다(ADR-0002 MUST).
     * 순서를 바꾸면 PostgreSQL이 이 인덱스를 충돌 판정에 쓰지 못한다.
     *
     * <p>IMPORTANT — {@code WHERE ... IS DISTINCT FROM}을 지우지 않는다. 값이 같은데도 갱신하면
     * MVCC가 행 버전을 새로 만든다. 러너가 매일 lookback 창 전체를 재계산해 거의 같은 값을 다시
     * 쓰기 때문에 이 낭비가 그대로 쌓인다 — 실제로 <b>1.39M행짜리 테이블에 누적 갱신 2억
     * 3천만 건</b>, 인덱스만 1,987MB가 됐다. 있어도 컴파일되고 테스트도 통과하며, 느려지는
     * 것은 몇 달 뒤다.
     *
     * <p>IMPORTANT — <b>이 가드가 서 있는 한 HOT 갱신은 원리적으로 0이다.</b> 가드가 값이 같은
     * 갱신을 지우므로 <b>살아남는 갱신은 전부 {@code value}를 바꾸고</b>, {@code value}는
     * 인덱스(PK의 {@code INCLUDE})에 있어 HOT의 전제를 깬다. 실측 2026-09-13:
     * 누적 갱신 8,530만 건에 {@code n_tup_hot_upd}가 <b>0</b>이다.
     *
     * <p>MUST NOT: 이것을 <b>fillfactor로 고치려 하지 않는다.</b> fillfactor 100에서는 어느
     * 모양이든 0이라 그 수만으로는 못 가르지만, fillfactor 70으로 낮춰 대조하면
     * {@code value}가 <b>어느 인덱스에도 없을 때만</b> HOT이 나고 {@code INCLUDE}에 있어도
     * 0이다. 즉 페이지 여유가 아니라 <b>인덱스에 있다는 사실</b>이 막는 것이고, 그것을 풀면
     * 파생층 읽기가 슬라이스마다 무작위 힙 페치 1,500건이 된다(ADR-0140).
     *
     * <p>즉 이 조건절이 하는 일은 HOT을 늘리는 것이 아니라 <b>새 행 버전을 없애는 것</b>이다.
     * <b>잠금은 못 없앤다</b> — {@code DO UPDATE}의 {@code WHERE}가 거짓이어도 충돌한 행은 잠기고, 그
     * 잠금이 행마다 WAL 기록과 체크포인트 뒤 첫 페이지 이미지를 남긴다(실측 2026-10-21: 같은 값 20만 행
     * 업서트가 WAL 21.8MB · 운영 매 평일 화해 한 판이 쓴 행 0으로 약 10GB). 그래서 같은 값은
     * {@code NOT EXISTS}로 <b>충돌 경로에 보내기 전에</b> 거른다 — 그 비교는 저장 열과 같은
     * {@code numeric(24,8)}로 반올림해서 한다({@code EXCLUDED.value}가 그 열 타입이라 그와 같은 비교다 —
     * 캐스트를 빼면 소수 8자리를 넘는 값이 늘 「다름」이 되어 선필터가 안 걸린다).
     *
     * <p>{@code IS DISTINCT FROM}인 이유는 {@code <>}가 NULL에서 NULL을 내기 때문이다. 지금은
     * {@code value}가 NOT NULL이지만 그 전제에 기대지 않는다.
     *
     * <p>MUST: 같은 키의 동시 갱신·삭제에 대한 배타는 이 문장이 아니라 ADR-0175 잠금이 진다. 선필터는 문장 시작
     * 스냅숏으로 거르므로 다른 트랜잭션이 같은 행을 바꾸는 중이면 기다리지 않고 건너뛴다 — {@code ON CONFLICT}가
     * 지키는 것은 삽입끼리의 경합뿐이다.
     *
     * <p><b>한 (종목, 지표)를 한 문장으로 쓴다</b>({@code unnest}) — 점마다 문장을 보내면 {@code INSERT … SELECT}는
     * 드라이버의 배치 재작성을 못 타고, 쓰기 가드(ADR-0175)의 문장 단위 트리거도 점마다 돈다.
     */
    private static final String UPSERT = """
            INSERT INTO indicator_value
                (stock_id, trade_date, indicator_code, value, updated_at)
            SELECT ?, p.trade_date, ?, CAST(p.value AS numeric(24, 8)), now()
            FROM unnest(?::date[], ?::numeric[]) AS p(trade_date, value)
            WHERE NOT EXISTS (
                SELECT 1 FROM indicator_value cur
                WHERE cur.trade_date = p.trade_date
                  AND cur.indicator_code = ?
                  AND cur.stock_id = ?
                  AND cur.value IS NOT DISTINCT FROM CAST(p.value AS numeric(24, 8)))
            ON CONFLICT (trade_date, indicator_code, stock_id) DO UPDATE SET
                value      = EXCLUDED.value,
                updated_at = now()
            WHERE indicator_value.value IS DISTINCT FROM EXCLUDED.value
            """;

    private static final String DELETE_AT = """
            DELETE FROM indicator_value
            WHERE stock_id = ? AND indicator_code = ? AND trade_date = ANY (?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public IndicatorValueWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 한 종목·한 지표의 계산 결과를 저장한다.
     *
     * @return <b>값이 실제로 달라진</b> 행 수. 이미 같은 값이 있던 행은 세지 않는다 —
     *         재계산이 대부분 같은 값을 다시 내므로, 같은 날을 두 번 돌리면 0에 가깝다.
     *         "몇 행을 조회했나"가 아니라 "무엇이 바뀌었나"를 답하는 수다.
     */
    public long write(long stockId, String indicatorCode, List<IndicatorPoint> points) {
        if (points.isEmpty()) {
            return 0L;
        }

        // 한 문장 안에서 같은 키를 두 번 갱신하면 ON CONFLICT가 오류를 낸다 — 점마다 순차로 업서트하던 때의
        // 최종 상태(마지막 점)를 그대로 남긴다.
        Map<LocalDate, BigDecimal> last = new LinkedHashMap<>();
        for (IndicatorPoint point : points) {
            last.put(point.tradeDate(), point.value());
        }
        Date[] dates = last.keySet().stream().map(Date::valueOf).toArray(Date[]::new);
        BigDecimal[] values = last.values().toArray(BigDecimal[]::new);
        return jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(UPSERT);
            statement.setLong(1, stockId);
            statement.setString(2, indicatorCode);
            statement.setArray(3, connection.createArrayOf("date", dates));
            statement.setArray(4, connection.createArrayOf("numeric", values));
            statement.setString(5, indicatorCode);
            statement.setLong(6, stockId);
            return statement;
        });
    }

    /**
     * 한 종목·한 지표의 <b>이미 저장된</b> 값을 지운다.
     *
     * <p>IMPORTANT — 이것이 없으면 계산기를 고쳐도 제외가 성립하지 않는다. {@link #write}는
     * UPSERT뿐이라 <b>쓰지 않기로 한 날짜의 옛 행은 그대로 남고</b>, 남은 값은 그럴듯해서
     * 화면에서 드러나지 않는다. P8이 이 함정을 *"계산기를 고치는 것만으로는 제외가 성립하지
     * 않는다"*로 적어 두었고, ADR-0055의 억제가 그 자리에 정확히 들어간다.
     *
     * <p>호출자가 셋이고 <b>소유하는 날짜가 서로 겹치지 않는다</b> — 억제된 일자(ADR-0055) ·
     * 창 전체가 빈 코드의 창(ADR-0080) · 마지막 산출 일자 뒤의 꼬리(ADR-0085). 겹치게 만들면
     * 한쪽을 고칠 때 다른 쪽이 조용히 남는다.
     *
     * <p>날짜를 배열로 한 번에 넘긴다 — 날짜마다 문장을 던지면 5종목 × 71지표 × 보존 일수가
     * 수만 번의 왕복이 된다.
     *
     * @param dates 지울 거래일. 비면 아무것도 하지 않는다
     * @return 실제로 지워진 행 수. 계산된 적 없는 날짜는 0이다
     */
    public long deleteAt(long stockId, String indicatorCode, Collection<LocalDate> dates) {
        if (dates.isEmpty()) {
            return 0L;
        }
        return jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(DELETE_AT);
            statement.setLong(1, stockId);
            statement.setString(2, indicatorCode);
            statement.setArray(3, connection.createArrayOf("date",
                    dates.stream().map(Date::valueOf).toArray()));
            return statement;
        });
    }
}
