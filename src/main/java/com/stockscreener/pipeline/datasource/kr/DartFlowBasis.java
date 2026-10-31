package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.FlowBasis;
import com.stockscreener.pipeline.datasource.RawFinancialReport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>한 해의 분기 손익이 한 기저인가</b>(국내 관문 켬 · 재고 A6). 뒤 보고서가 앞 분기 손익을 다른 기저로 다시 실으면(사업 매각 · 회계정책 ·
 * 연결 범위) 저장된 그 분기 3개월 값은 옛 기저라, 두 기저가 한 TTM 창에 섞인다. 원장이 새 기저의 그 분기 값을 안 주므로(다음 해 전기 열을 값으로
 * 넣으면 매출 26/50 · 영업이익 29/63만 닫힌다 — 그 뒤 또 다시 실은 해 · 원장 자기 모순) 값을 바꾸지 않고 <b>그 칸이 그날부터 낡았다</b>고만
 * 적는다({@link FlowBasis}).
 *
 * <p>원칙 하나 · 단계 셋 · 계정마다: 보고서가 실은 어떤 기간의 값이 저장된 그 기간 값과 안 닫히면 그 공시일부터 그 계정의 그 해 분기는 낡았다.
 * ① 반기 누계 ≠ 1 + 2분기 → 1분기 ② 9개월 누계 ≠ 반기 누계 + 3분기 → 1 · 2분기 ③ 다음 해 1분기 보고서의 전기 열 ≠ 1분기 → 1~4분기.
 * 대체일은 쓴 보고서 공시일의 최댓값이다(뒤 정정판으로 앞 날짜를 판정하지 않는다).
 *
 * <p>닫힘의 허용: 저장된 항 절댓값 합의 1%(부호가 바뀌는 영업이익도 분모가 안 무너진다) — 미국의 같은 꼴(비교열 세대 맞춤
 * {@code SecEdgarFacts.keepSameGeneration} · 「1% 안의 변화는 같은 세대」)과 같은 문턱이다.
 */
final class DartFlowBasis {

    static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    static final List<String> LABELS = List.of("Q1", "Q2", "Q3", "Q4");

    private DartFlowBasis() {
    }

    /** 한 계정 한 해의 재료 — 3개월 값(1 · 2 · 3분기) · 누계(반기 · 9개월) · 다음 해 1분기 보고서의 전기 열. 빠진 칸은 {@code null}. */
    record Values(BigDecimal q1, BigDecimal q2, BigDecimal h1, BigDecimal q3, BigDecimal nine, BigDecimal nextQ1Prior) {
    }

    /** 그 해 보고서의 공시일(없으면 {@code null}) — 1분기 · 반기 · 3분기 · 다음 해 1분기. */
    record Dates(LocalDate q1, LocalDate h1, LocalDate q3, LocalDate nextQ1) {
    }

    /**
     * 한 해 · 계정들의 판정 → 분기 라벨 → (칸 → 대체일). 판정할 재료가 하나도 없는 계정은 판정하지 않는다.
     *
     * @param values 칸(DB 컬럼명) → 재료
     */
    static Map<String, Map<String, LocalDate>> judge(Map<String, Values> values, Dates dates) {
        Map<String, Map<String, LocalDate>> out = new HashMap<>();
        for (Map.Entry<String, Values> e : values.entrySet()) {
            String column = e.getKey();
            Values v = e.getValue();
            if (apart(v.h1(), v.q1(), v.q2())) {
                mark(out, List.of("Q1"), column, latest(dates.q1(), dates.h1()));
            }
            if (apart(v.nine(), v.h1(), v.q3())) {
                mark(out, List.of("Q1", "Q2"), column, latest(dates.h1(), dates.q3()));
            }
            if (apart(v.nextQ1Prior(), v.q1(), null)) {
                mark(out, LABELS, column, latest(dates.q1(), dates.nextQ1()));
            }
        }
        return out;
    }

    /** 그 해 분기 보고서마다 판정 결과를 싣는다 — 판정한 칸 · 대체된 칸. */
    static List<FlowBasis> bases(int year, Map<String, RawFinancialReport> reports, Set<String> judged,
                                 Map<String, Map<String, LocalDate>> verdict) {
        List<FlowBasis> out = new ArrayList<>();
        for (String label : LABELS) {
            RawFinancialReport r = reports.get(label);
            if (r != null) {
                out.add(new FlowBasis(year, label, r.publishedAt(), judged, verdict.getOrDefault(label, Map.of())));
            }
        }
        return out;
    }

    /** {@code total}이 {@code a + b}(b 없으면 a)와 |a| + |b|의 1% 밖으로 어긋나는가 — 재료가 빠지면 안 잰다. */
    private static boolean apart(BigDecimal total, BigDecimal a, BigDecimal b) {
        if (total == null || a == null) {
            return false;
        }
        BigDecimal parts = b == null ? a : a.add(b);
        BigDecimal scale = a.abs().add(b == null ? BigDecimal.ZERO : b.abs());
        return scale.signum() > 0 && total.subtract(parts).abs().compareTo(scale.multiply(TOLERANCE)) > 0;
    }

    private static void mark(Map<String, Map<String, LocalDate>> out, List<String> labels, String column, LocalDate since) {
        if (since == null) {
            return;
        }
        for (String label : labels) {
            out.computeIfAbsent(label, k -> new HashMap<>()).merge(column, since, (a, b) -> a.isBefore(b) ? a : b);
        }
    }

    private static LocalDate latest(LocalDate a, LocalDate b) {
        if (a == null || b == null) {
            return null;
        }
        return a.isAfter(b) ? a : b;
    }
}
