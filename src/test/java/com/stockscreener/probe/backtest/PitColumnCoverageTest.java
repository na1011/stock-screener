package com.stockscreener.probe.backtest;

import com.stockscreener.support.ReadOnlyDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지문이 <b>읽는 쪽이 읽는 컬럼을 빠뜨리지 않았는가</b>를 지킨다.
 *
 * <p><b>왜 있는가.</b> 빠지면 저장본은 <b>낡았는데 유효해 보인다</b> — 앞선 판이 재무 38컬럼 중
 * 3개만 봤고, {@code capex}를 지우는 대조군에서 옛 식은 0종목·지금 식은 4,362종목이 움직였다.
 * 그 부류를 다시 만들지 않는 것이 이 검사의 몫이다.
 *
 * <p><b>불변식이 둘이고, 지문마다 다르다.</b>
 * <ul>
 *   <li><b>종목 지문 ⊇ 읽는 컬럼.</b> 과검출은 그 종목에만 걸리므로 넉넉한 쪽이 안전하다.</li>
 *   <li><b>공유 지문 == 읽는 컬럼.</b> 과검출의 파급이 <b>날짜 전체</b>라, 안 읽는 컬럼 하나가
 *       저장소를 흔든다. 실제로 {@code universe}가 그 자리에 있었고 값 경로는 그 표를 한 번도
 *       안 읽는다.</li>
 * </ul>
 *
 * <p><b>MUST: 이 검사는 「전부 덮는다」가 아니다.</b> <b>알려진 표</b>의 컬럼만 본다 —
 * <b>입력에 새 표가 추가되는 것</b>은 원리적으로 못 본다. 그것은 {@code IndicatorDataReader}가
 * 읽는 표를 사람이 셀 때만 드러난다.
 *
 * <p><b>{@code to_jsonb}로 만든 성분은 여기서 잡을 것이 없다</b>(전 컬럼 − 감사 컬럼이라
 * ⊇가 깨질 수 없다). 이 검사가 값을 갖는 자리는 <b>손으로 나열한 성분</b>이고, 실제로 그것이
 * {@code stock}·{@code universe_membership}과 공유 지문의 둘이다.
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class PitColumnCoverageTest {

    /**
     * 손으로 나열한 지문 성분 → 값 경로가 그 표에서 읽는 컬럼.
     *
     * <p>읽는 쪽을 직접 셌다. 근거를 코드에 적어 두어야 다음 세션이 다시 세지 않는다.
     */
    private static final Map<String, Set<String>> READ_COLUMNS = new LinkedHashMap<>(Map.of(
            // PointInTimeScreener.readSectors · selectSectorPopulationIds · selectStockIds,
            // IndicatorDataReader.readStockAttributes
            "stock", Set.of("id", "market_code", "is_active",
                    "industry_group_key", "industry_division_key"),
            // PointInTimeScreener.selectSectorPopulationIds · selectStockIds
            "universe_membership", Set.of("stock_id", "universe_code", "effective_to"),
            // IndicatorDataReader.readMarketIndexes
            "market_index_daily", Set.of("index_code", "trade_date", "close"),
            // IndicatorDataReader.CURRENCY_GATE · readStockAttributes
            "market", Set.of("code", "currency_code")));

    /** 그 성분이 어느 지문에 있는가. 불변식이 갈린다. */
    private static final Set<String> SHARED = Set.of("market_index_daily", "market");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("손으로 나열한 지문 성분이 읽는 컬럼을 빠뜨리지 않는다")
    void handListedComponentsCoverWhatTheValuePathReads() {
        String stockSql = PitFingerprint.stockFingerprintSql();
        String sharedSql = PitFingerprint.sharedFingerprintSql();
        Set<String> missing = new TreeSet<>();
        Set<String> extraInShared = new TreeSet<>();

        for (Map.Entry<String, Set<String>> e : READ_COLUMNS.entrySet()) {
            String table = e.getKey();
            String sql = SHARED.contains(table) ? sharedSql : stockSql;
            for (String column : e.getValue()) {
                if (!mentions(sql, column)) {
                    missing.add(table + "." + column);
                }
            }
            if (SHARED.contains(table)) {
                for (String column : columnsOf(table)) {
                    if (!e.getValue().contains(column) && mentions(sql, column)) {
                        extraInShared.add(table + "." + column);
                    }
                }
            }
        }

        System.out.printf("%n===== 지문 컬럼 커버리지 =====%n");
        READ_COLUMNS.forEach((t, cols) -> System.out.printf("   %-22s 읽는 컬럼 %d · %s%n",
                t, cols.size(), SHARED.contains(t) ? "공유 지문(==)" : "종목 지문(⊇)"));

        assertThat(missing)
                .as("지문이 값 경로가 읽는 컬럼을 빠뜨렸다 — 저장본이 낡았는데 유효해 보인다")
                .isEmpty();
        assertThat(extraInShared)
                .as("공유 지문에 값 경로가 안 읽는 컬럼이 있다 — 그 하나가 날짜 전체를 흔든다")
                .isEmpty();
    }

    /**
     * <b>지문이 읽는 쪽의 타임존에 안 갈리는가.</b>
     *
     * <p><b>왜 값이 아니라 기제를 단언하는가.</b> 종목 지문의 기업행위 성분이
     * {@code to_jsonb(ca.*)}로 {@code applied_at}({@code timestamptz})을 <b>세션 타임존으로
     * 렌더</b>한다. 실측: 같은 질의가 {@code Asia/Seoul}↔{@code UTC}에서
     * <b>KRW 65 · USD 91종목</b>({@code applied_at IS NOT NULL}인 수와 같다)이 갈렸다.
     * 저장본은 KST 세션에서 찍혔으므로 다른 TZ로 돌면 그 156종목이 <b>저장된 전 날짜에서</b>
     * 낡음이 된다.
     *
     * <p>그래서 {@code build.gradle}의 {@code wireHarnessSwitches}가 포크 JVM에
     * {@code user.timezone=Asia/Seoul}을 박는다. 여기서 <b>두 끝을 함께</b> 단언한다 —
     * JVM 기본 TZ와 <b>PG 세션 TZ</b>. pgjdbc가 접속 스타트업 패킷에
     * {@code TimeZone.getDefault().getID()}를 실으므로 둘은 같아야 하고, 앞만 보면
     * <b>드라이버가 그 전달을 그만둔 날을 못 본다.</b>
     *
     * <p>MUST NOT: 명령줄 {@code -Duser.timezone=…}으로 이것을 재려 하지 않는다. Gradle의
     * {@code -D}는 <b>데몬 JVM에만</b> 붙고 {@code wireHarnessSwitches}는
     * {@code pit.*}/{@code sweep.*}만 포크에 넘기므로 그 시도는 <b>언제나 초록</b>이다.
     */
    @Test
    @DisplayName("포크 JVM과 DB 세션의 타임존이 둘 다 고정돼 있다")
    void fingerprintTimeZoneIsPinnedOnBothEnds() {
        String jvm = java.util.TimeZone.getDefault().getID();
        String session = jdbcTemplate.queryForObject("SHOW TimeZone", String.class);
        System.out.printf("   JVM %s · DB 세션 %s%n", jvm, session);

        assertThat(jvm)
                .as("포크 JVM의 기본 타임존이 안 고정됐다 — 지문의 applied_at 렌더가 기계마다 갈린다")
                .isEqualTo(PINNED_ZONE);
        assertThat(session)
                .as("DB 세션 타임존이 JVM을 안 따라간다 — pgjdbc가 스타트업 패킷에 TimeZone을 "
                        + "안 싣는 것이고, 그러면 build.gradle의 고정이 지문에 안 닿는다")
                .isEqualTo(PINNED_ZONE);
    }

    /** {@code build.gradle}의 {@code wireHarnessSwitches}가 박는 값. 갈리면 여기가 먼저 운다. */
    private static final String PINNED_ZONE = "Asia/Seoul";

    /**
     * SQL이 그 컬럼을 <b>테이블 별칭과 함께</b> 언급하는가.
     *
     * <p>MUST: 별칭 점을 요구한다. 맨 이름으로 찾으면 {@code code}가 {@code index_code}·
     * {@code currency_code} 안에 걸려 <b>안 쓰는 컬럼을 쓴다고 읽는다.</b>
     */
    private static boolean mentions(String sql, String column) {
        return java.util.regex.Pattern.compile("\\.\\Q" + column + "\\E\\b").matcher(sql).find();
    }

    private Set<String> columnsOf(String table) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, table));
    }

}
