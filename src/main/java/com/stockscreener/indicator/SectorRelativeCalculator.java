package com.stockscreener.indicator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;
import java.util.List;

/**
 * 지표 값의 <b>업종 내</b> 상대값과 그 표본 수를 산출한다(ADR-0044).
 *
 * <p>{@link PercentileCalculator}와 나란히, 개별 지표 계산이 전부 끝난 뒤 하루 단위로 한 번
 * 돈다. 종목 단위 러너 안에서는 원리적으로 계산할 수 없다 — 같은 날 여러 종목의 값이 있어야
 * 정해지기 때문이다.
 *
 * <p><b>모집단이 {@link PercentileCalculator}와 다르다.</b> 저쪽은 유니버스별로 나누고
 * (ADR-0036), 이쪽은 <b>활성 유니버스 전체 중 같은 체계의 같은 업종</b>으로 묶는다.
 * 유니버스로 한 번 더 나누면 350종목이 유니버스 2 × 업종 40으로 쪼개져 표본이 남지 않는다.
 * 그것이 ADR-0036을 다시 여는 지점이며, 그래서 화면이 두 모집단을 구분해 설명해야 한다.
 *
 * <p><b>지수는 안 가리지만 분류 체계는 가린다</b>(ADR-0092). 파티션 키가
 * {@code stock.industry_group_key}·{@code industry_division_key}이고 그 값에 체계 접두가
 * 구워져 있다 — KSIC와 SIC는 <b>앞 2자리가 실제로 겹친다</b>(둘 다 {@code 01}이 있고,
 * {@code 65}는 한쪽이 보험 다른 쪽이 부동산이다). 접두를 떼면 두 나라가 한 모집단이 되는데
 * <b>아무 에러도 안 나고 값이 그럴듯하다.</b>
 *
 * <p>IMPORTANT — <b>두 층의 순위표를 각각 만들고 종목이 쓸 층을 고른다.</b> 소분류 표본이
 * 모자란 종목만 중분류로 올라가는데, 그때 그 종목은 <b>중분류 전체를 모집단으로</b> 삼아야
 * 통계가 성립한다. 한 파티션 안에 소분류 기준 종목과 중분류 기준 종목을 섞으면 순위의
 * 모집단이 종목마다 다른 채 값만 그럴듯하게 나온다.
 *
 * <p>유니버스 편입을 {@code JOIN}이 아니라 {@code EXISTS}로 확인한다. 한 종목이 두 활성
 * 유니버스에 걸리면 조인이 행을 늘려 <b>표본 수가 부풀고 순위가 어긋나는데</b>, 그것은
 * 에러 없이 그럴듯한 값을 낸다. {@code EXISTS}는 그 가능성을 구조적으로 없앤다.
 */
@Component
public class SectorRelativeCalculator {

    private static final Logger log = LoggerFactory.getLogger(SectorRelativeCalculator.class);

    /**
     * 업종 표본의 최소 종목 수(ADR-0044).
     *
     * <p>우리가 정한 수가 아니라 원본 명세 §2-2 P-04가 정한 값이다. 이 아래에서는 중앙값이
     * 사실상 그 종목 자신이 되고, "업종 대비"라는 말이 뜻을 잃는다.
     *
     * <p>{@link PercentileCalculator}의 2와 다른 것이 <b>정상</b>이다 — 저쪽은 "순위가
     * 성립하는가"의 최소치이고 여기는 "업종을 대표하는가"의 최소치다.
     */
    static final int MIN_PEERS = 5;

    /**
     * 모집단. 값이 있는 <b>활성 편입</b> 종목 중 업종이 채워진 것만이다.
     *
     * <p>업종이 NULL인 종목을 여기서 빼는 것이 곧 "업종이 비어 있으면 판정불가"의 구현이다.
     * 남겨 두면 NULL끼리 한 그룹이 되어 <b>업종 없는 종목끼리 순위가 매겨진다.</b>
     *
     * <p><b>키가 NULL인지로 묻는다.</b> 체계가 둘이므로 {@code industry_code IS NOT NULL}로
     * 물으면 SIC를 채운 종목이 통째로 빠진다 — 생성 컬럼이 두 원문 중 있는 쪽에서 만들어지므로
     * 키의 NULL 여부가 "업종을 아는가"의 유일한 답이다(ADR-0092).
     */
    private static final String MEMBER = """
            member AS (
                SELECT iv.stock_id, iv.value,
                       s.industry_group_key AS fine, s.industry_division_key AS broad
                FROM indicator_value iv
                JOIN stock s ON s.id = iv.stock_id
                WHERE iv.indicator_code = ?
                  AND iv.trade_date = ?
                  AND s.industry_group_key IS NOT NULL
                  AND EXISTS (
                      SELECT 1 FROM universe_membership m
                      JOIN universe u ON u.code = m.universe_code
                                     AND (u.is_active OR u.code = ?)
                      WHERE m.stock_id = iv.stock_id AND m.effective_to IS NULL)
            )
            """;

