package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.indicator.GovernanceMetric;
import com.stockscreener.pipeline.datasource.GovernanceDerivation;
import com.stockscreener.pipeline.datasource.GovernanceEndpoint;
import com.stockscreener.pipeline.datasource.GovernanceFact;
import com.stockscreener.pipeline.datasource.GovernanceReportRow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사건 스트림을 <b>회계연도 하나의 스칼라</b>로 옮기는 규칙(P28). 여기서 틀리면 값이 그럴듯하게
 * 나오므로 화면에서도 배치 로그에서도 안 드러난다.
 */
class AlpacaGovernanceFactMapperTest {

    private final AlpacaGovernanceFactMapper mapper = new AlpacaGovernanceFactMapper();

    /** 6월 결산. 역년으로 자르면 분자와 분모의 기간이 어긋나는 그 자리다(미국 편입의 약 24%). */
    private static final GovernanceDerivation.AnnualPeriod FY2025_JUNE =
            new GovernanceDerivation.AnnualPeriod(2025,
                    LocalDate.of(2024, 7, 1), LocalDate.of(2025, 6, 30),
                    LocalDate.of(2025, 8, 20), new BigDecimal("1000"));

    @Test
    @DisplayName("역년이 아니라 그 회사의 회계연도로 자른다")
    void bucketsByFiscalYearNotCalendarYear() {
        List<GovernanceReportRow> rows = List.of(
                payment(2024, "2024-08-15", "1.00"),   // FY2025 안
                payment(2024, "2024-03-15", "9.99"),   // FY2025 밖(직전 회계연도)
                payment(2025, "2025-05-15", "2.00"),   // FY2025 안
                payment(2025, "2025-09-15", "8.88"));  // FY2025 밖(다음 회계연도)

        List<GovernanceFact> facts = mapper.derive(GovernanceEndpoint.DIVIDEND, rows,
                context(LocalDate.of(2026, 9, 1), FY2025_JUNE));

        assertThat(valueOf(facts, GovernanceMetric.DPS_COMMON))
                .as("회계연도 창 [2024-07-01, 2025-06-30]에 든 둘만 더해야 한다")
                .isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("공시일은 그 회계연도 연간 재무의 최초 공시일이고, 접수번호가 그 날짜다")
    void stampsWithTheFirstAnnualFiling() {
        // 6월 결산 FY2025는 역년 둘에 걸친다. 둘 다 관측해야 부분 합이 아니다.
        List<GovernanceFact> facts = mapper.derive(GovernanceEndpoint.DIVIDEND,
                List.of(noData(2024), payment(2025, "2025-05-15", "2.00")),
                context(LocalDate.of(2026, 9, 1), FY2025_JUNE));

        assertThat(facts).isNotEmpty();
        assertThat(facts.getFirst().publishedAt()).isEqualTo(LocalDate.of(2025, 8, 20));
        assertThat(facts.getFirst().receiptNo())
                .as("정정본 선택이 문자열 비교라 숫자 여덟 자리여야 한다")
                .isEqualTo("20250820");
    }

    @Test
    @DisplayName("배당이 없던 회계연도는 0을 낸다 — 짝은 연간 재무다")
    void emitsZeroForYearsWithoutPayments() {
        List<GovernanceReportRow> rows = List.of(noData(2024), noData(2025));

        List<GovernanceFact> facts = mapper.derive(GovernanceEndpoint.DIVIDEND, rows,
                context(LocalDate.of(2026, 9, 1), FY2025_JUNE));

        assertThat(valueOf(facts, GovernanceMetric.DPS_COMMON)).isEqualByComparingTo("0");
        assertThat(valueOf(facts, GovernanceMetric.CASH_DIVIDEND_TOTAL)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("회계연도가 끝난 지 얼마 안 되면 값을 내지 않는다 — 처리 지연이 남아 있다")
    void refusesYearsThatCouldStillReceiveEvents() {
        // 기말 2025-06-30 + 60일 = 2025-08-29. 수집일이 그 앞이면 다 봤다고 말할 수 없다.
        List<GovernanceFact> facts = mapper.derive(GovernanceEndpoint.DIVIDEND,
                List.of(noData(2024), noData(2025)),
                context(LocalDate.of(2025, 8, 28), FY2025_JUNE));

        assertThat(facts)
                .as("소스의 조회 창이 처리 시각 기준이라 늦게 처리된 배당이 「없다」와 같은 모양이다")
                .isEmpty();
    }

    @Test
    @DisplayName("회계연도가 걸친 역년 중 하나라도 안 받았으면 부분 합을 내지 않는다")
    void refusesWhenAnObservedYearIsMissing() {
        // FY2025(6월 결산)는 2024와 2025에 걸치는데 2025만 받았다.
        List<GovernanceFact> facts = mapper.derive(GovernanceEndpoint.DIVIDEND,
                List.of(payment(2025, "2025-05-15", "2.00")),
                context(LocalDate.of(2026, 9, 1), FY2025_JUNE));

        assertThat(facts)
                .as("부분 합은 결손이 아니라 「배당이 적다」로 읽힌다")
                .isEmpty();
    }

    @Test
    @DisplayName("회계연도 안에 분할이 있으면 공시일 기준 주식수로 환산한다")
    void normalisesPaymentsAcrossSplits() {
        GovernanceDerivation context = new GovernanceDerivation(
                LocalDate.of(2026, 9, 1), List.of(FY2025_JUNE),
                // 2025-01-01에 2:1 분할이 있었다고 본다.
                (from, to) -> from.isBefore(LocalDate.of(2025, 1, 1))
                        && !to.isBefore(LocalDate.of(2025, 1, 1))
                        ? new BigDecimal("2") : BigDecimal.ONE);

        List<GovernanceFact> facts = mapper.derive(GovernanceEndpoint.DIVIDEND,
                List.of(payment(2024, "2024-08-15", "2.00"),
                        payment(2025, "2025-05-15", "1.00")),
                context);

        assertThat(valueOf(facts, GovernanceMetric.DPS_COMMON))
                .as("분할 전 2.00은 분할 후 기준으로 1.00이다 — 안 나누면 그 해 합이 배수만큼 크다")
                .isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("주식수가 없으면 총액을 안 내고 주당배당금은 낸다")
    void skipsTotalWhenShareCountIsUnusable() {
        GovernanceDerivation.AnnualPeriod noShares = new GovernanceDerivation.AnnualPeriod(
                2025, LocalDate.of(2024, 7, 1), LocalDate.of(2025, 6, 30),
                LocalDate.of(2025, 8, 20), null);

        List<GovernanceFact> facts = mapper.derive(GovernanceEndpoint.DIVIDEND,
                List.of(payment(2024, "2024-08-15", "1.00"), noData(2025)),
                context(LocalDate.of(2026, 9, 1), noShares));

        assertThat(valueOf(facts, GovernanceMetric.DPS_COMMON)).isEqualByComparingTo("1.00");
        assertThat(valueOf(facts, GovernanceMetric.CASH_DIVIDEND_TOTAL))
                .as("총액은 재구성값이라 재료가 온전할 때만 낸다")
                .isNull();
    }

    @Test
    @DisplayName("special·foreign을 둘 다 더한다")
    void countsSpecialAndForeignDividends() {
        GovernanceReportRow special = payment(2024, "2024-08-15", "1.00");
        GovernanceReportRow foreign = payment(2025, "2025-05-15", "2.00");

        List<GovernanceFact> facts = mapper.derive(GovernanceEndpoint.DIVIDEND,
                List.of(flagged(special, "special"), flagged(foreign, "foreign")),
                context(LocalDate.of(2026, 9, 1), FY2025_JUNE));

        assertThat(valueOf(facts, GovernanceMetric.DPS_COMMON))
                .as("foreign은 발행사가 외국이라는 뜻이고 rate는 USD다(실측)")
                .isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("수집일을 모르면 아무것도 내지 않는다")
    void refusesWithoutACollectionDate() {
        GovernanceDerivation blind = new GovernanceDerivation(
                null, List.of(FY2025_JUNE), (from, to) -> BigDecimal.ONE);

        assertThat(mapper.derive(GovernanceEndpoint.DIVIDEND,
                List.of(payment(2024, "2024-08-15", "1.00")), blind)).isEmpty();
    }

    @Test
    @DisplayName("배당 말고 다른 표를 물으면 아무것도 내지 않는다")
    void ignoresOtherEndpoints() {
        assertThat(mapper.derive(GovernanceEndpoint.SHARE_TOTALS,
                List.of(payment(2025, "2025-05-15", "1.00")),
                context(LocalDate.of(2026, 9, 1), FY2025_JUNE))).isEmpty();
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private static GovernanceDerivation context(LocalDate collectedOn,
                                                GovernanceDerivation.AnnualPeriod... periods) {
        return new GovernanceDerivation(collectedOn, List.of(periods),
                (from, to) -> BigDecimal.ONE);
    }

    /** 어댑터가 내는 결제 행. {@code publishedAt}이 {@code ex_date}다. */
    private static GovernanceReportRow payment(int bsnsYear, String exDate, String rate) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("symbol", "TEST");
        fields.put("ex_date", exDate);
        fields.put("rate", rate);
        return new GovernanceReportRow(GovernanceEndpoint.DIVIDEND, bsnsYear,
                AlpacaDividendAdapter.REPORT_CODE, exDate.replace("-", ""),
                LocalDate.parse(exDate), 0, fields);
    }

    private static GovernanceReportRow flagged(GovernanceReportRow row, String flag) {
        Map<String, String> fields = new LinkedHashMap<>(row.fields());
        fields.put(flag, "true");
        return new GovernanceReportRow(row.endpoint(), row.bsnsYear(), row.reportCode(),
                row.receiptNo(), row.publishedAt(), row.ordinal(), fields);
    }

    /** 그 해에 사건이 0건이었다는 행. 어댑터가 접수번호를 찍어 낸다. */
    private static GovernanceReportRow noData(int bsnsYear) {
        return GovernanceReportRow
                .noData(GovernanceEndpoint.DIVIDEND, bsnsYear, AlpacaDividendAdapter.REPORT_CODE)
                .stampedWith(bsnsYear + "1231", null);
    }

    private static BigDecimal valueOf(List<GovernanceFact> facts, GovernanceMetric metric) {
        List<BigDecimal> found = new ArrayList<>();
        facts.stream().filter(fact -> fact.metric() == metric)
                .forEach(fact -> found.add(fact.value()));
        return found.isEmpty() ? null : found.getFirst();
    }
}
