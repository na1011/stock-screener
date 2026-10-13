package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.us.SecFilingConsumption.Ground;
import com.stockscreener.pipeline.datasource.us.SecFilingConsumption.OpenFiling;
import com.stockscreener.pipeline.datasource.us.SecFilingConsumption.Verdict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 미국 장부 행의 소비 판정(ADR-0176 2차). 근거 셋과, 열린 채 남는 행의 사유 머리(경보가 가른다)를 잠근다.
 * 표본은 2026-10-13 원장 대조의 꼴이다 — 10-Q는 companyfacts에, 6-K는 submissions의 XBRL 없음으로 닫혔다.
 */
class SecFilingConsumptionTest {

    private static final long CIK = 63754L;
    private static final OpenFiling TEN_Q = new OpenFiling(1, "0000063754-26-000329", "10-Q", LocalDate.of(2026, 10, 1));
    private static final OpenFiling SIX_K = new OpenFiling(2, "0001178913-26-004687", "6-K", LocalDate.of(2026, 10, 1));
    private static final OpenFiling SUPPLEMENTED =
            new OpenFiling(3, "0000063754-26-000400", "10-Q", LocalDate.of(2026, 10, 2));

    @Test
    @DisplayName("companyfacts가 실으면 COMPANYFACTS · 보충이 끝냈으면 SUPPLEMENT — submissions를 묻지 않는다")
    void localGroundsNeedNoCall() throws Exception {
        SecFilingClient client = mock(SecFilingClient.class);

        List<Verdict> verdicts = new SecFilingConsumption(client).judge(CIK, List.of(TEN_Q, SUPPLEMENTED),
                Set.of(TEN_Q.accession()), Map.of(SUPPLEMENTED.accession(), "NO_XBRL"));

        assertThat(verdicts).extracting(Verdict::ground).containsExactly(Ground.COMPANYFACTS, Ground.SUPPLEMENT);
        assertThat(verdicts).extracting(Verdict::evidence).containsExactly(TEN_Q.accession(), "NO_XBRL");
        verify(client, never()).filings(anyLong(), any());
    }

    @Test
    @DisplayName("XBRL 없는 공시는 submissions로 닫는다 — 보충 행이 안 생기는 갈래(대조군: XBRL 있으면 안 닫는다)")
    void noXbrlClosesOnlyWithoutXbrl() throws Exception {
        SecFilingClient client = mock(SecFilingClient.class);
        OpenFiling xbrlTenQ = new OpenFiling(4, "0000063754-26-000500", "10-Q", LocalDate.of(2026, 10, 2));
        when(client.filings(CIK, LocalDate.of(2026, 9, 24))).thenReturn(List.of(
                new SecFilingClient.Filing(SIX_K.accession(), "6-K", SIX_K.filedOn(), SIX_K.filedOn(), false),
                new SecFilingClient.Filing(xbrlTenQ.accession(), "10-Q", xbrlTenQ.filedOn(), LocalDate.of(2026, 8, 31),
                        true)));

        List<Verdict> verdicts = new SecFilingConsumption(client).judge(CIK, List.of(SIX_K, xbrlTenQ), Set.of(),
                Map.of());

        assertThat(verdicts.get(0).ground()).isEqualTo(Ground.NO_XBRL);
        assertThat(verdicts.get(0).evidence()).isEqualTo("isXBRL=0");
        assertThat(verdicts.get(1).consumed()).as("XBRL이 있는데 companyfacts·보충 어디에도 없다 — 먹이지 않았다").isFalse();
        assertThat(verdicts.get(1).reason()).startsWith(SecFilingConsumption.OUR_SIDE);
    }

    @Test
    @DisplayName("열린 채 남는 사유: submissions에 없음은 「원장 아직」 · 보충 실패는 「우리 쪽」")
    void reasonsSplitBySide() {
        List<Verdict> verdicts = SecFilingConsumption.judgeWithSubmissions(List.of(TEN_Q, SUPPLEMENTED),
                List.of(new SecFilingClient.Filing(SUPPLEMENTED.accession(), "10-Q", SUPPLEMENTED.filedOn(),
                        LocalDate.of(2026, 8, 31), true)),
                Map.of(SUPPLEMENTED.accession(), "FAILED"));

        assertThat(verdicts.get(0).reason()).startsWith(SecFilingConsumption.SOURCE_PENDING);
        assertThat(verdicts.get(1).reason()).isEqualTo(SecFilingConsumption.OUR_SIDE + "보충 FAILED");
    }

    @Test
    @DisplayName("보충 FAILED(해석 실패 UNREADABLE 포함 — 종류는 failure_kind)는 근거가 아니다 — 그 공시의 팩트가 이번 출력에 없다")
    void unsettledSupplementIsNotAGround() {
        for (String status : List.of("FAILED")) {
            Verdict v = SecFilingConsumption.judgeLocally(List.of(SUPPLEMENTED), Set.of(),
                    Map.of(SUPPLEMENTED.accession(), status)).get(0);
            assertThat(v.consumed()).as(status).isFalse();
        }
    }

    @Test
    @DisplayName("submissions 조회가 실패해도 던지지 않는다 — 던지면 그 종목의 FR 쓰기까지 되돌아간다")
    void submissionsFailureLeavesRowsOpen() throws Exception {
        SecFilingClient client = mock(SecFilingClient.class);
        when(client.filings(anyLong(), any())).thenThrow(new DataSourceException(DataSourceException.Kind.TRANSIENT,
                SecEdgarResponse.SOURCE_NAME, "끊김"));

        List<Verdict> verdicts = new SecFilingConsumption(client).judge(CIK, List.of(TEN_Q, SIX_K),
                Set.of(TEN_Q.accession()), Map.of());

        assertThat(verdicts.get(0).ground()).isEqualTo(Ground.COMPANYFACTS);
        assertThat(verdicts.get(1).consumed()).isFalse();
        assertThat(verdicts.get(1).reason()).startsWith(SecFilingConsumption.OUR_SIDE + "submissions 조회 실패 TRANSIENT");
    }
}
