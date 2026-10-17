package com.stockscreener.pipeline.collector;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문 손 재적재의 미국 모집단 — 활성 편입 없음 ∧ 시대가 보존 창과 겹침(탐색 {@code US-PAST-LISTING-IMPL-1018.md} U6). 오늘 후보보다 넓다 —
 * 닫힌 과거 구성원과 들인 폐지 실체 행이 그 시대 안 날짜의 과거 명부 후보라 재무가 필요하다.
 *
 * <p><b>대조군</b>: 오늘 후보({@link StockRegistrar#usRankingCandidateTickers})는 닫힌 행을 안 낸다 — 그 술어로 관문 재적재를 돌면 과거 명부의 닫힌
 * 행에 재무가 없다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class RefillWindowPopulationTest {

    private static final LocalDate WINDOW_START = LocalDate.of(2025, 10, 10);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StockRegistrar registrar;

    @Test
    void closedRowsWhoseEraOverlapsTheWindowAreInTheRefillPopulation() {
        row("RWOPEN", null);
        row("RWCLOSED~2026-03-01", LocalDate.of(2026, 3, 2));
        row("RWGONE~2025-06-30", LocalDate.of(2025, 7, 1));

        List<String> window = registrar.usRefillWindowTickers(WINDOW_START);
        List<String> today = registrar.usRankingCandidateTickers();

        assertThat(window).contains("RWOPEN", "RWCLOSED~2026-03-01").doesNotContain("RWGONE~2025-06-30");
        assertThat(today).as("대조군 — 오늘 후보는 닫힌 행을 안 낸다").contains("RWOPEN").doesNotContain("RWCLOSED~2026-03-01");
        assertThat(registrar.usRefillWindowIdsByTicker(List.of("RWCLOSED~2026-03-01", "RWGONE~2025-06-30"), WINDOW_START)).hasSize(1);
    }

    private void row(String ticker, LocalDate eraTo) {
        jdbc.update("INSERT INTO stock (market_code, ticker, name, era_to, is_active) VALUES ('US', ?, ?, ?, true)", ticker, ticker, eraTo);
    }
}
