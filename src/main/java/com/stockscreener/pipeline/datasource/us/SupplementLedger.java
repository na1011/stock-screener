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

    /** 이 발행사의 행 중 그 접수번호들. 키가 (접수번호, CIK)다 — 공동 등록 공시는 CIK마다 팩트가 다르다. */
    Map<String, SecFilingSupplementStore.Row> find(long cik, Collection<String> accessions);

    void saveOk(SecFilingClient.Filing filing, long cik, String fileName, long fileSize,
                XbrlInstanceReader.Instance instance);

    void saveFailed(SecFilingClient.Filing filing, long cik, String failureKind, String message);

    /** 원장이 XBRL이 있다고 했는데 공시 폴더에 XBRL 산출물이 없다 — 실패가 아니라 원장의 사실이다. */
    void saveNoXbrl(SecFilingClient.Filing filing, long cik);

    /** 이 발행사의 실패 행과 COVERED 행(실패였다가 companyfacts가 실은 것). */
    Map<String, SecFilingSupplementStore.Row> settleRowsOf(long cik);

    /** 실패 행을 COVERED로 바꾼다 — 지우지 않는다(그 판을 매 실행 연다). */
    void markCovered(String accession, long cik);

    /** 이 실행이 대상으로 본 행에 시각을 찍는다 — 실행 기록의 미해결이 이 실행이 본 행만 세게. */
    void touch(long cik, Collection<String> accessions);

    /** 이 발행사의 OK 행 전부 — 공시 목록을 못 받은 실행이 쓸 수 있는 저장분이다. */
    Map<String, SecFilingSupplementStore.Row> okRowsOf(long cik);
}
