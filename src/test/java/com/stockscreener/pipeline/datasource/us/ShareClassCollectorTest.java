package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts;
import com.stockscreener.pipeline.datasource.us.shareclass.IssuerFacts;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 클래스 축 인스턴스 받기 실패가 표면에 닿는지(ADR-0179 · 후보 손 FR 계획 v2 반증) — 실패는 행과 종류로 남고, 한도에 걸린 뒤의 공시도
 * 콜 없이 같은 종류의 실패 행이 된다. 예전에는 로그 한 줄로 끝나 시장 판정·공시 소비 어디에도 안 닿았다.
 */
class ShareClassCollectorTest {

    private static final long CIK = 42L;

    @Test
    @DisplayName("한도에 걸리면 남은 앵커 후보를 묻지 않고 RATE_LIMITED 실패 행으로 적는다 — 미해결이 표 하나로 선다")
    void rateLimitRecordsTheRestWithoutCalling() throws Exception {
        SecFilingClient client = mock(SecFilingClient.class);
        ShareClassStore store = mock(ShareClassStore.class);
        when(store.supplementedFilings(CIK)).thenReturn(Map.of());
        when(store.factRows(CIK)).thenReturn(Map.of());
        when(client.folder(anyLong(), anyString())).thenThrow(new DataSourceException(
                DataSourceException.Kind.RATE_LIMITED, "sec-edgar", "429"));

        ShareClassCollector.Outcome outcome = new ShareClassCollector(client, store)
                .fetchInstances(CIK, issuer("A1", "A2", "A3"), "test");

        verify(client, times(1)).folder(anyLong(), anyString());
        verify(store, times(3)).saveFact(eq(CIK), anyString(), eq("10-Q"), any(LocalDate.class), eq("FAILED"), isNull(),
                eq("RATE_LIMITED"), anyString());
        assertThat(outcome.targets()).isEqualTo(3);
        assertThat(outcome.fetched()).isZero();
        assertThat(outcome.failed()).containsExactly("A1", "A2", "A3");
        assertThat(outcome.unresolved()).containsOnly(DataSourceException.Kind.RATE_LIMITED).hasSize(3);
    }

    @Test
    @DisplayName("일시 실패는 미해결로 싣고 다음 공시를 계속 묻는다 — 대조군: 저장된 OK 행은 다시 묻지 않는다")
    void transientFailureIsUnresolvedAndStoredRowsAreSkipped() throws Exception {
        SecFilingClient client = mock(SecFilingClient.class);
        ShareClassStore store = mock(ShareClassStore.class);
        when(store.supplementedFilings(CIK)).thenReturn(Map.of());
        when(store.factRows(CIK)).thenReturn(Map.of("A1", new ShareClassStore.FactRow("A1",
                com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader.READER_VERSION, "OK", null)));
        when(client.folder(anyLong(), anyString())).thenThrow(new DataSourceException(
                DataSourceException.Kind.TRANSIENT, "sec-edgar", "502"));

        ShareClassCollector.Outcome outcome = new ShareClassCollector(client, store)
                .fetchInstances(CIK, issuer("A1", "A2", "A3"), "test");

        verify(client, times(2)).folder(anyLong(), anyString());
        assertThat(outcome.failed()).containsExactly("A2", "A3");
        assertThat(outcome.unresolved()).containsExactly(DataSourceException.Kind.TRANSIENT,
                DataSourceException.Kind.TRANSIENT);
    }

    @Test
    @DisplayName("축 없는 표지가 있는데 companyfacts에 당기 분모가 없는 공시도 받는다(ADR-0189) — 대조군: 당기 분모가 있으면 안 받는다")
    void currentPathDenominatorCandidatesAreFetched() throws Exception {
        SecFilingClient client = mock(SecFilingClient.class);
        ShareClassStore store = mock(ShareClassStore.class);
        when(store.supplementedFilings(CIK)).thenReturn(Map.of());
        when(store.factRows(CIK)).thenReturn(Map.of());
        when(client.folder(anyLong(), anyString())).thenThrow(new DataSourceException(
                DataSourceException.Kind.TRANSIENT, "sec-edgar", "502"));
        Map<String, IssuerFacts.Filing> filings = new LinkedHashMap<>();
        filings.put("D1", new IssuerFacts.Filing("10-Q", LocalDate.of(2026, 7, 27)));
        filings.put("D2", new IssuerFacts.Filing("10-Q", LocalDate.of(2026, 4, 24)));
        IssuerFacts.Key quarter = new IssuerFacts.Key("D1",
                new ClassFacts.Period(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30)));
        IssuerFacts.Key earlier = new IssuerFacts.Key("D2",
                new ClassFacts.Period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)));
        // D2는 분자도 companyfacts로 선다(NI = EPS × 분모) — 분자 후보(ADR-0191)로 받히지 않게 해 이 시험이 분모 축만 잰다.
        IssuerFacts cf = new IssuerFacts(filings, Map.of(earlier, 992_000_000d), Map.of(earlier, 2.00), Map.of(),
                Map.of(quarter, 450_000_000d), Map.of("D1", 992_674_071d, "D2", 992_068_697d), IssuerFacts.Witnesses.NONE,
                new IssuerFacts.Numerators(Map.of(earlier, 2.00), Map.of(), Map.of(quarter, 450_000_000d, earlier, 1_984_000_000d),
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));

        ShareClassCollector.Outcome outcome = new ShareClassCollector(client, store).fetchInstances(CIK, cf, "test");

        assertThat(outcome.failed()).as("D1은 순이익만 있고 분모가 축 맥락에만 있다(BKR 꼴) · D2는 분모가 있다").containsExactly("D1");
    }

    /** 표지 주식수 없는 2020-09 뒤 10-Q들 — 전부 앵커 후보다. */
    private static IssuerFacts issuer(String... accessions) {
        Map<String, IssuerFacts.Filing> filings = new LinkedHashMap<>();
        LocalDate filed = LocalDate.of(2025, 5, 1);
        for (String a : accessions) {
            filings.put(a, new IssuerFacts.Filing("10-Q", filed));
            filed = filed.plusMonths(3);
        }
        return new IssuerFacts(filings, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }
}
