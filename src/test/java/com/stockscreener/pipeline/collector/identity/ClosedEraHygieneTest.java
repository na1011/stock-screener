package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.batch.StockFoldJob;
import com.stockscreener.pipeline.collector.FilingLedgerStore;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedFiling;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 닫힌 시대의 위생(ADR-0180 · 계획 {@code US-PAST-LISTING-PLAN-1017.md} P0). 실측 ★14·★15: 접기가 닫은 6행이 옛 기호를 쥔 채 오늘의
 * 후보 풀에 들었고, 옛 등록인이 시대 끝 뒤에 낸 공시가 그 행 장부에 붙었다.
 *
 * <p><b>대조군</b>: 이 판 전 코드에서 닫힌 행은 {@link StockRegistrar#usRankingCandidateTickers}에 들었다(술어가 {@code is_active}뿐 —
 * {@code is_active}를 거짓으로 쓰는 코드는 없다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class ClosedEraHygieneTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/New_York"));

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EraClosing eraClosing;
    @Autowired
    private StockRegistrar registrar;
    @Autowired
    private FilingLedgerStore ledger;
    @Autowired
    private StockFoldJob foldJob;

    @Test
    @DisplayName("시대 닫기는 표지를 비운다 — 이미 비운 표지는 다시 안 비운다")
    void closingVacatesTheLabelOnce() {
        long id = stock("IPEXT");
        eraClosing.close(id, LocalDate.of(2026, 9, 28));
        assertThat(ticker(id)).isEqualTo("IPEXT~2026-09-27");

        eraClosing.close(id, LocalDate.of(2026, 9, 28));
        assertThat(ticker(id)).as("날짜가 겹쳐 쌓이지 않는다").isEqualTo("IPEXT~2026-09-27");
        assertThat(jdbc.queryForObject("SELECT era_to FROM stock WHERE id = ?", LocalDate.class, id))
                .isEqualTo(LocalDate.of(2026, 9, 28));
    }

    @Test
    @DisplayName("닫힌 행은 오늘의 후보 풀에 안 든다 · 시대가 뒤에 끝나는 행은 든다")
    void closedRowIsNotATodayCandidate() {
        long closed = stock("CLOSEDT");
        long endsLater = stock("LATERT");
        long open = stock("OPENT");
        jdbc.update("UPDATE stock SET era_to = ? WHERE id = ?", TODAY, closed);
        jdbc.update("UPDATE stock SET era_to = ? WHERE id = ?", TODAY.plusDays(1), endsLater);

        assertThat(registrar.usRankingCandidateTickers()).contains("LATERT", "OPENT").doesNotContain("CLOSEDT");
        assertThat(registrar.usRankingCandidateIdsByTicker(List.of("CLOSEDT", "LATERT", "OPENT")))
                .containsExactlyInAnyOrder(endsLater, open);
    }

    @Test
    @DisplayName("장부는 행의 시대 밖 공시를 안 붙인다 — 시대 안 공시는 닫힌 행에도 붙는다(그 행의 역사다)")
    void ledgerAttachesOnlyWithinTheRowsEra() {
        long closed = stock("WKEYT");
        jdbc.update("UPDATE stock SET era_to = ? WHERE id = ?", LocalDate.of(2026, 10, 5), closed);

        int inserted = ledger.recordOpen("US", List.of(
                filing("0001-26-1", closed, LocalDate.of(2026, 10, 1)),
                filing("0001-26-2", closed, LocalDate.of(2026, 10, 5)),
                filing("0001-26-3", closed, LocalDate.of(2026, 10, 14))));

        assertThat(inserted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT filing_no FROM filing_consumption WHERE stock_id = ?", String.class, closed))
                .as("시대 끝(10-05)은 제외다").containsExactly("0001-26-1");
    }

    @Test
    @DisplayName("손 치유 대상 — 표지가 기호 그대로인 닫힌 행과 시대 밖에 열린 장부 행만 고른다")
    void healTargetsAreExactlyTheUnhygienicRows() {
        long plain = stock("COLAT");
        long vacated = stock("NHICT~2026-09-21");
        jdbc.update("UPDATE stock SET era_to = ? WHERE id IN (?, ?)", LocalDate.of(2026, 9, 22), plain, vacated);
        // 고치기 전 코드가 붙인 시대 밖 행을 손으로 둔다(장부는 이제 안 붙인다).
        jdbc.update("""
                INSERT INTO filing_consumption (market, filing_no, stock_id, cik, form, filed_on)
                VALUES ('US', 'X-OUT', ?, 1, '6-K', DATE '2026-10-14'), ('US', 'X-IN', ?, 1, '6-K', DATE '2026-09-01')
                """, plain, plain);

        StockFoldJob.HealTargets targets = foldJob.healTargets();

        assertThat(targets.labels()).extracting(r -> r.get("ticker")).contains("COLAT").doesNotContain("NHICT~2026-09-21");
        assertThat(targets.ledger()).extracting(r -> r.get("filing_no")).contains("X-OUT").doesNotContain("X-IN");
    }

    private long stock(String ticker) {
        return jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name) VALUES ('US', ?, ?) RETURNING id",
                Long.class, ticker, ticker);
    }

    private String ticker(long id) {
        return jdbc.queryForObject("SELECT ticker FROM stock WHERE id = ?", String.class, id);
    }

    private static ListedFiling filing(String no, long stockId, LocalDate filed) {
        return new ListedFiling(no, stockId, 1L, null, "6-K", null, filed, null, null, null);
    }
}
