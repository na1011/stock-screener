package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.kr.DartDayList;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 국내 공시 게시 시각 캐시 → 관문 표(ADR-0204) — 그 분의 끝(59초) · 그 줄이 실린 날짜 · 깨진 캐시는 던진다. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class KrFilingTimeStoreTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    Path dir;

    @Test
    @DisplayName("캐시를 표에 올린다 — 게시 시각은 그 분의 끝(59초 · KST) · 날짜는 그 줄이 실린 쪽의 날짜 · 다시 올려도 같다")
    void loadsCacheIntoTheGateTable() {
        KrFilingTimeStore store = new KrFilingTimeStore(jdbcTemplate, dir.toString());
        LocalDate day = LocalDate.of(2025, 11, 14);
        store.writeCache(day, List.of(new DartDayList.Row("20251114002447", "00126380", "삼성전자", "분기보고서", day, LocalTime.of(16, 33))));
        assertThat(store.cached(day)).isTrue();
        assertThat(store.loadAll()).isEqualTo(1);
        assertThat(store.loadAll()).as("다시 올려도 줄 수는 같다").isEqualTo(1);
        Instant opened = jdbcTemplate.queryForObject("SELECT opened_at FROM kr_filing_time WHERE rcept_no = '20251114002447'",
                java.sql.Timestamp.class).toInstant();
        assertThat(opened).isEqualTo(Instant.parse("2025-11-14T07:33:59Z"));
    }

    @Test
    @DisplayName("줄 수가 첫 줄 총 건수와 다른 캐시는 깨진 것이라 던진다")
    void brokenCacheThrows() throws Exception {
        Files.writeString(dir.resolve("20251114.tsv"), "total=2\n20251114002447\t00126380\t삼성전자\t분기보고서\t2025-11-14\t16:33\n");
        assertThatThrownBy(() -> new KrFilingTimeStore(jdbcTemplate, dir.toString()).loadAll()).isInstanceOf(IllegalStateException.class);
    }
}
