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
 * <b>한 해의 손익이 한 기저인가</b>(국내 관문 켬 · 재고 A6 · ADR-0200). 뒤 보고서가 앞 기간 손익을 다른 기저로 다시 실으면(사업 매각 · 회계정책 ·
 * 연결 범위) 저장된 그 기간 값은 옛 기저라, 두 기저가 한 TTM 창 · 성장률에 섞인다. 원장이 새 기저의 그 기간 값을 다 주지 않으므로(다음 해 전기 열을
 * 값으로 넣으면 매출 26/50 · 영업이익 29/63만 닫힌다) 값을 바꾸지 않고 <b>그 칸이 그날부터 낡았다</b>고만 적는다({@link FlowBasis}).
 *
 * <p>원칙 하나 · 계정마다: <b>보고서가 실은 어떤 기간의 값이 저장된 그 기간 값과 안 닫히면 그 보고서 공시일부터 그 기간의 그 칸은 낡았다.</b>
 * 짝은 그 해 안의 누계(① 반기 누계 ↔ 1 + 2분기 → 1분기 · ② 9개월 누계 ↔ 반기 누계 + 3분기 → 1 · 2분기)와 다음 해 보고서 넷의 전기 열이다
 * (3개월 열의 신호는 그 분기와 그 뒤 분기 — 뒤 분기의 새 기저 값은 아직 확인할 재료가 없다 · 누계 열의 신호는 1~4분기: ③ 1분기 →
 * 1~4분기 ④ 반기: 전기 2분기 → 2~4분기 · 전기 반기 누계 → 1~4분기 ⑤ 3분기: 전기 3분기 → 3 · 4분기 · 전기 9개월 → 1~4분기 ⑥ 사업보고서:
 * 전기 FY → FY · 전기 FY − 전기 9개월 → 4분기 ⑦ 두 해 뒤 사업보고서의 전전기 FY → FY). 대체일은 쓴 보고서 공시일의 최댓값이고, 한 칸이 여러 짝에 걸리면 이른 날이다.
 *
 * <p>닫힘의 허용: 저장된 항 절댓값 합의 1% — 미국의 같은 꼴(비교열 세대 맞춤 {@code SecEdgarFacts.keepSameGeneration} · 「1% 안의 변화는 같은
 * 세대」)과 같은 문턱. 저장된 항이 0이면 0이 아닌 값이 곧 어긋남이다(중단영업은 대개 0에서 생긴다).
 */
final class DartFlowBasis {

    static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    static final List<String> LABELS = List.of("Q1", "Q2", "Q3", "Q4", "FY");
    private static final List<String> QUARTERS = List.of("Q1", "Q2", "Q3", "Q4");

    private DartFlowBasis() {
    }

    /**
     * 한 계정 한 해의 재료 — 그 해 보고서의 3개월 값 · 누계 · FY와 다음 해 보고서의 전기 열(3개월 · 누계 · FY). 빠진 칸은 {@code null}.
     */
    record Values(BigDecimal q1, BigDecimal q2, BigDecimal h1, BigDecimal q3, BigDecimal nine, BigDecimal fy,
                  BigDecimal priorQ1, BigDecimal priorQ2, BigDecimal priorH1, BigDecimal priorQ3, BigDecimal priorNine,
                  BigDecimal priorFy, BigDecimal twoYearsPriorFy) {

        Values(BigDecimal q1, BigDecimal q2, BigDecimal h1, BigDecimal q3, BigDecimal nine, BigDecimal fy, BigDecimal priorQ1,
               BigDecimal priorQ2, BigDecimal priorH1, BigDecimal priorQ3, BigDecimal priorNine, BigDecimal priorFy) {
            this(q1, q2, h1, q3, nine, fy, priorQ1, priorQ2, priorH1, priorQ3, priorNine, priorFy, null);
        }
    }

    /** 보고서 공시일 — 그 해 1분기 · 반기 · 3분기 · 사업보고서와 다음 해의 넷. 없으면 {@code null}. */
    record Dates(LocalDate q1, LocalDate h1, LocalDate q3, LocalDate fy,
                 LocalDate nextQ1, LocalDate nextH1, LocalDate nextQ3, LocalDate nextFy, LocalDate twoYearsLaterFy) {

        Dates(LocalDate q1, LocalDate h1, LocalDate q3, LocalDate fy, LocalDate nextQ1, LocalDate nextH1, LocalDate nextQ3,
              LocalDate nextFy) {
            this(q1, h1, q3, fy, nextQ1, nextH1, nextQ3, nextFy, null);
        }
    }

