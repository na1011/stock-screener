package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.FlowBasis;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.StatementPeriod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>판 비교의 지배주주 순이익 칸도 매퍼와 같은 규칙으로 읽는다</b>(국내 관문 켬 · 계획 K19-b). 삼성전기 2021 FY 꼴 — 표준 지배 줄 1조 554억이 계속영업 몫이고
 * 자본변동표 지배 열과 비표준 지배 줄이 8,924억이다. 그 해 · 다음 해 판은 같은 틀린 표준 줄을 싣고 두 해 뒤 판이 표준 줄을 8,924억으로 바로잡았다.
 * 표준 줄끼리 견주면 그 바로잡음이 「다시 실었다」로 읽히고, 매퍼가 이미 고른 8,924억은 다시 실린 것이 아니다.
 */
class DartControllingFlowBasisTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("국내 관문 켬: 원장 둘이 반박한 표준 지배 줄의 바로잡음은 「다시 실음」이 아니다 · 꺼짐은 지금처럼 다시 실음(대조군)")
    void correctedControllingLineIsNotARestatementWhenGated() {
        Map<StatementPeriod, DartFullStatementAdapter.Pending> year = Map.of(StatementPeriod.FY,
                new DartFullStatementAdapter.Pending(2021, StatementPeriod.FY, annual("20220308000667", "thstrm_amount", "1055411299515"), true));
        Map<StatementPeriod, DartFullStatementAdapter.Pending> next = Map.of(StatementPeriod.FY,
                new DartFullStatementAdapter.Pending(2022, StatementPeriod.FY, annual("20230307000111", "frmtrm_amount", "1055411299515"), true));
        Map<StatementPeriod, DartFullStatementAdapter.Pending> twoLater = Map.of(StatementPeriod.FY,
                new DartFullStatementAdapter.Pending(2023, StatementPeriod.FY, annual("20240329000222", "bfefrmtrm_amount", "892445316914"), true));
        Map<String, RawFinancialReport> reports = Map.of("FY", report());

        FlowBasis off = DartFullStatementAdapter.flowBases(2021, year, next, twoLater, reports, false).get(0);
        FlowBasis on = DartFullStatementAdapter.flowBases(2021, year, next, twoLater, reports, true).get(0);

        assertThat(off.restated()).as("꺼짐 — 지금 동작").containsKey("net_income_controlling");
        assertThat(on.restated()).doesNotContainKey("net_income_controlling");
        assertThat(on.superseded()).doesNotContainKey("net_income_controlling");
    }

    @Test
    @DisplayName("국내 관문 켬(계획 K12 D1-a · D1-b): 다음 해 판과 닫힌 지배 NI는 닫힘으로 싣고 · 연결 중단만 실은 열의 지배 중단은 0이 아니라 모름 · 꺼짐은 지금처럼")
    void closuresAndControllingDiscontinuedAbsenceWhenGated() {
        Map<String, RawFinancialReport> reports = Map.of("FY", report());
        // 그 해 판: 연결 중단 30 · 지배 중단 25. 다음 해 판의 전기 열: 연결 중단 30 · 지배 중단 줄 없음 · 순이익 · 지배 NI는 같다.
        Map<StatementPeriod, DartFullStatementAdapter.Pending> year = Map.of(StatementPeriod.FY, new DartFullStatementAdapter.Pending(2021,
                StatementPeriod.FY, withDiscontinued("20220308000667", "thstrm_amount", true), true));
        Map<StatementPeriod, DartFullStatementAdapter.Pending> next = Map.of(StatementPeriod.FY, new DartFullStatementAdapter.Pending(2022,
                StatementPeriod.FY, withDiscontinued("20230307000111", "frmtrm_amount", false), true));

        FlowBasis off = DartFullStatementAdapter.flowBases(2021, year, next, Map.of(), reports, false).get(0);
        FlowBasis on = DartFullStatementAdapter.flowBases(2021, year, next, Map.of(), reports, true).get(0);

        assertThat(off.closed()).as("꺼짐은 닫힘을 안 낸다").isEmpty();
        assertThat(off.restated().get("discontinued_income_controlling")).as("꺼짐 — 지금 동작(줄 없음 = 0으로 다시 실음)")
                .extracting(FlowBasis.Restatement::value).containsExactly(BigDecimal.ZERO);
        assertThat(on.closed()).containsOnlyKeys("net_income_controlling", "discontinued_income");
        assertThat(on.closed().get("net_income_controlling")).containsExactly(LocalDate.of(2023, 3, 7));
        assertThat(on.superseded()).doesNotContainKey("discontinued_income_controlling");
        assertThat(on.restated()).doesNotContainKey("discontinued_income_controlling");
    }

    /** 순이익 100억 · 지배 90억 · 비지배 10억 · 연결 중단 30억 · 지배 중단 25억(줄을 실을 때만). */
    private static JsonNode withDiscontinued(String rceptNo, String field, boolean controllingLine) {
        String row = "{\"rcept_no\":\"%s\",\"currency\":\"KRW\",\"sj_div\":\"CIS\",\"account_id\":\"%s\",\"account_nm\":\"%s\",\"%s\":\"%s\"}";
        List<String> rows = new java.util.ArrayList<>(List.of(
                row.formatted(rceptNo, "ifrs-full_ProfitLoss", "당기순이익", field, "10000000000"),
                row.formatted(rceptNo, "ifrs-full_ProfitLossAttributableToOwnersOfParent", "지배기업 소유주", field, "9000000000"),
                row.formatted(rceptNo, "ifrs-full_ProfitLossAttributableToNoncontrollingInterests", "비지배지분", field, "1000000000"),
                row.formatted(rceptNo, "ifrs-full_ProfitLossFromDiscontinuedOperations", "중단영업이익", field, "3000000000")));
        if (controllingLine) {
            rows.add(row.formatted(rceptNo, "ifrs-full_IncomeFromDiscontinuedOperationsAttributableToOwnersOfParent", "지배 중단", field,
                    "2500000000"));
        }
        return MAPPER.readTree("{\"list\":[" + String.join(",", rows) + "]}").get("list");
    }

    /** 사업보고서 응답 — 그 칸에 표준 지배 줄 {@code owners} · 순이익 9,154억 · 비지배 222억(산수가 안 닫히면 표준 줄이 틀렸다) · 비표준 지배 줄과 자본변동표 지배 열 8,924억. */
    private static JsonNode annual(String rceptNo, String field, String owners) {
        String minority = "892445316914".equals(owners) ? "22987135781" : "22249439344";
        return MAPPER.readTree("""
                {"list":[
                  {"rcept_no":"%1$s","currency":"KRW","sj_div":"CIS","account_id":"ifrs-full_ProfitLoss","account_nm":"당기순이익","%2$s":"915432452695"},
                  {"rcept_no":"%1$s","currency":"KRW","sj_div":"CIS","account_id":"ifrs-full_ProfitLossAttributableToOwnersOfParent","account_nm":"지배기업 소유주","%2$s":"%3$s"},
                  {"rcept_no":"%1$s","currency":"KRW","sj_div":"CIS","account_id":"ifrs-full_ProfitLossAttributableToNoncontrollingInterests","account_nm":"비지배지분","%2$s":"%4$s"},
                  {"rcept_no":"%1$s","currency":"KRW","sj_div":"CIS","account_id":"-표준계정코드 미사용-","account_nm":"지배기업의 소유주에게 귀속되는 당기순이익","%2$s":"892445316914"},
                  {"rcept_no":"%1$s","currency":"KRW","sj_div":"SCE","account_id":"ifrs-full_ProfitLoss","account_nm":"당기순이익","account_detail":"자본 [member]|지배기업 소유주지분","%2$s":"892445316914"}
                ]}""".formatted(rceptNo, field, owners, minority)).get("list");
    }

    private static RawFinancialReport report() {
        BigDecimal n = null;
        return new RawFinancialReport(2021, "FY", LocalDate.of(2021, 1, 1), LocalDate.of(2021, 12, 31), LocalDate.of(2022, 3, 8), "11011",
                "KRW", n, n, n, n, n, n, n, n, n, n, n, n, n, n, n, n, n, n, new BigDecimal("892445316914"), n, n, n, n, n, n, n, n, n, n);
    }
}
