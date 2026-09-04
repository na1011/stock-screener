package com.stockscreener.probe.backtest;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 저장본이 <b>아직 유효한가</b>를 판정할 재료. 종목 하나의 시점 재현 입력을 해시 하나로 접는다.
 *
 * <p><b>감사 타임스탬프를 절대 넣지 않는다.</b> UPSERT가 값과 무관하게 {@code updated_at}을
 * 올리기 때문이다 — 실측으로 24시간 동안 {@code updated_at}은 <b>1,350종목 전부</b>에서
 * 움직였는데 값이 실제로 바뀐 종목은 <b>1개</b>(APH, 기업행위 조정 재작성)였다. 타임스탬프를
 * 넣었다면 저장소가 매일 통째로 무효가 됐을 것이고, 그러면 저장하는 의미가 없다.
 *
 * <p><b>컬럼을 손으로 나열하지 않는다.</b> {@code information_schema}에서 만든다 — 나열하면
 * 나중에 더해진 컬럼이 조용히 지문 밖으로 빠지고, 그때 저장본은 <b>낡았는데 유효해 보인다.</b>
 * 앞선 판(재무 3컬럼만 나열)이 실제로 그랬다: {@code capex}를 지워 보는 대조군에서 옛 식은
 * <b>0종목</b>, 지금 식은 <b>4,362종목</b>(= capex가 채워진 종목 수)이 움직였다.
 *
 * <p><b>기준일로 자른다.</b> {@code asOf} 뒤에 붙은 봉은 그날의 값에 영향을 주지 않으므로
 * 낡음이 아니다. 안 자르면 매일의 시세 적재가 전 날짜를 무효로 만든다.
 *
 * <p>비용은 실측 날짜당 KRW 0.56초 · USD 1.43초다(전 이력 기준). 값 계산이 14.5초·37.0초이므로
 * 약 4%이고, 1년치 적재 전체에 약 15분을 더한다.
 */
final class PitFingerprint {

    /** 시세는 행이 220만이라 전 컬럼 jsonb가 3.6배 비쌌다(실측 5.81초 대 1.43초). */
    private static final String PRICE_COLUMNS = """
            SELECT string_agg('coalesce(p.' || quote_ident(column_name) || '::text,'''')',
                              ' || '':'' || ' ORDER BY ordinal_position)
            FROM information_schema.columns
            WHERE table_name = 'price_daily' AND column_name NOT IN ('created_at', 'updated_at')
            """;

    private final JdbcTemplate jdbcTemplate;
    private final String priceExpression;

    PitFingerprint(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.priceExpression = jdbcTemplate.queryForObject(PRICE_COLUMNS, String.class);
        if (priceExpression == null || priceExpression.isBlank()) {
            throw new IllegalStateException(
                    "price_daily 컬럼 목록이 비었다 — 지문이 시세를 아예 안 덮는다");
        }
    }

    /**
     * 한 통화 모집단의 종목별 지문. 모집단 선정은 {@code PointInTimeScreener}가 값을 만들 때
     * 쓰는 것과 <b>같은 조건</b>이어야 한다 — 어긋나면 매니페스트에 지문 없는 종목이 생기고,
     * 그 종목은 영원히 「낡음」으로 읽혀 날짜가 완료되지 않는다.
     */
    Map<Long, String> forCurrency(String currencyCode, LocalDate asOf) {
        return forCurrency(currencyCode, asOf, null);
    }