    /** 층별 순위표 둘 + 종목별 층 선택. {@code metric}이 최종 값이다. */
    private static final String PERCENTILE_CHOSEN = MEMBER + """
            , fineRank AS (
                SELECT stock_id,
                       PERCENT_RANK() OVER (PARTITION BY fine ORDER BY value) AS pct,
                       count(*)       OVER (PARTITION BY fine)                AS n
                FROM member
            )
            , broadRank AS (
                SELECT stock_id,
                       PERCENT_RANK() OVER (PARTITION BY broad ORDER BY value) AS pct,
                       count(*)       OVER (PARTITION BY broad)                AS n
                FROM member
            )
            , chosen AS (
                SELECT f.stock_id,
                       CASE WHEN f.n >= ? THEN f.pct ELSE b.pct END AS metric,
                       CASE WHEN f.n >= ? THEN f.n   ELSE b.n   END AS peers
                FROM fineRank f JOIN broadRank b ON b.stock_id = f.stock_id
            )
            """;

    /**
     * 층별 중앙값 둘. {@link #MEDIAN_RATIO_CHOSEN}과 {@link #MEDIAN_CHOSEN}이 함께 쓴다.
     *
     * <p>둘로 베껴 두지 않는 이유는 한쪽만 층 정의를 고쳐도 <b>두 지표가 다른 모집단의 중앙값을
     * 말하면서 둘 다 그럴듯한 값</b>을 내기 때문이다.
     */
    private static final String MEDIAN_LAYERS = """
            , fineMedian AS (
                SELECT fine,
                       (percentile_cont(0.5) WITHIN GROUP (ORDER BY value))::numeric AS med,
                       count(*) AS n
                FROM member GROUP BY fine
            )
            , broadMedian AS (
                SELECT broad,
                       (percentile_cont(0.5) WITHIN GROUP (ORDER BY value))::numeric AS med,
                       count(*) AS n
                FROM member GROUP BY broad
            )
            """;

    /**
     * 값 ÷ 그 종목이 쓸 층의 중앙값.
     *
     * <p>중앙값이 0 이하면 배수가 뜻을 잃으므로 값을 내지 않는다(원본 §2-2 P-07 음수 분모 가드).
     * 자기자본이 잠식된 업종에서 실제로 생길 수 있다.
     */
    private static final String MEDIAN_RATIO_CHOSEN = MEMBER + MEDIAN_LAYERS + """
            , chosen AS (
                SELECT m.stock_id,
                       CASE WHEN f.n >= ? THEN
                                 CASE WHEN f.med > 0 THEN round(m.value / f.med, 8) END
                            ELSE CASE WHEN b.med > 0 THEN round(m.value / b.med, 8) END
                       END AS metric,
                       CASE WHEN f.n >= ? THEN f.n ELSE b.n END AS peers
                FROM member m
                JOIN fineMedian  f ON f.fine  = m.fine
                JOIN broadMedian b ON b.broad = m.broad
            )
            """;

    /**
     * 그 종목이 쓸 층의 중앙값 <b>자체</b>. 같은 업종 종목끼리 같은 값을 받는다.
     *
     * <p>MUST NOT: 위 배수의 음수 분모 가드를 여기로 복사하지 않는다. 그 가드는 <b>0으로 나누는
     * 것이 뜻이 없어서</b> 있는 것이지 중앙값 자체가 무의미해서가 아니다. 복사하면 자본잠식
     * 업종에서 <b>두 지표가 함께 사라진다</b> — 참조값은 그때도 보여 줄 수 있어야 한다.
     *
     * <p>MUST: {@code chosen}이 바인딩하는 {@code ?}의 수를 다른 둘과 <b>같게 유지한다</b>(둘).
     * {@link #upsert}가 자리표시자 순서를 텍스트 순서로 세므로, 하나만 늘면 파라미터 배열이
     * 통째로 밀리면서 <b>값이 그럴듯하게 틀린다.</b>
     */
    private static final String MEDIAN_CHOSEN = MEMBER + MEDIAN_LAYERS + """
            , chosen AS (
                SELECT m.stock_id,
                       CASE WHEN f.n >= ? THEN f.med ELSE b.med END AS metric,
                       CASE WHEN f.n >= ? THEN f.n   ELSE b.n   END AS peers
                FROM member m
                JOIN fineMedian  f ON f.fine  = m.fine
                JOIN broadMedian b ON b.broad = m.broad
            )
            """;

