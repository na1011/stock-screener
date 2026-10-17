package com.stockscreener.pipeline.datasource.us.listing;

import com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 정기 공시 표지의 증권 줄 — 클래스 축 구성원마다 기호 · 거래소 · 증권 이름(탐색 {@code US-PAST-LISTING-IMPL-1018.md} U2·U3).
 *
 * <p><b>표지 기호와 시세 기호는 꼴이 다르다</b> — Brown-Forman 10-Q(2026-09-02) 표지는 {@code BFA} · {@code BFB}이고 시세 소스는
 * {@code BF.A} · {@code BF.B}다. 그래서 둘을 견줄 때 점 · 하이픈 · 빗금 · 공백을 걷고 대문자로 맞춘다({@link #normalize}).
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

    /** 그 기호의 줄 — 기호는 {@link #normalize}로 맞춘다. 같은 기호 줄이 둘 이상이면 비운다(어느 것인지 원장이 안 말한다). */
    public static Optional<Line> lineFor(ClassFacts cover, String symbol) {
        String wanted = normalize(symbol);
        List<Line> found = lines(cover).stream().filter(l -> normalize(l.symbol()).equals(wanted)).toList();
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    /** 기호 꼴 맞추기 — 대문자 · 점 · 하이픈 · 빗금 · 공백 제거. */
    public static String normalize(String symbol) {
        return symbol == null ? "" : symbol.toUpperCase(Locale.ROOT).replaceAll("[.\\-/\\s]", "");
    }
}
