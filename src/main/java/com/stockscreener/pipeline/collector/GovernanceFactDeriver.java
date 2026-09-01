package com.stockscreener.pipeline.collector;

import com.stockscreener.indicator.GovernanceMetric;
import com.stockscreener.pipeline.datasource.GovernanceDerivation;
import com.stockscreener.pipeline.datasource.GovernanceEndpoint;
import com.stockscreener.pipeline.datasource.GovernanceFact;
import com.stockscreener.pipeline.datasource.GovernanceFactMapper;
import com.stockscreener.pipeline.datasource.GovernanceReportRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

/**
 * 저장된 원문에서 {@code governance_fact}를 <b>다시 짓는다. 외부 호출이 0이다.</b>
 *
 * <p>이 클래스가 있는 이유가 P18의 저장 구조를 가른 이유와 같다 — 유도 규칙은 틀린다.
 * 실제로 개발가이드와 어긋난 자리가 넷이었고(dart-governance.md의 「350종목 전수 적재가 답한 것」 절) 앞으로도
 * 더 나온다. 원문이 남아 있으면
 * 그 정정이 <b>수천 콜이 아니라 이 잡 한 번</b>으로 끝난다.
 *
 * <p>MUST: 종목 하나를 <b>통째로</b> 다시 짓는다. 회계연도나 엔드포인트로 좁히면 감사인 교체처럼
 * <b>보고서 둘을 견줘 나오는 값</b>이 조용히 빠진다.
 */
@Component
public class GovernanceFactDeriver {

