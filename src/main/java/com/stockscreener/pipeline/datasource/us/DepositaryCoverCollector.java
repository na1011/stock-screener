package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.us.listing.DepositaryCover;
import com.stockscreener.pipeline.datasource.us.listing.DepositaryRatio;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 판마다 예탁 비율(ADR-0183)의 재료를 받는다 — 관문 켬에서만 {@link SecEdgarFinancialAdapter}가 부른다.
 *
 * <p><b>누구의 표지를 받나</b>: 예탁증권 줄 발행사 — 해외 양식(20-F · 40-F · 6-K)을 내거나, 저장 이름이 예탁증권이거나, 이미 받은 표지가
 * 예탁증권 줄을 싣는다. 그 발행사의 2019 뒤 정기 공시 전부(앵커 후보 판정과 무관 — 클래스 축 판정 종류를 안 바꾼다). 이름이 평범한
 * 10-K ADS(AMRN 꼴)는 이 범위 밖이고 원장 사건으로 기저를 고치는 SHIFTED(ADR-0162)가 맡는다 — 순위 후보 상위 1,300 중 그 꼴은 AMRN
 * 하나였다(2026-10-22 · 3,542콜 탐침).
 *
 * <p>클래스 축 수집기와 같은 받기({@link SecFilingClient#folder} · {@link SecFilingClient#instance} · {@link SecFilingSupplement#pickInstance})를
 * 부르기만 한다. 받기 실패는 종목을 세우지 않고 행으로 남는다(다음 받기가 다시 받는다) · 해석 실패는 판독 판이 같은 동안 다시 안 받는다.
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class DepositaryCoverCollector {

    private static final Logger log = LoggerFactory.getLogger(DepositaryCoverCollector.class);

    /** 받는 창의 시작 — 표지 비율이 실리기 시작한 무렵(2019 뒤 20-F의 표지 · 탐색 v1 §1d). */
    static final LocalDate SINCE = LocalDate.of(2019, 1, 1);
    static final Set<String> PERIODIC = Set.of("10-K", "10-K/A", "10-Q", "10-Q/A", "20-F", "20-F/A", "40-F", "40-F/A");
    private static final Set<String> FOREIGN = Set.of("20-F", "20-F/A", "40-F", "40-F/A", "6-K", "6-K/A");

    /** @param targets 예탁증권 줄 발행사면 받기 대상 정기 공시 수(아니면 0) */
    public record Outcome(boolean depositary, int targets, int fetched, List<String> failed,
                          List<DataSourceException.Kind> unresolved) {
    }

    private final SecFilingClient client;
    private final DepositaryCoverStore store;

    public DepositaryCoverCollector(SecFilingClient client, DepositaryCoverStore store) {
        this.client = client;
        this.store = store;
    }

    public Outcome collect(SecurityRef security, long cik, String context) throws DataSourceException {
        List<SecFilingClient.Filing> filings = client.filings(cik, SINCE);
        if (!depositaryIssuer(security, cik, filings)) {
            return new Outcome(false, 0, 0, List.of(), List.of());
        }
        Map<String, DepositaryCoverStore.CoverRow> stored = store.coverRows(cik);
        int targets = 0;
        int fetched = 0;
        List<String> failed = new ArrayList<>();
        List<DataSourceException.Kind> unresolved = new ArrayList<>();
        for (SecFilingClient.Filing filing : filings) {
            if (!PERIODIC.contains(filing.form()) || !filing.xbrl()) {
                continue;
            }
            targets++;
            DepositaryCoverStore.CoverRow row = stored.get(filing.accession());
            if (row != null && row.readerVersion() == DepositaryCoverStore.READER_VERSION
                    && (!"FAILED".equals(row.status()) || "UNREADABLE".equals(row.failureKind()))) {
                continue;
            }
            String where = context + " depositary cover " + filing.accession();
            try {
                fetch(cik, filing, where);
                fetched++;
            } catch (SecFilingSupplement.UnreadableFiling e) {
                store.saveCover(cik, filing.accession(), filing.form(), filing.filed(), "FAILED", null, "UNREADABLE",
                        e.getMessage());
                failed.add(filing.accession());
                log.warn("예탁 표지를 해석하지 못했다 — 판독 판이 같은 동안 다시 받지 않는다. {} — {}", where, e.getMessage());
            } catch (DataSourceException e) {
                if (e.kind() == DataSourceException.Kind.AUTH) {
                    throw e;
                }
                store.saveCover(cik, filing.accession(), filing.form(), filing.filed(), "FAILED", null, e.kind().name(),
                        e.getMessage());
                failed.add(filing.accession());
                unresolved.add(e.kind());
                log.warn("예탁 표지를 받지 못했다 — 다음 받기가 다시 받는다. {} 종류={} — {}", where, e.kind(), e.getMessage());
                if (e.kind() == DataSourceException.Kind.RATE_LIMITED) {
                    break;
                }
            }
        }
        return new Outcome(true, targets, fetched, failed, unresolved);
    }

    private boolean depositaryIssuer(SecurityRef security, long cik, List<SecFilingClient.Filing> filings) {
        if (filings.stream().anyMatch(f -> FOREIGN.contains(f.form()))) {
            return true;
        }
        if (store.stockName(security.stockId()).map(DepositaryRatio::isDepositary).orElse(false)) {
            return true;
        }
        for (DepositaryCoverStore.Cover cover : store.covers(cik)) {
            DepositaryCover.ReadingKind kind = DepositaryCover.read(ClassFacts.fromJson(cover.facts()), security.ticker()).kind();
            if (kind == DepositaryCover.ReadingKind.RATIO || kind == DepositaryCover.ReadingKind.ABSENT
                    || kind == DepositaryCover.ReadingKind.CONFLICT) {
                return true;
            }
        }
        return false;
    }

    private void fetch(long cik, SecFilingClient.Filing filing, String context)
            throws DataSourceException, SecFilingSupplement.UnreadableFiling {
        List<SecFilingClient.FolderItem> items = client.folder(cik, filing.accession());
        String name = SecFilingSupplement.pickInstance(items.stream().map(SecFilingClient.FolderItem::name).toList(), context);
        if (name == null) {
            store.saveCover(cik, filing.accession(), filing.form(), filing.filed(), "NO_XBRL", null, null, null);
            return;
        }
        SecFilingClient.FolderItem item = items.stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
        byte[] bytes = client.instance(cik, filing.accession(), item);
        ClassFacts facts;
        try {
            facts = ClassFactReader.readListingCover(new ByteArrayInputStream(bytes), cik, context + " " + name);
        } catch (DataSourceException e) {
            throw new SecFilingSupplement.UnreadableFiling(e.getMessage(), e);
        }
        store.saveCover(cik, filing.accession(), filing.form(), filing.filed(), "OK", facts.toJson(), null, null);
    }
}
