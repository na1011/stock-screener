package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.StatementPeriod;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Basis;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Fact;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.StatementFact;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>그 판의 XBRL 인스턴스를 전체 재무제표({@code fnlttSinglAcntAll}) 응답 행 모양으로 옮긴다</b>(국내 관문 켬 · 재고 「얼린 비최신 판」).
 *
 * <p>DART 재무 API는 보고서마다 가장 늦은 판만 준다. 그래서 정정 전 판이 최신이던 동안 전체 재무제표를 못 받은 저장 판은 그 칸(매출원가 ·
 * 지배주주 순이익 · 지배 자본 · 현금흐름 …)이 영영 빈다 — 원장은 그 판의 인스턴스(접수번호마다 하나 · 디스크 캐시)로 값을 준다. 행 모양으로
 * 옮겨 <b>같은 매퍼</b>({@link DartFullStatementAdapter}의 보고서 짓기)를 태운다 — 계정 규칙이 두 벌이 되지 않게.
 *
 * <p>대응(0콜 실측 · 녹화본 응답 9,122 · 같은 접수번호의 인스턴스 · 2026-11-01): 표준 행 금액 칸의 약 96.5%가 아래 규칙으로 사실과 정확히
 * 같다 — 재무상태표 = 기말 시점 · 손익 {@code thstrm_amount} = 그 분기 3개월(사업보고서는 12개월) · {@code thstrm_add_amount} = 누계 ·
 * 현금흐름 = 누계 · 연결 = {@code ConsolidatedMember}(없으면 별도 {@code SeparateMember}). 연결 축 밖의 차원이 붙은 사실은 표의 줄이 아니라
 * 버린다.
 *
 * <p><b>줄은 같은 보고서의 최신 응답이 그 표에 실은 줄만 옮긴다</b>({@code face} — 표마다 {@code account_id} · 접두어도 응답 것). 인스턴스에는
 * 주석의 표준 사실도 함께 실려(주당이익 주석의 「보통주 귀속 중단영업」 등) 그대로 옮기면 매퍼가 본문에 없는 줄을 집는다(대조 스윕: 지배 중단영업
 * 다름 6 · 인스턴스만 10). 같은 기간 · 같은 기저에 값이 갈린 사실(제출자 중복 맥락)은 모른다 — 버린다.
 *
 * <p>계정명: 표준 줄은 최신 응답의 그 줄 이름, 표준계정코드 없는 줄(회사 확장 개념)은 그 판의 한국어 이름표({@code DartXbrlLabels} — 응답의
 * 계정명이 그것이다)가 최신 응답의 같은 표 비표준 줄 이름과 같을 때만 싣는다. 이름표를 아직 안 받은 판은 그 줄을 못 옮긴다.
 *
 * <p>못 옮기는 것: 자본변동표의 구성요소 줄(차원 · 이익잉여금 대체).
 */
final class DartXbrlStatementRows {

    private DartXbrlStatementRows() {
    }

    /**
     * 옮긴 행과 그 기저 · 손익 값을 모르는 개념({@code unknownIncome} — 누계로만 태깅돼 3개월 사실이 없거나, 그 기간 사실이 있는데 본문 줄에 없거나
     * 값이 갈려 버린 것 · 이름은 {@code ifrs-full_} · {@code dart_} 접두). 그 줄의 부재는 0이 아니라 모른다. 그 기간의 보고서가 아니면 {@code null}.
     */
    record Built(JsonNode list, boolean consolidated, java.util.Set<String> unknownIncome) {
    }

    /** 표준계정코드 없는 줄의 열쇠 — 그 줄은 이름으로만 갈린다. */
    private static final char NAME = '\u0001';

    /**
     * 최신 응답의 표마다 실린 줄 — 재무상태표 · 손익(IS · CIS) · 현금흐름. 열쇠는 표준 줄이면 {@code account_id}, 표준계정코드 없는 줄이면 그
     * 문자열 + 계정명이다. {@code names}는 열쇠 → 응답의 계정명(이름 폴백이 같은 이름을 보게).
     */
    record Face(java.util.Set<String> balance, java.util.Set<String> income, java.util.Set<String> cashFlow, Map<String, String> names) {

