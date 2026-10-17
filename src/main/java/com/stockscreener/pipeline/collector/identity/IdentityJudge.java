package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.datasource.IdentitySource.EntityBar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 미국 행의 신원 사건을 가른다(ADR-0180 · 탐색 {@code US-IDENTITY-PLAN-1016.md} v3.1 D2). <b>입출력이 없다</b> — 받은 봉 계열과 등록인
 * 파일로만 판정하고, 등록인 교체의 전제(접수번호 · 끝의 증거)는 {@link SplitEvidence}가 따로 판정한다. 그래서 저장한 원자료로 0콜
 * 재현(R0)이 된다.
 *
 * <p><b>같은 실체의 정의</b>: 두 계열이 겹친 세션에서 (종가 · 거래량 · 체결 수)가 전부 같다. 두 쪽이 같은 요청 창이면(고리 2 —
 * 같은 기호의 닻 대 오늘) 겹침이 하나라도 있으면 된다 — 거래 드문 증권은 창에 봉이 몇 개뿐이다. 다른 기호끼리(고리 1 — 새 기호 대
 * 목록 밖 행)는 <b>겹침이 {@link #MIN_OVERLAP} 이상</b>이어야 한다 — 겹침 0의 「전부 같음」은 공허한 참이고, 신규 상장은 봉이 짧아
 * 거기 못 닿는다. 개명된 실체는 오늘 기호의 기본 계열이 옛 시대 봉까지 주므로 겹침이 창 전체다(실측 ★3·★4).
 */
public final class IdentityJudge {

    /** 다른 기호끼리 같은 실체로 볼 최소 겹침 세션. 창 45일(약 31세션)의 2/3쯤 — 개명 짝 34는 전부 54/54였다(★4). */
    public static final int MIN_OVERLAP = 20;

    private IdentityJudge() {
    }

    /**
     * 판정 대상 행 — 시대가 열린 미국 행.
     *
     * @param hasFinancials 재무 행이 하나라도 있는가 — 등록인이 안 묶인 채 재무가 있는 행은 개명으로도 못 가른다
     */
    public record OpenRow(long id, String ticker, Long secCik, boolean hasFinancials) {
    }

    /** 판정. */
    public sealed interface Verdict permits Rename, SplitCandidate, Reuse, Ambiguous {
    }

    /** 같은 실체 · 같은 등록인(또는 섞일 판이 없음) — 행의 표지를 새 기호로 바꾼다. */
    public record Rename(long stockId, String oldTicker, String newSymbol, LocalDate renamedOn, String evidence)
            implements Verdict {
    }

    /**
     * 같은 실체 · 다른 등록인 — 전제가 서면 행을 끊는다.
     *
     * @param sameSymbol 기호 그대로의 교체(고리 2 — REAX꼴)인가, 새 기호로의 교체(고리 1 — COLA꼴)인가
     * @param renamedOn  고리 1이면 새 기호의 첫 날(개명일) — 새 행의 기호 시대에 옛 기호 시대를 넣는 경계
     */
    public record SplitCandidate(long stockId, String oldTicker, String newSymbol, long newCik, boolean sameSymbol,
                                 LocalDate renamedOn, String evidence) implements Verdict {
    }

    /** 그 기호를 다른 실체가 가졌다 — 행의 표지를 비우고 시대를 닫는다. */
    public record Reuse(long stockId, String ticker, LocalDate lastBar, String evidence) implements Verdict {
    }

    /** 원장이 판정을 안 준다 — 그 기호(새 기호면 등록 안 함) 하나만 멈춘다. */
    public record Ambiguous(Long stockId, String symbol, String evidence) implements Verdict {
    }

    /**
     * 판정 결과.
     *
     * @param verdicts       사건마다 하나
     * @param blockedSymbols 이번 실행에 등록하지 않을 <b>새</b> 기호(보류 판정) — 이미 행이 쥔 기호는 안 든다(넣으면 그 행이 명부에서 빠진다)
     * @param needsLiteral   개명 후보인데 문자열 봉 재료가 없어 판정을 안 낸 기호 — 호출부가 그 기호만 받아 다시 판정한다
     */
    public record Result(List<Verdict> verdicts, Set<String> blockedSymbols, Set<String> needsLiteral, Counts counts) {
    }

    /** 귀속 재료 — RUN 줄의 근거. */
    public record Counts(int rows, int listed, int sameEntity, int noBars, int orphans, int unclaimed) {
    }

    /**
     * @param rows          시대가 열린 미국 행 전부
     * @param listed        오늘 목록의 기호
     * @param anchorSeries  행 id → 닻 계열({@code bars(닻 기호, asof=닻 날짜)})
     * @param todaySeries   기호 → 오늘 계열({@code bars(기호)} 기본 asof)
     * @param literalSeries 기호 → 그 문자열로 거래된 봉 — 개명일을 읽는다. 키가 없는 개명 후보는 {@link Result#needsLiteral}로 돌려준다
     * @param fileCik       오늘 등록인 파일
     */
    public static Result judge(Collection<OpenRow> rows, Set<String> listed, Map<Long, List<EntityBar>> anchorSeries,
                               Map<String, List<EntityBar>> todaySeries, Map<String, List<EntityBar>> literalSeries,
                               Function<String, Optional<Long>> fileCik) {
        List<Verdict> verdicts = new ArrayList<>();
        Set<String> blocked = new LinkedHashSet<>();
        Set<String> needsLiteral = new LinkedHashSet<>();
        Map<String, OpenRow> holder = new HashMap<>();
        for (OpenRow row : rows) {
            holder.put(row.ticker(), row);
        }

        // 고리 2 — 오늘 목록에 있는 행: 닻 계열이 오늘 그 기호의 실체인가.
        List<OpenRow> displaced = new ArrayList<>();
        int same = 0;
        int noBars = 0;
        for (OpenRow row : rows) {
            if (!listed.contains(row.ticker())) {
                continue;
            }
            List<EntityBar> anchor = anchorSeries.getOrDefault(row.id(), List.of());
            List<EntityBar> today = todaySeries.getOrDefault(row.ticker(), List.of());
            switch (compareSameWindow(anchor, today)) {
                case SAME -> {
                    same++;
                    Optional<Long> cik = fileCik.apply(row.ticker());
                    if (row.secCik() != null && cik.isPresent() && !cik.get().equals(row.secCik())) {
                        verdicts.add(new SplitCandidate(row.id(), row.ticker(), row.ticker(), cik.get(), true, null,
                                "닻 계열 = 오늘 계열(%d세션) · 오늘 파일 CIK %d ≠ 행 CIK %d"
                                        .formatted(anchor.size(), cik.get(), row.secCik())));
                    }
                }
                case DIFFERENT -> displaced.add(row);
                case BOTH_EMPTY -> noBars++;
                case ONE_EMPTY -> {
                    // 한쪽만 비었다 — 같은 실체인지 원장이 안 준다. 그 행을 손대지 않는다(기호는 그 행이 쥔 채라 고리 1의
                    // 주인 없는 기호에도 안 든다). 막을 새 기호가 아니므로 blocked에 넣지 않는다 — 넣으면 그 행이 명부에서 빠진다.
                    verdicts.add(new Ambiguous(row.id(), row.ticker(), "닻 봉 %d · 오늘 봉 %d — 한쪽만 비어 실체를 못 가른다"
                            .formatted(anchor.size(), today.size())));
                }
            }
        }

        // 고리 1 — 주인 없는 기호(오늘 목록에 있는데 같은 실체의 행이 없다) 대 실체가 끊긴 행(목록 밖 · 밀려남).
        Set<Long> displacedIds = new LinkedHashSet<>();
        for (OpenRow row : displaced) {
            displacedIds.add(row.id());
        }
        List<OpenRow> orphans = new ArrayList<>();
        for (OpenRow row : rows) {
            if (!listed.contains(row.ticker()) || displacedIds.contains(row.id())) {
                orphans.add(row);
            }
        }
        List<String> unclaimed = new ArrayList<>();
        for (String symbol : new TreeSet<>(listed)) {
            OpenRow held = holder.get(symbol);
            if ((held == null || displacedIds.contains(held.id())) && !blocked.contains(symbol)) {
                unclaimed.add(symbol);
            }
        }
        // 목록에 있고 같은 실체로 판정된 행도 맞춘다 — 목록은 상장 사실이 아니어서(ADR-0171) 개명 뒤에도 옛 기호를 한동안 활성으로 싣는다
        // (실측 2026-10-17: HKIT·BDAI 둘 다 단건 active · 봉 같음). 그 행을 빼면 같은 실체의 두 번째 기호가 매주 새 행이 된다.
        Set<Long> orphanIds = new LinkedHashSet<>();
        orphans.forEach(o -> orphanIds.add(o.id()));
        List<OpenRow> matchable = new ArrayList<>(orphans);
        for (OpenRow row : rows) {
            if (!orphanIds.contains(row.id()) && listed.contains(row.ticker())) {
                matchable.add(row);
            }
        }
        Map<String, List<OpenRow>> matchesBySymbol = new LinkedHashMap<>();
        Map<Long, List<String>> matchesByOrphan = new HashMap<>();
        for (String symbol : unclaimed) {
            List<EntityBar> today = todaySeries.getOrDefault(symbol, List.of());
            if (today.size() < MIN_OVERLAP) {
                continue; // 신규 상장 · 봉 없는 기호 — 어느 행과도 겹침 20에 못 닿는다
            }
            for (OpenRow orphan : matchable) {
                if (matchesAcrossSymbols(anchorSeries.getOrDefault(orphan.id(), List.of()), today)) {
                    matchesBySymbol.computeIfAbsent(symbol, k -> new ArrayList<>()).add(orphan);
                    matchesByOrphan.computeIfAbsent(orphan.id(), k -> new ArrayList<>()).add(symbol);
                }
            }
        }

        Set<Long> claimed = new LinkedHashSet<>();
        for (Map.Entry<String, List<OpenRow>> entry : matchesBySymbol.entrySet()) {
            String symbol = entry.getKey();
            List<OpenRow> matched = entry.getValue();
            if (matched.size() > 1) {
                verdicts.add(new Ambiguous(null, symbol, "닻 계열이 같은 행이 %d — %s"
                        .formatted(matched.size(), matched.stream().map(OpenRow::ticker).toList())));
                blocked.add(symbol);
                matched.forEach(r -> claimed.add(r.id()));
                continue;
            }
            OpenRow t = matched.get(0);
            claimed.add(t.id());
            if (!orphanIds.contains(t.id())) {
                // 같은 실체가 두 기호로 목록에 있다 — 어느 쪽이 지금 기호인지 원장(목록)이 안 준다. 새 기호를 등록하지 않고 멈춘다. 옛 기호가
                // 목록에서 빠지면 그 행이 끊긴 행이 되어 다음 판정이 개명으로 잇는다.
                verdicts.add(new Ambiguous(t.id(), symbol, "%s의 닻 계열 = %s 오늘 계열(겹침 %d)인데 %s도 목록에 활성 — 같은 실체의 두 기호"
                        .formatted(t.ticker(), symbol, overlap(anchorSeries.get(t.id()), todaySeries.get(symbol)), t.ticker())));
                blocked.add(symbol);
                continue;
            }
            List<String> rivals = matchesByOrphan.get(t.id());
            if (rivals.size() > 1) {
                verdicts.add(new Ambiguous(t.id(), symbol, "행 %s의 닻 계열이 기호 %d과 같다 — %s"
                        .formatted(t.ticker(), rivals.size(), rivals)));
                blocked.add(symbol);
                continue;
            }
            int overlap = overlap(anchorSeries.get(t.id()), todaySeries.get(symbol));
            if (!literalSeries.containsKey(symbol)) {
                needsLiteral.add(symbol);
                continue;
            }
            LocalDate renamedOn = renameDate(literalSeries.getOrDefault(symbol, List.of()), todaySeries.get(symbol));
            if (renamedOn == null) {
                verdicts.add(new Ambiguous(t.id(), symbol, "%s 닻 계열 = %s 오늘 계열(겹침 %d)인데 %s 문자열의 봉이 없어 개명일을 못 읽는다"
                        .formatted(t.ticker(), symbol, overlap, symbol)));
                blocked.add(symbol);
                continue;
            }
            Optional<Long> cik = fileCik.apply(symbol);
            String basis = "%s 닻 계열 = %s 오늘 계열(겹침 %d) · %s 첫 봉 %s".formatted(t.ticker(), symbol, overlap, symbol, renamedOn);
            if (t.secCik() == null) {
                if (t.hasFinancials()) {
                    verdicts.add(new Ambiguous(t.id(), symbol, basis + " · 행에 재무가 있는데 등록인이 안 묶였다(sec-cik-bind 전)"));
                    blocked.add(symbol);
                } else {
                    verdicts.add(new Rename(t.id(), t.ticker(), symbol, renamedOn, basis + " · 재무 0행(섞일 판 없음)"));
                }
            } else if (cik.isEmpty() || cik.get().equals(t.secCik())) {
                verdicts.add(new Rename(t.id(), t.ticker(), symbol, renamedOn, basis + (cik.isEmpty()
                        ? " · 오늘 파일에 기호 없음 — 행 CIK %d로만 받는다".formatted(t.secCik())
                        : " · 같은 등록인 %d".formatted(t.secCik()))));
            } else {
                verdicts.add(new SplitCandidate(t.id(), t.ticker(), symbol, cik.get(), false, renamedOn,
                        basis + " · 오늘 파일 CIK %d ≠ 행 CIK %d".formatted(cik.get(), t.secCik())));
            }
        }

        // 밀려난 행 중 다른 기호로 간 것이 없으면 그 기호를 다른 실체가 가졌다 — 재사용.
        for (OpenRow row : displaced) {
            if (claimed.contains(row.id())) {
                continue;
            }
            List<EntityBar> anchor = anchorSeries.getOrDefault(row.id(), List.of());
            LocalDate last = anchor.get(anchor.size() - 1).date();
            verdicts.add(new Reuse(row.id(), row.ticker(), last, "닻 계열(%d · 끝 %s) ≠ 오늘 %s 계열(%d) · 다른 기호로 간 곳 없음"
                    .formatted(anchor.size(), last, row.ticker(), todaySeries.getOrDefault(row.ticker(), List.of()).size())));
        }
        return new Result(verdicts, blocked, needsLiteral,
                new Counts(rows.size(), listed.size(), same, noBars, orphans.size(), unclaimed.size()));
    }

    enum Comparison { SAME, DIFFERENT, BOTH_EMPTY, ONE_EMPTY }

    /**
     * 같은 요청 창의 두 계열(같은 기호 · 닻 대 오늘). 겹친 날이 하나라도 다르면 다른 실체이고, 겹침 0이면(한쪽이 끝난 뒤 다른 쪽이
     * 시작) 다른 실체다. 겹친 날이 전부 같으면 같은 실체다.
     */
    static Comparison compareSameWindow(List<EntityBar> anchor, List<EntityBar> today) {
        if (anchor.isEmpty() && today.isEmpty()) {
            return Comparison.BOTH_EMPTY;
        }
        if (anchor.isEmpty() || today.isEmpty()) {
            return Comparison.ONE_EMPTY;
        }
        Map<LocalDate, EntityBar> byDate = byDate(today);
        int overlap = 0;
        for (EntityBar bar : anchor) {
            EntityBar other = byDate.get(bar.date());
            if (other == null) {
                continue;
            }
            if (!bar.sameAs(other)) {
                return Comparison.DIFFERENT;
            }
            overlap++;
        }
        return overlap == 0 ? Comparison.DIFFERENT : Comparison.SAME;
    }

    /** 다른 기호끼리 — 겹침 {@link #MIN_OVERLAP} 이상이고 겹친 날이 전부 같다. */
    public static boolean matchesAcrossSymbols(List<EntityBar> a, List<EntityBar> b) {
        if (a == null || b == null) {
            return false;
        }
        Map<LocalDate, EntityBar> byDate = byDate(b);
        int overlap = 0;
        for (EntityBar bar : a) {
            EntityBar other = byDate.get(bar.date());
            if (other == null) {
                continue;
            }
            if (!bar.sameAs(other)) {
                return false;
            }
            overlap++;
        }
        return overlap >= MIN_OVERLAP;
    }

    static int overlap(List<EntityBar> a, List<EntityBar> b) {
        Map<LocalDate, EntityBar> byDate = byDate(b);
        int n = 0;
        for (EntityBar bar : a) {
            if (byDate.containsKey(bar.date())) {
                n++;
            }
        }
        return n;
    }

    /**
     * 개명일 — 그 문자열로 거래된 봉 중 <b>끝에서부터 실체 계열과 이어지는 구간</b>의 첫 날. 그 문자열을 창 안에서 다른 실체가 먼저 썼어도
     * 그 봉은 실체 계열과 안 맞아 구간에 안 든다. 실측: HOS 첫 봉 09-02 = 원장 name_changes 처리일 09-02 · 원장에 없는 KRSA 09-09.
     *
     * @return 이어지는 봉이 없으면 {@code null}
     */
    static LocalDate renameDate(List<EntityBar> literal, List<EntityBar> entity) {
        Map<LocalDate, EntityBar> byDate = byDate(entity);
        LocalDate first = null;
        for (int i = literal.size() - 1; i >= 0; i--) {
            EntityBar bar = literal.get(i);
            EntityBar other = byDate.get(bar.date());
            if (other == null || !bar.sameAs(other)) {
                break;
            }
            first = bar.date();
        }
        return first;
    }

    private static Map<LocalDate, EntityBar> byDate(List<EntityBar> bars) {
        Map<LocalDate, EntityBar> map = new HashMap<>();
        for (EntityBar bar : bars) {
            map.put(bar.date(), bar);
        }
        return map;
    }
}
