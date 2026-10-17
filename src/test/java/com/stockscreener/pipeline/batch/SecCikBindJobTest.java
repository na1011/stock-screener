package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.RegistrantLookup;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 등록인 묶기 — 오늘 SEC 파일에 없는 행(닫힌 행 · 파일 밖)은 등록인 조회가 후보를 내고, 묶기는 그대로 포함 검사가 정한다(탐색
 * {@code US-PAST-LISTING-IMPL-1018.md} U3 · 못 묶은 43).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class SecCikBindJobTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 18);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BatchExecutions batchExecutions;

    @Test
    void closedRowOutsideTodaysFileIsBoundThroughTheLookupWhenContained() {
        long id = row("NHIX~2026-09-30", "NHIX Corp Common Stock", LocalDate.of(2026, 10, 1), "acc-1");
        List<String> asked = new ArrayList<>();

        job(Map.of(), Set.of("acc-1"), lookupGiving(5555L, asked)).run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, List.of(),
                Map.of(), true);

        assertThat(asked).containsExactly("NHIX@2026-10-01 name=NHIX Corp Common Stock");
        assertThat(jdbc.queryForObject("SELECT sec_cik FROM stock WHERE id = ?", Long.class, id)).isEqualTo(5555L);
    }

    @Test
    void lookupCandidateFailingContainmentIsNotBound() {
        long id = row("FILX", "File Out Inc.", null, "acc-2");

        job(Map.of(), Set.of("other"), lookupGiving(6666L, new ArrayList<>())).run(AS_OF,
                BatchExecutionRecorder.TriggeredBy.MANUAL, List.of(), Map.of(), true);

        assertThat(jdbc.queryForObject("SELECT sec_cik FROM stock WHERE id = ?", Long.class, id)).isNull();
    }

    @Test
    void todaysFileWinsAndTheLookupIsNotOpened() {
        long id = row("FILE", "In File Inc.", null, "acc-3");
        List<String> asked = new ArrayList<>();

        job(Map.of("FILE", 7777L), Set.of("acc-3"), lookupGiving(1L, asked)).run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL,
                List.of("FILE"), Map.of(), true);

        assertThat(asked).isEmpty();
        assertThat(jdbc.queryForObject("SELECT sec_cik FROM stock WHERE id = ?", Long.class, id)).isEqualTo(7777L);
    }

    private long row(String ticker, String name, LocalDate eraTo, String accession) {
        long id = jdbc.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, alpaca_anchor_symbol, alpaca_anchor_date, era_to)
                VALUES ('US', ?, ?, ?, DATE '2026-08-28', ?) RETURNING id""", Long.class, ticker, name, ticker.split("~")[0], eraTo);
        jdbc.update("""
                INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at,
                                                 currency_code, report_code, revision_no, is_latest)
                VALUES (?, 2025, 'FY', DATE '2025-01-01', DATE '2025-12-31', DATE '2026-02-20', 'USD', ?, 0, true)""",
                id, "10-K:" + accession);
        return id;
    }

    private SecCikBindJob job(Map<String, Long> file, Set<String> accessions, RegistrantLookup lookup) {
        IdentitySource source = (IdentitySource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {IdentitySource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "registrantFile" -> (IdentitySource.RegistrantFile) symbol -> Optional.ofNullable(file.get(symbol));
                    case "registrantAccessions" -> accessions;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("identitySource", source);
        beans.addBean("registrantLookup", lookup);
        return new SecCikBindJob(beans.getBeanProvider(IdentitySource.class), beans.getBeanProvider(RegistrantLookup.class),
                batchExecutions, jdbc);
    }

    private static RegistrantLookup lookupGiving(long cik, List<String> asked) {
        return (from, asOf) -> (symbol, before, name, renames) -> {
            asked.add(symbol + "@" + before + " name=" + name.orElse(""));
            return new RegistrantLookup.Result(RegistrantLookup.Outcome.BOUND, cik, "NYSE", name.orElse(""), "가짜 확인");
        };
    }
}
