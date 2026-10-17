package com.stockscreener.pipeline.datasource.us.listing;

import com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts;
import com.stockscreener.pipeline.datasource.us.shareclass.DenominatorJudge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 정기 공시 표지의 증권 줄 — 클래스 축 구성원마다 기호 · 거래소 · 증권 이름(탐색 {@code US-PAST-LISTING-IMPL-1018.md} U2·U3).
 *
 * <p><b>표지 기호와 시세 기호는 꼴이 다르다</b> — Brown-Forman 10-Q(2026-09-02) 표지는 {@code BFA} · {@code BFB}이고 시세 소스는
 * {@code BF.A} · {@code BF.B}다. 그래서 둘을 견줄 때 클래스 축과 같은 규칙으로 맞춘다({@link DenominatorJudge#nk} · 한 사실에 기호가
 * 여럿이면 {@link DenominatorJudge#splitSymbols}).
 * 사채 줄({@code BF28} · 「2.600% Notes due 2028」)은 클래스 축이 아니라 부채 축 맥락이라 표지 판독이 이미 뺀다.
 */
public final class ListingCover {

    private ListingCover() {
    }

    /**
     * @param venue 표지 거래소 코드를 읽은 것(모르는 코드면 비었다)
     * @param title 증권 이름({@code Security12bTitle}) — 클래스 글자와 보통주 여부를 여기서 읽는다
     */
    public record Line(String member, String symbol, String venueCode, Optional<Venue> venue, String title) {

        public Optional<String> letter() {
            return title == null ? Optional.empty() : ClassLines.classLetter(title);
        }

        public boolean common() {
            return title != null && ClassLines.isCommon(title);
        }
    }

    /** 기호가 있는 줄 전부. */
    public static List<Line> lines(ClassFacts cover) {
        Map<String, String> symbols = cover.coverOf(ClassFactReader.TRADING_SYMBOL);
        Map<String, String> venues = cover.coverOf(ClassFactReader.SECURITY_EXCHANGE);
        Map<String, String> titles = cover.coverOf(ClassFactReader.SECURITY_TITLE);
        List<Line> out = new ArrayList<>();
        symbols.forEach((member, symbol) -> {
            String code = venues.get(member);
            out.add(new Line(member, symbol.strip(), code, Venue.ofDeiCode(code), titles.get(member)));
        });
        return out;
    }

    /**
     * 그 기호의 줄 — <b>기호가 통째로 같은 줄이 먼저다.</b> 표지는 사채 · 우선주 줄도 같은 기호로 시작해 싣는다(Kellanova {@code K} ·
     * {@code K 29} · {@code K 34} · Comerica {@code CMA} · {@code CMA PrB} — R-D6-0 2026-10-18). 쪼개 담기만 보면 그 줄들이 다 맞아 「하나가
     * 아님」이 된다. 통째로 같은 줄이 없을 때만 한 사실에 든 여러 기호(「GOOGL, GOOG」)를 쪼개 본다. 어느 쪽이든 둘 이상이면 비운다.
     */
    public static Optional<Line> lineFor(ClassFacts cover, String symbol) {
        String wanted = DenominatorJudge.nk(symbol);
        List<Line> all = lines(cover);
        List<Line> exact = all.stream().filter(l -> DenominatorJudge.nk(l.symbol()).equals(wanted)).toList();
        if (!exact.isEmpty()) {
            return exact.size() == 1 ? Optional.of(exact.get(0)) : Optional.empty();
        }
        List<Line> split = all.stream().filter(l -> DenominatorJudge.splitSymbols(l.symbol()).contains(wanted)).toList();
        return split.size() == 1 ? Optional.of(split.get(0)) : Optional.empty();
    }
}
