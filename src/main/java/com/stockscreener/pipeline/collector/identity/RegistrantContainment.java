package com.stockscreener.pipeline.collector.identity;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 등록인 포함 검사(ADR-0180 · 계획 v3.1 ★1) — 한 행의 저장 재무 접수번호가 <b>전부</b> 후보 등록인의 것인가.
 *
 * <p>「후보 등록인의 것」은 그 CIK의 companyfacts 접수번호 ∪ 같은 CIK의 공시 보충 표 접수번호다 — companyfacts는 정기 공시 일부의
 * us-gaap를 안 싣고 보충이 그 공시를 실었다(ADR-0173). 실측(2026-10-16 · 재무 행 있는 미국 5,128): 오늘 파일 CIK로 5,070 통과 ·
 * 부분 누락 109종목의 112건은 전부 같은 CIK의 보충분 · 실패 3은 등록인 교체(REAX · XRPN · XRPNW — 공유 0).
 *
 * <p>읽는 자리는 넷이다 — 첫 FR의 등록인 묶기({@code FinancialCollector}) · 손 잡 {@code sec-cik-bind} · 접기 잡의 같은 등록인
 * 판정 · 신원 판정의 끊기 전제 1(공유 0).
 */
public final class RegistrantContainment {

    private RegistrantContainment() {
    }

    public enum Result {
        /** 저장 접수번호가 전부 든다 — 같은 등록인 */
        ALL,
        /** 하나도 안 든다 — 다른 등록인(끊기 전제 1) */
        NONE,
        /** 일부만 든다 — 판정을 못 준다 */
        PARTIAL,
        /** 저장 접수번호가 없다 — 섞일 판이 없다 */
        NO_STORED
    }

    public record Check(Result result, int stored, int missing, List<String> missingSample) {
        public String describe() {
            return "포함 검사 %s(저장 %d · 누락 %d%s)".formatted(result, stored, missing,
                    missingSample.isEmpty() ? "" : " · 예 " + missingSample);
        }
    }

    public static Check check(Set<String> stored, Set<String> known) {
        if (stored.isEmpty()) {
            return new Check(Result.NO_STORED, 0, 0, List.of());
        }
        TreeSet<String> missing = new TreeSet<>();
        for (String accession : stored) {
            if (!known.contains(accession)) {
                missing.add(accession);
            }
        }
        Result result = missing.isEmpty() ? Result.ALL : missing.size() == stored.size() ? Result.NONE : Result.PARTIAL;
        return new Check(result, stored.size(), missing.size(), missing.stream().limit(3).toList());
    }

    /** 그 행의 저장 재무 접수번호({@code report_code}는 「양식:접수번호」다). */
    public static Set<String> storedAccessions(JdbcTemplate jdbc, long stockId) {
        Set<String> out = new HashSet<>();
        for (String code : jdbc.queryForList(
                "SELECT DISTINCT report_code FROM financial_statement WHERE stock_id = ? AND report_code IS NOT NULL",
                String.class, stockId)) {
            out.add(code.substring(code.indexOf(':') + 1));
        }
        return out;
    }

    /** 그 CIK의 공시 보충 표 접수번호 — 상태를 안 가린다(받기에 실패한 공시도 그 등록인의 공시다). */
    public static Set<String> supplementAccessions(JdbcTemplate jdbc, long cik) {
        return new HashSet<>(jdbc.queryForList("SELECT accession FROM sec_filing_supplement WHERE cik = ?", String.class, cik));
    }

    /** companyfacts 접수번호 ∪ 같은 CIK 보충 표. */
    public static Set<String> known(JdbcTemplate jdbc, long cik, Set<String> companyFactsAccessions) {
        Set<String> out = new HashSet<>(companyFactsAccessions);
        out.addAll(supplementAccessions(jdbc, cik));
        return out;
    }
}
