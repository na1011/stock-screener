package com.stockscreener.indicator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 지표 값의 <b>업종 내</b> 상대값과 그 표본 수를 산출한다(ADR-0044).
 *
 * <p>{@link PercentileCalculator}와 나란히, 개별 지표 계산이 전부 끝난 뒤 하루 단위로 한 번
 * 돈다. 종목 단위 러너 안에서는 원리적으로 계산할 수 없다 — 같은 날 여러 종목의 값이 있어야
 * 정해지기 때문이다.
 *
 * <p><b>모집단이 {@link PercentileCalculator}와 다르다.</b> 저쪽은 유니버스별로 나누고
 * (ADR-0036), 이쪽은 <b>활성 유니버스 전체 중 같은 업종</b>으로 묶는다. 유니버스로 한 번 더
 * 나누면 350종목이 유니버스 2 × 업종 40으로 쪼개져 표본이 남지 않는다. 그것이 ADR-0036을
 * 다시 여는 지점이며, 그래서 화면이 두 모집단을 구분해 설명해야 한다.
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
     */
    private static final String MEMBER = """
            member AS (
                SELECT iv.stock_id, iv.value, s.industry_group AS fine, s.industry_division AS broad
                FROM indicator_value iv
                JOIN stock s ON s.id = iv.stock_id
                WHERE iv.indicator_code = ?
                  AND iv.trade_date = ?
                  AND s.industry_code IS NOT NULL
                  AND EXISTS (
                      SELECT 1 FROM universe_membership m
                      JOIN universe u ON u.code = m.universe_code AND u.is_active
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
     * 층별 중앙값 둘 + 종목별 층 선택.
     *
     * <p>중앙값이 0 이하면 배수가 뜻을 잃으므로 값을 내지 않는다(원본 §2-2 P-07 음수 분모 가드).
     * 자기자본이 잠식된 업종에서 실제로 생길 수 있다.
     */
    private static final String MEDIAN_RATIO_CHOSEN = MEMBER + """
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
    public long calculate(LocalDate tradeDate) {
        long written = 0;
        long removed = 0;
        for (SectorMetricSpec spec : specs) {
            String chosen = spec.kind() == SectorMetricSpec.Kind.PERCENTILE
                    ? PERCENTILE_CHOSEN : MEDIAN_RATIO_CHOSEN;

            written += upsert(chosen, spec.sourceCode(), tradeDate, spec.code(), "metric");
            // 표본 수는 상대값과 같은 층·같은 모집단에서 나온다. 따로 세면 둘이 갈린다.
            written += upsert(chosen, spec.sourceCode(), tradeDate, spec.peerCountCode(), "peers");

            // MUST: 표본 수도 함께 정리한다. 상대값만 지우면 "값은 없는데 표본은 40"이 남고,
            // 그 짝이 어긋난 것은 화면에서 표본만 보이는 형태로 나타난다.
            removed += DerivedIndicatorCleanup.removeOrphans(
                    jdbcTemplate, spec.code(), spec.sourceCode(), tradeDate);
            removed += DerivedIndicatorCleanup.removeOrphans(
                    jdbcTemplate, spec.peerCountCode(), spec.sourceCode(), tradeDate);
        }
        log.info("업종 상대값을 계산했다. tradeDate={} 지표={}쌍 저장={}행 정리={}행",
                tradeDate, specs.size(), written, removed);
        return written;
    }

    private int upsert(String chosen, String sourceCode, LocalDate tradeDate,
                       String targetCode, String column) {
        return jdbcTemplate.update(
                UPSERT.formatted(chosen, column, column),
                // 자리표시자 순서는 SQL 텍스트 순서다 — member(2) → chosen(2) → SELECT(2) → WHERE(1).
                sourceCode, tradeDate,
                MIN_PEERS, MIN_PEERS,
                tradeDate, targetCode,
                MIN_PEERS);
    }

    /** 등록된 선언. 카탈로그 동기화가 이것을 읽어 지표 행을 만든다. */
    public List<SectorMetricSpec> specs() {
        return specs;
    }
}