        static Face of(JsonNode list) {
            java.util.Set<String> balance = new java.util.HashSet<>();
            java.util.Set<String> income = new java.util.HashSet<>();
            java.util.Set<String> cashFlow = new java.util.HashSet<>();
            Map<String, String> names = new HashMap<>();
            for (JsonNode row : list) {
                String id = row.path("account_id").asString("");
                String name = row.path("account_nm").asString("");
                if (id.contains("표준계정코드")) {
                    id = DartFullAccountMapper.NO_STANDARD_CODE + NAME + name;
                }
                names.putIfAbsent(id, name);
                switch (row.path("sj_div").asString("")) {
                    case DartFullAccountMapper.SJ_BALANCE -> balance.add(id);
                    case DartFullAccountMapper.SJ_INCOME, DartFullAccountMapper.SJ_COMPREHENSIVE_INCOME -> income.add(id);
                    case DartFullAccountMapper.SJ_CASH_FLOW -> cashFlow.add(id);
                    default -> {
                    }
                }
            }
            return new Face(balance, income, cashFlow, names);
        }

        /**
         * 그 표에 실린 줄의 열쇠 — 표준 개념은 두 접두어(옛 IASB 이름공간 {@code ifrs_}) 중 응답이 쓴 것, 회사 확장 개념은 그 판의 이름표가 응답의
         * 표준계정코드 없는 줄 이름과 같을 때. 안 실렸으면 {@code null}.
         */
        String idIn(java.util.Set<String> section, DartXbrlFactReader.Source source, String concept, Map<String, String> entityLabels) {
            if (source == DartXbrlFactReader.Source.ENTITY) {
                String label = entityLabels.get(concept);
                String key = label == null ? null : DartFullAccountMapper.NO_STANDARD_CODE + NAME + label;
                return key != null && section.contains(key) ? key : null;
            }
            List<String> ids = source == DartXbrlFactReader.Source.IFRS
                    ? List.of("ifrs-full_" + concept, "ifrs_" + concept) : List.of("dart_" + concept);
            return ids.stream().filter(section::contains).findFirst().orElse(null);
        }
    }

    static Built rows(List<StatementFact> facts, int year, StatementPeriod period, String rceptNo, String reprtCode, Face face) {
        return rows(facts, year, period, rceptNo, reprtCode, face, Map.of());
    }

