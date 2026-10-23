package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>미국 관문 설정이 켜지면 유도 Q4의 지배주주 순이익이 지배주주끼리 뺀 값이 된다</b>(S0 · `docs/reload-gate.md`).
 *
 * <p><b>왜 있는가</b>: 꺼진 상태(관문 전 운영)의 {@code controllingIncomeQ4} 마지막 갈래는
 * {@code fy.netIncome() − Σq.netIncome()}인데 ADR-0159 뒤로 {@code net_income}이 연결이라 Q4 지배주주가 Q4 연결이
 * 된다. 그 결함에 시험이 하나도 안 울었다 — 「Q4도 두 컬럼을 다 만든다」만 물었기 때문이다.
 *
 * <p><b>성질로 묻는다</b>: 켜진 상태에서 유도 Q4의 지배주주 = 같은 공시일 FY 판의 지배주주 − 그때 보이는 분기 판의
 * 지배주주 합. 그러면 같은 해 네 분기의 지배주주 합이 FY 지배주주와 같아진다 — {@code EPS_TTM}이 FY 끝 창에서
 * 연간 값과 맞는 이유다. 픽스처는 실제 companyfacts를 순이익·자본·자산·부채·매출 태그로 자른 것이다.
 */
class SecEdgarControllingQ4GateTest {

    private static final int FROM = 2023;

    private static JsonNode facts(String fixture) throws Exception {
        Path path = Path.of("src/test/resources/fixtures/sec", fixture);
        return new ObjectMapper().readTree(Files.readString(path, StandardCharsets.UTF_8)).path("facts");
    }

    private static List<RawFinancialReport> build(String fixture, boolean usGate) throws Exception {
        return SecEdgarFacts.build(facts(fixture), FROM, usGate).reports();
    }

    private static List<RawFinancialReport> derivedQ4(List<RawFinancialReport> reports) {
        return reports.stream().filter(r -> "Q4".equals(r.fiscalPeriod()) && r.derived()).toList();
    }

    /** 그 라벨의 판 중 {@code at} 이하에서 가장 늦은 것. */
    private static Optional<RawFinancialReport> vintageAt(List<RawFinancialReport> reports, int fiscalYear,
                                                          String period, java.time.LocalDate at) {
        return reports.stream()
                .filter(r -> r.fiscalYear() == fiscalYear && period.equals(r.fiscalPeriod()))
                .filter(r -> !r.publishedAt().isAfter(at))
                .max(Comparator.comparing(RawFinancialReport::publishedAt));
    }

    /** 유도 Q4마다 「FY 지배 − 그때 보이는 분기 지배 합」과 같은지. 비교할 재료가 없는 판은 센 수에서 뺀다. */
    private static int assertResidualIsControlling(List<RawFinancialReport> reports) {
        int checked = 0;
        for (RawFinancialReport q4 : derivedQ4(reports)) {
            if (q4.netIncomeControlling() == null) {
                continue;
            }
            Optional<RawFinancialReport> fy = vintageAt(reports, q4.fiscalYear(), "FY", q4.publishedAt());
            List<Optional<RawFinancialReport>> quarters = List.of(
                    vintageAt(reports, q4.fiscalYear(), "Q1", q4.publishedAt()),
                    vintageAt(reports, q4.fiscalYear(), "Q2", q4.publishedAt()),
                    vintageAt(reports, q4.fiscalYear(), "Q3", q4.publishedAt()));
            if (fy.isEmpty() || fy.get().netIncomeControlling() == null
                    || quarters.stream().anyMatch(q -> q.isEmpty() || q.get().netIncomeControlling() == null)) {
                continue;
            }
            BigDecimal expected = fy.get().netIncomeControlling();
            for (Optional<RawFinancialReport> q : quarters) {
                expected = expected.subtract(q.get().netIncomeControlling());
            }
            assertThat(q4.netIncomeControlling())
                    .as("%d/Q4@%s — FY 지배주주 − 분기 지배주주 합이어야 한다", q4.fiscalYear(), q4.publishedAt())
                    .isEqualByComparingTo(expected);
            checked++;
        }
        return checked;
    }

    @Test
    @DisplayName("설정을 끄면 기존 진입점과 출력이 같다")
    void gateOffMatchesLegacyEntryPoint() throws Exception {
        for (String fixture : List.of("acm-controlling-q4.json", "ccl-controlling-q4.json",
                "ares-controlling-q4.json", "adbe-controlling-q4.json", "bx-income-basis.json")) {
            assertThat(SecEdgarFacts.build(facts(fixture), FROM).reports())
                    .as(fixture + " — 꺼진 설정이 출력을 바꿨다")
                    .isEqualTo(build(fixture, false));
        }
    }

