package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.Register;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncomeRows.Row;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>주식수 시간표</b>(계획 K3-b · 국내 관문 켬 {@link KrCommonIncome.Rule#SW}) — 원장이 W를 안 준 행의 기간 가중 보통주 유통 구간 [lo, hi]를 같은
 * 판(반기 · 3분기는 직전 판 자기주식 표까지)의 증자(감자) 이력 · 자기주식 표 · 그 판 명부와 앞 해 명부로 짓는다. <b>값을 만들지 않는다</b> — 증인으로만
 * 쓴다(지배 NI가 E × W와 닫히는가).
 *
 * <ul>
 *   <li>시작 유통 = 앞 해 명부 보통주 유통(역산하지 않는다 — 역산은 닫힘을 구성상 참으로 만든다). 반기 · 3분기는 그 해 1분기 시작부터 기간 앞날까지의
 *       보통주 사건과 직전 판 자기주식 누계로 굴린다.</li>
 *   <li>기간 안 보통주 사건은 일자부터 끝까지 가중하고, 소급 형태(무상증자 · 주식배당 · 주식분할 · 무상감자)는 전 기간이다. 자기주식 순변동은 일자가
 *       없어 [−취득, +처분]으로 둔다.</li>
 *   <li><b>닫힘</b>: 시작 유통 + 기간 사건 − 취득 + 처분 = 그 판 명부 보통주 유통(±0.1%). 안 닫히면 구간을 안 낸다 — 빠진 사건 · 부호를 거꾸로 낸 처분
 *       (표본 대조군 「안 닫힘」 8 중 6이 원장 W 밖이었다).</li>
 *   <li>명부 보통주 줄은 이름이 아니라 가장 큰 종류 줄이다(ADR-0192 · 이름 목록이면 숫자 명부 96 중 10이 빈칸).</li>
 * </ul>
 *
 * <p>보이는 날은 쓴 표들의 판 접수일 중 가장 늦은 날이다 — 원장은 그 보고서 코드의 가장 늦은 판(정정 포함)을 준다(ADR-0207).
 */
public final class KrShareTimeline {

    static final Set<String> COMMON_KINDS = Set.of("보통주", "보통주식", "기명식보통주", "기명식 보통주");
    /** 소급 가중(IAS 33 — 자원 유입 없이 주식수만 바뀐다). */
    static final Set<String> RETROACTIVE = Set.of("무상증자", "주식배당", "주식분할", "무상감자");
    static final Set<String> DECREASING = Set.of("무상감자", "유상감자", "상환권행사");
    private static final BigDecimal CLOSURE = new BigDecimal("0.001");

    /** 기간 가중 보통주 유통 구간과 그 재료가 보이는 첫날. */
    public record Window(BigDecimal low, BigDecimal high, LocalDate visible) {
    }

    private record Table(String rceptNo, List<JsonNode> rows) {
    }

    private record Event(LocalDate day, BigDecimal quantity, boolean retroactive) {
    }

    public static final KrShareTimeline NONE = new KrShareTimeline(List.of(), Map.of());

    private final Map<String, Table> tables = new HashMap<>();
    private final Map<Integer, Register> registers;

    public KrShareTimeline(List<KrXbrlStore.ShareMaterial> material, Map<Integer, Register> registers) {
        material.forEach(m -> tables.put(key(m.bsnsYear(), m.reprtCode(), m.endpoint()), new Table(m.rceptNo(), m.rows())));
        this.registers = registers;
    }

    /** 그 행의 구간 — 재료가 모자라거나 닫힘이 안 서면 {@code null}. */
    Window window(Row row, List<Row> ordered) {
        Register prior = registers.get(row.fiscalYear() - 1);
        if (prior == null || prior.commonOutstanding() == null || prior.commonOutstanding().signum() == 0) {
            return null;
        }
        String code = code(row.fiscalPeriod());
        Table irds = tables.get(key(row.fiscalYear(), code, "irdsSttus"));
        Table treasury = tables.get(key(row.fiscalYear(), code, "tesstkAcqsDspsSttus"));
        if (irds == null || treasury == null) {
            return null;
        }
        List<String> used = new ArrayList<>(List.of(irds.rceptNo(), treasury.rceptNo()));
        BigDecimal[] now = treasury(treasury.rows());
        if (now == null) {
            return null;
        }
        BigDecimal end;
        if ("FY".equals(row.fiscalPeriod())) {
            Register same = registers.get(row.fiscalYear());
            end = same == null ? null : same.commonOutstanding();
        } else {
            Table register = tables.get(key(row.fiscalYear(), code, "stockTotqySttus"));
            end = register == null ? null : registerOutstanding(register.rows());
            if (register != null) {
                used.add(register.rceptNo());
            }
        }
        if (end == null || end.signum() == 0) {
            return null;
        }
        BigDecimal start = prior.commonOutstanding();
        BigDecimal acquired = now[0];
        BigDecimal disposed = now[1];
        if ("Q2".equals(row.fiscalPeriod()) || "Q3".equals(row.fiscalPeriod())) {
            Row first = vintage(ordered, row.fiscalYear(), "Q1", row.published());
            Table previous = tables.get(key(row.fiscalYear(), code("Q2".equals(row.fiscalPeriod()) ? "Q1" : "Q2"), "tesstkAcqsDspsSttus"));
            if (first == null || previous == null) {
                return null;
            }
            BigDecimal[] before = treasury(previous.rows());
            List<Event> earlier = events(irds.rows(), first.start(), row.start().minusDays(1));
            if (before == null || earlier == null) {
                return null;
            }
            used.add(previous.rceptNo());
            for (Event e : earlier) {
                start = start.add(e.quantity());
            }
            start = start.subtract(before[0]).add(before[1]);
            acquired = acquired.subtract(before[0]);
            disposed = disposed.subtract(before[1]);
        }
        List<Event> events = events(irds.rows(), row.start(), row.end());
        if (events == null || acquired.signum() < 0 || disposed.signum() < 0) {
            return null;
        }
        BigDecimal closing = start;
        for (Event e : events) {
            closing = closing.add(e.quantity());
        }
        closing = closing.subtract(acquired).add(disposed);
        if (closing.subtract(end).abs().compareTo(CLOSURE.multiply(end)) > 0) {
            return null;
        }
        BigDecimal days = BigDecimal.valueOf(ChronoUnit.DAYS.between(row.start(), row.end()) + 1);
        BigDecimal weighted = start;
        for (Event e : events) {
            weighted = weighted.add(e.retroactive() ? e.quantity()
                    : e.quantity().multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(e.day(), row.end()) + 1)).divide(days, MathContext.DECIMAL128));
        }
        LocalDate visible = row.published();
        for (String rcept : used) {
            LocalDate d = receipt(rcept);
            if (d != null && d.isAfter(visible)) {
                visible = d;
            }
        }
        return new Window(weighted.subtract(acquired), weighted.add(disposed), visible);
    }

    /**
     * 그 분기 행 시점의 명부(K3-d · {@link KrCommonIncome.Rule#QREG}) — 그 판과 같은 해 앞 판의 주식총수 표 중 그 행 공시일까지 접수된 것, 숫자가
     * 실린 가장 늦은 것. 기준일은 행 기간 끝으로 단다(자본 몫 판정이 그 명부를 그 날짜 명부로 고른다). 없으면 {@code null}.
     */
    Register quarterRegister(Row row) {
        List<String> periods = switch (row.fiscalPeriod()) {
            case "Q3" -> List.of("Q3", "Q2", "Q1");
            case "Q2" -> List.of("Q2", "Q1");
            case "Q1" -> List.of("Q1");
            default -> List.of();
        };
        for (String period : periods) {
            Table table = tables.get(key(row.fiscalYear(), code(period), "stockTotqySttus"));
            LocalDate receipt = table == null ? null : receipt(table.rceptNo());
            if (receipt == null || receipt.isAfter(row.published())) {
                continue;
            }
            List<KrCommonIncome.ShareRow> rows = new ArrayList<>();
            for (JsonNode r : table.rows()) {
                if ("비고".equals(r.path("se").asString("").strip())) {
                    continue;   // 주석 문구 줄(자기주식 칸에 「분할로 인한 단주 취득」) — 명부 요약과 같이 수로 안 읽는다
                }
                BigDecimal issued = number(r.path("istc_totqy").asString(""));
                BigDecimal treasury = number(r.path("tesstk_co").asString(""));
                if (issued == null || treasury == null) {
                    rows = null;
                    break;
                }
                rows.add(new KrCommonIncome.ShareRow(r.path("se").asString(""), issued, treasury));
            }
            Register register = rows == null ? null : Register.of(row.end(), rows);
            if (register != null) {
                return register;
            }
        }
        return null;
    }

    private static List<Event> events(List<JsonNode> rows, LocalDate from, LocalDate to) {
        List<Event> out = new ArrayList<>();
        for (JsonNode r : rows) {
            String form = r.path("isu_dcrs_stle").asString("").strip();
            LocalDate day = day(r.path("isu_dcrs_de").asString(""));
            if (form.isEmpty() || "-".equals(form) || day == null || day.isBefore(from) || day.isAfter(to)
                    || !COMMON_KINDS.contains(r.path("isu_dcrs_stock_knd").asString("").strip())) {
                continue;
            }
            BigDecimal quantity = number(r.path("isu_dcrs_qy").asString(""));
            if (quantity == null) {
                return null;
            }
            out.add(new Event(day, DECREASING.contains(form) ? quantity.negate() : quantity, RETROACTIVE.contains(form)));
        }
        return out;
    }

    /** 보통주 자기주식 그해 누계 {취득, 처분} — 「총계」 줄의 합. 수량을 못 읽으면 {@code null}. */
    private static BigDecimal[] treasury(List<JsonNode> rows) {
        BigDecimal acquired = BigDecimal.ZERO;
        BigDecimal disposed = BigDecimal.ZERO;
        for (JsonNode r : rows) {
            if (!"총계".equals(r.path("acqs_mth1").asString("").strip()) || !COMMON_KINDS.contains(r.path("stock_knd").asString("").strip())) {
                continue;
            }
            BigDecimal a = number(r.path("change_qy_acqs").asString(""));
            BigDecimal d = number(r.path("change_qy_dsps").asString(""));
            if (a == null || d == null) {
                return null;
            }
            acquired = acquired.add(a);
            disposed = disposed.add(d);
        }
        return new BigDecimal[]{acquired, disposed};
    }

    /** 그 판 주식총수 표의 보통주 유통 — 가장 큰 종류 줄(발행 − 자기주식). 수량이 「-」뿐이면 {@code null}. */
    private static BigDecimal registerOutstanding(List<JsonNode> rows) {
        BigDecimal issued = null;
        BigDecimal treasury = null;
        for (JsonNode r : rows) {
            String label = r.path("se").asString("").strip();
            if ("합계".equals(label) || "비고".equals(label)) {
                continue;
            }
            BigDecimal i = number(r.path("istc_totqy").asString(""));
            BigDecimal t = number(r.path("tesstk_co").asString(""));
            if (i == null || t == null || i.signum() <= 0) {
                continue;
            }
            if (issued == null || i.compareTo(issued) > 0) {
                issued = i;
                treasury = t;
            }
        }
        return issued == null ? null : issued.subtract(treasury);
    }

    /** 원장 수량 — 쉼표를 빼고 읽는다. 빈칸 · 「-」는 0, 못 읽으면 {@code null}. */
    private static BigDecimal number(String raw) {
        String s = raw.replace(",", "").strip();
        if (s.isEmpty() || "-".equals(s)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate day(String raw) {
        String s = raw.strip().replace('.', '-');
        if (s.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate receipt(String rceptNo) {
        if (rceptNo == null || rceptNo.length() < 8) {
            return null;
        }
        try {
            return LocalDate.parse(rceptNo.substring(0, 4) + "-" + rceptNo.substring(4, 6) + "-" + rceptNo.substring(6, 8));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static Row vintage(List<Row> ordered, int year, String period, LocalDate notAfter) {
        Row found = null;
        for (Row r : ordered) {
            if (r.fiscalYear() == year && period.equals(r.fiscalPeriod()) && !r.published().isAfter(notAfter)) {
                found = r;
            }
        }
        return found;
    }

    private static String code(String fiscalPeriod) {
        return switch (fiscalPeriod) {
            case "Q1" -> "11013";
            case "Q2" -> "11012";
            case "Q3" -> "11014";
            default -> "11011";
        };
    }

    private static String key(int year, String code, String endpoint) {
        return year + "/" + code + "/" + endpoint;
    }
}
