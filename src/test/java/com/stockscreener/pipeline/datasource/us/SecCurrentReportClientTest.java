package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.us.SecCurrentReportClient.CurrentReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * submissions의 8-K 항목(ADR-0195). 열 이름과 값 꼴은 2026-10-30 GOOGL(CIK 1652044) 원장 응답에서 옮겼다 — 실적 8-K는
 * {@code 2.02,9.01}, 10-Q의 항목은 빈 문자열이다.
 */
class SecCurrentReportClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("8-K · 8-K/A만 낸다 — since 앞은 빼고 항목은 원장 그대로다")
    void keepsCurrentReportsSince() throws Exception {
        var table = mapper.readTree("""
                {"accessionNumber":["0001652044-26-000090","0001652044-26-000100","0001652044-26-000050","0001652044-25-000010"],
                 "form":["8-K","10-Q","8-K/A","8-K"],
                 "filingDate":["2026-10-28","2026-10-29","2026-08-01","2025-06-01"],
                 "items":["2.02,9.01","","5.02","2.02,9.01"]}
                """);

        assertThat(SecCurrentReportClient.parse(table, LocalDate.of(2025, 7, 1), "t"))
                .extracting(CurrentReport::accession, CurrentReport::form, CurrentReport::items)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("0001652044-26-000090", "8-K", "2.02,9.01"),
                        org.assertj.core.groups.Tuple.tuple("0001652044-26-000050", "8-K/A", "5.02"));
    }

    @Test
    @DisplayName("항목 열 길이가 다르면 본문 이상이다 — 어긋나면 남의 항목이 붙는다")
    void rejectsMisalignedColumns() throws Exception {
        var table = mapper.readTree("""
                {"accessionNumber":["a","b"],"form":["8-K","8-K"],"filingDate":["2026-10-28","2026-10-29"],"items":["2.02"]}
                """);

        assertThatThrownBy(() -> SecCurrentReportClient.parse(table, LocalDate.of(2025, 7, 1), "t"))
                .isInstanceOf(DataSourceException.class)
                .extracting(e -> ((DataSourceException) e).kind()).isEqualTo(DataSourceException.Kind.MALFORMED);
    }

    @Test
    @DisplayName("실적 발표는 항목 2.02가 있는 것이다 — 2.020 같은 접두 일치는 아니다")
    void earningsReleaseIsItem202() {
        assertThat(CurrentReport.isEarningsRelease("2.02,9.01")).isTrue();
        assertThat(CurrentReport.isEarningsRelease("7.01, 2.02")).isTrue();
        assertThat(CurrentReport.isEarningsRelease("5.02,9.01")).isFalse();
        assertThat(CurrentReport.isEarningsRelease("")).isFalse();
        assertThat(CurrentReport.isEarningsRelease(null)).isFalse();
    }
}
