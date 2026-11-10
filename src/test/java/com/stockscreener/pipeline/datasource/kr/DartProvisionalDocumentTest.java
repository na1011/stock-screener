package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DART 웹 뷰어 원문(2026-11-10 받은 그대로 · {@code src/test/resources/dart-prov/}) → 서식 값. 갈래마다 원문 한 장이다 — 연결 분기 ·
 * 정정(정정 표 뒤 서식) · 별도 · 월간 · I001 · I001 정정.
 */
class DartProvisionalDocumentTest {

    @Test
    @DisplayName("삼성전자 3분기 첫 잠정 — 조원 · 당기와 누계 기간 · 매출 · 영업이익만(나머지 칸 -)")
    void consolidatedQuarter() throws Exception {
        DartProvisionalDocument.Parsed p = parse("20261008800004", "연결재무제표기준영업(잠정)실적(공정공시)");

        assertThat(p.form()).isEqualTo(DartProvisionalDocument.Form.I002);
        assertThat(p.consolidated()).isTrue();
        assertThat(p.periodStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(p.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(p.cumulativeStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(p.releasePeriod()).isEqualTo(DartProvisionalDocument.ReleasePeriod.QUARTER);
        assertThat(p.unitWon()).isEqualTo(1_000_000_000_000L);
        assertThat(p.current().revenue()).isEqualByComparingTo("195.00");
        assertThat(p.current().revenue().scale()).as("끝자리가 반올림 폭이다").isEqualTo(2);
        assertThat(p.cumulative().revenue()).isEqualByComparingTo("500.37");
        assertThat(p.current().operatingIncome()).isEqualByComparingTo("107.40");
        assertThat(p.current().netIncome()).isNull();
        assertThat(p.cumulative().netIncomeControlling()).isNull();
    }

    @Test
    @DisplayName("삼성전자 3분기 정정 — 정정전 · 정정후 표 뒤의 고친 서식을 읽는다(정정 표의 195.00이 아니라 195.25)")
    void correctionReadsTheRestatedForm() throws Exception {
        DartProvisionalDocument.Parsed p = parse("20261029800099", "[기재정정]연결재무제표기준영업(잠정)실적(공정공시)");

        assertThat(p.current().revenue()).isEqualByComparingTo("195.25");
        assertThat(p.cumulative().revenue()).isEqualByComparingTo("500.63");
        assertThat(p.current().operatingIncome()).isEqualByComparingTo("107.44");
    }

    @Test
    @DisplayName("046890 2분기 정정 — 단위가 「구분(단위 : 백만원, %)」 칸에 붙어 온다 · 다섯 줄 전부 · 음수 없음")
    void correctionWithAllLines() throws Exception {
        DartProvisionalDocument.Parsed p = parse("20260811900428", "[기재정정]연결재무제표기준영업(잠정)실적(공정공시)");

        assertThat(p.unitWon()).isEqualTo(1_000_000L);
        assertThat(p.current().revenue()).isEqualByComparingTo("292104");
        assertThat(p.cumulative().revenue()).isEqualByComparingTo("530273");
        assertThat(p.current().profitBeforeTax()).isEqualByComparingTo("3888");
        assertThat(p.current().netIncome()).isEqualByComparingTo("6194");
        assertThat(p.current().netIncomeControlling()).isEqualByComparingTo("2792");
        assertThat(p.cumulative().netIncomeControlling()).isEqualByComparingTo("7747");
    }

    @Test
    @DisplayName("005070 별도 양식 — 제목에 연결이 없다 · 누계 음수(-2,319)")
    void separateForm() throws Exception {
        DartProvisionalDocument.Parsed p = parse("20260722800226", "영업(잠정)실적(공정공시)");

        assertThat(p.consolidated()).isFalse();
        assertThat(p.current().profitBeforeTax()).isEqualByComparingTo("2288");
        assertThat(p.cumulative().profitBeforeTax()).isEqualByComparingTo("-2319");
        assertThat(p.cumulative().netIncome()).isEqualByComparingTo("-1764");
        assertThat(p.current().netIncomeControlling()).isNull();
    }

    @Test
    @DisplayName("옛 서식(2025-07 삼성전자 2분기) — 「실적기간」 표 없이 당기 칸 머리의 분기 표지('25.2Q)로 기간을 읽고 누계는 연초부터")
    void oldFormReadsTheQuarterLabel() throws Exception {
        DartProvisionalDocument.Parsed p = parse("20250708800003", "연결재무제표기준영업(잠정)실적(공정공시)");

        assertThat(p.periodStart()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(p.periodEnd()).isEqualTo(LocalDate.of(2025, 6, 30));
        assertThat(p.cumulativeStart()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(p.current().revenue()).isEqualByComparingTo("74.00");
        assertThat(p.cumulative().revenue()).isEqualByComparingTo("153.14");
        assertThat(p.current().operatingIncome()).isEqualByComparingTo("4.60");
    }

    @Test
    @DisplayName("기아 월간 실적 — 같은 제목이지만 본문 기간이 한 달이라 실적 발표가 아니다")
    void monthlyIsNotARelease() throws Exception {
        DartProvisionalDocument.Parsed p = parse("20261102800493", "영업(잠정)실적(공정공시)");

        assertThat(p.periodStart()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(p.periodEnd()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(p.releasePeriod()).isEqualTo(DartProvisionalDocument.ReleasePeriod.MONTH);
    }

    @Test
    @DisplayName("옛 서식의 기간 표지 꼴 넷 — 날짜 구간 · 연도 앞 분기 · 분기 앞 연도 · 월(2026-11-10 캐시 전수에서 본 꼴)")
    void oldFormLabels() {
        LocalDate q2Start = LocalDate.of(2025, 4, 1);
        LocalDate q2End = LocalDate.of(2025, 6, 30);
        for (String label : new String[]{"('25.2Q)", "(2025.2Q)", "(2025-2Q)", "(25.2분기)", "('25년2분기)", "(2025년2분기)", "(2Q25)",
                "(2Q'25)", "(2025.4.1~2025.6.30)", "('25.4.1~'25.6.30)", "(2025-04-01~2025-06-30)", "('25.Q2)", "(2025년2/4분기)", "('25Y2Q)", "('25.4.1~6.30)", "(2025.04.01.~2025.06.30.)", "2025.04.01~2025.06.30"}) {
            assertThat(DartProvisionalDocument.periodOfLabel(label)).as(label).containsExactly(q2Start, q2End);
        }
        assertThat(DartProvisionalDocument.periodOfLabel("(2025.8월)")).containsExactly(LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 31));
        assertThat(DartProvisionalDocument.periodOfLabel("(-)")).isNull();
        assertThat(DartProvisionalDocument.periodOfLabel("()")).isNull();
        assertThat(DartProvisionalDocument.periodOfLabel("(제71기2분기)")).as("회사 기수는 달력 연도가 아니다").isNull();
    }

    @Test
    @DisplayName("기간 꼴 — 한 달 안만 월간이고, 잘못 적힌 기간(2026-03-01 ~ 06-30 · 098460)은 월간이 아니다(발표로 남는다)")
    void periodKinds() {
        assertThat(DartProvisionalDocument.periodOf(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 6, 30)))
                .isEqualTo(DartProvisionalDocument.ReleasePeriod.OTHER);
        assertThat(DartProvisionalDocument.periodOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .isEqualTo(DartProvisionalDocument.ReleasePeriod.MONTH);
        assertThat(DartProvisionalDocument.periodOf(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30)))
                .isEqualTo(DartProvisionalDocument.ReleasePeriod.QUARTER);
        assertThat(DartProvisionalDocument.periodOf(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)))
                .isEqualTo(DartProvisionalDocument.ReleasePeriod.YEAR);
    }

    @Test
    @DisplayName("LG유플러스 I001 — 연결 · 결산기간 · 천원 · 손익 넷(지배 순이익 줄은 없다)")
    void structureChange() throws Exception {
        DartProvisionalDocument.Parsed p = parse("20260120800764", "매출액또는손익구조30%(대규모법인은15%)이상변경");

        assertThat(p.form()).isEqualTo(DartProvisionalDocument.Form.I001);
        assertThat(p.consolidated()).isTrue();
        assertThat(p.periodStart()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(p.periodEnd()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(p.releasePeriod()).isEqualTo(DartProvisionalDocument.ReleasePeriod.YEAR);
        assertThat(p.unitWon()).isEqualTo(1_000L);
        assertThat(p.current().revenue()).isEqualByComparingTo("15451744694");
        assertThat(p.current().operatingIncome()).isEqualByComparingTo("892131156");
        assertThat(p.current().profitBeforeTax()).isEqualByComparingTo("680456371");
        assertThat(p.current().netIncome()).isEqualByComparingTo("509225618");
        assertThat(p.current().netIncomeControlling()).isNull();
        assertThat(p.cumulative()).isEqualTo(p.current());
    }

    @Test
    @DisplayName("I001 정정 — 정정 표 뒤의 서식(마지막 「재무제표의 종류」)을 읽는다")
    void structureChangeCorrection() throws Exception {
        DartProvisionalDocument.Parsed p = parse("20260126800053", "[기재정정]매출액또는손익구조30%(대규모법인은15%)이상변경");

        assertThat(p.form()).isEqualTo(DartProvisionalDocument.Form.I001);
        assertThat(p.periodEnd()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(p.current().revenue()).isNotNull();
        assertThat(p.current().netIncome()).isNotNull();
    }

    @Test
    @DisplayName("서식이 아닌 본문은 사유를 단 「못 읽음」이다 — 빈 값으로 접지 않는다")
    void unreadableCarriesAReason() {
        assertThatThrownBy(() -> DartProvisionalDocument.parse("<html><body>점검 중</body></html>".getBytes(), "영업(잠정)실적(공정공시)"))
                .isInstanceOf(DartProvisionalDocument.Unreadable.class).hasMessage("no-anchor");
    }

    @Test
    @DisplayName("칸의 수 — 쉼표 · △ · 괄호 음수 · - 없음")
    void numbers() {
        assertThat(DartProvisionalDocument.number("1,234")).isEqualByComparingTo("1234");
        assertThat(DartProvisionalDocument.number("△1,234")).isEqualByComparingTo("-1234");
        assertThat(DartProvisionalDocument.number("(12.5)")).isEqualByComparingTo("-12.5");
        assertThat(DartProvisionalDocument.number("-4,607")).isEqualByComparingTo("-4607");
        assertThat(DartProvisionalDocument.number("-")).isNull();
        assertThat(DartProvisionalDocument.number("")).isNull();
        assertThat(DartProvisionalDocument.number(new BigDecimal("1").toString())).isEqualByComparingTo("1");
    }

    private static DartProvisionalDocument.Parsed parse(String rceptNo, String reportName) throws Exception {
        return DartProvisionalDocument.parse(fixture(rceptNo), reportName);
    }

    private static byte[] fixture(String rceptNo) throws IOException {
        try (InputStream in = DartProvisionalDocumentTest.class.getResourceAsStream("/dart-prov/" + rceptNo + ".html")) {
            assertThat(in).as(rceptNo).isNotNull();
            return in.readAllBytes();
        }
    }
}
