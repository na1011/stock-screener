package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DART 웹 「오늘의 공시」 한 쪽(ADR-0204) — 모양은 2025-11-14 2/31쪽(POST search.ax)에서 옮겼다. */
class DartDayListTest {

    private static final String PAGE = """
            <input type="hidden" name="totalCnt" id="totalCnt" value="3095"/>
            <table summary="시간,공시대상회사,보고서명,제출인,접수일자,비고"><tbody>
            <tr> <td> 17:34 </td> <td class="tL"> <span class="innerWrapTag"> <span class="tagCom_kospi" title="유가증권시장">유</span>
             <a href="javascript:openCorpInfoNew('00111722', 'winCorpInfo', '/dsae001/selectPopup.ax');" title="미래에셋증권 기업개황 새창" > 미래에셋증권 </a> </span> </td>
             <td class="tL"> <a href="/dsaf001/main.do?rcpNo=20251114003029" onclick="openReportViewer('20251114003029'); return false;" id="r_20251114003029" title="일괄신고서(파생결합증권-주가연계증권) 공시뷰어 새창" ><span class="txtCB">[기재정정]</span>일괄신고서(파생결합증권-주가연계증권) </a> </td>
             <td class="tL ellipsis" title="미래에셋증권">미래에셋증권</td> <td>2025.11.14</td> <td></td> </tr>
            <tr> <td> 17:15 </td> <td class="tL"> <span class="innerWrap"> <span class="tagCom_etc" title="기타법인">기</span>
             <a href="javascript:openCorpInfoNew('00383019', 'winCorpInfo', '/dsae001/selectPopup.ax');" title="한국서부발전 기업개황 새창" > 한국서부발전 </a> </span> </td>
             <td class="tL"> <a href="/dsaf001/main.do?rcpNo=20251114003042" onclick="openReportViewer('20251114003042'); return false;" id="r_20251114003042" title="분기보고서 (2025.09) 공시뷰어 새창" >분기보고서 (2025.09) </a> </td>
             <td class="tL ellipsis" title="한국서부발전">한국서부발전</td> <td>2025.11.14</td> <td></td> </tr>
            </tbody></table>
            <div class="pageInfo">[2/31] [총 3,095건]</div>
            """;

    @Test
    @DisplayName("한 쪽 — 줄마다 접수번호 · 기업 고유번호 · 보고서명 · 접수일자 · 게시 시각(분) · 총 건수 · 쪽 수")
    void parsesRowsAndTotals() throws Exception {
        DartDayList.Page page = DartDayList.parse(PAGE, "시험");
        assertThat(page.total()).isEqualTo(3095);
        assertThat(page.pages()).isEqualTo(31);
        assertThat(page.rows()).hasSize(2);
        DartDayList.Row q = page.rows().get(1);
        assertThat(q.rceptNo()).isEqualTo("20251114003042");
        assertThat(q.corpCode()).isEqualTo("00383019");
        assertThat(q.corpName()).isEqualTo("한국서부발전");
        assertThat(q.reportName()).isEqualTo("분기보고서 (2025.09)");
        assertThat(q.receiptDate()).isEqualTo(LocalDate.of(2025, 11, 14));
        assertThat(q.time()).isEqualTo(LocalTime.of(17, 15));
        assertThat(page.rows().get(0).reportName()).as("정정 표지는 보고서명이 아니다").isEqualTo("일괄신고서(파생결합증권-주가연계증권)");
    }

    @Test
    @DisplayName("공시 줄인데 칸을 못 읽으면 던진다 — 조용히 빼면 그 공시가 「시각 모름」이 되고 완결 판정이 못 본다 · 총 건수가 없어도 던진다")
    void brokenRowsThrow() {
        assertThatThrownBy(() -> DartDayList.parse(PAGE.replace("<td>2025.11.14</td>", "<td></td>"), "시험"))
                .isInstanceOf(DataSourceException.class);
        assertThatThrownBy(() -> DartDayList.parse(PAGE.replace("name=\"totalCnt\"", "name=\"x\""), "시험"))
                .isInstanceOf(DataSourceException.class);
    }

    @Test
    @DisplayName("보고서명에 이스케이프 안 된 큰따옴표가 들어도 읽는다(2019-07-15 MDS스피어)")
    void quotesInsideTheReportName() throws Exception {
        String row = PAGE.replace("title=\"분기보고서 (2025.09) 공시뷰어 새창\"",
                "title=\"투자판단관련주요경영사항(인도네시아 \"Super K-POP Festival\" 계약 체결) 공시뷰어 새창\"");
        DartDayList.Page page = DartDayList.parse(row, "시험");
        assertThat(page.rows().get(1).reportName()).isEqualTo("투자판단관련주요경영사항(인도네시아 \"Super K-POP Festival\" 계약 체결)");
    }
}
