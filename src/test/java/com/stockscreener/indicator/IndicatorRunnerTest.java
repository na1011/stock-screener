package com.stockscreener.indicator;

import com.stockscreener.indicator.calculator.Atr14Calculator;
import com.stockscreener.indicator.calculator.EpsGrowthTtmYoyCalculator;
import com.stockscreener.indicator.calculator.ExcessReturn26wCalculator;
import com.stockscreener.indicator.calculator.MacdCalculator;
import com.stockscreener.indicator.calculator.MarketCapCalculator;
import com.stockscreener.indicator.calculator.MacdVCalculator;
import com.stockscreener.indicator.calculator.PerCalculator;
import com.stockscreener.indicator.calculator.Rsi14Calculator;
import com.stockscreener.indicator.calculator.Sma20Calculator;
import com.stockscreener.indicator.calculator.Sma200Calculator;
import com.stockscreener.pipeline.collector.CorporateActionStatus;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지표 계산 엔진의 완료 판정.
 *
 * <p>합성 시세로 검증하므로 초기 적재와 무관하게 성립한다. 실데이터는 값의 분포를 볼 때
 * 필요하지 저 구조가 도는지를 볼 때 필요한 것이 아니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class IndicatorRunnerTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);

    @Autowired
    private IndicatorRunner runner;

    @Autowired
    private IndicatorRegistry registry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;

    @BeforeEach
    void insertStock() {
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '005930', '삼성전자')
                RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("시세가 충분하면 기술적 지표를 계산해 저장한다")
    void computesAndStoresTechnicalIndicators() {
        insertPriceHistory(300);

        long written = runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(written).isPositive();
        assertThat(storedCodes()).contains(
                Sma20Calculator.CODE, Sma200Calculator.CODE,
                Rsi14Calculator.CODE, Atr14Calculator.CODE, MacdCalculator.CODE);
    }

    @Test
    @DisplayName("이력이 부족한 지표는 행을 만들지 않는다 — NULL 행 대신 부재로 표현한다")
    void writesNoRowWhenHistoryIsInsufficient() {
        // 20일선은 되지만 200일선은 안 되는 길이다.
        insertPriceHistory(30);

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(storedCodes())
                .contains(Sma20Calculator.CODE)
                .doesNotContain(Sma200Calculator.CODE);
    }

    @Test
    @DisplayName("파생 지표는 부모의 계산을 재구현하지 않고 이미 계산된 시계열을 받는다")
    void derivedIndicatorConsumesParentSeries() {
        insertPriceHistory(300);

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(registry.find(MacdVCalculator.CODE)).isPresent();
        assertThat(registry.executionOrder().stream().map(IndicatorCalculator::code).toList())
                .as("위상 정렬이 부모를 먼저 놓아야 자식이 값을 받을 수 있다")
                .satisfies(order -> {
                    assertThat(order.indexOf(MacdVCalculator.CODE))
                            .isGreaterThan(order.indexOf(MacdCalculator.CODE));
                    assertThat(order.indexOf(MacdVCalculator.CODE))
                            .isGreaterThan(order.indexOf(Atr14Calculator.CODE));
                });
        assertThat(storedCodes())
                .as("부모가 모두 계산됐으므로 MACD-V도 값을 낸다")
                .contains(MacdVCalculator.CODE);
    }

    @Test
    @DisplayName("두 번 실행해도 행이 늘지 않는다 — 멱등해야 한다")
    void isIdempotent() {
        insertPriceHistory(300);

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());
        int afterFirst = countIndicatorValues();
        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(countIndicatorValues()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("재무가 없으면 재무 지표는 저장하지 않고 기술적 지표는 정상 저장한다")
    void skipsFundamentalsWithoutFinancials() {
        insertPriceHistory(300);

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(storedCodes())
                .contains(Sma200Calculator.CODE)
                .doesNotContain(PerCalculator.CODE);
    }

    @Test
    @DisplayName("시세가 없으면 아무것도 저장하지 않는다")
    void storesNothingWithoutPrices() {
        assertThat(runner.run(stockId, AS_OF, Optional.empty(), Map.of())).isZero();
        assertThat(countIndicatorValues()).isZero();
    }

    @Test
    @DisplayName("기술적 지표는 수정주가로 계산한다 — 분할 구간에서 원주가를 쓰면 값이 튄다")
    void technicalIndicatorsUseAdjustedClose() {
        // 원주가는 2배로 두고 수정주가만 일정하게 둔다. 수정주가를 쓰면 이동평균이
        // 그 일정한 값과 같아야 하고, 원주가를 썼다면 2배로 나온다.
        for (int i = 0; i < 300; i++) {
            LocalDate date = AS_OF.minusDays(300L - i);
            insertBar(date, new BigDecimal("2000"), new BigDecimal("1000"));
        }

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        BigDecimal sma = jdbcTemplate.queryForObject("""
                SELECT value FROM indicator_value
                WHERE stock_id = ? AND indicator_code = ? AND trade_date = ?
                """, BigDecimal.class, stockId, Sma20Calculator.CODE, AS_OF.minusDays(1));

        assertThat(sma).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("보존 경계 이하는 저장하지 않는다 — 경계일 자신도 뺀다(purge와 같은 부등호)")
    void storesNothingAtOrBeforeRetentionCutoff() {
        insertPriceHistory(300);
        LocalDate cutoff = AS_OF.minusDays(100);

        runner.run(stockId, AS_OF, Optional.of(cutoff), Map.of());

        assertThat(storedTradeDates())
                .isNotEmpty()
                .allSatisfy(date -> assertThat(date)
                        .as("경계일 이하가 남으면 purge가 매 실행 그것을 다시 지운다")
                        .isAfter(cutoff));
    }

    @Test
    @DisplayName("경계를 걸어도 남는 값은 창 전체로 계산한 값과 같다 — 자르는 것은 저장뿐이다")
    void retentionCutoffDoesNotChangeComputedValues() {
        insertPriceHistory(300);
        LocalDate lastBar = AS_OF.minusDays(1);

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());
        BigDecimal withoutCutoff = storedValue(Atr14Calculator.CODE, lastBar);

        jdbcTemplate.update("DELETE FROM indicator_value WHERE stock_id = ?", stockId);
        runner.run(stockId, AS_OF, Optional.of(AS_OF.minusDays(100)), Map.of());

        // ATR은 앞 구간이 뒤로 이어지는 재귀 지표다. 경계로 계산 입력까지 잘랐다면
        // 워밍업이 짧아져 이 값이 달라지는데, 값이 그럴듯해 화면으로는 드러나지 않는다.
        assertThat(storedValue(Atr14Calculator.CODE, lastBar))
                .isEqualByComparingTo(withoutCutoff);
    }

    @Test
    @DisplayName("권리락 구간의 시가총액이 원장의 비율만큼 보정된다 — 러너가 원장을 함께 읽는다")
    void marketCapInsideExRightsWindowIsScaledByTheLedger() {
        LocalDate exRights = AS_OF.minusDays(20);
        LocalDate newListing = AS_OF.minusDays(6);
        insertExRightsHistory(exRights, newListing);
        approvedAction("BONUS_ISSUE", "2", exRights, newListing);

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        // 권리락 전날: 2,000 × 1,000,000. 권리락 당일은 가격이 반이고 주식수는 그대로라
        // 보정이 없으면 절반으로 찍힌다. 러너가 readShareAdjustments를 빠뜨리면 여기서 깨진다.
        assertThat(storedValue(MarketCapCalculator.CODE, exRights.minusDays(1)))
                .isEqualByComparingTo("2000000000");
        assertThat(storedValue(MarketCapCalculator.CODE, exRights))
                .isEqualByComparingTo("2000000000");
        assertThat(storedValue(MarketCapCalculator.CODE, newListing))
                .isEqualByComparingTo("2000000000");
    }

    @Test
    @DisplayName("신주상장일이 빈 행은 효력일로 접혀 구간이 공집합이 된다 — NPE도 보정도 없다")
    void missingListingDateFoldsIntoAnEmptyWindow() {
        LocalDate exRights = AS_OF.minusDays(20);
        LocalDate newListing = AS_OF.minusDays(6);
        insertExRightsHistory(exRights, newListing);
        // 액면병합은 두 날짜가 같아 원장이 신주상장일을 비워 둔다. 지금 원장에 실제로
        // 그 모양의 행이 승인 대기 중이다(대한전선·아시아나항공).
        approvedAction("REVERSE_SPLIT", "0.5", exRights, null);

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(storedValue(MarketCapCalculator.CODE, exRights))
                .as("접힌 구간은 비어 있으므로 원값 그대로여야 한다")
                .isEqualByComparingTo("1000000000");
    }

    @Test
    @DisplayName("보정 못 한 기업 행위가 창 안이면 그 지표의 값을 내지 않는다 — 창 밖 지표는 그대로다")
    void suppressesIndicatorsWhoseWindowSpansAnUnadjustedAction() {
        insertPriceHistory(250);
        // 인덱스 60. SMA_200의 창(200봉)은 산출 일자 전부에서 이 날을 삼키고,
        // SMA_20의 창(20봉)은 그 앞뒤 19일자에서만 삼킨다.
        unadjustedAction(CorporateActionStatus.UNSUPPORTED, AS_OF.minusDays(190));

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(storedCodes())
                .as("두 축척이 이어붙은 창으로 낸 값은 그럴듯하지만 틀리다")
                .doesNotContain(Sma200Calculator.CODE)
                .contains(Sma20Calculator.CODE);
        assertThat(storedTradeDates())
                .as("사건이 미래인 일자는 축척이 하나뿐이라 살아남는다")
                .contains(AS_OF.minusDays(200));
    }

    @Test
    @DisplayName("판정 전 후보(OBSERVED)도 같다 — 보정되지 않았다는 사실이 판정 여부보다 앞선다")
    void suppressesForUnjudgedCandidatesToo() {
        insertPriceHistory(250);
        unadjustedAction(CorporateActionStatus.OBSERVED, AS_OF.minusDays(190));

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(storedCodes()).doesNotContain(Sma200Calculator.CODE);
    }

    @Test
    @DisplayName("이미 저장된 행을 실제로 지운다 — 안 쓰는 것만으로는 제외가 성립하지 않는다")
    void deletesRowsThatWereStoredBeforeTheActionWasKnown() {
        insertPriceHistory(250);
        runner.run(stockId, AS_OF, Optional.empty(), Map.of());
        assertThat(storedCodes()).contains(Sma200Calculator.CODE);

        // 사건이 뒤늦게 원장에 들어오는 것이 정상 경로다 — 휴리스틱 탐지도 공시 수집도
        // 시세보다 늦다. 이때 SMA_200은 산출 일자가 전부 빠져 points가 **빈 목록**이 되는데,
        // 러너가 거기서 continue하면 아래 단언이 깨진다.
        unadjustedAction(CorporateActionStatus.UNSUPPORTED, AS_OF.minusDays(190));
        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(storedCodes())
                .as("UPSERT뿐이면 옛 행이 남고, 남은 값은 그럴듯해서 화면에서 드러나지 않는다")
                .doesNotContain(Sma200Calculator.CODE);
    }

    @Test
    @DisplayName("계산이 그만 나면 옛 행을 지운다 — UPSERT만으로는 사라진 값이 사라지지 않는다")
    void deletesRowsOfIndicatorsThatStoppedProducing() {
        insertPriceHistory(300);
        // 재무를 **읽을 수는 있는데** TTM이 음수라 PER이 성립하지 않는 상태다. 001040 CJ가 정확히
        // 이 형태였다 — 2026 Q2가 들어오며 TTM이 −233억이 됐고, 그 뒤로 계산기는 PER을 한 행도
        // 안 내는데 백필은 UPSERT뿐이라 옛 178행을 못 덮었다.
        insertLossMakingQuarters();
        insertIndicatorRow(PerCalculator.CODE, AS_OF.minusDays(10), new BigDecimal("12.34"));

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(storedCodes())
                .as("남은 값은 그럴듯한 숫자라 화면에서도 테스트에서도 드러나지 않는다")
                .doesNotContain(PerCalculator.CODE);
    }

    @Test
    @DisplayName("재무를 한 행도 못 읽었으면 안 지운다 — 「값이 없다」와 「모른다」는 다르다")
    void keepsRowsWhenTheInputCouldNotBeReadAtAll() {
        insertPriceHistory(300);
        // 재무가 통째로 없다. 공시가 늦은 구간이 이 모양이고, 여기에 삭제를 걸면 그 종목의
        // 재무 지표 이력이 사라졌다가 다음 실행에 다시 쓰인다 — ADR-0045가 닫은 왕복이다.
        insertIndicatorRow(PerCalculator.CODE, AS_OF.minusDays(10), new BigDecimal("12.34"));

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(storedValue(PerCalculator.CODE, AS_OF.minusDays(10)))
                .as("입력을 못 읽은 것을 「값이 사라졌다」로 읽으면 공시 지연이 이력을 지운다")
                .isNotNull();
    }

    @Test
    @DisplayName("시장 지수를 통째로 못 읽었으면 안 지운다 — 재무·거버넌스와 같은 판정이다")
    void keepsRowsWhenTheMarketIndexCouldNotBeReadAtAll() {
        insertPriceHistory(300);
        // 지수를 안 넘긴다. 지수 적재가 실패했거나 어댑터가 등록되지 않은 상태가 이 모양이고,
        // 그때 EXCESS_RETURN_*·BETA_104W는 「값이 사라진 것」이 아니라 「아직 모른다」이다.
        // 선언이 없던 동안 이 경로로 실측 180,735행이 지워질 수 있었다(ADR-0080 §결과).
        insertIndicatorRow(ExcessReturn26wCalculator.CODE, AS_OF.minusDays(10),
                new BigDecimal("1.5"));

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(storedValue(ExcessReturn26wCalculator.CODE, AS_OF.minusDays(10)))
                .as("보상 통제인 지수 결손 경보는 일일 갱신에만 있어 백필에서는 아무것도 안 막는다")
                .isNotNull();
    }

    @Test
    @DisplayName("읽은 창 밖은 안 지운다 — 과거 일자 백필이 그 뒤의 라이브 행을 건드리면 안 된다")
    void neverDeletesBeyondTheWindowItRead() {
        insertPriceHistory(300);
        // asOf 뒤의 행이다. 창은 asOf에서 끝나므로 이번 실행의 사정거리 밖이다.
        insertIndicatorRow(PerCalculator.CODE, AS_OF.plusDays(3), new BigDecimal("12.34"));

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(storedValue(PerCalculator.CODE, AS_OF.plusDays(3)))
                .as("삭제 범위가 창의 거래일이 아니라 「경계 뒤 전부」가 되면 이 행이 사라진다")
                .isNotNull();
    }

    @Test
    @DisplayName("마지막 산출 일자 뒤에 남은 행을 지운다 — 창의 앞쪽을 아직 내면 빈-목록 분기가 안 걸린다")
    void deletesRowsAfterTheLastProducedDate() {
        // 앞 290봉은 주식수가 있고 뒤 10봉은 없다. MarketCapCalculator가 주식수 없는 날짜를
        // continue하므로 **창의 앞쪽은 내면서 어느 시점부터 그만 내는** 모양이 된다.
        // 006400 삼성SDI의 배당성향이 이 모양이었다 — 앵커가 적자 연도로 옮겨간 뒤로 값이
        // 안 나오는데 FY2024로 계산된 112행이 남았다.
        insertPriceHistoryWithSharesUntil(300, 290);
        insertIndicatorRow(MarketCapCalculator.CODE, AS_OF.minusDays(5),
                new BigDecimal("1000000000"));

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(storedValueOrNull(MarketCapCalculator.CODE, AS_OF.minusDays(5)))
                .as("write는 UPSERT라 산출한 날짜만 덮는다 — 꼬리를 안 걷으면 옛 값이 그대로 남는다")
                .isNull();
    }

    @Test
    @DisplayName("마지막 산출 일자 앞의 구멍은 그대로 둔다 — 꼬리 걷기가 창 전체로 번지면 안 된다")
    void keepsRowsBeforeTheLastProducedDate() {
        // 창 **안쪽**에 산출 구멍을 하나 뚫는다. 꼬리 뒤에 있는 행과 달리 이 행은 write가
        // 다시 쓰지 않으므로, 후보가 「창 안에서 안 낸 날짜 전부」로 넓어지면 **여기서만**
        // 드러난다. 꼬리 밖 행으로 단언하면 삭제 직후 write가 되살려 항등식이 된다.
        insertPriceHistoryWithSharesUntil(300, 290, 150);
        LocalDate hole = AS_OF.minusDays(150);
        insertIndicatorRow(MarketCapCalculator.CODE, hole, new BigDecimal("777"));

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(storedValueOrNull(MarketCapCalculator.CODE, hole))
                .as("「창 안에서 안 낸 날짜를 전부 지운다」로 넓히면 정상적으로 일부만 내는 "
                        + "계산기들의 보존 창이 매 실행 깎인다(ADR-0080 §버린 대안)")
                .isNotNull();
        assertThat(storedDatesOf(MarketCapCalculator.CODE))
                .doesNotContain(AS_OF.minusDays(10), AS_OF.minusDays(1));
    }

    @Test
    @DisplayName("꼬리에 지수가 없는 날짜는 안 지운다 — 부분 결손은 DataRequirement 축이 못 가른다")
    void doesNotCullTheTailWhenTheMarketIndexIsMissingOnThatDate() {
        insertPriceHistory(300);
        // 창 **끝쪽** 지수만 빠진 상태다. ExcessReturn.at이 그 날짜를 건너뛰므로 points는
        // 안 비고 lastProduced만 앞으로 당겨진다 — 축은 「통째로 없는가」만 가르므로 여기서
        // 걸러지지 않고, 그대로 두면 지수 재적재가 실패한 날 꼬리가 조용히 지워진다.
        NavigableMap<LocalDate, BigDecimal> index = new TreeMap<>();
        for (int i = 0; i < 300; i++) {
            LocalDate date = AS_OF.minusDays(300L - i);
            if (date.isBefore(AS_OF.minusDays(10))) {
                index.put(date, new BigDecimal(2000 + (i % 5) * 10));
            }
        }
        insertIndicatorRow(ExcessReturn26wCalculator.CODE, AS_OF.minusDays(5),
                new BigDecimal("1.5"));

        runner.run(stockId, AS_OF, Optional.empty(), Map.of("KOSPI", index));

        assertThat(storedValueOrNull(ExcessReturn26wCalculator.CODE, AS_OF.minusDays(5)))
                .as("지수 결손을 「값이 사라졌다」로 읽으면 지수 복구 전에 꼬리가 날아간다")
                .isNotNull();
    }

    @Test
    @DisplayName("꼬리 걷기도 읽은 창 밖은 안 지운다 — 상한이 asOf로 묶여 있어야 한다")
    void tailCullNeverReachesBeyondTheWindowItRead() {
        insertPriceHistoryWithSharesUntil(300, 290);
        insertIndicatorRow(MarketCapCalculator.CODE, AS_OF.plusDays(3),
                new BigDecimal("1000000000"));

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(storedValueOrNull(MarketCapCalculator.CODE, AS_OF.plusDays(3)))
                .as("후보가 창의 거래일이 아니라 「lastProduced 뒤 전부」가 되면 이 행이 사라진다")
                .isNotNull();
    }

    @Test
    @DisplayName("억제로 빈 것은 이 경로가 지우지 않는다 — 억제 창 밖까지 번지면 안 된다")
    void doesNotCullWhenEmptinessCameFromSuppression() {
        insertPriceHistory(250);
        LocalDate earliest = AS_OF.minusDays(250);
        // 효력일보다 **앞선** 일자는 억제 대상이 아니다(창 시작 == 효력일이면 억제하지 않는다).
        insertIndicatorRow(Sma200Calculator.CODE, earliest, new BigDecimal("1000"));
        unadjustedAction(CorporateActionStatus.UNSUPPORTED, AS_OF.minusDays(190));

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(storedValue(Sma200Calculator.CODE, earliest))
                .as("억제로 points가 비었을 때까지 창 전체를 지우면 TRADING_DAYS_750처럼 "
                        + "실행당 1일자인 지표는 복구에 날짜 수만큼의 백필이 든다")
                .isNotNull();
    }

    @Test
    @DisplayName("억제가 보존 창 아래로만 있으면 걷는다 — 삭제 0행인데 게이트만 막히면 안 된다")
    void cullsWhenSuppressionIsEntirelyBelowTheRetentionCutoff() {
        insertPriceHistory(400);
        // 억제 일자가 **전부 보존 컷오프 아래**인 상태를 만든다. 그러면 억제 분기의 삭제는
        // 0행인데, 게이트가 거르지 않은 집합을 보면 걷기가 통째로 거부된다 — 그 조합에서
        // 옛 행이 영영 안 걷힌다. 010060 OCI홀딩스가 실제로 그 상태였다(79행).
        LocalDate cutoff = AS_OF.minusDays(100);
        unadjustedAction(CorporateActionStatus.UNSUPPORTED, AS_OF.minusDays(399));
        // 앞 4분기 합이 음수라 EpsGrowthTtmYoyCalculator가 루프 앞에서 빈 목록을 낸다.
        // **억제 때문이 아니다** — 그 구분이 이 테스트의 전부다.
        insertLossMakingQuarters();
        insertIndicatorRow(EpsGrowthTtmYoyCalculator.CODE, AS_OF.minusDays(10),
                new BigDecimal("12.34"));

        runner.run(stockId, AS_OF, Optional.of(cutoff), Map.of());

        assertThat(storedCodes())
                .as("억제 효력일이 보존 창보다 오래되면 그 종목은 걷기에서 영구히 면제된다")
                .doesNotContain(EpsGrowthTtmYoyCalculator.CODE);
    }

    @Test
    @DisplayName("보정된 사건은 억제하지 않는다 — 계수가 이미 축척을 하나로 만들었다")
    void appliedActionsDoNotSuppress() {
        insertPriceHistory(250);
        approvedAction("SPLIT", "5", AS_OF.minusDays(190), AS_OF.minusDays(190));

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(storedCodes()).contains(Sma200Calculator.CODE);
    }

    @Test
    @DisplayName("오탐으로 판정된 사건은 억제하지 않는다 — 그런 일이 없었으므로 값이 맞다")
    void rejectedActionsDoNotSuppress() {
        insertPriceHistory(250);
        unadjustedAction(CorporateActionStatus.REJECTED, AS_OF.minusDays(190));

        runner.run(stockId, AS_OF, Optional.empty(), Map.of());

        assertThat(storedCodes()).contains(Sma200Calculator.CODE);
    }

    private void unadjustedAction(CorporateActionStatus status, LocalDate effectiveDate) {
        jdbcTemplate.update("""
                INSERT INTO corporate_action
                    (stock_id, effective_date, type, ratio, detection_source, status)
                VALUES (?, ?, 'UNKNOWN', 5, 'HEURISTIC', ?)
                """, stockId, effectiveDate, status.name());
    }

    /** 권리락에서 가격이 반토막 나고 신주상장에서 주식수가 두 배가 되는 시세. */
    private void insertExRightsHistory(LocalDate exRights, LocalDate newListing) {
        for (int i = 0; i < 60; i++) {
            LocalDate date = AS_OF.minusDays(60L - i);
            BigDecimal close = new BigDecimal(date.isBefore(exRights) ? 2000 : 1000);
            long shares = date.isBefore(newListing) ? 1_000_000L : 2_000_000L;
            jdbcTemplate.update("""
                    INSERT INTO price_daily
                        (stock_id, trade_date, open, high, low, close, adj_close, adj_factor,
                         volume, listed_shares)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1000, ?)
                    """, stockId, date, close, close, close, close, close, shares);
        }
    }

    private void approvedAction(String type, String ratio,
                                LocalDate effectiveDate, LocalDate listingDate) {
        jdbcTemplate.update("""
                INSERT INTO corporate_action
                    (stock_id, effective_date, listing_date, type, ratio,
                     detection_source, status)
                VALUES (?, ?, ?, ?, ?::numeric, 'SOURCE_DISCLOSURE', 'APPROVED')
                """, stockId, effectiveDate, listingDate, type, ratio);
    }

    private List<LocalDate> storedTradeDates() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT trade_date FROM indicator_value WHERE stock_id = ?",
                LocalDate.class, stockId);
    }

    private BigDecimal storedValue(String indicatorCode, LocalDate tradeDate) {
        return jdbcTemplate.queryForObject("""
                SELECT value FROM indicator_value
                WHERE stock_id = ? AND indicator_code = ? AND trade_date = ?
                """, BigDecimal.class, stockId, indicatorCode, tradeDate);
    }

    /** 최근 네 분기의 순이익 합이 음수인 상태. PerCalculator가 루프 앞에서 빈 목록을 낸다. */
    private void insertLossMakingQuarters() {
        LocalDate end = AS_OF.withDayOfMonth(1).minusDays(1);
        for (int i = 0; i < 8; i++) {
            LocalDate periodEnd = end.minusMonths(3L * i);
            jdbcTemplate.update(
                    "INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period,"
                            + " period_start, period_end, published_at, revision_no, is_latest,"
                            + " currency_code, net_income)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 0, true, 'KRW', ?)",
                    stockId, periodEnd.getYear(), "Q" + (i % 4 + 1),
                    periodEnd.minusMonths(3), periodEnd, periodEnd.plusDays(45),
                    new BigDecimal("-1000000000"));
        }
    }

    private void insertIndicatorRow(String indicatorCode, LocalDate tradeDate, BigDecimal value) {
        jdbcTemplate.update(
                "INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)"
                        + " VALUES (?, ?, ?, ?)",
                stockId, tradeDate, indicatorCode, value);
    }

    /**
     * 앞 {@code barsWithShares}봉에만 주식수를 넣는다. MarketCapCalculator가 주식수 없는
     * 날짜를 건너뛰므로 「창의 앞쪽은 내고 뒤쪽은 안 내는」 계산기가 된다.
     */
    private void insertPriceHistoryWithSharesUntil(int days, int barsWithShares) {
        insertPriceHistoryWithSharesUntil(days, barsWithShares, -1);
    }

    private void insertPriceHistoryWithSharesUntil(int days, int barsWithShares, int holeIndex) {
        for (int i = 0; i < days; i++) {
            LocalDate date = AS_OF.minusDays((long) days - i);
            BigDecimal close = new BigDecimal(1000 + (i % 7) * 10);
            if (i < barsWithShares && i != holeIndex) {
                jdbcTemplate.update("""
                        INSERT INTO price_daily
                            (stock_id, trade_date, open, high, low, close, adj_close, adj_factor,
                             volume, listed_shares)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1000, 1000000)
                        """, stockId, date, close, close, close, close, close);
            } else {
                insertBar(date, close, close);
            }
        }
    }

    private BigDecimal storedValueOrNull(String indicatorCode, LocalDate tradeDate) {
        List<BigDecimal> found = jdbcTemplate.queryForList("""
                SELECT value FROM indicator_value
                WHERE stock_id = ? AND indicator_code = ? AND trade_date = ?
                """, BigDecimal.class, stockId, indicatorCode, tradeDate);
        return found.isEmpty() ? null : found.get(0);
    }

    private List<LocalDate> storedDatesOf(String indicatorCode) {
        return jdbcTemplate.queryForList("""
                SELECT trade_date FROM indicator_value
                WHERE stock_id = ? AND indicator_code = ? ORDER BY trade_date
                """, LocalDate.class, stockId, indicatorCode);
    }

    private void insertPriceHistory(int days) {
        for (int i = 0; i < days; i++) {
            LocalDate date = AS_OF.minusDays((long) days - i);
            // 완만하게 오르내리게 해서 RSI·볼린저의 분모가 0이 되지 않게 한다.
            BigDecimal close = new BigDecimal(1000 + (i % 7) * 10);
            insertBar(date, close, close);
        }
    }

    private void insertBar(LocalDate date, BigDecimal close, BigDecimal adjClose) {
        jdbcTemplate.update("""
                INSERT INTO price_daily
                    (stock_id, trade_date, open, high, low, close, adj_close, adj_factor, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1000)
                """,
                stockId, date, close, close, close, close, adjClose);
    }

    private List<String> storedCodes() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT indicator_code FROM indicator_value WHERE stock_id = ?",
                String.class, stockId);
    }

    private int countIndicatorValues() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM indicator_value WHERE stock_id = ?", Integer.class, stockId);
    }
}
