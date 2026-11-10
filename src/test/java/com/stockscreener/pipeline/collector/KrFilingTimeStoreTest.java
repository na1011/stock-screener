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
        KrFilingTimeStore store = new KrFilingTimeStore(jdbcTemplate, dir.toString(), false);
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
        assertThatThrownBy(() -> new KrFilingTimeStore(jdbcTemplate, dir.toString(), false).loadAll()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("관문 켬: 재게시 줄(접수일 < 목록 날짜)은 원래 시각을 덮지 않고, 원래 날짜 줄이 없으면 그 줄로 올라간 행을 걷는다 — 꺼짐은 덮는다(대조군)")
    void repostedRowsDoNotOverwriteTheOriginalTime() {
        LocalDate original = LocalDate.of(2026, 10, 8);
        LocalDate later = LocalDate.of(2026, 11, 3);
        LocalDate halfYear = LocalDate.of(2026, 8, 14);
        LocalDate halfYearLater = LocalDate.of(2026, 9, 30);
        LocalDate afterHours = LocalDate.of(2026, 3, 31);
        KrFilingTimeStore off = new KrFilingTimeStore(jdbcTemplate, dir.toString(), false);
        // 373220 잠정: 원래 날짜 목록에 있고, 재게시 날짜 목록에 접수일만 원래 날짜로 다시 실렸다.
        off.writeCache(original, List.of(new DartDayList.Row("20261008800055", "01515323", "LG에너지솔루션", "잠정", original, LocalTime.of(8, 34))));
        // 반기보고서: 원래 날짜 목록(나중에 받음)에는 없고 재게시 날짜 목록에만 있다.
        off.writeCache(halfYearLater, List.of(new DartDayList.Row("20260814001563", "00000001", "고영", "반기보고서", halfYear, LocalTime.of(15, 33))));
        // 영업시간 밖 제출: 접수일이 목록 날짜보다 늦다 — 그날의 진짜 게시 시각이다.
        off.writeCache(afterHours, List.of(new DartDayList.Row("20260331009999", "00000002", "가", "사업보고서", afterHours.plusDays(1), LocalTime.of(19, 5))));
        off.writeCache(later, List.of(new DartDayList.Row("20261008800055", "01515323", "LG에너지솔루션", "잠정", original, LocalTime.of(9, 27))));

        off.loadAll();
        assertThat(listedOn("20261008800055")).as("꺼짐 — 지금 동작(늦은 목록이 덮는다)").isEqualTo(later);
        assertThat(listedOn("20260814001563")).isEqualTo(halfYearLater);

        new KrFilingTimeStore(jdbcTemplate, dir.toString(), true).loadAll();
        assertThat(listedOn("20261008800055")).isEqualTo(original);
        assertThat(jdbcTemplate.queryForObject("SELECT opened_at FROM kr_filing_time WHERE rcept_no = '20261008800055'",
                java.sql.Timestamp.class).toInstant()).isEqualTo(Instant.parse("2026-10-07T23:34:59Z"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM kr_filing_time WHERE rcept_no = '20260814001563'", Integer.class))
                .as("원래 날짜 줄이 없으면 시각 모름").isZero();
        assertThat(listedOn("20260331009999")).as("영업시간 밖 제출은 그대로").isEqualTo(afterHours);
    }

    private LocalDate listedOn(String rceptNo) {
        return jdbcTemplate.queryForObject("SELECT listed_on FROM kr_filing_time WHERE rcept_no = ?", LocalDate.class, rceptNo);
    }
}
