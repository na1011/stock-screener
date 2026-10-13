package com.stockscreener.pipeline.datasource;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 한 종목의 수집이 <b>원장 응답에서 본 공시 번호</b>. 값이 아니라 근거다 — 공시 소비 단계(ADR-0176 2차)가 「이 실행이 그 공시를
 * 먹었는가」를 쓴 그 응답으로 판정한다. 출력(보고서)에는 영향이 없다.
 *
 * <p>소스마다 모양이 다르다 — 부재의 뜻을 소스마다 다시 묻는다.
 * <ul>
 *   <li><b>SEC</b>: companyfacts가 us-gaap 팩트를 하나라도 싣는 접수번호({@link #accessions}). <b>보충 전</b> 트리로 잰다 — 보충이
 *       트리에 팩트를 더하므로 뒤에 재면 보충으로 실은 공시가 섞인다(보충은 따로 판정한다).</li>
 *   <li><b>DART</b>: (사업연도, 보고서 코드)마다 응답의 {@code rcept_no}({@link #latestByReport}). 그 보고서의 가장 늦은 판이다
 *       ({@code docs/api-spec/dart-filing-list.md}) — 응답이 없으면({@code 013}) 키가 없다.</li>
 * </ul>
 */
public record FilingTrace(Set<String> accessions, Map<ReportKey, String> latestByReport) {

    /** 근거를 안 내는 소스·시험용. */
    public static final FilingTrace NONE = new FilingTrace(Set.of(), Map.of());

    public FilingTrace {
        accessions = Set.copyOf(accessions);
        latestByReport = Map.copyOf(latestByReport);
    }

    public static FilingTrace ofAccessions(Set<String> accessions) {
        return new FilingTrace(accessions, Map.of());
    }

    public static FilingTrace ofReports(Map<ReportKey, String> latestByReport) {
        return new FilingTrace(Set.of(), latestByReport);
    }

    /** DART 보고서 하나의 키 — 재무 API의 {@code bsns_year}·{@code reprt_code}. */
    public record ReportKey(int bsnsYear, String reprtCode) {
    }

    /** 응답이 실은 접수번호 중 큰 쪽을 남긴다 — 같은 보고서의 행이 서로 다른 번호를 실으면 늦은 판이 근거다. */
    public static void putLatest(Map<ReportKey, String> into, ReportKey key, String rceptNo) {
        if (rceptNo == null || rceptNo.isBlank()) {
            return;
        }
        into.merge(key, rceptNo, (a, b) -> a.compareTo(b) >= 0 ? a : b);
    }

    /** 가변 맵 — 어댑터가 응답마다 {@link #putLatest}로 채운다. */
    public static Map<ReportKey, String> newReportMap() {
        return new HashMap<>();
    }
}
