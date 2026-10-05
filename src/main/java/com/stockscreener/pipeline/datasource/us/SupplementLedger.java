package com.stockscreener.pipeline.datasource.us;

import java.util.Collection;
import java.util.Map;

/**
 * 보충한 공시를 접수번호마다 드는 원장(ADR-0173). 운영 구현은 {@link SecFilingSupplementStore}({@code sec_filing_supplement}
 * 표)이다.
 *
 * <p>인터페이스로 둔 이유는 스윕이다 — 저장한 원자료로 보충 전/후 출력을 DB 없이 대조하려면 {@link SecFilingSupplement}를
 * <b>한 줄도 안 바꾸고</b> 태워야 한다. 메모리 구현은 시험 쪽에 있다.
 */
interface SupplementLedger {

    Map<String, SecFilingSupplementStore.Row> find(Collection<String> accessions);

    void saveOk(SecFilingClient.Filing filing, long cik, String fileName, long fileSize,
                XbrlInstanceReader.Instance instance);

    void saveFailed(SecFilingClient.Filing filing, long cik, String failureKind, String message);

    /** 이 발행사의 OK 행 전부 — 공시 목록을 못 받은 실행이 쓸 수 있는 저장분이다. */
    Map<String, SecFilingSupplementStore.Row> okRowsOf(long cik);
}