    private static final Logger log = LoggerFactory.getLogger(GovernanceFactDeriver.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final CorporateActionLedger ledger;

    /**
     * 시장 → 매퍼. <b>빈 하나를 주입받던 자리다</b> — 매퍼가 둘이 되는 순간 Spring이 어느 쪽을
     * 쓸지 정하지 못해 기동에 실패하므로, {@code DataSourceRegistry}와 같은 형태로 색인한다.
     */
    private final Map<String, GovernanceFactMapper> mappersByMarket;

    public GovernanceFactDeriver(JdbcTemplate jdbcTemplate, CorporateActionLedger ledger,
                                 List<GovernanceFactMapper> mappers) {
        this.jdbcTemplate = jdbcTemplate;
        this.ledger = ledger;
        Map<String, GovernanceFactMapper> index = new HashMap<>();
        for (GovernanceFactMapper candidate : mappers) {
            for (String market : candidate.supportedMarketCodes()) {
                GovernanceFactMapper previous = index.put(market, candidate);
                if (previous != null) {
                    // 두 매퍼가 같은 시장을 주장하면 어느 쪽이 이겼는지가 스캔 순서에 달린다.
                    throw new IllegalStateException(
                            "시장 하나에 거버넌스 매퍼가 둘이다: " + market + " — "
                                    + previous.getClass().getSimpleName() + " · "
                                    + candidate.getClass().getSimpleName());
                }
            }
        }
        this.mappersByMarket = Map.copyOf(index);
    }

    /**
     * 한 종목의 사실을 다시 짓는다.
     *
     * <p>지우고 다시 쓰는 것이 옳다 — 유도 규칙이 바뀌면 <b>안 나오게 된 사실</b>이 생기는데,
     * upsert만 하면 그 옛 행이 남아 지표가 계속 그것을 읽는다. 화면에서는 그럴듯한 숫자라
     * 여기 말고는 드러나는 자리가 없다.
     *
     * @return 쓴 사실의 수
     */
    @Transactional
    public long derive(long stockId) {
        Map<GovernanceEndpoint, List<GovernanceReportRow>> byEndpoint = readRows(stockId);
        String market = marketOf(stockId);
        GovernanceFactMapper mapper = mappersByMarket.get(market);
        if (mapper == null) {
            // 원문은 받아 왔는데 유도할 매퍼가 없다. 조용히 0을 쓰면 「그 회사에 값이 없다」와
            // 구별되지 않으므로 남긴다 — 원문이 없으면 아래 루프가 어차피 0이다.
            if (!byEndpoint.isEmpty()) {
                log.warn("거버넌스 원문이 있는데 그 시장의 매퍼가 없다. stockId={} market={} 표={}",
                        stockId, market, byEndpoint.keySet());
            }
            return 0L;
        }
        GovernanceDerivation context = derivationOf(stockId);
        List<GovernanceFact> facts = new ArrayList<>();
        byEndpoint.forEach((endpoint, rows) ->
                facts.addAll(mapper.derive(endpoint, rows, context)));

        jdbcTemplate.update("DELETE FROM governance_fact WHERE stock_id = ?", stockId);
        if (facts.isEmpty()) {
            return 0L;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO governance_fact
                    (stock_id, fiscal_year, metric_key, value, published_at, receipt_no)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (stock_id, fiscal_year, metric_key, receipt_no) DO UPDATE
                    SET value = EXCLUDED.value, published_at = EXCLUDED.published_at
                """, facts, BATCH_SIZE, (ps, fact) -> {
            ps.setLong(1, stockId);
            ps.setInt(2, fact.fiscalYear());
            ps.setString(3, fact.metric().name());
            ps.setBigDecimal(4, fact.value());
            ps.setObject(5, fact.publishedAt());
            ps.setString(6, fact.receiptNo());
        });
        return facts.size();
    }

    /**
     * 저장된 {@code metric_key} 중 <b>지금 코드가 모르는 것</b>. 실행당 한 번 부른다.
     *
     * <p>{@code governance_fact}에 CHECK가 없고 리더가 모르는 키를 던지지 않고 건너뛰므로,
     * enum 상수의 이름을 바꾸면 <b>컴파일도 테스트도 초록인 채</b> 그 지표만 전 종목
     * 판정불가가 된다. 이것이 그 사고가 드러나는 <b>유일한 자리</b>다.
     */
    public Map<String, Long> unknownMetricKeys() {
        Map<String, Long> unknown = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT metric_key, count(*) AS n FROM governance_fact
                 GROUP BY metric_key ORDER BY metric_key
                """, rs -> {
            String key = rs.getString("metric_key");
            if (GovernanceMetric.find(key) == null) {
                unknown.put(key, rs.getLong("n"));
            }
        });
        return unknown;
    }

    private String marketOf(long stockId) {
        List<String> found = jdbcTemplate.queryForList(
                "SELECT market_code FROM stock WHERE id = ?", String.class, stockId);
        return found.isEmpty() ? null : found.getFirst();
    }

    /**
     * 원문 밖에서 오는 재료를 모은다. <b>매퍼가 DB를 읽지 않게 하려는 자리다</b>(ADR-0081).
     *
     * <p>{@code collectedOn}은 그 종목의 원문을 마지막으로 받은 날이다. 「그 해에 사건이
     * 없었다」를 말하려면 그 해를 끝까지 봤어야 하는데, 미국 소스의 조회 창이 <b>처리 시각</b>
     * 기준이라 공시일만으로는 그것이 보증되지 않는다.
     *
     * <p>{@code firstPublishedAt}은 <b>개정판 전부에 걸친 최솟값</b>이다. {@code is_latest} 행만
     * 보면 정정이 있는 24%에서 계단이 중앙값 1년 넘게 뒤로 밀린다(실측). 주식수는 <b>가장 이른
     * 판의 값이 아니라 처음 채워진 판의 값</b>을 쓴다 — {@code fillMissing}이 그 순간의
     * {@code is_latest} 판만 채우므로(ADR-0056) 원 판이 비어 있을 수 있다.
     */
    private GovernanceDerivation derivationOf(long stockId) {
        java.time.LocalDate collectedOn = jdbcTemplate.queryForObject("""
                SELECT max(created_at)::date FROM governance_report_row WHERE stock_id = ?
                """, java.time.LocalDate.class, stockId);

        List<GovernanceDerivation.AnnualPeriod> periods = jdbcTemplate.query("""
                SELECT fiscal_year, period_start, period_end, first_published_at,
                       -- 주식수 온전성 게이트. 소스가 주는 후보가 넷인데 뒤 둘이 **가중평균
                       -- 주식수**라, 앞의 것이 없는 회사는 조용히 다른 것을 세고 있다
                       -- (어느 후보가 쓰였는지 저장하는 열이 없다). 실측으로 미국 997종목 중
                       -- 66(6.6%)이 net_income/eps 함의값과 20% 넘게 어긋난다.
                       -- 어긋나면 NULL로 두고, 매퍼가 그때 총액을 안 낸다.
                       -- **못 재는 것과 어긋난 것은 다르다** — eps가 없거나 0이면 검산이
                       -- 성립하지 않으므로 그대로 둔다.
                       CASE WHEN implied IS NULL OR implied = 0 THEN shares
                            WHEN abs(shares / implied - 1) > 0.20 THEN NULL
                            ELSE shares END AS shares
                  FROM (SELECT fiscal_year,
                               min(period_start) AS period_start,
                               max(period_end) AS period_end,
                               min(published_at) AS first_published_at,
                               (array_agg(shares_outstanding ORDER BY published_at)
                                FILTER (WHERE shares_outstanding > 0))[1] AS shares,
                               (array_agg(net_income / nullif(eps, 0) ORDER BY published_at)
                                FILTER (WHERE net_income IS NOT NULL AND eps IS NOT NULL
                                          AND eps <> 0))[1] AS implied
                          FROM financial_statement
                         WHERE stock_id = ? AND fiscal_period = 'FY'
                         GROUP BY fiscal_year
                        HAVING min(published_at) IS NOT NULL) g
                 ORDER BY fiscal_year DESC
                """, (rs, rowNum) -> new GovernanceDerivation.AnnualPeriod(
                        rs.getInt("fiscal_year"),
                        rs.getObject("period_start", java.time.LocalDate.class),
                        rs.getObject("period_end", java.time.LocalDate.class),
                        rs.getObject("first_published_at", java.time.LocalDate.class),
                        rs.getBigDecimal("shares")),
                stockId);

        List<CorporateActionLedger.Entry> events = ledger.findApplicable(stockId);
        return new GovernanceDerivation(collectedOn, periods, (from, to) -> {
            // IndicatorContext.corporateActionMultiple과 같은 경계다 — from 제외, to 포함.
            BigDecimal multiple = BigDecimal.ONE;
            for (CorporateActionLedger.Entry event : events) {
                java.time.LocalDate effective = event.effectiveDate();
                if (effective != null && !effective.isAfter(to) && effective.isAfter(from)) {
                    multiple = multiple.multiply(event.ratio());
                }
            }
            return multiple;
        });
    }

    private Map<GovernanceEndpoint, List<GovernanceReportRow>> readRows(long stockId) {
        Map<GovernanceEndpoint, List<GovernanceReportRow>> byEndpoint =
                new EnumMap<>(GovernanceEndpoint.class);
        jdbcTemplate.query("""
                SELECT endpoint, bsns_year, reprt_code, receipt_no, published_at,
                       row_ordinal, payload
                  FROM governance_report_row
                 WHERE stock_id = ?
                 ORDER BY endpoint, bsns_year, receipt_no, row_ordinal
                """, rs -> {
            String rawEndpoint = rs.getString("endpoint");
            GovernanceEndpoint endpoint;
            try {
                endpoint = GovernanceEndpoint.valueOf(rawEndpoint);
            } catch (IllegalArgumentException e) {
                // 상수 이름이 바뀌었다. 던지면 그 종목의 유도가 통째로 멈추므로 건너뛰고 남긴다.
                log.warn("모르는 엔드포인트 이름이라 건너뛴다. stockId={} endpoint={}",
                        stockId, rawEndpoint);
                return;
            }
            byEndpoint.computeIfAbsent(endpoint, key -> new ArrayList<>())
                    .add(new GovernanceReportRow(endpoint,
                            rs.getInt("bsns_year"),
                            rs.getString("reprt_code"),
                            rs.getString("receipt_no"),
                            rs.getObject("published_at", LocalDate.class),
                            rs.getInt("row_ordinal"),
                            toFields(rs.getString("payload"))));
        }, stockId);
        return byEndpoint;
    }

    private static Map<String, String> toFields(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        JsonNode node = JSON.readTree(payload);
        node.propertyStream().forEach(entry -> fields.put(entry.getKey(),
                entry.getValue() == null ? "" : entry.getValue().asString("")));
        return fields;
    }
}