    /**
     * 같은 지문을 <b>일부 종목만</b> 잰다. 읽기 시점 검증이 이것을 쓴다 — 값 싼 훑기가
     * 의심으로 올린 종목만 내용으로 확정하면 되고, 실측상 그 확정에서 살아남는 것은
     * 하루에 한 종목 남짓이다.
     *
     * @param only {@code null}이면 모집단 전체
     */
    Map<Long, String> forCurrency(String currencyCode, LocalDate asOf,
                                  java.util.Collection<Long> only) {
        if (only != null && only.isEmpty()) {
            return Map.of();
        }
        String sql = """
                SELECT s.id, md5(concat_ws('|',
                  coalesce((SELECT md5(string_agg(%s, ',' ORDER BY p.trade_date))
                            FROM price_daily p
                            WHERE p.stock_id = s.id AND p.trade_date <= ?), ''),
                  coalesce((SELECT md5(string_agg((to_jsonb(f.*) - 'created_at')::text,
                                                 ',' ORDER BY f.id))
                            FROM financial_statement f
                            WHERE f.stock_id = s.id AND f.published_at <= ?), ''),
                  coalesce((SELECT md5(string_agg((to_jsonb(g.*) - 'created_at')::text,
                                                 ',' ORDER BY g.fiscal_year, g.metric_key))
                            FROM governance_fact g
                            WHERE g.stock_id = s.id AND g.published_at <= ?), ''),
                  coalesce((SELECT md5(string_agg(
                                (to_jsonb(ca.*) - 'created_at' - 'updated_at')::text,
                                ',' ORDER BY ca.id))
                            FROM corporate_action ca WHERE ca.stock_id = s.id), ''),
                  (to_jsonb(s.*) - 'created_at' - 'updated_at')::text,
                  coalesce((SELECT string_agg(m.universe_code, ',' ORDER BY m.universe_code)
                            FROM universe_membership m
                            WHERE m.stock_id = s.id AND m.effective_to IS NULL), '')
                ))
                FROM stock s
                JOIN market mk ON mk.code = s.market_code
                WHERE s.is_active AND mk.currency_code = ? AND EXISTS (
                    SELECT 1 FROM universe_membership m
                    JOIN universe u ON u.code = m.universe_code AND u.is_active
                    WHERE m.stock_id = s.id AND m.effective_to IS NULL)
                %s
                ORDER BY s.id
                """.formatted(priceExpression, only == null ? ""
                : "AND s.id IN (" + only.stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(",")) + ")");