    /** @param entityLabels 그 판의 회사 확장 개념 이름표({@code DartXbrlLabels}) — 없으면 빈 맵(표준계정코드 없는 줄은 못 옮긴다) */
    static Built rows(List<StatementFact> facts, int year, StatementPeriod period, String rceptNo, String reprtCode, Face face,
                      Map<String, String> entityLabels) {
        LocalDate[] range = DartReportPeriods.periodRange(year, period.label());
        LocalDate yearStart = DartReportPeriods.periodRange(year, "FY")[0];
        LocalDate end = range[1];
        // 기간 · 기저 · 통화는 표준 사실로 판정한다 — 회사 확장 사실은 다른 날짜(후속 사건) · 다른 단위를 싣는다(판정에 섞으면 대조 366판이 빈다).
        List<StatementFact> standard = facts.stream().filter(f -> f.fact().source() != DartXbrlFactReader.Source.ENTITY).toList();
        // 그 기간의 보고서인가 — 인스턴스의 가장 늦은 기말이 그 기간 말일이어야 한다(비12월 결산은 달력 근사의 말일이 전기말에 걸린다).
        LocalDate latestInstant = standard.stream().filter(f -> f.fact().instant()).map(f -> f.fact().end())
                .max(LocalDate::compareTo).orElse(null);
        if (!end.equals(latestInstant)) {
            return null;
        }
        boolean consolidated = standard.stream().anyMatch(f -> f.fact().basis() == Basis.CONSOLIDATED && f.fact().instant()
                && end.equals(f.fact().end()));
        boolean separate = !consolidated && standard.stream().anyMatch(f -> f.fact().instant() && end.equals(f.fact().end())
                && f.fact().basis() != Basis.CONSOLIDATED);
        if (!consolidated && !separate) {
            return null;
        }
        java.util.Set<String> unknownIncome = new java.util.HashSet<>();
        Basis wanted = consolidated ? Basis.CONSOLIDATED : Basis.SEPARATE;
        Map<String, Fact> balance = new LinkedHashMap<>();
        Map<String, Fact> quarter = new LinkedHashMap<>();
        Map<String, Fact> cumulative = new LinkedHashMap<>();
        Map<String, Fact> cumulativeCash = new LinkedHashMap<>();
        java.util.Set<String> conflicted = new java.util.HashSet<>();
        Map<String, String> currencies = new HashMap<>();
        for (StatementFact sf : facts) {
            Fact f = sf.fact();
            if (!f.members().isEmpty() || (f.basis() != wanted && f.basis() != Basis.NONE) || !end.equals(f.end())) {
                continue;
            }
            if (sf.currency() != null && f.source() != DartXbrlFactReader.Source.ENTITY) {
                currencies.merge(sf.currency(), "", (a, b) -> a);
            }
            if (f.instant()) {
                put(balance, face.idIn(face.balance(), f.source(), f.concept(), entityLabels), f, "B", conflicted);
                continue;
            }
            String incomeId = face.idIn(face.income(), f.source(), f.concept(), entityLabels);
            if (incomeId == null && f.source() != DartXbrlFactReader.Source.ENTITY
                    && (f.start().equals(range[0]) || f.start().equals(yearStart))) {
                unknownIncome.add(canonical(f));
            }
            if (f.start().equals(range[0])) {
                put(quarter, incomeId, f, "Q", conflicted);
            }
            if (f.start().equals(yearStart)) {
                put(cumulative, incomeId, f, "C", conflicted);
                put(cumulativeCash, face.idIn(face.cashFlow(), f.source(), f.concept(), entityLabels), f, "F", conflicted);
            }
        }
        conflicted.forEach(key -> {
            String id = key.substring(1);
            if (key.charAt(0) == 'Q' || key.charAt(0) == 'C') {
                unknownIncome.add(id.startsWith("ifrs_") ? "ifrs-full_" + id.substring("ifrs_".length()) : id);
            }
            switch (key.charAt(0)) {
                case 'B' -> balance.remove(id);
                case 'Q' -> quarter.remove(id);
                case 'C' -> cumulative.remove(id);
                default -> cumulativeCash.remove(id);
            }
        });
        if (currencies.size() != 1) {
            return null;   // 통화를 하나로 못 정한다 — 꾸민 통화 표지를 달지 않는다
        }
        String currency = currencies.keySet().iterator().next();
        ArrayNode list = JsonNodeFactory.instance.arrayNode();
        balance.forEach((id, f) -> list.add(row(rceptNo, reprtCode, year, currency, DartFullAccountMapper.SJ_BALANCE, id, face,
                f.value().toPlainString(), null)));
        boolean annual = period == StatementPeriod.FY;
        // 손익: 사업보고서는 12개월 하나 · 분기는 3개월과 누계(1분기는 둘이 같다). 3개월 사실 없이 누계만 있는 개념은 3개월 행을 안 만든다 —
        // 누계를 3개월 칸에 넣으면 다른 개념에서 3개월 값을 받던 칸이 누계로 바뀐다(대조 스윕: 영업이익 6 · 순이익 4 판).
        Map<String, Fact> income = annual ? cumulative : quarter;
        if (!annual) {
            cumulative.keySet().stream().filter(id -> !quarter.containsKey(id))
                    .map(id -> id.startsWith("ifrs_") ? "ifrs-full_" + id.substring("ifrs_".length()) : id).forEach(unknownIncome::add);
        }
        income.forEach((id, f) -> list.add(row(rceptNo, reprtCode, year, currency, DartFullAccountMapper.SJ_COMPREHENSIVE_INCOME, id, face,
                f.value().toPlainString(), annual || !cumulative.containsKey(id) ? null : cumulative.get(id).value().toPlainString())));
        cumulativeCash.forEach((id, f) -> list.add(row(rceptNo, reprtCode, year, currency, DartFullAccountMapper.SJ_CASH_FLOW, id, face,
                f.value().toPlainString(), null)));
        return list.isEmpty() ? null : new Built(list, consolidated, java.util.Set.copyOf(unknownIncome));
    }

    private static String canonical(Fact f) {
        return (f.source() == DartXbrlFactReader.Source.IFRS ? "ifrs-full_" : "dart_") + f.concept();
    }

    /** 표에 실린 줄이면 담는다 — 같은 칸에 다른 값이 이미 있으면 갈린 것으로 적는다(키 = 표 머리 글자 + 이름). */
    private static void put(Map<String, Fact> into, String id, Fact f, String section, java.util.Set<String> conflicted) {
        if (id == null) {
            return;
        }
        Fact prior = into.putIfAbsent(id, f);
        if (prior != null && prior.value().compareTo(f.value()) != 0) {
            conflicted.add(section + id);
        }
    }

    private static ObjectNode row(String rceptNo, String reprtCode, int year, String currency, String sjDiv, String key, Face face,
                                  String amount, String cumulative) {
        int cut = key.indexOf(NAME);
        String accountId = cut < 0 ? key : key.substring(0, cut);
        ObjectNode r = JsonNodeFactory.instance.objectNode();
        r.put("rcept_no", rceptNo);
        r.put("reprt_code", reprtCode);
        r.put("bsns_year", String.valueOf(year));
        r.put("sj_div", sjDiv);
        r.put("account_id", accountId);
        r.put("account_nm", face.names().getOrDefault(key, ""));
        r.put("account_detail", "-");
        r.put("thstrm_amount", amount);
        if (cumulative != null) {
            r.put("thstrm_add_amount", cumulative);
        }
        r.put("currency", currency);
        return r;
    }
}
