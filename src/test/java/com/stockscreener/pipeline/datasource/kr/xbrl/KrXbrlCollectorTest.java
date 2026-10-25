package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.support.TestDatabaseGuard;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.kr.DartCorpCodeCache;
import com.stockscreener.pipeline.datasource.kr.DartXbrlClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 국내 보통주 몫 재료 받기(ADR-0192) — 판 짝 · 받기 · 원본 없음 · 캐시 재사용 · 실패 기록. 외부 호출은 목이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class KrXbrlCollectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    private static final String HALF = "20250814000001";
    private static final String ANNUAL = "20260310000002";

    private static final String INSTANCE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance" xmlns:xbrldi="http://xbrl.org/2006/xbrldi"
                xmlns:ifrs-full="https://xbrl.ifrs.org/taxonomy/2023-03-23/ifrs-full">
              <xbrli:context id="Q2"><xbrli:entity><xbrli:identifier scheme="x">00000001</xbrli:identifier></xbrli:entity>
                <xbrli:period><xbrli:startDate>2025-04-01</xbrli:startDate><xbrli:endDate>2025-06-30</xbrli:endDate></xbrli:period>
              </xbrli:context>
              <ifrs-full:ProfitLossAttributableToOwnersOfParent contextRef="Q2" decimals="0" unitRef="KRW">1000</ifrs-full:ProfitLossAttributableToOwnersOfParent>
            </xbrli:xbrl>
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KrXbrlStore store;

    @TempDir
    Path cache;

    private DartXbrlClient client;
    private KrXbrlCollector collector;
    private KrXbrlStore.Target target;

    @BeforeEach
    void setUp() throws DataSourceException {
        client = mock(DartXbrlClient.class);
        DartCorpCodeCache corpCodes = mock(DartCorpCodeCache.class);
        when(corpCodes.findCorpCode("XB0001")).thenReturn(Optional.of("00000001"));
        collector = new KrXbrlCollector(provider(client), provider(corpCodes), store, cache.toString());
        long stockId = jdbc.queryForObject(
                "INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', 'XB0001', '재료 시험') RETURNING id", Long.class);
        insertRow(stockId, 2025, "Q2", LocalDate.of(2025, 4, 1), LocalDate.of(2025, 6, 30), LocalDate.of(2025, 8, 14));
        insertRow(stockId, 2025, "FY", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), LocalDate.of(2026, 3, 10));
        insertRow(stockId, 2025, "Q4", LocalDate.of(2025, 10, 1), LocalDate.of(2025, 12, 31), LocalDate.of(2026, 3, 10));
        target = new KrXbrlStore.Target(stockId, "XB0001", "KOSPI", 12);
        when(client.periodicFilings(eq("00000001"), any(), any())).thenReturn(List.of(
                new DartXbrlClient.Filing("A002", "반기보고서 (2025.06)", HALF, LocalDate.of(2025, 8, 14)),
                new DartXbrlClient.Filing("A001", "사업보고서 (2025.12)", ANNUAL, LocalDate.of(2026, 3, 10))));
        when(client.shareTotals(eq("00000001"), anyInt())).thenReturn(register());
    }

    @Test
    @DisplayName("재무 판마다 같은 날 접수번호를 짝지어 받는다 — 원본 없음은 NO_FILE · 명부는 행의 해와 앞 해 · Q4는 판이 없다")
    void bindsFetchesAndRecords() throws Exception {
        when(client.instance(HALF)).thenReturn(Optional.of(INSTANCE.getBytes(StandardCharsets.UTF_8)));
        when(client.instance(ANNUAL)).thenReturn(Optional.empty());

        KrXbrlCollector.Collected c = collector.collect(target, TODAY, new AtomicInteger(10));

        assertThat(c.fetched()).isEqualTo(1);
        assertThat(c.noFile()).isEqualTo(1);
        assertThat(c.unbound()).isZero();
        assertThat(status(HALF)).isEqualTo("OK");
        assertThat(status(ANNUAL)).isEqualTo("NO_FILE");
        assertThat(store.okFacts(target.stockId()).get(HALF)).hasSize(1);
        assertThat(store.registerYears(target.stockId())).containsExactly(2024, 2025);
        assertThat(Files.exists(cache.resolve("00000001").resolve(HALF + ".xbrl.gz"))).isTrue();
    }

    @Test
    @DisplayName("이미 받은 판은 다시 안 받는다 · 캐시에 원본이 있으면 외부 0콜로 다시 판독한다")
    void reusesStoredAndCachedMaterial() throws Exception {
        when(client.instance(HALF)).thenReturn(Optional.of(INSTANCE.getBytes(StandardCharsets.UTF_8)));
        when(client.instance(ANNUAL)).thenReturn(Optional.empty());
        collector.collect(target, TODAY, new AtomicInteger(10));

        KrXbrlCollector.Collected again = collector.collect(target, TODAY, new AtomicInteger(10));
        assertThat(again.fetched()).isZero();
        verify(client, times(1)).instance(HALF);
        verify(client, times(1)).periodicFilings(any(), any(), any());

        // 판독기 판이 바뀐 꼴 — 재료 행을 옛 판으로 돌리면 캐시에서 다시 읽는다(외부 호출 없이).
        jdbc.update("UPDATE kr_report_xbrl SET reader_version = 0 WHERE rcept_no = ?", HALF);
        KrXbrlCollector.Collected reread = collector.collect(target, TODAY, new AtomicInteger(0));
        assertThat(reread.reread()).isEqualTo(1);
        verify(client, times(1)).instance(HALF);
        assertThat(jdbc.queryForObject("SELECT reader_version FROM kr_report_xbrl WHERE rcept_no = ?", Integer.class, HALF))
                .isEqualTo(DartXbrlFactReader.READER_VERSION);
    }

    @Test
    @DisplayName("판 하나의 받기 실패는 FAILED로 남고 종목은 실패로 올린다 · 상한이 0이면 새로 안 받는다")
    void recordsFailuresAndRespectsTheBudget() throws Exception {
        when(client.instance(HALF)).thenThrow(new DataSourceException(DataSourceException.Kind.TRANSIENT, "dart", "끊김"));
        when(client.instance(ANNUAL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> collector.collect(target, TODAY, new AtomicInteger(10)))
                .isInstanceOf(DataSourceException.class).hasMessageContaining("판 1개");
        assertThat(status(HALF)).isEqualTo("FAILED");
        assertThat(store.unresolved()).isGreaterThanOrEqualTo(1);

        // 앞 스텁이 던지므로 when(...)으로 다시 부르면 그 호출이 던진다 — doReturn으로 바꾼다.
        org.mockito.Mockito.doReturn(Optional.of(INSTANCE.getBytes(StandardCharsets.UTF_8))).when(client).instance(HALF);
        KrXbrlCollector.Collected none = collector.collect(target, TODAY, new AtomicInteger(0));
        assertThat(none.fetched()).isZero();
        assertThat(status(HALF)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("재료 사실 JSON은 갈래 · 기저 · 구성원 · 시점을 그대로 되돌린다")
    void factsRoundTrip() throws DataSourceException {
        List<DartXbrlFactReader.Fact> facts = List.of(
                new DartXbrlFactReader.Fact(DartXbrlFactReader.Source.DART, "HybridBonds", null, LocalDate.of(2026, 6, 30),
                        DartXbrlFactReader.Basis.CONSOLIDATED, List.of(), new java.math.BigDecimal("299230000000")),
                new DartXbrlFactReader.Fact(DartXbrlFactReader.Source.IFRS, "BasicEarningsLossPerShare", LocalDate.of(2026, 4, 1),
                        LocalDate.of(2026, 6, 30), DartXbrlFactReader.Basis.SEPARATE,
                        List.of(new DartXbrlFactReader.Member("ClassesOfShareCapitalAxis", "FirstPreferredSharesMember",
                                DartXbrlFactReader.Source.ENTITY)), new java.math.BigDecimal("-2.06")));
        assertThat(KrXbrlStore.decode(KrXbrlStore.encode(facts))).isEqualTo(facts);
    }

    private String status(String rceptNo) {
        return jdbc.queryForObject("SELECT xbrl_status FROM kr_report_xbrl WHERE rcept_no = ?", String.class, rceptNo);
    }

    private void insertRow(long stockId, int year, String period, LocalDate start, LocalDate end, LocalDate published) {
        jdbc.update("""
                INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at,
                                                 currency_code, report_code, net_income_controlling)
                VALUES (?, ?, ?, ?, ?, ?, 'KRW', '11012', 1000)
                """, stockId, year, period, start, end, published);
    }

    private static List<JsonNode> register() {
        ObjectMapper m = new ObjectMapper();
        return List.of(m.readTree("{\"se\":\"보통주\",\"istc_totqy\":\"1,000\",\"tesstk_co\":\"-\",\"rcept_no\":\"20260310000002\",\"stlm_dt\":\"2025-12-31\"}"),
                m.readTree("{\"se\":\"합계\",\"istc_totqy\":\"1,000\",\"tesstk_co\":\"-\",\"rcept_no\":\"20260310000002\",\"stlm_dt\":\"2025-12-31\"}"));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getObject()).thenReturn(bean);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }
}
