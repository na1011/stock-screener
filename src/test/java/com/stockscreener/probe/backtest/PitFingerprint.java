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

    /**
     * {@code stock}에서 <b>값 경로가 읽는 것</b>. 전 컬럼을 넣으면 {@code name}·{@code ticker}·
     * {@code accounting_month}처럼 안 읽는 것이 지문을 흔드는데, 이 성분에는 날짜 절이 없어
     * 한 행이 바뀌면 그 종목이 <b>저장된 전 날짜에서</b> 낡음이 되고 스윕 하나가 9분이 된다.
     *
     * <p>{@code industry_*_key}는 {@code industry_code}·{@code sic_code}에서 만들어지는 생성
     * 컬럼이라 원본을 따로 안 넣는다 — 원본이 바뀌어도 키가 같으면 값이 안 바뀐다.
     */
    private static final String STOCK_COLUMNS =
            "concat_ws(':', s.id, s.market_code, s.is_active,"
                    + " coalesce(s.industry_group_key, ''), coalesce(s.industry_division_key, ''))";

    /**
     * 기업행위 성분. <b>{@code applied_at}을 텍스트로 렌더하지 않는다.</b>
     *
     * <p>{@code to_jsonb}는 {@code timestamptz}를 <b>세션 타임존으로</b> 문자열화한다. 그래서
     * 같은 행이 {@code Asia/Seoul}과 {@code UTC}에서 다른 지문을 냈다 — 실측
     * <b>KRW 65 · USD 91종목</b>({@code applied_at IS NOT NULL}인 수와 같다). epoch는 그 렌더를
     * 안 지나므로 <b>타임존에 안 갈린다</b>(실측: 두 통화 모두 차이 0).
     *
     * <p><b>신호는 안 잃는다.</b> 값 경로가 읽는 CA 컬럼은 {@code id}·{@code effective_date}·
     * {@code listing_date}·{@code type}·{@code ratio}(+{@code stock_id}·{@code status})이고
     * <b>{@code applied_at}을 읽는 SELECT는 운영 코드에 하나도 없다.</b> 게다가
     * {@code CorporateActionLedger.markApplied}가
     * {@code SET status=?, applied_at=coalesce(applied_at, now()), updated_at=now()}라
     * <b>{@code status}·{@code updated_at}과 함께만 움직인다.</b>
     */
    private static final String CORPORATE_ACTION_COLUMNS =
            "(to_jsonb(ca.*) - 'created_at' - 'updated_at' - 'applied_at')::text"
                    + " || coalesce(extract(epoch from ca.applied_at)::text, '')";

    /**
     * <b>저장본을 실제로 찍은 식.</b> 마이그레이션의 기준선이고, 그것 말고 다른 뜻은 없다.
     *
     * <p><b>MUST: 좁히기를 할 때마다 여기를 「직전 판」으로 갱신한다.</b> 부분집합 논증은
     * <b>한 번 쓰면 소진된다</b> — {@code stock} 성분을 좁힐 때 이 자리가
     * {@code to_jsonb(s.*)}였고, 그 마이그레이션이 끝난 뒤로는 저장본이 <b>좁은 식</b>으로
     * 찍혀 있어 옛 식은 100% 불일치가 됐다(실측 KRW 350/350 · USD 1000/1000). 그 상태로
     * {@code -Dpit.recheck=fingerprint}를 돌리면 512날짜가 전부 {@code moved}로 나와
     * <b>마이그레이션이 아니라 전량 재적재</b>가 된다.
     */
    private static final String CORPORATE_ACTION_COLUMNS_BEFORE_NARROWING =
            CORPORATE_ACTION_COLUMNS;

    /**
     * 종목 지문 SQL. {@code %1$s}=시세 컬럼식 · {@code %2$s}=stock 식 · {@code %3$s}=종목 한정 ·
     * {@code %4$s}=기업행위 식.
     */
    private static final String FOR_CURRENCY_SQL = """
                SELECT s.id, md5(concat_ws('|',
                  coalesce((SELECT md5(string_agg(%1$s, ',' ORDER BY p.trade_date))
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
                  coalesce((SELECT md5(string_agg(%4$s, ',' ORDER BY ca.id))
                            FROM corporate_action ca WHERE ca.stock_id = s.id), ''),
                  %2$s,
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
                %3$s
                ORDER BY s.id
            """;

    /** 공유 지문 SQL. 여기는 <b>==</b>가 불변식이라 안 읽는 컬럼이 하나도 없어야 한다. */
    private static final String FOR_SHARED_SQL = """
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
            """;

    /**
     * <b>저장본을 찍은</b> 공유 지문 SQL. 좁히기 마이그레이션의 기준선이다.
     *
     * <p><b>MUST: 좁힐 때 옛 {@code FOR_SHARED_SQL}을 여기로 옮긴다.</b> 값과 판이 <b>이 상수
     * 하나</b>에서 나오게 둔 이유가 그것이다 — 둘을 따로 적으면 하나만 옮겼을 때
     * <b>새 식의 값을 옛 판으로 대조</b>하게 되고, 그러면 전 날짜가 「원자료가 움직였다」로
     * 나온다. 이 커밋이 없애려던 바로 그 오독이다.
     */
    private static final String FOR_SHARED_SQL_BEFORE_NARROWING = FOR_SHARED_SQL;

    /**
     * <b>지문 식의 판.</b> {@code calculatorFp}와 같은 꼴이고 같은 이유다 — DB가 하나도 안
     * 움직여도 <b>식이 바뀌면 저장본은 낡는다.</b>
     *
     * <p><b>이것이 없으면 「쉬는 상태」와 「기준선이 소진된 상태」를 구별할 수 없다.</b> 둘 다
     * 「전량 일치 아님」으로 보이기 때문이다 — 실측: 소진이면 275/350·909/1000, <b>기업행위
     * 승인 하나면 349/350</b>이고, 그런 날이 최근 31일 중 <b>14일</b>이다(종목 지문의
     * {@code corporate_action}·{@code stock}·{@code universe_membership} 성분에 날짜 절이 없어
     * 한 행이 바뀌면 <b>전 날짜</b>의 전량 일치가 깨진다). 일치 수로 가르는 것은 원리적으로
     * 안 된다. <b>등호로 판정한다.</b>
     *
     * <p><b>시세 컬럼식을 포함한다.</b> {@code stockFingerprintSql()}은 펜스가 읽는 요약이라
     * 그 자리에 {@code <price>} 리터럴이 들어가는데, 그것으로 판을 내면 {@code price_daily}에
     * 컬럼이 늘어도 판이 안 움직여 <b>매니페스트가 「쉬는 상태」로 위장한다.</b>
     */
    String stockFormulaFp() {
        return md5(FOR_CURRENCY_SQL.formatted(priceExpression, STOCK_COLUMNS, "",
                CORPORATE_ACTION_COLUMNS));
    }

    /** 공유 지문 식의 판. 위와 같은 이유다. */
    static String sharedFormulaFp() {
        return md5(FOR_SHARED_SQL);
    }

    /** 저장본을 찍은 <b>직전 판</b>의 식. 좁히기 마이그레이션이 이것과 저장값을 대조한다. */
    static String sharedFormulaFpBeforeNarrowing() {
        return md5(FOR_SHARED_SQL_BEFORE_NARROWING);
    }

    /** 저장본을 찍은 <b>직전 판</b>의 식. 좁히기 마이그레이션이 이것과 저장값을 대조한다. */
    static String stockFormulaFpBeforeNarrowing() {
        // 직전 판은 시세 컬럼식을 <price> 리터럴로 두고 냈다. 그래서 `price_daily`에 컬럼이
        // 늘거나 줄어도 판이 안 움직였고, 그 매니페스트가 「쉬는 상태」로 위장했다.
        return md5(FOR_CURRENCY_SQL.formatted("<price>", STOCK_COLUMNS, "",
                CORPORATE_ACTION_COLUMNS_BEFORE_NARROWING));
    }

    private static String md5(String text) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5가 없다", e);
        }
    }

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
        return forCurrency(currencyCode, asOf, only, STOCK_COLUMNS, CORPORATE_ACTION_COLUMNS);
    }

    private Map<Long, String> forCurrency(String currencyCode, LocalDate asOf,
                                          java.util.Collection<Long> only, String stockExpression,
                                          String corporateActionExpression) {
        if (only != null && only.isEmpty()) {
            return Map.of();
        }
        String sql = FOR_CURRENCY_SQL
                .formatted(priceExpression, stockExpression, only == null ? ""
                : "AND s.id IN (" + only.stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(",")) + ")",
                corporateActionExpression);

        Map<Long, String> out = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            out.put(rs.getLong(1), rs.getString(2));
        }, asOf, asOf, asOf, currencyCode);
        return out;
    }

    /**
     * <b>저장본을 찍은 식으로 낸</b> 종목 지문. 마이그레이션에만 쓴다 — 좁힌 입력이 같은 행의
     * <b>재인코딩</b>이므로, 이 값이 저장된 것과 같으면 <b>원행이 안 바뀌었고</b> 따라서 좁은
     * 식도 반드시 안 움직였다.
     *
     * <p><b>MUST: 이 메서드는 「지금 저장본이 무엇으로 찍혔는가」를 따라간다.</b> 새 좁히기를
     * 넣을 때마다 여기를 직전 판으로 갱신하고, 마이그레이션이 끝나면 <b>다음 좁히기 전까지
     * 그대로 둔다.</b> 그러지 않으면 옛 식이 저장본과 100% 불일치가 되어 이 레버가 통째로
     * 죽는다 — 실제로 {@code stock} 좁히기 뒤에 그렇게 돼 있었고, 아무도 그것을 몰랐다.
     *
     * <p><b>MUST: 이것을 돌릴 때 타임존이 고정돼 있어야 한다.</b> 지금 지문 식은
     * <b>timestamptz를 텍스트로 렌더하지 않는다</b>(실측: 같은 질의가 `Asia/Seoul`↔`UTC`에서
     * 불일치 <b>0</b>, 옛 wide 식 대조군은 <b>65</b>). 그 성질이 깨지는 순간 — 누가
     * {@code to_jsonb} 성분에 timestamptz를 되돌리면 — 저장본이 기계마다 낡는다.
     * {@code build.gradle}이 포크 JVM을 `Asia/Seoul`로 박고 {@code PitColumnCoverageTest}가
     * JVM·DB 세션 양 끝을 단언한다.
     */
    Map<Long, String> forCurrencyBeforeNarrowing(String currencyCode, LocalDate asOf) {
        return forCurrency(currencyCode, asOf, null, STOCK_COLUMNS,
                CORPORATE_ACTION_COLUMNS_BEFORE_NARROWING);
    }

    /**
     * 펜스({@code PitColumnCoverageTest})가 보는 <b>실제 SQL</b>. 요약본을 따로 적지 않는다 —
     * 사본을 두면 두 곳이 갈릴 때까지 드러나지 않고, 그때 펜스는 <b>거짓 초록</b>이 된다.
     */
    static String stockFingerprintSql() {
        return FOR_CURRENCY_SQL.formatted("<price>", STOCK_COLUMNS, "",
                CORPORATE_ACTION_COLUMNS);
    }

    static String sharedFingerprintSql() {
        return FOR_SHARED_SQL;
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
        return jdbcTemplate.queryForObject(FOR_SHARED_SQL, String.class, asOf, currencyCode, currencyCode);
    }

    /**
     * <b>저장본을 찍은 식으로 낸</b> 공유 지문. 마이그레이션에만 쓴다.
     *
     * <p><b>MUST: 좁힐 때 옛 식을 여기로 옮긴다.</b> 지금은 현행 식과 같다 — 마이그레이션이
     * 끝난 <b>쉬는 상태</b>이고, 그 상태를 {@code sharedFormulaFp()}가 등호로 증명한다.
     */
    String forSharedBeforeNarrowing(String currencyCode, LocalDate asOf) {
        return jdbcTemplate.queryForObject(FOR_SHARED_SQL_BEFORE_NARROWING, String.class,
                asOf, currencyCode, currencyCode);
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
