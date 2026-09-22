package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.StatementPeriod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>비지배 전무 게이트의 증거를 어디서 세는가.</b>
 *
 * <p>매퍼 시험({@code DartFullAccountMapperTest})은 {@code gateOpen}을 <b>인자로 받아</b>
 * 네 갈래가 도는지만 잠근다. 여기서 잠그는 것은 <b>그 인자를 어댑터가 올바로 만드는가</b>다.
 *
 * <p><b>왜 갈라야 하는가</b>(2026-09-22 · 라이브 6콜): {@code fetchOne}은 CFS가 비면 OFS로
 * 다시 부르고 그 응답을 그대로 쓴다. 별도재무제표에는 비지배 행이 <b>원리적으로</b> 없으므로
 * 그것을 증거로 세면 게이트가 전원 발화한다. 그런데 {@code 450080 에코프로머티}는 FY의 CFS가
 * {@code 013}이라 OFS만 오는 회사이고 <b>비지배 손익이 41.5%</b>다 — 게이트가 켜지면
 * <b>별도 순이익이 지배주주 값으로 박힌다.</b>
 */
class DartFullStatementGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 손익에 비지배 행이 있는 응답. */
    private static JsonNode withNoncontrolling() {
        return MAPPER.readTree("""
                {"list":[
                  {"account_id":"ifrs-full_ProfitLoss","sj_div":"CIS","thstrm_amount":"1000"},
                  {"account_id":"ifrs-full_ProfitLossAttributableToNoncontrollingInterests","sj_div":"CIS","thstrm_amount":"0"}
                ]}""").get("list");
    }

    /** 지분 구분이 없는 응답. CFS면 「비지배가 없다」의 증거이고 OFS면 아무 증거도 아니다. */
    private static JsonNode withoutSplit() {
        return MAPPER.readTree("""
                {"list":[
                  {"account_id":"ifrs-full_ProfitLoss","sj_div":"CIS","thstrm_amount":"1000"}
                ]}""").get("list");
    }

    @Test
    @DisplayName("CFS 응답의 비지배 행만 증거로 센다 — OFS의 부재는 증거가 아니다")
    void onlyConsolidatedResponsesCountAsEvidence() {
        // 대조군 — 같은 응답을 CFS로 받으면 증거가 된다.
        assertThat(DartFullStatementAdapter.lastNoncontrollingIncome(List.of(
                new DartFullStatementAdapter.Pending(2024, StatementPeriod.FY,
                        withNoncontrolling(), true))))
                .as("CFS에서 비지배 행을 봤으면 그 기간이 마지막 관측이다")
                .isEqualTo(LocalDate.of(2024, 12, 31));

        // 본체 — **같은 응답을 OFS로 받으면 세지 않는다.**
        //
        // MUST: 여기에 「지분 구분이 없는」 OFS를 주면 이 단언은 `continue`를 빼도 통과한다
        // (행이 없으니 어차피 null이다). 방어선을 태우려면 **비지배 행이 있는** 응답을 OFS로
        // 줘야 한다 — 그래야 「OFS는 안 센다」와 「행이 없어서 못 셌다」가 갈린다.
        assertThat(DartFullStatementAdapter.lastNoncontrollingIncome(List.of(
                new DartFullStatementAdapter.Pending(2024, StatementPeriod.FY,
                        withNoncontrolling(), false))))
                .as("OFS로 받은 응답은 증거로 세지 않는다")
                .isNull();
    }

    @Test
    @DisplayName("OFS 응답에서는 게이트가 절대 안 열린다 — 이력이 비어 있어도")
    void gateNeverOpensOnSeparateStatements() {
        DartFullStatementAdapter.Pending separate =
                new DartFullStatementAdapter.Pending(2024, StatementPeriod.FY, withoutSplit(), false);
        assertThat(DartFullStatementAdapter.gateOpen(separate, null))
                .as("이력이 비어도 OFS면 못 연다 — 450080이 정확히 이 모양이다")
                .isFalse();

        // 대조군 — 같은 이력에서 CFS면 열린다.
        DartFullStatementAdapter.Pending consolidated =
                new DartFullStatementAdapter.Pending(2024, StatementPeriod.FY, withoutSplit(), true);
        assertThat(DartFullStatementAdapter.gateOpen(consolidated, null)).isTrue();
    }

    @Test
    @DisplayName("이 기간 뒤로 비지배를 냈으면 닫힌다 — 옛 이력에는 발목 잡히지 않는다")
    void gateFollowsThePeriodNotTheWholeHistory() {
        DartFullStatementAdapter.Pending fy2024 =
                new DartFullStatementAdapter.Pending(2024, StatementPeriod.FY, withoutSplit(), true);

        assertThat(DartFullStatementAdapter.gateOpen(fy2024, LocalDate.of(2025, 12, 31)))
                .as("이 기간 뒤에 비지배를 냈으면 그 기간도 못 연다")
                .isFalse();
        assertThat(DartFullStatementAdapter.gateOpen(fy2024, LocalDate.of(2023, 12, 31)))
                .as("비지배가 이 기간 앞에서 끊겼으면 연다")
                .isTrue();
    }

    /**
     * <b>같은 회계연도 안에서 갈래가 섞이지 않는다.</b> 섞이면
     * {@code deriveFourthQuarterControlling}의 뺄셈이 「직접 태그 − 게이트 값」이 되어
     * 어느 쪽도 아닌 수를 낸다.
     */
    @Test
    @DisplayName("FY가 비지배를 내면 그 해 분기도 전부 닫힌다 — Q4 뺄셈이 안 섞인다")
    void oneFiscalYearNeverMixesBranches() {
        List<DartFullStatementAdapter.Pending> year = List.of(
                new DartFullStatementAdapter.Pending(2024, StatementPeriod.FY,
                        withNoncontrolling(), true),
                new DartFullStatementAdapter.Pending(2024, StatementPeriod.Q1,
                        withoutSplit(), true),
                new DartFullStatementAdapter.Pending(2024, StatementPeriod.Q3,
                        withoutSplit(), true));
        LocalDate last = DartFullStatementAdapter.lastNoncontrollingIncome(year);
        for (DartFullStatementAdapter.Pending p : year) {
            assertThat(DartFullStatementAdapter.gateOpen(p, last))
                    .as("%s가 열렸다 — 같은 해에 비지배가 관측됐는데 갈래가 섞인다", p.period())
                    .isFalse();
        }
    }
}
