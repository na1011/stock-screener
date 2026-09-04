package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.GovernanceReportRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Types;
import java.time.LocalDate;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

/**
 * 정기보고서 주요정보 응답을 {@code governance_report_row}에 원문 그대로 쓴다.
 *
 * <p>MUST NOT: 여기서 값을 정리하지 않는다. 콤마도 센티널 {@code -}도 개행이 든 기수 표기도
 * 그대로 둔다 — 원문이 남아 있어야 유도 규칙을 고칠 때 <b>재적재가 아니라 재유도</b>로 끝난다.
 */
@Component
public class GovernanceRawWriter {

    private static final Logger log = LoggerFactory.getLogger(GovernanceRawWriter.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final int BATCH_SIZE = 100;

    /**
     * 접수번호가 키에 있으므로 정정본은 <b>새 행</b>이고 같은 판의 재적재만 여기서 만난다.
     *
     * <p>{@code IS DISTINCT FROM}으로 거르는 이유는 다른 UPSERT와 같다 — 값이 그대로면
     * 아무 행도 안 세어 <b>재실행에서 0행이 나오는 것이 멱등의 증거</b>가 된다.
     */
    private static final String UPSERT = """
            INSERT INTO governance_report_row
                (stock_id, endpoint, bsns_year, reprt_code, receipt_no, published_at,
                 row_ordinal, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (stock_id, endpoint, bsns_year, reprt_code, receipt_no, row_ordinal)
            DO UPDATE SET published_at = EXCLUDED.published_at,
                          payload      = EXCLUDED.payload
             WHERE governance_report_row.payload      IS DISTINCT FROM EXCLUDED.payload
                OR governance_report_row.published_at IS DISTINCT FROM EXCLUDED.published_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public GovernanceRawWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** @return 실제로 쓰인 행 수. 재실행에서 0이 정상이다. */
    public long write(long stockId, List<GovernanceReportRow> rows) {
        if (rows.isEmpty()) {
            return 0L;
        }
        int[][] results = jdbcTemplate.batchUpdate(UPSERT, rows, BATCH_SIZE, (ps, row) -> {
            ps.setLong(1, stockId);
            ps.setString(2, row.endpoint().name());
            ps.setInt(3, row.bsnsYear());
            ps.setString(4, row.reportCode());
            ps.setString(5, row.receiptNo());
            if (row.publishedAt() == null) {
                ps.setNull(6, Types.DATE);
            } else {
                ps.setObject(6, row.publishedAt());
            }
            ps.setInt(7, row.ordinal());
            ps.setString(8, JSON.writeValueAsString(row.fields()));
        });
        long written = 0;
        for (int[] batch : results) {
            for (int count : batch) {
                // 드라이버가 SUCCESS_NO_INFO(-2)를 주는 경우가 있어 음수를 1행으로 센다.
                written += count < 0 ? 1 : count;
            }
        }
        return written;
    }

    /**
     * 데이터 없음 행에 <b>같은 종목·같은 사업연도·같은 보고서의 다른 표</b>가 받은 접수번호를 찍는다.
     *
     * <p>정기보고서 하나에서 나온 표들이므로 접수번호가 같다 — 지어내는 값이 아니다. 여러
     * 접수번호가 있으면 <b>가장 큰 것</b>(가장 늦게 접수된 판)을 쓴다.
     *
     * <p>이 자리가 필요한 이유는 표를 하나씩 받기 때문이다. {@code CONTROL_CHANGE}가 비어 온
     * 종목은 그 호출 안에 다른 표가 없어, 여덟을 다 받은 뒤에야 찍을 수 있다.
     *
     * <p><b>MUST: 찍기 전에 이미 찍힌 자리의 빈 행을 걷는다.</b> 아래 UPDATE의 {@code WHERE}에는
     * {@code endpoint}가 없는데(그것이 의도다 — 표를 가로질러 찍는 것이 이 메서드다)
     * <b>유니크 키에는 있다.</b> 그래서 <b>두 번째 실행</b>에서 터진다: 1회차가
     * {@code (…, '', -1)}을 찍어 {@code (…, 20240318000327, -1)}로 만들고, 2회차에 같은 표가
     * 또 비어 와 {@code (…, '', -1)}이 다시 들어오면, 이 UPDATE가 그것을 <b>1회차가 만든 행과
     * 같은 키</b>로 바꾸려 해 {@code uq_gov_row}를 위반한다. 실측(2026-09-04): 국내
     * {@code 033100}/2023/11011의 {@code OUTSIDE_DIRECTOR}에서 실제로 났고, 같은 모양의 후보가
     * <b>793그룹</b>이다 — 32종목만 돌려 1건이었던 것이지 드문 일이 아니다.
     *
     * <p>걷는 것이 안전한 근거: 센티넬의 {@code payload}는 <b>1,050행 전부가 {@code &#123;&#125;}</b>이라
     * 빈 행과 찍힌 행의 내용이 같다. 같은 자리에 <b>같은 접수번호</b>가 이미 있을 때만 지우므로,
     * 접수번호가 다르면(=다른 판이면) 그대로 두고 UPDATE가 찍는다.
     */
    public long stampNoData(long stockId, int bsnsYear, String reportCode) {
        jdbcTemplate.update("""
                DELETE FROM governance_report_row target
                 WHERE target.stock_id = ? AND target.bsns_year = ? AND target.reprt_code = ?
                   AND target.row_ordinal < 0
                   AND target.receipt_no = ''
                   AND EXISTS (SELECT 1 FROM governance_report_row dup
                                WHERE dup.stock_id    = target.stock_id
                                  AND dup.endpoint    = target.endpoint
                                  AND dup.bsns_year   = target.bsns_year
                                  AND dup.reprt_code  = target.reprt_code
                                  AND dup.row_ordinal = target.row_ordinal
                                  AND dup.receipt_no  = (SELECT receipt_no
                                                           FROM governance_report_row src
                                                          WHERE src.stock_id   = target.stock_id
                                                            AND src.bsns_year  = target.bsns_year
                                                            AND src.reprt_code = target.reprt_code
                                                            AND src.row_ordinal >= 0
                                                            AND src.published_at IS NOT NULL
                                                          ORDER BY src.receipt_no DESC
                                                          LIMIT 1))
                """, stockId, bsnsYear, reportCode);
        return jdbcTemplate.update("""
                UPDATE governance_report_row target
                   SET receipt_no   = source.receipt_no,
                       published_at = source.published_at
                  FROM (SELECT receipt_no, published_at
                          FROM governance_report_row
                         WHERE stock_id = ? AND bsns_year = ? AND reprt_code = ?
                           AND row_ordinal >= 0 AND published_at IS NOT NULL
                         ORDER BY receipt_no DESC
                         LIMIT 1) source
                 WHERE target.stock_id = ? AND target.bsns_year = ? AND target.reprt_code = ?
                   AND target.row_ordinal < 0
                   AND target.receipt_no = ''
                """, stockId, bsnsYear, reportCode, stockId, bsnsYear, reportCode);
    }

    /**
     * 접수번호를 끝내 못 찍은 데이터 없음 행 수.
     *
     * <p>0이 아니면 <b>그 종목의 그 해는 여덟이 다 비어 있었다</b>는 뜻이고, 그 사실 자체가
     * 커버리지 판정의 재료다 — 「그 회사에 없음」과 「미수집」을 가르는 것이 이 행의 존재 이유인데,
     * 시점이 없으면 지표가 그것을 쓰지 못한다.
     */
    public long unstampedNoDataRows() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM governance_report_row
                 WHERE row_ordinal < 0 AND receipt_no = ''
                """, Long.class);
        return count == null ? 0L : count;
    }

    /** 그 종목이 받은 공시일들. 진단용이다. */
    public List<LocalDate> publishedDates(long stockId) {
        return jdbcTemplate.query("""
                SELECT DISTINCT published_at FROM governance_report_row
                 WHERE stock_id = ? AND published_at IS NOT NULL
                 ORDER BY published_at
                """, (rs, rowNum) -> rs.getObject(1, LocalDate.class), stockId);
    }
}