        Map<Long, String> out = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            out.put(rs.getLong(1), rs.getString(2));
        }, asOf, asOf, asOf, currencyCode);
        return out;
    }

    /**
     * 종목에 안 걸리는 입력. 종목 지문에 넣을 자리가 없다 — 넣으면 지수가 한 번 바뀔 때마다
     * 전 종목이 낡은 것으로 읽혀 종목 단위 무효화가 무의미해진다. 매니페스트에 따로 적는다.
     *
     * <p><b>사용 범위로 좁힌다.</b> 넓게 잡으면 값과 무관한 변경이 저장소를 흔든다.
     * <ul>
     *   <li><b>그 통화가 쓰는 지수만.</b> {@code IndicatorRunner:438}이
     *       {@code indexes.get(attributes.marketCode())} 하나로 고르므로 갈리는 축은 종목의
     *       {@code market_code}이고, 통화는 {@code market.currency_code}로 유도한다
     *       (KRW = KOSPI·KOSDAQ · USD = US). <b>이 유도는 {@code index_code == market_code}
     *       관례에 기댄다</b> — 그것을 강제하는 자리는 그 한 줄뿐이다.
     *       안 좁히면 US 지수가 붙은 날 KRW 256날짜가, KR 지수가 붙은 날 USD 256날짜가
     *       <b>증명 가능하게 무용한</b> 불일치가 된다.</li>
     *   <li><b>{@code close}만.</b> {@code IndicatorDataReader.readMarketIndexes}가 그것만 읽는다.
     *       {@code open}·{@code volume}·{@code constituent_count}는 값에 안 닿는다.</li>
     *   <li><b>{@code market}은 {@code currency_code}만.</b> 재무 통화 게이트의 판정 축이다.</li>
     *   <li><b>{@code universe}는 뺀다.</b> {@code IndicatorDataReader}가 그 표를 한 번도 안
     *       읽는다 — 모집단 축이고, 그것은 {@code PitSweep.repair}의 완전성 검사와
     *       {@code retainAll}, 적재 경로의 지문 부재가 이미 진다. 실측으로 {@code universe} 7행이
     *       30일 창의 네 날짜에 늘었고, 날짜로 안 잘리므로 <b>그때마다 전 날짜를 흔들었다.</b></li>
     * </ul>
     *
     * <p>MUST NOT: 컬럼을 {@code information_schema}에서 만들지 않는다(ADR-0123 개정). 여기서는
     * <b>읽는 쪽이 읽는 것</b>이 기준이고, 전 컬럼을 넣으면 값에 안 닿는 컬럼이 저장소를 흔든다.
     */
    String forShared(String currencyCode, LocalDate asOf) {
        return jdbcTemplate.queryForObject("""
                SELECT md5(concat_ws('|',
                  coalesce((SELECT md5(string_agg(i.index_code || ':' || i.trade_date || ':'
                                                 || i.close, ',' ORDER BY i.index_code, i.trade_date))
                            FROM market_index_daily i
                            WHERE i.trade_date <= ?
                              AND i.index_code IN (SELECT mk.code FROM market mk
                                                   WHERE mk.currency_code = ?)), ''),
                  coalesce((SELECT string_agg(mk.code || ':' || mk.currency_code, ',' ORDER BY mk.code)
                            FROM market mk WHERE mk.currency_code = ?), '')
                ))
                """, String.class, asOf, currencyCode, currencyCode);
    }

    /**
     * <b>좁히기 이전의</b> 지수 지문. 마이그레이션에만 쓴다 — 좁힌 입력은 이것의
     * <b>진부분집합</b>이므로, 이 값이 저장된 것과 같으면 <b>좁은 입력도 반드시 안 움직였다.</b>
     *
     * <p>시각({@code updated_at})으로 판정하지 않는 이유: {@code market}·{@code universe}에는
     * 시각 컬럼이 <b>아예 없고</b> 삭제도 시각에 안 남는다. 시각을 최종 판결로 쓰면
     * ADR-0123 §규칙의 「싼 훑기는 의심을 고르고 내용이 확정한다」가 뒤집힌다.
     *
     * <p>실측: 512개 매니페스트 전수 대조에 <b>4.0초 · 불일치 0</b>.
     */
    @Deprecated
    String forSharedBeforeNarrowing(LocalDate asOf) {
        return jdbcTemplate.queryForObject("""
                SELECT md5(concat_ws('|',
                  coalesce((SELECT md5(string_agg((to_jsonb(i.*) - 'created_at' - 'updated_at')::text,
                                                 ',' ORDER BY i.index_code, i.trade_date))
                            FROM market_index_daily i WHERE i.trade_date <= ?), ''),
                  coalesce((SELECT md5(string_agg((to_jsonb(m.*) - 'created_at')::text,
                                                 ',' ORDER BY m.code)) FROM market m), ''),
                  coalesce((SELECT md5(string_agg((to_jsonb(u.*) - 'created_at')::text,
                                                 ',' ORDER BY u.code)) FROM universe u), '')
                ))
                """, String.class, asOf);
    }

    /**
     * <b>값을 만든 계산기의 판.</b> DB가 하나도 안 움직여도 공식이 바뀌면 저장본은 낡는다.
     *
     * <p>이것이 없으면 {@code MacdVCalculator}의 식을 고치거나 워밍업 창을 바꿔도 저장본이
     * 「유효」로 읽히고, 스윕이 <b>옛 공식으로 만든 값</b>으로 최초 통과일을 낸다 — 예외도
     * 경고도 없다. 이 저장소의 용도가 「전략을 바꿀 때마다 스윕」인데 전략 손질과 지표 손질은
     * 같은 세션에 일어난다(최근 커밋이 연달아 {@code fix(indicator)}다).
     *
     * <p>클래스 파일의 <b>내용</b>을 해시한다. 수정 시각으로는 재컴파일만으로 달라져
     * 과검출이 상시가 되고, 코드 목록만으로는 <b>식을 고친 것</b>을 못 본다.
     */
    static String forCalculators(com.stockscreener.indicator.IndicatorRegistry registry) {
        java.nio.file.Path classes;
        try {
            classes = java.nio.file.Path.of(
                    com.stockscreener.indicator.IndicatorRunner.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI())
                    .resolve("com/stockscreener/indicator");
        } catch (Exception e) {
            throw new IllegalStateException("지표 계산기의 클래스 위치를 못 찾았다 — "
                    + "그러면 공식이 바뀌어도 저장본이 유효해 보인다", e);
        }
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("MD5");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5가 없다", e);
        }
        md.update(String.join(",", new java.util.TreeSet<>(registry.codes()))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        md.update(("|" + registry.maxEffectiveLookback() + "|" + registry.readWindowBars())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var walk = java.nio.file.Files.walk(classes)) {
            for (java.nio.file.Path f : walk
                    .filter(x -> x.getFileName().toString().endsWith(".class"))
                    .sorted().toList()) {
                md.update(f.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                md.update(java.nio.file.Files.readAllBytes(f));
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("지표 클래스 파일을 읽지 못했다: " + classes, e);
        }
        return java.util.HexFormat.of().formatHex(md.digest());
    }

    /** 통화별 모집단. 값 생산과 같은 조건을 쓰는지 시험이 확인할 수 있게 노출한다. */
    List<Long> populationOf(String currencyCode) {
        return jdbcTemplate.queryForList("""
                SELECT s.id FROM stock s
                JOIN market mk ON mk.code = s.market_code
                WHERE s.is_active AND mk.currency_code = ? AND EXISTS (
                    SELECT 1 FROM universe_membership m
                    JOIN universe u ON u.code = m.universe_code AND u.is_active
                    WHERE m.stock_id = s.id AND m.effective_to IS NULL)
                ORDER BY s.id
                """, Long.class, currencyCode);
    }
}
