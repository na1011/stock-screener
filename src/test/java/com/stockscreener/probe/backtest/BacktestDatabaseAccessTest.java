package com.stockscreener.probe.backtest;

import com.stockscreener.support.ReadOnlyDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시점 재현 하네스가 <b>운영 DB를 읽고, 쓰지는 못한다</b>는 것을 실행으로 확인한다
 * (P17, ADR-0071).
 *
 * <p>이 테스트가 통과하는 것 자체가 {@link ReadOnlyDatabaseGuard}의 기동 단언이 돌았다는
 * 뜻이다 — 그 단언은 컨텍스트 기동 시점에 실행되므로, 깨지면 여기가 아니라 컨텍스트가
 * 먼저 죽는다.
 *
 * <p>그럼에도 본문에서 <b>한 번 더 쓰기를 던지는</b> 이유는, 기동 단언이 쓰는 커넥션과
 * 하네스가 실제로 쓸 커넥션이 <b>풀에서 서로 다른 객체</b>이기 때문이다. Hikari가 커넥션마다
 * {@code setReadOnly}를 다시 부르지 않으면 첫 커넥션만 읽기 전용이고 나머지는 아니다 —
 * 그 어긋남은 하네스가 값을 쓴 뒤에야 드러난다.
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class BacktestDatabaseAccessTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("운영 DB의 실데이터가 보인다 — 하네스가 잴 것이 있다")
    void readsProductionData() {
        Integer stocks = jdbcTemplate.queryForObject("SELECT count(*) FROM stock", Integer.class);
        Integer bars = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT trade_date) FROM price_daily", Integer.class);

        System.out.printf("운영 DB: stock %d종목 · 거래일 %d일%n", stocks, bars);

        // 0이면 테스트 DB를 보고 있는 것이고, 그때 하네스는 "통과 0종목"을 내면서
        // 그것이 게이트 탓처럼 보인다. 가드가 먼저 잡지만 여기서도 값으로 확인한다.
        assertThat(stocks).as("종목이 0이면 운영 DB가 아니다").isPositive();
        assertThat(bars).as("거래일이 0이면 as-of를 하나도 고를 수 없다").isPositive();
    }

    @Test
    @DisplayName("풀에서 새로 꺼낸 커넥션도 쓰기를 거부한다 — 첫 커넥션만 읽기 전용이 아니다")
    void rejectsWritesOnEveryPooledConnection() {
        // 앞선 조회가 커넥션을 반납한 뒤라 이 호출은 풀에서 다시 꺼내 온다.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO indicator_value "
                        + "(stock_id, trade_date, indicator_code, value, updated_at) "
                        + "VALUES (-1, DATE '1900-01-01', '__READONLY_GUARD__', 0, now())"))
                .as("쓰기가 통과하면 하네스가 운영 지표를 덮어쓸 수 있다")
                .hasStackTraceContaining("25006");
    }
}
