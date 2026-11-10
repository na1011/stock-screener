package com.stockscreener.pipeline.collector;

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

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 국내 실적 발표 본문 캐시 → 관문 표 — 읽은 서식 · 못 읽은 서식(사유 행) · 꺼짐은 표를 안 쓴다. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class KrProvisionalStoreTest {

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path dir;

    @Test
    @DisplayName("켬: 캐시가 있는 발표 줄을 해석해 올린다 — 값은 문서 단위 그대로, 못 읽은 본문은 사유 행 · 캐시 없는 줄은 건너뛴다")
    void loadsParsedAndUnreadableRows() throws Exception {
        long stock = jdbc.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, accounting_month) VALUES ('KOSPI', '999901', '시험', 12) RETURNING id
                """, Long.class);
        release(stock, "20261029800099", "[기재정정]연결재무제표기준영업(잠정)실적(공정공시)", "2026-10-29");
        release(stock, "20261102800493", "영업(잠정)실적(공정공시)", "2026-11-02");
        release(stock, "20261103000001", "영업(잠정)실적(공정공시)", "2026-11-03");
        release(stock, "20261104000001", "영업(잠정)실적(공정공시)", "2026-11-04");
        KrProvisionalStore store = new KrProvisionalStore(jdbc, dir.toString(), true);
        store.writeCache("20261029800099", fixture("20261029800099"));
        store.writeCache("20261102800493", fixture("20261102800493"));
        store.writeCache("20261103000001", "<html>점검 중</html>".getBytes());

        assertThat(store.loadAll()).containsExactly(3, 1);
        Map<String, Object> q3 = jdbc.queryForMap("SELECT * FROM kr_provisional_release WHERE rcept_no = '20261029800099'");
        assertThat(q3.get("status")).isEqualTo("OK");
        assertThat(q3.get("release_period")).isEqualTo("QUARTER");
        assertThat((BigDecimal) q3.get("revenue")).isEqualByComparingTo("195.25");
        assertThat(((BigDecimal) q3.get("revenue")).scale()).isEqualTo(2);
        assertThat(q3.get("unit_won")).isEqualTo(1_000_000_000_000L);
        assertThat(jdbc.queryForObject("SELECT release_period FROM kr_provisional_release WHERE rcept_no = '20261102800493'",
                String.class)).isEqualTo("OTHER");
        assertThat(jdbc.queryForObject("SELECT status FROM kr_provisional_release WHERE rcept_no = '20261103000001'", String.class))
                .isEqualTo("no-anchor");
        assertThat(store.loadAll()).as("다시 올려도 같다").containsExactly(3, 1);
    }

    @Test
    @DisplayName("꺼짐: 관문 표를 쓰지 않는다(운영 DB에 그 표가 없다)")
    void offRefusesToLoad() {
        assertThatThrownBy(() -> new KrProvisionalStore(jdbc, dir.toString(), false).loadAll()).isInstanceOf(IllegalStateException.class);
    }

    private void release(long stock, String rceptNo, String name, String filedOn) {
        jdbc.update("""
                INSERT INTO earnings_release (market, filing_no, stock_id, form, report_name, filed_on, effective_on)
                VALUES ('KR', ?, ?, 'I002', ?, ?::date, ?::date)
                """, rceptNo, stock, name, filedOn, filedOn);
    }

    private static byte[] fixture(String rceptNo) throws Exception {
        try (InputStream in = KrProvisionalStoreTest.class.getResourceAsStream("/dart-prov/" + rceptNo + ".html")) {
            return in.readAllBytes();
        }
    }
}
