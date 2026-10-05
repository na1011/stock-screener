package com.stockscreener.pipeline.datasource.us;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** DB 없는 보충 원장 — 스윕용(ADR-0173). 운영 표와 같은 상태(OK · NO_XBRL · FAILED · COVERED)와 키((접수번호, CIK))를 흉내 낸다. */
final class MemorySupplementLedger implements SupplementLedger {

    private final Map<String, SecFilingSupplementStore.Row> rows = new HashMap<>();

    @Override
    public Map<String, SecFilingSupplementStore.Row> find(long cik, Collection<String> accessions) {
        Map<String, SecFilingSupplementStore.Row> out = new HashMap<>();
        for (String accession : accessions) {
            SecFilingSupplementStore.Row row = rows.get(key(accession, cik));
            if (row != null) {
                out.put(accession, row);
            }
        }
        return out;
    }

    @Override
    public void saveOk(SecFilingClient.Filing filing, long cik, String fileName, long fileSize,
                       XbrlInstanceReader.Instance instance) {
        rows.put(key(filing.accession(), cik), new SecFilingSupplementStore.Row(filing.accession(), cik, filing.form(),
                filing.filed(), XbrlInstanceReader.READER_VERSION, "OK", instance.fiscalYearFocus(),
                instance.fiscalPeriodFocus(), instance.facts(), null));
    }

    @Override
    public void saveFailed(SecFilingClient.Filing filing, long cik, String failureKind, String message) {
        rows.put(key(filing.accession(), cik), new SecFilingSupplementStore.Row(filing.accession(), cik, filing.form(),
                filing.filed(), XbrlInstanceReader.READER_VERSION, "FAILED", null, null, List.of(), failureKind));
    }

    @Override
    public void saveNoXbrl(SecFilingClient.Filing filing, long cik) {
        rows.put(key(filing.accession(), cik), new SecFilingSupplementStore.Row(filing.accession(), cik, filing.form(),
                filing.filed(), XbrlInstanceReader.READER_VERSION, "NO_XBRL", null, null, List.of(), null));
    }

    @Override
    public Map<String, SecFilingSupplementStore.Row> rowsOf(long cik) {
        Map<String, SecFilingSupplementStore.Row> out = new HashMap<>();
        rows.values().stream().filter(r -> r.cik() == cik).forEach(r -> out.put(r.accession(), r));
        return out;
    }

    @Override
    public void markCovered(String accession, long cik) {
        SecFilingSupplementStore.Row r = rows.get(key(accession, cik));
        if (r != null && !"COVERED".equals(r.status())) {
            rows.put(key(accession, cik), new SecFilingSupplementStore.Row(r.accession(), r.cik(), r.form(), r.filed(),
                    r.readerVersion(), "COVERED", null, null, List.of(), null));
        }
    }

    @Override
    public void markStaleUsed(String accession, long cik) {
        // 메모리 원장은 실행 기록을 안 낸다 — 시각을 안 든다.
    }

    @Override
    public void touch(long cik, Collection<String> accessions) {
        // 메모리 원장은 실행 기록을 안 낸다 — 시각을 안 든다.
    }

    @Override
    public Map<String, SecFilingSupplementStore.Row> okRowsOf(long cik) {
        Map<String, SecFilingSupplementStore.Row> out = new HashMap<>();
        rows.values().stream().filter(r -> r.cik() == cik && r.ok()).forEach(r -> out.put(r.accession(), r));
        return out;
    }

    private static String key(String accession, long cik) {
        return accession + "#" + cik;
    }
}