    /**
     * {@code chosen}에서 한 컬럼을 골라 저장한다.
     *
     * <p>표본이 모자라면 <b>행을 쓰지 않는다.</b> {@code indicator_value}는 계산할 수 없는
     * 값에 행을 만들지 않으며(ADR-0002), 행이 없는 것이 곧 판정불가다 — 0을 쓰면
     * "업종 최하위"로 읽혀 조건을 충족시킨다.
     *
     * <p>{@code IS DISTINCT FROM}의 이유는 {@code IndicatorValueWriter}와 같다.
     */
    private static final String UPSERT = """
            WITH %s
            INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value, updated_at)
            SELECT stock_id, ?, ?, %s, now()
            FROM chosen
            WHERE peers >= ? AND %s IS NOT NULL
            ON CONFLICT (trade_date, indicator_code, stock_id) DO UPDATE SET
                value      = EXCLUDED.value,
                updated_at = now()
            WHERE indicator_value.value IS DISTINCT FROM EXCLUDED.value
            """;

    /**
     * {@link #UPSERT}가 쓰지 <b>않기로 한</b> 종목의 그날 옛 행을 지운다.
     *
     * <p>MUST: 술어를 {@code UPSERT}의 {@code WHERE}와 <b>정확히 여집합</b>으로 둔다. 그 조건은
     * {@code peers >= ? AND … IS NOT NULL}이라 <b>배제 사유가 둘</b>이고, 표본 수만 보면
     * 중앙값이 0 이하라 값이 빠진 자리(자본잠식 업종)의 옛 행이 남는다.
     *
     * <p>IMPORTANT — 이것은 {@link DerivedIndicatorCleanup}의 javadoc이 금지하는
     * <b>「이번에 안 썼다」가 아니다.</b> 그쪽이 경계하는 것은 <b>UPSERT가 반환한 행 수</b>를
     * 기준으로 삼는 것인데({@code IS DISTINCT FROM} 때문에 값이 그대로인 날은 0행이라 멀쩡한
     * 행을 지운다), 여기 기준은 <b>집합의 소속</b>이다 — 값이 안 바뀐 날에도 그 종목은
     * {@code chosen}에 있으므로 지워지지 않는다.
     *
     * <p>이 술어가 {@code removeOrphans}의 「원본이 없다」를 <b>포함한다.</b> 원본이 없는 종목은
     * {@code member}에 안 들어가 {@code chosen}에도 없기 때문이다. 그래서 날짜별
     * {@code removeOrphans} 호출을 이것으로 갈음한다 — 같은 행의 소유가 둘이면 한쪽을 고칠 때
     * 다른 쪽이 조용히 남는다.
     *
     * <p><b>지우는 것은 그날 하나뿐이다.</b> 과거 일자에 남은 것은
     * {@link #removeOrphansInWindow}와 유니버스 이탈 정리가 본다.
     */
    private static final String DELETE_UNWRITTEN = """
            WITH %s
            DELETE FROM indicator_value v
            WHERE v.trade_date = ?
              AND v.indicator_code = ?
              AND NOT EXISTS (
                  SELECT 1 FROM chosen c
                  WHERE c.stock_id = v.stock_id AND c.peers >= ? AND c.%s IS NOT NULL)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final List<SectorMetricSpec> specs;

    public SectorRelativeCalculator(JdbcTemplate jdbcTemplate, List<SectorMetricSpec> specs) {
        this.jdbcTemplate = jdbcTemplate;
        this.specs = List.copyOf(specs);
    }

    /**
     * 해당 날짜의 업종 상대값과 표본 수를 전부 계산해 저장한다.
     *
     * @return <b>값이 실제로 달라진</b> 행 수. 같은 날을 다시 돌리면 0에 가깝다.
     */
    public long removeOrphansInWindow(Optional<LocalDate> cutoff) {
        long removed = 0;
        for (SectorMetricSpec spec : specs) {
            removed += DerivedIndicatorCleanup.removeOrphansAfter(
                    jdbcTemplate, spec.code(), spec.sourceCode(), cutoff);
            // MUST: 표본 수도 함께 — calculate()의 날짜별 정리와 같은 이유다.
            removed += DerivedIndicatorCleanup.removeOrphansAfter(
                    jdbcTemplate, spec.peerCountCode(), spec.sourceCode(), cutoff);
        }
        log.info("보존 창의 고아 업종 상대값을 정리했다. 지표={}쌍 정리={}행", specs.size(), removed);
        return removed;
    }

    public long calculate(LocalDate tradeDate, String backfillUniverseCode) {
        // PercentileCalculator와 같은 이유로 빈 문자열로 접는다.
        String extra = backfillUniverseCode == null ? "" : backfillUniverseCode;
        long written = 0;
        long removed = 0;
        for (SectorMetricSpec spec : specs) {
            // MUST: switch로 둔다. 삼항연산자면 Kind가 늘 때 새 값이 else로 조용히 떨어져
            // 다른 뜻의 SQL이 그 코드로 저장된다 — 컴파일도 테스트도 통과하고 값만 틀린다.
            String chosen = switch (spec.kind()) {
                case PERCENTILE -> PERCENTILE_CHOSEN;
                case MEDIAN_RATIO -> MEDIAN_RATIO_CHOSEN;
                case MEDIAN -> MEDIAN_CHOSEN;
            };

            written += upsert(chosen, spec.sourceCode(), tradeDate, extra, spec.code(), "metric");
            // 표본 수는 상대값과 같은 층·같은 모집단에서 나온다. 따로 세면 둘이 갈린다.
            written += upsert(chosen, spec.sourceCode(), tradeDate, extra,
                    spec.peerCountCode(), "peers");

            // 그날 안 쓰기로 한 자리의 옛 행을 걷는다(ADR-0085). 모집단이 MIN_PEERS 아래로
            // 떨어지거나 중앙값이 0 이하가 되면 UPSERT가 그 종목을 안 쓰는데, 이 정리가 없으면
            // **어제 쓴 값이 오늘 값처럼 남는다.** 실측으로 리밸런싱 한 번에 1,508행이 그렇게
            // 남아 있었다(490470이 빠지며 division 72의 표본이 5 → 4가 됐다).
            //
            // MUST: 표본 수도 함께 정리한다. 상대값만 지우면 "값은 없는데 표본은 40"이 남고,
            // 그 짝이 어긋난 것은 화면에서 표본만 보이는 형태로 나타난다.
            removed += deleteUnwritten(chosen, spec.sourceCode(), tradeDate, extra,
                    spec.code(), "metric");
            removed += deleteUnwritten(chosen, spec.sourceCode(), tradeDate, extra,
                    spec.peerCountCode(), "peers");
        }
        log.info("업종 상대값을 계산했다. tradeDate={} 지표={}쌍 저장={}행 정리={}행",
                tradeDate, specs.size(), written, removed);
        return written;
    }

    private int upsert(String chosen, String sourceCode, LocalDate tradeDate, String extraUniverse,
                       String targetCode, String column) {
        return jdbcTemplate.update(
                UPSERT.formatted(chosen, column, column),
                // 자리표시자 순서는 SQL 텍스트 순서다 — member(3) → chosen(2) → SELECT(2) → WHERE(1).
                sourceCode, tradeDate, extraUniverse,
                MIN_PEERS, MIN_PEERS,
                tradeDate, targetCode,
                MIN_PEERS);
    }

    private int deleteUnwritten(String chosen, String sourceCode, LocalDate tradeDate,
                                String extraUniverse, String targetCode, String column) {
        return jdbcTemplate.update(
                DELETE_UNWRITTEN.formatted(chosen, column),
                // 자리표시자 순서가 upsert와 같다 — member(3) → chosen(2) → DELETE(3).
                sourceCode, tradeDate, extraUniverse,
                MIN_PEERS, MIN_PEERS,
                tradeDate, targetCode,
                MIN_PEERS);
    }

    /** 등록된 선언. 카탈로그 동기화가 이것을 읽어 지표 행을 만든다. */
    public List<SectorMetricSpec> specs() {
        return specs;
    }
}
