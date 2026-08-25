package com.stockscreener.pipeline.collector;

import com.stockscreener.indicator.GovernanceMetric;
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
import java.util.ArrayList;
import java.util.EnumMap;
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
    private final GovernanceFactMapper mapper;

    public GovernanceFactDeriver(JdbcTemplate jdbcTemplate, GovernanceFactMapper mapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
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
        List<GovernanceFact> facts = new ArrayList<>();
        byEndpoint.forEach((endpoint, rows) -> facts.addAll(mapper.derive(endpoint, rows)));

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
