package com.stockscreener.indicator;

import com.stockscreener.support.JpaSliceTest;
import com.stockscreener.support.RowVersions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 업종 상대값. 이 테스트가 지키는 것은 <b>모집단이 뜻을 갖는가</b>다.
 *
 * <p>임계 미달·업종 없음·층 폴백은 전부 <b>값이 그럴듯하게 나오는</b> 실패라 화면으로는
 * 드러나지 않는다(ADR-0044). 여기서 잡지 못하면 "업종 대비 저평가"라는 문구만 남고 실제로는
 * 그 종목이 자기 자신과 비교된다.
 */
@JpaSliceTest
class SectorRelativeCalculatorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 7);
    private static final String SOURCE = "DEBT_RATIO";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int nextTicker = 100000;

    @BeforeEach
    void seedCatalog() {
        jdbcTemplate.update("""
                INSERT INTO indicator (code, name, category, value_type)
                VALUES ('DEBT_RATIO', '부채비율', 'FUNDAMENTAL', 'RATIO')
                ON CONFLICT (code) DO NOTHING
                """);
        for (String code : List.of("DEBT_RATIO_SECTOR_REL", "DEBT_RATIO_SECTOR_N",
                "DEBT_RATIO_SECTOR_PCTL", "DEBT_RATIO_SECTOR_MEDIAN")) {
            jdbcTemplate.update("""
                    INSERT INTO indicator (code, name, category, value_type)
                    VALUES (?, ?, 'DERIVED', 'RATIO') ON CONFLICT (code) DO NOTHING
                    """, code, code);
        }
    }

    @Test
    @DisplayName("표본 5 이상인 소분류에서 중앙값 배수를 낸다")
    void computesMedianRatioWhenFineGroupIsBigEnough() {
        // 소분류 261에 다섯. 중앙값은 30이고 60인 종목은 배수 2.0이다.
        long target = member("26111", "10");
        member("26112", "20");
        member("26113", "30");
        member("26114", "40");
        long high = member("26115", "60");

        calculate(medianRatio());

        assertThat(value(target, "DEBT_RATIO_SECTOR_REL")).isEqualByComparingTo("0.33333333");
        assertThat(value(high, "DEBT_RATIO_SECTOR_REL")).isEqualByComparingTo("2.0");
        assertThat(value(target, "DEBT_RATIO_SECTOR_N")).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("소분류 표본이 모자라면 중분류로 올라가고, 표본 수가 그 사실을 드러낸다")
    void fallsBackToBroadDivision() {
        // 소분류 261에 둘뿐이지만 중분류 26에는 다섯이다.
        long target = member("26111", "10");
        member("26112", "20");
        member("26201", "30");
        member("26202", "40");
        member("26203", "50");

        calculate(medianRatio());

        // 중분류 다섯의 중앙값은 30이다. 소분류 둘로 쟀다면 중앙값이 15여서 배수가 달랐다.
        assertThat(value(target, "DEBT_RATIO_SECTOR_REL")).isEqualByComparingTo("0.33333333");
        assertThat(value(target, "DEBT_RATIO_SECTOR_N"))
                .as("표본 수가 5여야 중분류로 올라간 것이 드러난다")
                .isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("IMPORTANT — 폴백한 종목은 중분류 전체와 비교된다. 두 층이 한 파티션에 섞이지 않는다")
    void fallbackUsesTheWholeBroadDivision() {
        // 소분류 261은 다섯(자기 층을 쓴다), 262는 하나(중분류로 올라간다).
        long inFine = member("26101", "10");
        member("26102", "20");
        member("26103", "30");
        member("26104", "40");
        member("26105", "50");
        long lonely = member("26201", "60");

        calculate(percentile());

        // 261 종목은 소분류 5명 안에서 매겨진다 — 최솟값이므로 0.
        assertThat(value(inFine, "DEBT_RATIO_SECTOR_PCTL")).isEqualByComparingTo("0.00000000");
        assertThat(value(inFine, "DEBT_RATIO_SECTOR_N")).isEqualByComparingTo("5");
        // 262 종목은 중분류 6명 안에서 매겨진다 — 최댓값이므로 1. 자기 자신과 비교되지 않는다.
        assertThat(value(lonely, "DEBT_RATIO_SECTOR_PCTL")).isEqualByComparingTo("1.00000000");
        assertThat(value(lonely, "DEBT_RATIO_SECTOR_N")).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("중분류까지 5 미만이면 행을 쓰지 않는다 — 0을 쓰면 업종 최하위로 읽힌다")
    void writesNothingWhenEvenBroadDivisionIsTooSmall() {
        long lonely = member("12000", "10");
        member("12001", "20");

        calculate(medianRatio());

        assertThat(value(lonely, "DEBT_RATIO_SECTOR_REL")).isNull();
        assertThat(value(lonely, "DEBT_RATIO_SECTOR_N")).isNull();
    }

    @Test
    @DisplayName("업종이 비어 있는 종목은 모집단에서 빠진다 — NULL끼리 순위가 매겨지면 안 된다")
    void excludesStocksWithoutIndustry() {
        long unknown = member(null, "10");
        member(null, "20");
        member(null, "30");
        member(null, "40");
        member(null, "50");

        calculate(medianRatio());

        assertThat(value(unknown, "DEBT_RATIO_SECTOR_REL")).isNull();
    }

    @Test
    @DisplayName("중앙값이 0 이하면 배수를 내지 않는다 — 음수 분모 가드")
    void skipsNonPositiveMedian() {
        long target = member("26111", "-10");
        member("26112", "-20");
        member("26113", "0");
        member("26114", "-40");
        member("26115", "-50");

        calculate(medianRatio());

        assertThat(value(target, "DEBT_RATIO_SECTOR_REL")).isNull();
        assertThat(value(target, "DEBT_RATIO_SECTOR_N"))
                .as("배수를 못 내도 표본 수는 사실이므로 남는다")
                .isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("같은 날을 다시 돌리면 0행이다")
    void isIdempotent() {
        member("26111", "10");
        member("26112", "20");
        member("26113", "30");
        member("26114", "40");
        member("26115", "50");

        SectorMetricSpec spec = medianRatio();
        assertThat(calculate(spec)).isPositive();
        assertThat(calculate(spec)).isZero();
    }

    @Test
    @DisplayName("같은 업종 상대값을 다시 쓰면 WAL을 안 낸다 — float8 배수·순위를 저장 열 정밀도로 비교한다")
    void sameValuesRewriteWritesNoWal() {
        // 일곱 — 중앙값 35라 배수 10/35가, 순위가 k/6이라 소수 8자리를 넘는다.
        long low = member("26111", "10");
        for (String value : List.of("20", "30", "35", "50", "61", "70")) {
            member("26111", value);
        }
        SectorMetricSpec[] specs = {medianRatio(), percentile(), median()};
        assertThat(calculate(specs)).isPositive();

        List<String> before = RowVersions.of(jdbcTemplate, "indicator_value");
        assertThat(before).as("전제: 첫 계산 뒤 잠긴 행이 없다").allMatch(RowVersions::unlocked);
        assertThat(calculate(specs)).isZero();

        assertThat(value(low, "DEBT_RATIO_SECTOR_REL")).isEqualByComparingTo("0.28571429");
        assertThat(value(low, "DEBT_RATIO_SECTOR_MEDIAN")).isEqualByComparingTo("35");
        assertThat(RowVersions.of(jdbcTemplate, "indicator_value")).as("같은 업종 상대값을 다시 쓴 뒤의 행 판 — 새 판도 잠금도 없다")
                .isEqualTo(before);
    }

    private long calculate(SectorMetricSpec... specs) {
        return new SectorRelativeCalculator(jdbcTemplate, List.of(specs)).calculate(AS_OF, null).written();
    }

    @Test
    @DisplayName("중앙값 Kind는 배수가 아니라 중앙값 자체를 낸다 — 같은 업종은 같은 값이다")
    void storesTheMedianItselfNotTheRatio() {
        long low = member("26111", "10");
        member("26112", "20");
        long mid = member("26113", "30");
        member("26114", "40");
        long high = member("26115", "60");

        calculate(median());

        // 배수였다면 0.33 / 1.0 / 2.0이 나온다. 셋이 모두 30이어야 중앙값이다.
        assertThat(value(low, "DEBT_RATIO_SECTOR_MEDIAN")).isEqualByComparingTo("30");
        assertThat(value(mid, "DEBT_RATIO_SECTOR_MEDIAN")).isEqualByComparingTo("30");
        assertThat(value(high, "DEBT_RATIO_SECTOR_MEDIAN")).isEqualByComparingTo("30");
        assertThat(value(low, "DEBT_RATIO_SECTOR_N")).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("중앙값도 폴백한 층의 것이다 — 층이 섞이면 값이 그럴듯하게 틀린다")
    void medianComesFromTheChosenLayer() {
        // 소분류 261에 둘, 262에 셋. 중분류 26 전체는 다섯이고 그 중앙값은 30이다.
        long target = member("26111", "10");
        member("26112", "20");
        member("26201", "30");
        member("26202", "40");
        member("26203", "60");

        calculate(median());

        // 소분류(261)의 중앙값이면 15가 된다. 폴백한 중분류의 중앙값 30이어야 한다.
        assertThat(value(target, "DEBT_RATIO_SECTOR_MEDIAN")).isEqualByComparingTo("30");
        assertThat(value(target, "DEBT_RATIO_SECTOR_N")).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("중앙값이 0 이하여도 낸다 — 배수의 음수 분모 가드를 복사하지 않는다")
    void keepsNonPositiveMedian() {
        long target = member("26111", "-30");
        member("26112", "-20");
        member("26113", "-10");
        member("26114", "0");
        member("26115", "10");

        calculate(median(), medianRatio());

        assertThat(value(target, "DEBT_RATIO_SECTOR_MEDIAN"))
                .as("중앙값 -10은 참조값으로 성립한다. 0으로 나눌 때만 뜻이 없다")
                .isEqualByComparingTo("-10");
        assertThat(value(target, "DEBT_RATIO_SECTOR_REL"))
                .as("배수는 같은 그룹에서 값을 내지 않는다 — 둘이 함께 사라지면 가드를 복사한 것이다")
                .isNull();
    }

    @Test
    @DisplayName("표본이 임계 아래로 떨어지면 옛 행을 걷는다 — 어제 쓴 값이 오늘 값처럼 남으면 안 된다")
    void deletesRowsWhenThePopulationFallsBelowTheThreshold() {
        long lonely = member("12000", "10");
        member("12001", "20");
        // 어제까지는 표본이 5였다가 리밸런싱으로 빠진 상태다. 실측으로 490470이 유니버스에서
        // 빠지며 division 72의 표본이 5 → 4가 됐고, 그때 1,508행이 이렇게 남았다.
        staleRow(lonely, "DEBT_RATIO_SECTOR_REL", "1.5");
        staleRow(lonely, "DEBT_RATIO_SECTOR_N", "5");

        calculate(medianRatio());

        assertThat(value(lonely, "DEBT_RATIO_SECTOR_REL")).isNull();
        assertThat(value(lonely, "DEBT_RATIO_SECTOR_N"))
                .as("상대값만 걷으면 「값은 없는데 표본은 5」가 남는다")
                .isNull();
    }

    @Test
    @DisplayName("중앙값 가드로 값이 빠진 자리도 걷는다 — 배제 사유가 표본 수 하나가 아니다")
    void deletesRowsWhenTheMedianGuardRemovedTheValue() {
        long target = member("26111", "-10");
        member("26112", "-20");
        member("26113", "0");
        member("26114", "-40");
        member("26115", "-50");
        // 표본은 5라 임계를 넘는다. UPSERT의 WHERE가 `peers >= ? AND … IS NOT NULL`이므로
        // 표본 수만 보는 술어로는 이 행이 안 걷힌다 — 여집합이라야 걷힌다.
        staleRow(target, "DEBT_RATIO_SECTOR_REL", "1.5");

        calculate(medianRatio());

        assertThat(value(target, "DEBT_RATIO_SECTOR_REL")).isNull();
        assertThat(value(target, "DEBT_RATIO_SECTOR_N"))
                .as("표본 수는 사실이므로 남는다 — 이쪽까지 걷으면 여집합을 잘못 잡은 것이다")
                .isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("값이 안 바뀐 날에도 쓴 행은 남는다 — 「이번에 안 썼다」를 기준으로 삼으면 안 된다")
    void keepsRowsOnDaysWhenTheUpsertWroteNothing() {
        long target = member("26111", "10");
        member("26112", "20");
        member("26113", "30");
        member("26114", "40");
        member("26115", "50");
        SectorMetricSpec spec = medianRatio();
        calculate(spec);
        assertThat(value(target, "DEBT_RATIO_SECTOR_REL")).isNotNull();

        // 두 번째 실행은 IS DISTINCT FROM 때문에 0행을 쓴다. 삭제 술어를 「UPSERT가 반환한
        // 행 수」로 잡으면 여기서 멀쩡한 행이 사라진다 — DerivedIndicatorCleanup의 javadoc이
        // 그 함정을 적어 뒀고, 이 단언이 그 경계를 지킨다.
        calculate(spec);

        assertThat(value(target, "DEBT_RATIO_SECTOR_REL")).isNotNull();
        assertThat(value(target, "DEBT_RATIO_SECTOR_N")).isNotNull();
    }

    @Test
    @DisplayName("창 정리가 원본이 사라진 업종 상대값과 그 표본 수를 함께 지운다(ADR-0080)")
    void windowCleanupRemovesDerivedAndPeerCountWhoseSourceVanished() {
        long target = member("26111", "10");
        long other = member("26112", "20");
        member("26113", "30");
        member("26114", "40");
        member("26115", "50");
        SectorMetricSpec spec = medianRatio();
        SectorRelativeCalculator calculator = new SectorRelativeCalculator(jdbcTemplate, List.of(spec));
        calculator.calculate(AS_OF, null);
        assertThat(value(target, "DEBT_RATIO_SECTOR_REL")).isNotNull();

        // 러너가 그 종목의 원본을 그 날짜에서 지운 상태 — 파생 행은 그대로 남아 있다.
        jdbcTemplate.update("""
                DELETE FROM indicator_value WHERE stock_id = ? AND trade_date = ? AND indicator_code = ?
                """, target, AS_OF, SOURCE);

        calculator.removeOrphansInWindow(Optional.of(AS_OF.minusDays(1)));

        assertThat(value(target, "DEBT_RATIO_SECTOR_REL")).as("원본 없는 상대값은 지운다").isNull();
        assertThat(value(target, "DEBT_RATIO_SECTOR_N")).as("표본 수도 같은 축으로 지운다").isNull();
        assertThat(value(other, "DEBT_RATIO_SECTOR_REL")).as("원본이 있으면 남긴다").isNotNull();
        assertThat(value(other, "DEBT_RATIO_SECTOR_N")).isNotNull();
    }

    private void staleRow(long stockId, String code, String value) {
        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, ?, ?)
                """, stockId, AS_OF, code, new BigDecimal(value));
    }

    private static SectorMetricSpec medianRatio() {
        return new SectorMetricSpec(SOURCE, SectorMetricSpec.Kind.MEDIAN_RATIO, "배수", "설명", "해석");
    }

    private static SectorMetricSpec percentile() {
        return new SectorMetricSpec(SOURCE, SectorMetricSpec.Kind.PERCENTILE, "백분위", "설명", "해석");
    }

    private static SectorMetricSpec median() {
        return new SectorMetricSpec(SOURCE, SectorMetricSpec.Kind.MEDIAN, "중앙값", "설명", "해석");
    }

    @Test
    @DisplayName("IMPORTANT — 앞자리가 겹치는 두 분류 체계가 한 모집단이 되지 않는다")
    void keepsClassificationSchemesInSeparatePopulations() {
        // 접두가 없으면 이 열 종목이 한 파티션이 된다. KSIC 0100과 SIC 0100은 잘라 낸
        // 소분류·중분류가 글자 그대로 같기 때문이다(둘 다 010 / 01). 실제로 겹치는
        // 값이라 가정이 아니다 — submissions가 0100·0200을 주고 KSIC에는 011이 있다.
        long kr = member("01001", "10");
        member("01002", "20");
        member("01003", "30");
        member("01004", "40");
        member("01005", "50");

        long us = usMember("0100", "1000");
        usMember("0100", "2000");
        usMember("0100", "3000");
        usMember("0100", "4000");
        usMember("0100", "5000");

        calculate(medianRatio());

        // 섞였다면 열 종목의 중앙값이 분모가 되어 국내 종목의 배수가 완전히 달라진다.
        assertThat(value(kr, "DEBT_RATIO_SECTOR_REL")).isEqualByComparingTo("0.33333333");
        assertThat(value(us, "DEBT_RATIO_SECTOR_REL")).isEqualByComparingTo("0.33333333");

        // 그리고 표본 수가 각각 5다. 10이면 한 모집단이 된 것이다.
        assertThat(value(kr, "DEBT_RATIO_SECTOR_N")).isEqualByComparingTo("5");
        assertThat(value(us, "DEBT_RATIO_SECTOR_N")).isEqualByComparingTo("5");
    }

    /** 활성 유니버스에 편입된 종목 하나와 그날의 원본 지표 값. */
    private long member(String industryCode, String value) {
        String ticker = String.valueOf(nextTicker++);
        Long stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, industry_code)
                VALUES ('KOSPI', ?, ?, ?) RETURNING id
                """, Long.class, ticker, ticker, industryCode);

        // 테스트 DB는 시드 유니버스가 활성이다(.claude/rules/test.md). 그것을 그대로 쓴다.
        String universe = jdbcTemplate.queryForObject(
                "SELECT code FROM universe WHERE is_active AND market_code = 'KOSPI' LIMIT 1",
                String.class);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES (?, ?, ?)
                """, universe, stockId, AS_OF.minusYears(1));

        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, ?, ?)
                """, stockId, AS_OF, SOURCE, new BigDecimal(value));
        return stockId;
    }

    /**
     * 미국 종목 하나. 업종은 {@code sic_code}에 들어간다 — {@code ck_stock_sic_is_us}가
     * 시장으로 그것을 강제하므로 <b>이 헬퍼로만 만들 수 있다</b>(ADR-0092).
     *
     * <p>{@code US_LISTED}를 여기서 활성으로 올린다. 슬라이스 테스트가 트랜잭션째 롤백하므로
     * 다른 테스트에 새지 않는다. 운영에서 이 유니버스가 꺼져 있는 것은 별개의 사실이고,
     * 이 테스트가 묻는 것은 <b>켜졌을 때 두 체계가 섞이는가</b>다.
     */
    private long usMember(String sicCode, String value) {
        String ticker = "US" + nextTicker++;
        Long stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, sic_code)
                VALUES ('US', ?, ?, ?) RETURNING id
                """, Long.class, ticker, ticker, sicCode);

        jdbcTemplate.update("UPDATE universe SET is_active = true WHERE code = 'US_LISTED'");
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES ('US_LISTED', ?, ?)
                """, stockId, AS_OF.minusYears(1));

        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, ?, ?)
                """, stockId, AS_OF, SOURCE, new BigDecimal(value));
        return stockId;
    }

    private BigDecimal value(long stockId, String code) {
        List<BigDecimal> found = jdbcTemplate.queryForList("""
                SELECT value FROM indicator_value
                WHERE stock_id = ? AND trade_date = ? AND indicator_code = ?
                """, BigDecimal.class, stockId, AS_OF, code);
        return found.isEmpty() ? null : found.get(0);
    }
}
