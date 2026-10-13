package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 미국 장부 행이 소비됐는지 판정한다(ADR-0176 2차). 그 종목을 쓴 FR과 <b>같은 트랜잭션</b>에서 부른다 — 근거는 그 FR이 받은 것이다.
 *
 * <p>근거 셋, 이 순서로 본다. 셋 다 판 범위({@code ReaderVersionPinTest.SOURCE_SCOPE}) 밖이다 — 범위 파일은 부르기만 한다.
 * <ol>
 *   <li><b>COMPANYFACTS</b> — 그 FR의 companyfacts(보충 전)가 그 접수번호의 us-gaap 팩트를 싣는다.</li>
 *   <li><b>SUPPLEMENT</b> — 보충 표에 그 (접수번호, CIK)가 {@link #SETTLED_SUPPLEMENT} 상태로 있다. 보충은 같은 트랜잭션에서
 *       쓰므로 이번 FR이 막 실은 것도 보인다.</li>
 *   <li><b>NO_XBRL</b> — submissions가 그 접수번호를 XBRL 없음으로 싣는다. 보충은 XBRL 있는 공시만 다루므로 이 공시는 보충 행이
 *       안 생긴다 — 이 근거가 따로 있어야 한다(2026-10-13 표본: 6-K 7건 전부 이 갈래).</li>
 * </ol>
 * 셋 다 아니면 열린 채 둔다 — 「원장이 아직 안 줌」(submissions에 없음)과 「우리 쪽」(보충 실패 · 보충 행 없음 · 조회 실패)을
 * 사유 머리로 가른다.
 *
 * <p><b>보충 창 밖 근거를 두지 않는다</b>: 보충은 창을 <b>접수일</b>로 거른다({@code SecFilingSupplement.supplement}의
 * {@code client.filings(cik, windowStart)}) — 그날 접수된 공시는 늘 창 안이라 보충 행이 생긴다.
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class SecFilingConsumption {

    /** 보충이 끝낸 상태 — 그 공시의 팩트가 이번 FR 출력에 실렸거나(OK · COVERED) 실을 것이 없다(NO_XBRL). */
    public static final Set<String> SETTLED_SUPPLEMENT = Set.of("OK", "NO_XBRL", "COVERED");

    /** 사유 머리 — 경보가 이것으로 가른다. */
    public static final String SOURCE_PENDING = "원장 아직: ";
    public static final String OUR_SIDE = "우리 쪽: ";

    public enum Ground { COMPANYFACTS, SUPPLEMENT, NO_XBRL }

    /** 열린 장부 행 하나. */
    public record OpenFiling(long id, String accession, String form, LocalDate filedOn) {
    }

    /** 판정. {@code ground}가 있으면 소비됐고 {@code evidence}가 그 값이다. 없으면 {@code reason}이 왜 열린 채인지다. */
    public record Verdict(OpenFiling filing, Ground ground, String evidence, String reason) {
        public boolean consumed() {
            return ground != null;
        }
    }

    private final SecFilingClient client;

    public SecFilingConsumption(SecFilingClient client) {
        this.client = client;
    }

    /**
     * @param carried          그 FR이 받은 companyfacts(보충 전)의 us-gaap 접수번호
     * @param supplementStatus 보충 표의 (접수번호 → 상태) — 그 CIK의 열린 행 접수번호만
     */
    public List<Verdict> judge(long cik, List<OpenFiling> open, Set<String> carried, Map<String, String> supplementStatus) {
        List<Verdict> out = new ArrayList<>();
        List<OpenFiling> rest = new ArrayList<>();
        for (Verdict v : judgeLocally(open, carried, supplementStatus)) {
            if (v.consumed()) {
                out.add(v);
            } else {
                rest.add(v.filing());
            }
        }
        if (rest.isEmpty()) {
            return out;
        }
        LocalDate since = rest.stream().map(OpenFiling::filedOn).min(LocalDate::compareTo).orElseThrow().minusDays(7);
        List<SecFilingClient.Filing> filings;
        try {
            filings = client.filings(cik, since);
        } catch (DataSourceException e) {
            // 종목을 세우지 않는다 — 그러면 그 FR의 쓰기까지 되돌아간다. 행은 사유와 함께 열린 채 남아 다음 실행이 다시 묻는다.
            for (OpenFiling f : rest) {
                out.add(new Verdict(f, null, null, OUR_SIDE + "submissions 조회 실패 " + e.kind() + " — " + e.getMessage()));
            }
            return out;
        }
        out.addAll(judgeWithSubmissions(rest, filings, supplementStatus));
        return out;
    }

    /** 근거 ①·② — 외부 호출 없이. 남는 행은 사유 없이 낸다(다음 단계가 정한다). */
    static List<Verdict> judgeLocally(List<OpenFiling> open, Set<String> carried, Map<String, String> supplementStatus) {
        List<Verdict> out = new ArrayList<>();
        for (OpenFiling f : open) {
            String status = supplementStatus.get(f.accession());
            if (carried.contains(f.accession())) {
                out.add(new Verdict(f, Ground.COMPANYFACTS, f.accession(), null));
            } else if (status != null && SETTLED_SUPPLEMENT.contains(status)) {
                out.add(new Verdict(f, Ground.SUPPLEMENT, status, null));
            } else {
                out.add(new Verdict(f, null, null, null));
            }
        }
        return out;
    }

    /** 근거 ③과 열린 행의 사유. */
    static List<Verdict> judgeWithSubmissions(List<OpenFiling> rest, List<SecFilingClient.Filing> filings,
                                              Map<String, String> supplementStatus) {
        Map<String, SecFilingClient.Filing> byAccession = new HashMap<>();
        for (SecFilingClient.Filing filing : filings) {
            byAccession.put(filing.accession(), filing);
        }
        List<Verdict> out = new ArrayList<>();
        for (OpenFiling f : rest) {
            SecFilingClient.Filing listed = byAccession.get(f.accession());
            String status = supplementStatus.get(f.accession());
            if (listed != null && !listed.xbrl()) {
                out.add(new Verdict(f, Ground.NO_XBRL, "isXBRL=0", null));
            } else if (listed == null) {
                out.add(new Verdict(f, null, null, SOURCE_PENDING + "submissions에 아직 없다"));
            } else if (status != null) {
                out.add(new Verdict(f, null, null, OUR_SIDE + "보충 " + status));
            } else {
                out.add(new Verdict(f, null, null,
                        OUR_SIDE + "companyfacts·보충 어디에도 없다(XBRL 있음 · 폼 " + f.form() + ")"));
            }
        }
        return out;
    }
}
