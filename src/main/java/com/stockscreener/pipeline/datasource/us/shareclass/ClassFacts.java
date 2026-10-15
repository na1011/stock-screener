package com.stockscreener.pipeline.datasource.us.shareclass;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 공시 하나의 인스턴스에서 클래스 축 판정에 쓰는 팩트(ADR 예정 · 탐색 `SHARES-CLASS-PLAN-V5-1015.md` §1b·§10).
 *
 * <p>기간 팩트는 개념마다 <b>파일에 나온 순서</b>를 지킨다 — 판정이 「그 기간에 처음 나온 구성원」을 고르는 자리가 있어서다
 * (시뮬레이터 {@code rule5.py}와 같은 순서). 구성원은 파일에 적힌 QName 글자 그대로이고({@code us-gaap:CommonClassAMember}),
 * 축 없는 맥락은 {@link #AXISLESS}다. 접두어는 공시마다 다르지만 비교는 한 공시 안에서만 한다.
 *
 * @param durations 개념 → (구성원 · 기간) → 값. 기간이 있는 맥락만.
 * @param cover     표지 개념({@code EntityCommonStockSharesOutstanding} · {@code TradingSymbol} · {@code Security12bTitle}) → 구성원 → 글자
 */
public record ClassFacts(Map<String, LinkedHashMap<MemberPeriod, Measured>> durations,
                         Map<String, LinkedHashMap<String, String>> cover,
                         String documentType) {

    public static final String AXISLESS = "-";

    public record Period(LocalDate start, LocalDate end) {
        /** 시작과 끝 사이 날 수(끝 − 시작 · 시뮬레이터의 {@code span}). */
        public long days() {
            return ChronoUnit.DAYS.between(start, end);
        }
    }

    public record MemberPeriod(String member, Period period) {
    }

    /** 값과 선언 정밀도에서 나온 반올림 반폭({@code decimals}가 없거나 {@code INF}면 0). */
    public record Measured(double value, double halfWidth) {
    }

    public Map<MemberPeriod, Measured> of(String concept) {
        return durations.getOrDefault(concept, new LinkedHashMap<>());
    }

    public Map<String, String> coverOf(String concept) {
        return cover.getOrDefault(concept, new LinkedHashMap<>());
    }

    /** 클래스 축 앵커 = 표지 주식수가 클래스 구성원에 실린 공시(§0-5). */
    public boolean isClassAnchor() {
        return coverOf(ClassFactReader.SHARES_OUTSTANDING).keySet().stream().anyMatch(m -> !AXISLESS.equals(m));
    }

    static String local(String member) {
        int i = member.indexOf(':');
        return i < 0 ? member : member.substring(i + 1);
    }
}