    /** 한 해 · 계정들의 판정 → 라벨 → (칸 → 대체일). */
    static Map<String, Map<String, LocalDate>> judge(Map<String, Values> values, Dates d) {
        Map<String, Map<String, LocalDate>> out = new HashMap<>();
        for (Map.Entry<String, Values> e : values.entrySet()) {
            String c = e.getKey();
            Values v = e.getValue();
            if (apart(v.h1(), v.q1(), v.q2())) {
                mark(out, List.of("Q1"), c, latest(d.q1(), d.h1()));
            }
            if (apart(v.nine(), v.h1(), v.q3())) {
                mark(out, List.of("Q1", "Q2"), c, latest(d.h1(), d.q3()));
            }
            // 다음 해 보고서의 신호는 그 분기와 그 뒤 분기를 낡게 한다 — 뒤 분기의 새 기저 값은 아직 확인할 재료가 없다(누계 신호는 1~4분기).
            if (apart(v.priorQ1(), v.q1(), null)) {
                mark(out, QUARTERS, c, latest(d.q1(), d.nextQ1()));
            }
            if (apart(v.priorQ2(), v.q2(), null)) {
                mark(out, List.of("Q2", "Q3", "Q4"), c, latest(d.h1(), d.nextH1()));
            }
            if (apart(v.priorH1(), v.h1(), null)) {
                mark(out, QUARTERS, c, latest(d.h1(), d.nextH1()));
            }
            if (apart(v.priorQ3(), v.q3(), null)) {
                mark(out, List.of("Q3", "Q4"), c, latest(d.q3(), d.nextQ3()));
            }
            if (apart(v.priorNine(), v.nine(), null)) {
                mark(out, QUARTERS, c, latest(d.q3(), d.nextQ3()));
            }
            if (apart(v.priorFy(), v.fy(), null)) {
                mark(out, List.of("FY"), c, latest(d.fy(), d.nextFy()));
            }
            // 두 해 뒤 사업보고서의 전전기 열 — 기저가 그 뒤 해에 바뀐 FY를 5년 창이 섞지 않게(연결 전환 2년 앞 · 반증 안 렌즈).
            if (apart(v.twoYearsPriorFy(), v.fy(), null)) {
                mark(out, List.of("FY"), c, latest(d.fy(), d.twoYearsLaterFy()));
            }
            BigDecimal q4 = v.fy() != null && v.nine() != null ? v.fy().subtract(v.nine()) : null;
            BigDecimal priorQ4 = v.priorFy() != null && v.priorNine() != null ? v.priorFy().subtract(v.priorNine()) : null;
            if (apart(priorQ4, q4, null)) {
                mark(out, List.of("Q4"), c, latest(d.fy(), d.nextFy()));
            }
        }
        return out;
    }

    /** 그 해 보고서마다 판정 결과를 싣는다 — 판정한 칸 · 대체된 칸. */
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

    /**
     * {@code total}이 저장된 {@code a + b}(b 없으면 a)와 |a| + |b|의 1% 밖으로 어긋나는가 — 재료가 빠지면 안 잰다 · 저장된 항이 0이면
     * 0이 아닌 값이 곧 어긋남이다.
     */
    static boolean apart(BigDecimal total, BigDecimal a, BigDecimal b) {
        if (total == null || a == null) {
            return false;
        }
        BigDecimal parts = b == null ? a : a.add(b);
        BigDecimal scale = a.abs().add(b == null ? BigDecimal.ZERO : b.abs());
        BigDecimal gap = total.subtract(parts).abs();
        return scale.signum() == 0 ? gap.signum() != 0 : gap.compareTo(scale.multiply(TOLERANCE)) > 0;
    }

    private static void mark(Map<String, Map<String, LocalDate>> out, List<String> labels, String column, LocalDate since) {
        if (since == null) {
            return;
        }
        for (String label : labels) {
            out.computeIfAbsent(label, k -> new HashMap<>()).merge(column, since, (x, y) -> x.isBefore(y) ? x : y);
        }
    }

    private static LocalDate latest(LocalDate a, LocalDate b) {
        if (a == null || b == null) {
            return null;
        }
        return a.isAfter(b) ? a : b;
    }
}