    @Test
    @DisplayName("비지배 회사(ACM형): 꺼지면 Q4 지배주주 = Q4 연결, 켜면 지배주주끼리 뺀다")
    void nonControllingCompanySubtractsControlling() throws Exception {
        List<RawFinancialReport> off = build("acm-controlling-q4.json", false);
        List<RawFinancialReport> on = build("acm-controlling-q4.json", true);

        assertThat(derivedQ4(off)).as("픽스처가 유도 Q4를 안 내면 이 시험은 아무것도 안 잰다").isNotEmpty();
        assertThat(derivedQ4(off))
                .as("꺼진 상태 — 지금 결함의 모양이 그대로 있어야 대조군이 선다")
                .anyMatch(r -> r.netIncome() != null && r.netIncome().equals(r.netIncomeControlling()));
        assertThat(derivedQ4(on))
                .as("켜진 상태 — 유도 Q4의 지배주주가 연결과 갈려야 한다")
                .anyMatch(r -> r.netIncome() != null && r.netIncomeControlling() != null
                        && r.netIncome().compareTo(r.netIncomeControlling()) != 0);
        assertThat(assertResidualIsControlling(on)).as("항등식을 잰 판이 하나도 없다").isPositive();
    }

    @Test
    @DisplayName("연결이 비는 회사(CCL형): 꺼지면 Q4 지배주주도 비고, 켜면 채워진다")
    void consolidatedNullNoLongerEmptiesControlling() throws Exception {
        List<RawFinancialReport> off = build("ccl-controlling-q4.json", false);
        List<RawFinancialReport> on = build("ccl-controlling-q4.json", true);

        assertThat(derivedQ4(off))
                .as("꺼진 상태 — 연결이 null인 해의 Q4 지배주주가 비어 있어야 대조군이 선다")
                .anyMatch(r -> r.netIncome() == null && r.netIncomeControlling() == null);
        assertThat(derivedQ4(on))
                .as("켜진 상태 — 같은 해의 Q4 지배주주가 채워져야 한다")
                .anyMatch(r -> r.netIncome() == null && r.netIncomeControlling() != null);
        assertThat(assertResidualIsControlling(on)).isPositive();
    }

    @Test
    @DisplayName("분기 폴백 섞인 해(ARES형): Q4가 음수여도 FY 지배주주와 네 분기 합이 맞는다 — 가드를 두지 않는다")
    void mixedBasisYearStillSumsToAnnual() throws Exception {
        List<RawFinancialReport> on = build("ares-controlling-q4.json", true);

        // 분기 폴백(그 시점 10-Q에 NetIncomeLoss가 없어 연결이 들어간 분기)이 섞인 해에는 Q4가 그 분기들의
        // 비지배를 흡수해 음수가 될 수 있다. 칸 하나는 틀리지만 같은 해 네 분기 합은 FY 지배주주다 —
        // 「FY는 비지배가 있는데 분기는 지배 = 연결이면 연결 뺄셈으로 되돌린다」는 가드가 소비자 TTM에서
        // 더 틀려서 버렸다(controllingIncomeQ4 javadoc). 그 가드를 다시 넣으면 아래가 붉어진다.
        assertThat(derivedQ4(on))
                .as("픽스처의 2024 Q4가 음수여야 이 시험이 그 형태를 잰다")
                .anyMatch(r -> r.fiscalYear() == 2024 && r.netIncomeControlling() != null
                        && r.netIncomeControlling().signum() < 0);
        assertThat(assertResidualIsControlling(on)).isPositive();
    }

    @Test
    @DisplayName("비지배가 없는 회사: 켜도 바뀌지 않는다 — 켬이 더하는 중단영업 판정(F2b · 이 회사는 0) 말고는")
    void noNoncontrollingCompanyUnchanged() throws Exception {
        List<RawFinancialReport> on = build("adbe-controlling-q4.json", true);
        // 관문 켬은 중단영업 세 칸(ADR-0184)도 싣는다 — 이 시험이 묻는 것은 지배주주 Q4 규칙이라 그 셋은 따로 묻는다.
        assertThat(on)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(
                        "discontinuedIncome", "discontinuedIncomeControlling", "discontinuedRule")
                .isEqualTo(build("adbe-controlling-q4.json", false));
        assertThat(on).allSatisfy(r -> assertThat(r.discontinuedIncome()).isEqualByComparingTo("0"));
    }
}
