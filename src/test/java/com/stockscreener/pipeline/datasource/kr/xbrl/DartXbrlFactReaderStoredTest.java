package com.stockscreener.pipeline.datasource.kr.xbrl;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>판독기가 저장된 재료와 같은 사실을 내는가</b>(0콜 · 읽기만) — 판독기 판({@link DartXbrlFactReader#READER_VERSION})을 안 올린 변경이 저장 재료를
 * 바꾸지 않았음을 디스크 캐시 전부로 잰다. 운영 DB를 읽기만 한다.
 *
 * <pre>
 * STORED_DB_URL=jdbc:postgresql://localhost:5432/stock_screener KR_XBRL_DIR=~/.stock-screener/kr-xbrl ./gradlew test --tests "*DartXbrlFactReaderStoredTest*"
 * </pre>
 */
class DartXbrlFactReaderStoredTest {

    @Test
    @DisplayName("캐시된 인스턴스마다 판독 결과가 저장된 사실 · 못 읽은 값 수와 같다")
    void readerMatchesStoredFacts() throws Exception {
        String url = System.getenv("STORED_DB_URL");
        String dir = System.getenv("KR_XBRL_DIR");
        Assumptions.assumeTrue(url != null && dir != null, "STORED_DB_URL · KR_XBRL_DIR이 없어 건너뛴다");
        int compared = 0;
        int differ = 0;
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            c.setReadOnly(true);
            try (ResultSet rs = st.executeQuery("""
                    SELECT corp_code, rcept_no, facts::text, unreadable_values FROM kr_report_xbrl
                    WHERE xbrl_status = 'OK' AND reader_version = %d""".formatted(DartXbrlFactReader.READER_VERSION))) {
                while (rs.next()) {
                    Path cached = Path.of(dir, rs.getString(1), rs.getString(2) + ".xbrl.gz");
                    if (!Files.exists(cached)) {
                        continue;
                    }
                    DartXbrlFactReader.Result read;
                    try (InputStream in = new GZIPInputStream(Files.newInputStream(cached))) {
                        read = DartXbrlFactReader.read(in, rs.getString(2));
                    }
                    compared++;
                    String stored = KrXbrlStore.encode(KrXbrlStore.decode(rs.getString(3)));
                    if (!stored.equals(KrXbrlStore.encode(read.facts())) || read.unreadableValues() != rs.getInt(4)) {
                        differ++;
                        if (differ <= 5) {
                            System.out.println("다름 " + rs.getString(2));
                        }
                    }
                }
            }
        }
        System.out.printf("compared=%d differ=%d%n", compared, differ);
        assertThat(compared).isPositive();
        assertThat(differ).isZero();
    }
}
