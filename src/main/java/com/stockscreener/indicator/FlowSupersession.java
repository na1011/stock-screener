package com.stockscreener.indicator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 손익 칸 하나의 기저 대체(국내 관문 켬 · ADR-0200) — 그날부터 저장 값은 낡았고, 원장이 그 기간을 새 기저로 다시 실은 값이 있으면 그 공시일부터
 * 그 값이다(FY만 · 전기 · 전전기 열 — 미국이 비교열을 새 판으로 받는 것과 같은 기준).
 *
 * @param since    저장 값이 낡은 날
 * @param restated 공시일 → 새 값(비면 그 칸은 그날부터 모른다)
 */
public record FlowSupersession(LocalDate since, NavigableMap<LocalDate, BigDecimal> restated) {

    public FlowSupersession {
        restated = restated == null ? new TreeMap<>() : new TreeMap<>(restated);
    }

    public static FlowSupersession at(LocalDate since) {
        return new FlowSupersession(since, null);
    }

    /** {@code day}의 봉이 보는 그 칸 — 낡기 전이면 저장 값, 낡은 뒤면 그날까지 공개된 마지막 새 값(없으면 {@code null}). */
    BigDecimal seenOn(LocalDate day, BigDecimal stored) {
        if (since.isAfter(day)) {
            return stored;
        }
        var e = restated.floorEntry(day);
        return e == null ? null : e.getValue();
    }

    boolean staleOn(LocalDate day) {
        return !since.isAfter(day);
    }
}
