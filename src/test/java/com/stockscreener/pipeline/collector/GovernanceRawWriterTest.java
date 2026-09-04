package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.GovernanceEndpoint;
import com.stockscreener.pipeline.datasource.GovernanceReportRow;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 거버넌스 원문 적재가 <b>두 번째 실행에서 터지지 않는지</b>를 고정한다.
 *
 * <p>{@code stampNoData}의 {@code UPDATE}는 {@code WHERE}에 {@code endpoint}가 <b>없다</b> —
 * 표를 가로질러 접수번호를 찍는 것이 그 메서드의 목적이라 의도된 것이다. 그런데
 * {@code uq_gov_row}에는 {@code endpoint}가 <b>있다.</b> 그래서 1회차가 만든
 * {@code (…, 접수번호, -1)} 행과 2회차의 {@code (…, '', -1)} 행이 만나면, UPDATE가 뒤엣것을
 * 앞엣것과 <b>같은 키</b>로 바꾸려다 유니크 제약을 위반한다.
 *
 * <p><b>무증상이 아니다 — 실제로 났다.</b> 2026-09-04 {@code governance-load}가 국내
 * {@code 033100}/2023/11011의 {@code OUTSIDE_DIRECTOR}에서 {@code DuplicateKeyException}으로
 * 종목 하나를 통째로 잃었다. 같은 모양의 후보가 <b>793그룹</b>이라 32종목만 돌려 1건이었던
 * 것이지 드문 일이 아니다.
 */
@JpaSliceTest
@Import(GovernanceRawWriter.class)
class GovernanceRawWriterTest {

    private static final int YEAR = 2023;
    private static final String REPORT = "11011";
    private static final String RECEIPT = "20240318000327";

    @Autowired
    private GovernanceRawWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;

    @BeforeEach
    void insertStock() {
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSDAQ', '033100', '제룡전기')
                RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("같은 해를 두 번 적재해도 데이터 없음 행 찍기가 터지지 않는다")
    void stampingIsIdempotentAcrossReruns() {
        loadOnce();
        // 1회차에서 센티넬이 실제로 찍혔는지 먼저 확인한다 — 안 찍혔으면 2회차가 안전한 것이
        // 수정 덕분인지 「애초에 안 찍혀서」인지 갈리지 않는다.
        assertThat(stampedReceipts()).containsExactly(RECEIPT);

        assertThatCode(this::loadOnce).doesNotThrowAnyException();

        // 찍힌 행은 하나로 남고 중복이 생기지 않는다.
        assertThat(stampedReceipts()).containsExactly(RECEIPT);
        assertThat(sentinelCount()).isEqualTo(1);
        assertThat(writer.unstampedNoDataRows()).isZero();
    }

    @Test
    @DisplayName("접수번호가 아직 없으면 데이터 없음 행을 그대로 둔다 — 지어내지 않는다")
    void leavesSentinelAloneWhenNoReceiptExists() {
        writer.write(stockId, List.of(
                GovernanceReportRow.noData(GovernanceEndpoint.OUTSIDE_DIRECTOR, YEAR, REPORT)));

        long stamped = writer.stampNoData(stockId, YEAR, REPORT);

        assertThat(stamped).isZero();
        assertThat(sentinelCount()).isEqualTo(1);
        assertThat(writer.unstampedNoDataRows()).isEqualTo(1);
    }

    /** 한 회차: 접수번호를 가진 표 하나 + 비어 온 표 하나를 쓰고 찍는다. */
    private void loadOnce() {
        writer.write(stockId, List.of(
                new GovernanceReportRow(GovernanceEndpoint.SHARE_TOTALS, YEAR, REPORT,
                        RECEIPT, LocalDate.of(2024, 3, 18), 0, Map.of("se", "보통주")),
                GovernanceReportRow.noData(GovernanceEndpoint.OUTSIDE_DIRECTOR, YEAR, REPORT)));
        writer.stampNoData(stockId, YEAR, REPORT);
    }

    private List<String> stampedReceipts() {
        return jdbcTemplate.queryForList("""
                SELECT receipt_no FROM governance_report_row
                 WHERE stock_id = ? AND endpoint = 'OUTSIDE_DIRECTOR' AND row_ordinal < 0
                   AND receipt_no <> ''
                """, String.class, stockId);
    }

    private int sentinelCount() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM governance_report_row
                 WHERE stock_id = ? AND endpoint = 'OUTSIDE_DIRECTOR' AND row_ordinal < 0
                """, Integer.class, stockId);
    }
}
