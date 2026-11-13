package com.stockscreener.pipeline.collector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
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

    /**
     * 재료 행(별칭 {@code x})의 차원 없는 사실 — 비교의 단위. 정렬해 집합으로 견준다. 희석 EPS(국내 관문 켬의 판독기 판에만 있다)는 뺀다 — 재무 행
     * 값이 아니라 보통주 몫 상충 해소의 증거라, 정정이 그 줄만 고친 판(작은 DB 4쌍 · 180640 2023 3Q 등)에서 넣기 판정을 바꾸면 안 된다. 회사 확장
     * 사실(판독기 판 3 · {@code s = 'E'})도 같은 사유로 뺀다 — 보통주 몫 판정의 증거이고, 판마다 이름표 문구가 달라도 넣기 판정이 움직이면 안 된다.
     */
    private static final String FACE = """
            (SELECT jsonb_agg(e ORDER BY e::text) FROM jsonb_array_elements(%s.facts) e
             WHERE coalesce(jsonb_array_length(e->'m'), 0) = 0 AND e->>'c' <> 'DilutedEarningsLossPerShare' AND e->>'s' <> 'E')""";

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
            WHERE %s = %s
            ORDER BY bo.id, x.rcept_date, x.rcept_no
            """.formatted(FACE.formatted("x"), FACE.formatted("bo"));

    private final JdbcTemplate jdbc;
    private final FinancialWriter writer;

    /**
     * 복사본에 원천을 적고(`copied_from` · 관문 마이그레이션 V70_10) 매 실행 원천 값으로 다시 맞춘다. 복사본은 값 사본이라
     * 다시 지어지지 않아, 재적재가 원천 판에 채운 칸(중단영업 · 비표준 지배 NI · 기저 대체)이 빈 채 남는다(반증 안 렌즈 · 11-01).
     */
    public KrEarlierVintages(JdbcTemplate jdbc, FinancialWriter writer) {
        this(jdbc, writer, false);
    }

    /**
     * @param krGate 국내 관문({@code stockscreener.gate.kr-provisional}) — 켜면 지은 4분기의 중단영업 판정 규칙을 네 입력이 모두 그 규칙으로
     *               읽혔을 때만 FY 것으로 단다(계획 K17)
     */
    @org.springframework.beans.factory.annotation.Autowired
    public KrEarlierVintages(JdbcTemplate jdbc, FinancialWriter writer,
                             @org.springframework.beans.factory.annotation.Value("${stockscreener.gate.kr-provisional}") boolean krGate) {
        this.jdbc = jdbc;
        this.writer = writer;
        this.krGate = krGate;
    }

    private final boolean krGate;

    private record Candidate(long rowId, int fiscalYear, String fiscalPeriod, LocalDate earliest) {
    }

    /** 한 종목의 결과 — 넣은 이른 판 · 다시 짓거나 지운 4분기 판 · 원천으로 다시 맞춘 복사본. */
    public record Outcome(int inserted, int restored, int resynced) {
    }

    /** 그 종목의 라벨마다 이른 판을 넣는다. 호출부의 트랜잭션 안에서 돈다(종목 하나 = 한 트랜잭션). */
    public Outcome insert(long stockId) {
        List<Candidate> candidates = jdbc.query(CANDIDATES, (rs, i) -> new Candidate(rs.getLong("id"), rs.getInt("fiscal_year"),
                rs.getString("fiscal_period"), rs.getObject("earliest", LocalDate.class)), stockId, stockId, stockId);
        if (candidates.isEmpty()) {
            return new Outcome(0, restoreFourthQuarters(stockId), resync(stockId));
        }
        String columns = copiedColumns();
        int inserted = 0;
        for (Candidate c : candidates) {
            // 임시 번호는 그 라벨의 최대 + 1 — 넣은 즉시 공시일 순으로 다시 맞춘다(uq 번호 충돌을 같은 트랜잭션 안에서 풀려고).
            inserted += jdbc.update("""
                    INSERT INTO financial_statement (%1$s, published_at, revision_no, is_latest, copied_from)
                    SELECT %1$s, ?, (SELECT max(revision_no) + 1 FROM financial_statement
                                     WHERE stock_id = ? AND fiscal_year = ? AND fiscal_period = ?), false, %2$s
                    FROM financial_statement WHERE id = ?
                    """.formatted(columns, c.rowId()),
                    Date.valueOf(c.earliest()), stockId, c.fiscalYear(), c.fiscalPeriod(), c.rowId());
            writer.renumberLabel(stockId, c.fiscalYear(), c.fiscalPeriod());
        }
        return new Outcome(inserted, restoreFourthQuarters(stockId), resync(stockId));
    }

    /** 4분기 유량 칸 — FY − 그날 보이던 1~3분기로 짓는다(어댑터의 4분기 유도와 같은 칸). 나머지 유량은 국내 4분기 행이 안 싣는다. */
    private static final List<String> FOURTH_QUARTER_FLOWS = List.of("revenue", "operating_income", "net_income",
            "net_income_controlling", "discontinued_income", "discontinued_income_controlling");
    private static final java.util.Set<String> QUARTER_EMPTY = java.util.Set.of("cost_of_sales", "gross_profit", "operating_cash_flow",
            "capex", "income_tax_expense", "profit_before_tax", "interest_and_dividend_income", "noninterest_income", "interest_expense",
            "noninterest_expense", "benefits_losses_and_expenses", "operating_expenses", "costs_and_expenses", "eps");

    /**
     * 국내 관문 켬 — 저장된 FY 판마다 같은 공시일의 4분기 판이 없으면 짓는다(재고 「유도 Q4 가시성」 남은 것). 옛 회수가 지운 4분기 판은 DART가
     * 옛 FY를 다시 안 줘 어댑터로는 안 되살아난다 — 4분기 = FY − 그날 보이던 1~3분기(어댑터와 같은 항등식 · 같은 해 분기가 다 보이고 통화가
     * 같을 때만 · 칸이 하나라도 비면 그 칸은 비운다 · 그 라벨에 더 늦은 소스 4분기 판이 있을 때만). 잔액은 그 FY 판의 잔액이다. 지은 판은 원천 FY
     * 판을 적고({@code derived_from} · 관문 시험 마이그레이션 V70_11) 매 실행 같은 식으로 다시 맞춘다 — 넣고 · 바뀐 것만 고치고 · 식이 더는 안
     * 서면 지운다. 바꾼 판 수.
     */
    int restoreFourthQuarters(long stockId) {
        List<String> names = java.util.Arrays.asList(copiedColumns().split(", "));
        List<String> exprs = new ArrayList<>();
        for (String n : names) {
            if ("fiscal_period".equals(n)) {
                exprs.add("'Q4'");
            } else if ("period_start".equals(n) || "flow_superseded".equals(n)) {
                // 기간 시작과 기저 대체 표지는 그 라벨의 판이 공유한다(applyFlowBases가 판 전부에 싣는다) — 소스 4분기 판에서.
                exprs.add("t." + n);
            } else if (FOURTH_QUARTER_FLOWS.contains(n)) {
                exprs.add("fy.%1$s - (q1.%1$s + q2.%1$s + q3.%1$s)".formatted(n));
            } else if (QUARTER_EMPTY.contains(n)) {
                exprs.add("NULL::numeric");
            } else if (krGate && "discontinued_rule".equals(n)) {
                // 국내 관문 켬(계획 K17): 4분기 중단영업 = FY − ΣQ라, 1~3분기 중 하나라도 그 규칙으로 안 읽혔으면 4분기 칸이 NULL인데 FY의 규칙을
                // 달면 「규칙이 있는데 중단 몫 모름」이 되어 계속영업 손익이 빈다(10행/6종목 · NET_MARGIN 301칸). 네 입력이 모두 같은 규칙일 때만 FY 것.
                exprs.add("""
                        CASE WHEN q1.discontinued_rule IS NOT DISTINCT FROM fy.discontinued_rule
                              AND q2.discontinued_rule IS NOT DISTINCT FROM fy.discontinued_rule
                              AND q3.discontinued_rule IS NOT DISTINCT FROM fy.discontinued_rule THEN fy.discontinued_rule END""");
            } else {
                exprs.add("fy." + n);
            }
        }
        String columns = String.join(", ", names);
        String desired = """
                SELECT fy.id AS fy_id, fy.published_at AS fy_published, fy.fiscal_year AS fy_year, %1$s
                  FROM financial_statement fy
                  JOIN LATERAL (SELECT * FROM financial_statement x WHERE x.stock_id = fy.stock_id AND x.fiscal_year = fy.fiscal_year
                                  AND x.fiscal_period = 'Q4' AND x.derived_from IS NULL ORDER BY x.published_at LIMIT 1) t ON true
                  JOIN %2$s ON true
                  JOIN %3$s ON true
                  JOIN %4$s ON true
                 WHERE fy.stock_id = ? AND fy.fiscal_period = 'FY'
                   AND q1.currency_code = fy.currency_code AND q2.currency_code = fy.currency_code AND q3.currency_code = fy.currency_code
                   AND NOT EXISTS (SELECT 1 FROM financial_statement x WHERE x.stock_id = fy.stock_id AND x.fiscal_year = fy.fiscal_year
                                     AND x.fiscal_period = 'Q4' AND x.derived_from IS NULL AND x.published_at = fy.published_at)
                   AND EXISTS (SELECT 1 FROM financial_statement x WHERE x.stock_id = fy.stock_id AND x.fiscal_year = fy.fiscal_year
                                 AND x.fiscal_period = 'Q4' AND x.derived_from IS NULL AND x.published_at > fy.published_at)
                """.formatted(java.util.stream.IntStream.range(0, names.size())
                        .mapToObj(i -> exprs.get(i) + " AS " + names.get(i)).collect(Collectors.joining(", ")),
                QUARTER.formatted("Q1", "q1"), QUARTER.formatted("Q2", "q2"), QUARTER.formatted("Q3", "q3"));
        String fromDesired = names.stream().map(n -> "d." + n).collect(Collectors.joining(", "));
        String current = names.stream().map(n -> "c." + n).collect(Collectors.joining(", "));
        org.springframework.jdbc.core.RowMapper<Object[]> yearAndDay = (rs, i) -> new Object[]{rs.getInt(1),
                rs.getObject(2, LocalDate.class)};
        List<Object[]> renumber = new ArrayList<>(jdbc.query("""
                DELETE FROM financial_statement c WHERE c.stock_id = ? AND c.derived_from IS NOT NULL
                   AND c.derived_from NOT IN (SELECT d.fy_id FROM (%s) d)
                RETURNING c.fiscal_year, c.published_at
                """.formatted(desired), yearAndDay, stockId, stockId));
        List<Object[]> changed = new ArrayList<>(renumber);
        changed.addAll(jdbc.query("""
                UPDATE financial_statement c SET (%1$s) = (%2$s)
                  FROM (%3$s) d
                 WHERE c.derived_from = d.fy_id AND ROW(%4$s) IS DISTINCT FROM ROW(%2$s)
                RETURNING c.fiscal_year, c.published_at
                """.formatted(columns, fromDesired, desired, current), yearAndDay, stockId));
        List<Object[]> inserted = jdbc.query("""
                INSERT INTO financial_statement (%1$s, published_at, revision_no, is_latest, derived_from)
                SELECT %2$s, d.fy_published, 10000 + row_number() OVER (), false, d.fy_id
                  FROM (%3$s) d
                 WHERE NOT EXISTS (SELECT 1 FROM financial_statement c WHERE c.derived_from = d.fy_id)
                RETURNING fiscal_year, published_at
                """.formatted(columns, fromDesired, desired), yearAndDay, stockId);
        renumber.addAll(inserted);
        changed.addAll(inserted);
        changed.addAll(fillSourceFourthQuarters(stockId, names, yearAndDay));
        for (Integer year : new java.util.TreeSet<>(renumber.stream().map(r -> (Integer) r[0]).toList())) {
            writer.renumberLabel(stockId, year, "Q4");
        }
        return changed.size();
    }

    /**
     * 그 FY 판의 공시일에 앉은 소스 4분기 이른 판(최신 아님 · 복사본 · 유도 판 아님)의 빈 유량 칸을 같은 항등식으로 채운다 — FY 이른 판이 그 판의
     * 인스턴스로 채워지면(ADR-0201) 그 날 4분기만 빈 채 남지 않게. 값이 있는 칸은 안 바꾼다(그 판을 지은 어댑터의 값이다).
     */
    private List<Object[]> fillSourceFourthQuarters(long stockId, List<String> names,
                                                    org.springframework.jdbc.core.RowMapper<Object[]> yearAndDay) {
        List<String> flows = FOURTH_QUARTER_FLOWS.stream().filter(names::contains).toList();
        String set = flows.stream().map(n -> "%1$s = coalesce(c.%1$s, fy.%1$s - (q1.%1$s + q2.%1$s + q3.%1$s))".formatted(n))
                .collect(Collectors.joining(", "));
        String anyFillable = flows.stream().map(n -> "(c.%1$s IS NULL AND fy.%1$s - (q1.%1$s + q2.%1$s + q3.%1$s) IS NOT NULL)".formatted(n))
                .collect(Collectors.joining(" OR "));
        return jdbc.query("""
                UPDATE financial_statement c SET %1$s
                  FROM financial_statement fy
                  JOIN %2$s ON true
                  JOIN %3$s ON true
                  JOIN %4$s ON true
                 WHERE c.stock_id = ? AND c.fiscal_period = 'Q4' AND NOT c.is_latest AND c.copied_from IS NULL AND c.derived_from IS NULL
                   AND fy.stock_id = c.stock_id AND fy.fiscal_year = c.fiscal_year AND fy.fiscal_period = 'FY'
                   AND fy.published_at = c.published_at
                   AND q1.currency_code = fy.currency_code AND q2.currency_code = fy.currency_code AND q3.currency_code = fy.currency_code
                   AND (%5$s)
                RETURNING c.fiscal_year, c.published_at
                """.formatted(set, QUARTER.formatted("Q1", "q1"), QUARTER.formatted("Q2", "q2"), QUARTER.formatted("Q3", "q3"),
                anyFillable), yearAndDay, stockId);
    }

    // 판을 고쳐도 무효화하지 않는다 — 이 잡은 평일 14:16 사슬 끝이고 14:20 화해(ADR-0166)가 창 전체 원본층 · 파생층을 다시 쓴다(무효화는 그때까지
    // 0행 구간만 만든다 · FinancialWriter.fillMissing javadoc).

    /** 그 FY 판의 공시일에 보이던 그 해 분기 판 하나(공시일 · 번호가 가장 늦은 것). */
    private static final String QUARTER = """
            LATERAL (SELECT * FROM financial_statement q WHERE q.stock_id = fy.stock_id AND q.fiscal_year = fy.fiscal_year
                      AND q.fiscal_period = '%s' AND q.published_at <= fy.published_at
                      ORDER BY q.published_at DESC, q.revision_no DESC LIMIT 1) %s""";

    /** 국내 관문 켬 — 그 종목의 복사본을 원천 값으로 다시 맞춘다. 고친 행 수. */
    int resync(long stockId) {
        // 원천이 안 붙은 복사본(관문 마이그레이션 V70_10 뒤 꺼진 잡이 넣은 것)에 같은 술어로 먼저 원천을 붙인다 — 멱등.
        jdbc.update("""
                UPDATE financial_statement r
                   SET copied_from = (SELECT s.id FROM financial_statement s
                                       WHERE s.stock_id = r.stock_id AND s.fiscal_year = r.fiscal_year AND s.fiscal_period = r.fiscal_period
                                         AND s.published_at > r.published_at AND s.created_at < r.created_at
                                       ORDER BY s.published_at LIMIT 1)
                 WHERE r.stock_id = ? AND r.copied_from IS NULL AND r.derived_from IS NULL
                   AND EXISTS (SELECT 1 FROM financial_statement s
                                WHERE s.stock_id = r.stock_id AND s.fiscal_year = r.fiscal_year AND s.fiscal_period = r.fiscal_period
                                  AND s.published_at > r.published_at AND s.created_at < r.created_at)
                """, stockId);
        List<String> names = java.util.Arrays.asList(copiedColumns().split(", "));
        String target = String.join(", ", names);
        String source = names.stream().map(n -> "s." + n).collect(Collectors.joining(", "));
        String current = names.stream().map(n -> "c." + n).collect(Collectors.joining(", "));
        List<Object[]> changed = jdbc.query("""
                UPDATE financial_statement c SET (%1$s) = (%2$s)
                  FROM financial_statement s
                 WHERE s.id = c.copied_from AND c.stock_id = ? AND ROW(%3$s) IS DISTINCT FROM ROW(%2$s)
                RETURNING c.fiscal_year, c.published_at
                """.formatted(target, source, current), (rs, i) -> new Object[]{rs.getInt(1), rs.getObject(2, LocalDate.class)},
                stockId);
        return changed.size();
    }

    /**
     * 판을 복사할 열 — 식별 · 생성 시각 · 판 표지(공시일 · 번호 · 최신) · 공개 시각(그 공시의 것이라 다른 접수의 판에 옮기면 그 판의 보이는
     * 날이 틀린다 · V70_12)을 뺀 전부. 열이 늘어도 같이 따라간다.
     */
    private String copiedColumns() {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'financial_statement'
                  AND column_name NOT IN ('id', 'created_at', 'published_at', 'revision_no', 'is_latest', 'copied_from', 'derived_from',
                                          'accepted_at')
                ORDER BY ordinal_position
                """, String.class).stream().collect(Collectors.joining(", "));
    }
}
