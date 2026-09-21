package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>두 순이익 컬럼의 뜻을 잠근다</b> — {@code net_income}은 연결 총액,
 * {@code net_income_controlling}은 지배주주 귀속이다(ADR-0159 §결정).
 *
 * <p><b>왜 이 시험이 있는가</b>: 2026-09-21에 미국 적재가 그 계약을 실행하도록 고쳤는데
 * <b>기존 시험 1,602건이 하나도 안 깨졌다.</b> 뜻이 뒤집혀도 침묵한다는 뜻이고, 그러면
 * 다음 세션이 되돌려도 아무 데서도 안 드러난다.
 *
 * <p><b>픽스처가 {@code BX}인 이유가 이 시험의 전부다.</b> Blackstone은 {@code MinorityInterest}
 * (재무상태표 잔액)를 <b>안 내면서</b> 상환가능 비지배지분 귀속 손익은 <b>낸다.</b> 그래서
 * 폴백 게이트를 자본 축으로 만들면 「비지배가 없다」가 <b>거짓으로 참</b>이 되어 지배주주 값이
 * 연결총액 이름으로 저장된다. 실측(팩트 캐시 1,214종목)으로 그 형태가 <b>54티커</b>이고
 * 편입 기준 38이다. 게이트를 {@code MINORITY_INTEREST}로 되돌리면 아래 두 단언이 붉어진다.
 */
class SecEdgarIncomeBasisTest {

    private static SecEdgarFacts.Result build() throws Exception {
        return buildFrom("bx-income-basis.json");
    }

    private static SecEdgarFacts.Result buildFrom(String fixture) throws Exception {
        Path path = Path.of("src/test/resources/fixtures/sec", fixture);
        return SecEdgarFacts.build(
                new ObjectMapper().readTree(Files.readString(path, StandardCharsets.UTF_8))
                        .path("facts"),
                2023);
    }

    private static List<RawFinancialReport> annual(SecEdgarFacts.Result result) {
        return result.reports().stream().filter(r -> "FY".equals(r.fiscalPeriod())).toList();
    }

    @Test
    @DisplayName("두 컬럼이 갈라져 담기고, 연결이 지배주주보다 크다")
    void splitsConsolidatedFromControlling() throws Exception {
        List<RawFinancialReport> rows = annual(build());
        assertThat(rows).as("픽스처가 연간 행을 하나도 안 내면 이 시험은 아무것도 안 잰다")
                .isNotEmpty();

        List<RawFinancialReport> both = rows.stream()
                .filter(r -> r.netIncome() != null && r.netIncomeControlling() != null)
                .toList();
        assertThat(both).as("두 컬럼이 함께 찬 연간 행이 없다 — 갈라 담기가 안 된 것이다")
                .isNotEmpty();

        for (RawFinancialReport row : both) {
            // 비지배지분이 이익을 가져가므로 연결 총액이 지배주주 귀속보다 크다.
            // 게이트를 자본 축으로 되돌리면 둘이 같아져 이 단언이 붉어진다.
            assertThat(row.netIncome())
                    .as("%d/FY — 연결 총액이 지배주주 귀속과 같거나 작다", row.fiscalYear())
                    .isGreaterThan(row.netIncomeControlling());
        }
    }

    @Test
    @DisplayName("유도한 4분기도 두 컬럼을 다 만든다")
    void fourthQuarterCarriesBothColumns() throws Exception {
        List<RawFinancialReport> q4 = build().reports().stream()
                .filter(r -> "Q4".equals(r.fiscalPeriod()))
                .filter(r -> r.netIncome() != null)
                .toList();
        // Q4가 한쪽만 채우면 TTM 창에서 sumControllingNetIncome이 창 단위로 연결 합에
        // 떨어진다 — 편입 미국 983종목 중 960(97.7%)의 최신 창에 Q4 행이 든다.
        for (RawFinancialReport row : q4) {
            assertThat(row.netIncomeControlling())
                    .as("%d/Q4 — 연결만 있고 지배주주가 비었다", row.fiscalYear())
                    .isNotNull();
        }
    }

