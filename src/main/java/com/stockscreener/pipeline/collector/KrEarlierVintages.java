package com.stockscreener.pipeline.collector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 국내 재무 행의 <b>같은 숫자를 처음 공개한 판</b>을 넣는다(ADR-0195 · progress [A65]).
 *
 * <p>DART 재무 API({@code fnlttSinglAcnt} · {@code fnlttSinglAcntAll})는 보고서마다 <b>가장 늦은 판</b>만 준다. 그래서 재적재로 처음
 * 저장된 판의 공시일이 정정일로 밀린다 — 2026-10-31 실측: 국내 보고서 16,404건 중 1,680건(418종목)의 첫 저장 공시일이 원본 접수일보다
 * 늦다(중앙값 47일). KB금융 FY2025는 원본 03-13 · 저장 06-19, LG전자는 원본 03-13 · 저장 07-06이다. 그동안 지표는 옛 분기로 돌고, 새 실적
 * 발표가 공개된 봉의 재무 지표를 막는 규칙(ADR-0195) 아래서는 그 넉 달이 통째로 빈다.
 *
 * <p><b>규칙 하나</b>: 그 라벨의 첫 판이 기대는 판(같은 사업연도 · 보고서 코드 · 같은 날)과 <b>차원 없는 XBRL 사실 전부가 같은</b> 더 이른
 * 판이 있으면, 그중 가장 이른 판의 접수일로 같은 값의 판을 하나 넣는다({@code is_latest} 아님 · 번호는 공시일 순으로 다시 맞춘다). 숫자가
 * 하나라도 다르면 넣지 않는다 — 정정이 숫자를 바꾼 경우가 실제로 있다(같은 날 실측: 원본 · 마지막 정정 둘 다 받은 17쌍 중 4쌍이 다르다).
 * KB금융 · LG전자의 원본과 정정은 XBRL 인스턴스가 바이트까지 같았다.
 *
 * <p>기존 행은 안 고친다 — 그 행의 공시일을 당기면 다음 재무 적재가 소스의 정정일을 「더 늦은 판 = 정정」으로 읽어 지표를 지운다
 * ({@code FinancialWriter.writeOne}). 이른 판을 따로 넣으면 소스의 판은 저장된 최신 판과 같은 공시일이라 재수집이다. 미국의 이른 판 넣기
 * (ADR-0161)와 같은 꼴이다.
 */
@Component
public class KrEarlierVintages {

    /** 재료 행(별칭 {@code x})의 차원 없는 사실 — 비교의 단위. 정렬해 집합으로 견준다. */
    private static final String FACE = """
            (SELECT jsonb_agg(e ORDER BY e::text) FROM jsonb_array_elements(%s.facts) e
             WHERE coalesce(jsonb_array_length(e->'m'), 0) = 0)""";

    /**
     * 그 종목의 라벨마다 첫 판(공시일 가장 이른 행)과, 그 판이 기대는 재료 판과 사실이 같은 더 이른 판 중 가장 이른 접수일. 이미 그 날의
     * 판이 있는 라벨은 안 낸다.
     */
    private static final String CANDIDATES = """
            WITH firsts AS (
                SELECT DISTINCT ON (f.fiscal_year, f.fiscal_period) f.id, f.fiscal_year, f.fiscal_period, f.published_at, f.report_code
                FROM financial_statement f
                WHERE f.stock_id = ? AND f.report_code IN ('11011', '11012', '11013', '11014')
                ORDER BY f.fiscal_year, f.fiscal_period, f.published_at, f.revision_no
            ), bound AS (
                SELECT DISTINCT ON (fi.id) fi.id, fi.fiscal_year, fi.fiscal_period, fi.published_at, fi.report_code,
                       b.facts AS facts
                FROM firsts fi
                JOIN kr_report_xbrl b ON b.stock_id = ? AND b.bsns_year = fi.fiscal_year AND b.reprt_code = fi.report_code
                     AND (b.rcept_date = fi.published_at OR left(b.rcept_no, 8) = to_char(fi.published_at, 'YYYYMMDD'))
                WHERE b.xbrl_status = 'OK'
                ORDER BY fi.id, b.rcept_no DESC
            )
            SELECT DISTINCT ON (bo.id) bo.id, bo.fiscal_year, bo.fiscal_period, x.rcept_date AS earliest
            FROM bound bo
            JOIN kr_report_xbrl x ON x.stock_id = ? AND x.bsns_year = bo.fiscal_year AND x.reprt_code = bo.report_code
                 AND x.rcept_date < bo.published_at AND x.xbrl_status = 'OK'
            WHERE %s = (SELECT jsonb_agg(e ORDER BY e::text) FROM jsonb_array_elements(bo.facts) e
                        WHERE coalesce(jsonb_array_length(e->'m'), 0) = 0)
            ORDER BY bo.id, x.rcept_date, x.rcept_no
            """.formatted(FACE.formatted("x"));

    private final JdbcTemplate jdbc;
    private final FinancialWriter writer;

    public KrEarlierVintages(JdbcTemplate jdbc, FinancialWriter writer) {
        this.jdbc = jdbc;
        this.writer = writer;
    }

    private record Candidate(long rowId, int fiscalYear, String fiscalPeriod, LocalDate earliest) {
    }

    /** 그 종목의 라벨마다 이른 판을 넣는다. 넣은 판 수를 낸다. 호출부의 트랜잭션 안에서 돈다(종목 하나 = 한 트랜잭션). */
    public int insert(long stockId) {
        List<Candidate> candidates = jdbc.query(CANDIDATES, (rs, i) -> new Candidate(rs.getLong("id"), rs.getInt("fiscal_year"),
                rs.getString("fiscal_period"), rs.getObject("earliest", LocalDate.class)), stockId, stockId, stockId);
        if (candidates.isEmpty()) {
            return 0;
        }
        String columns = copiedColumns();
        int inserted = 0;
        for (Candidate c : candidates) {
            // 임시 번호는 그 라벨의 최대 + 1 — 넣은 즉시 공시일 순으로 다시 맞춘다(uq 번호 충돌을 같은 트랜잭션 안에서 풀려고).
            inserted += jdbc.update("""
                    INSERT INTO financial_statement (%1$s, published_at, revision_no, is_latest)
                    SELECT %1$s, ?, (SELECT max(revision_no) + 1 FROM financial_statement
                                     WHERE stock_id = ? AND fiscal_year = ? AND fiscal_period = ?), false
                    FROM financial_statement WHERE id = ?
                    """.formatted(columns), Date.valueOf(c.earliest()), stockId, c.fiscalYear(), c.fiscalPeriod(), c.rowId());
            writer.renumberLabel(stockId, c.fiscalYear(), c.fiscalPeriod());
        }
        return inserted;
    }

    /** 판을 복사할 열 — 식별 · 생성 시각 · 판 표지(공시일 · 번호 · 최신)를 뺀 전부. 열이 늘어도 같이 따라간다. */
    private String copiedColumns() {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'financial_statement'
                  AND column_name NOT IN ('id', 'created_at', 'published_at', 'revision_no', 'is_latest')
                ORDER BY ordinal_position
                """, String.class).stream().collect(Collectors.joining(", "));
    }
}