    /**
     * <b>폴백 게이트를 양방향으로 잠근다 — 픽스처 둘이 반대 갈래다.</b>
     *
     * <p>둘 다 {@code ProfitLoss}를 <b>안 내고</b> {@code MinorityInterest}도 <b>없다.</b>
     * 갈리는 것은 <b>비지배 손익을 언제까지 냈는가</b> 하나다.
     * <ul>
     *   <li>{@code UHAL} — <b>2026-06-30까지</b> 낸다. 지금도 비지배가 있으므로 연결 총액을
     *       못 만들고 <b>NULL</b>이다. 자본 축으로 되돌리면({@code MinorityInterest}가 없으니)
     *       폴백이 켜져 <b>지배주주 값이 연결총액 이름으로</b> 저장된다.</li>
     *   <li>{@code AXS} — <b>2016-12-31에 끊겼다.</b> 그 뒤 기간은 폴백을 받아야 한다.
     *       게이트를 「한 번도 안 냈는가」로 되돌리면 십 년 전 이력에 발목이 잡혀
     *       <b>값을 만들 수 있는데 안 만든다</b> — 실측으로 그 형태가 89종목이었고
     *       {@code BBY}는 최근 두 자본이 같아 원장이 비지배 0을 확인해 준다.</li>
     * </ul>
     *
     * <p><b>MUST: 두 시험을 함께 둔다.</b> 하나만 두면 게이트를 한쪽으로 밀어도 안 붉는다.
     */
    @Test
    @DisplayName("비지배를 지금도 내면 연결 총액을 비운다 — 지배주주 값으로 채우지 않는다")
    void leavesConsolidatedNullWhileNoncontrollingIsStillReported() throws Exception {
        List<RawFinancialReport> rows = annual(buildFrom("uhal-income-gate.json")).stream()
                .filter(r -> r.netIncomeControlling() != null)
                .toList();
        assertThat(rows).as("픽스처가 그 갈래를 안 태운다").isNotEmpty();
        for (RawFinancialReport row : rows) {
            assertThat(row.netIncome())
                    .as("%d/FY — 비지배를 지금도 내는데 연결 총액에 값이 들어갔다", row.fiscalYear())
                    .isNull();
        }
    }

    @Test
    @DisplayName("비지배가 오래전에 끊겼으면 연결 총액을 채운다 — 옛 이력에 발목 잡히지 않는다")
    void fillsConsolidatedAfterNoncontrollingStopped() throws Exception {
        List<RawFinancialReport> rows = annual(buildFrom("axs-income-gate.json")).stream()
                .filter(r -> r.netIncomeControlling() != null)
                .toList();
        assertThat(rows).as("픽스처가 그 갈래를 안 태운다").isNotEmpty();
        for (RawFinancialReport row : rows) {
            assertThat(row.netIncome())
                    .as("%d/FY — 비지배가 2016에 끊겼는데 연결 총액이 비었다", row.fiscalYear())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("지배주주 자본은 비지배를 뺀 값이라 연결 자본보다 작다")
    void controllingEquityIsSmallerThanTotal() throws Exception {
        List<RawFinancialReport> rows = annual(build()).stream()
                .filter(r -> r.totalEquity() != null && r.totalEquityControlling() != null)
                .toList();
        assertThat(rows).isNotEmpty();
        for (RawFinancialReport row : rows) {
            assertThat(row.totalEquityControlling())
                    .as("%d/FY — 지배주주 자본이 연결 자본보다 크다", row.fiscalYear())
                    .isLessThanOrEqualTo(row.totalEquity());
            assertThat(row.totalEquityControlling()).isNotEqualByComparingTo(BigDecimal.ZERO);
        }
    }
}
