package com.stockscreener.pipeline.datasource.us.listing;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 한 등록인의 거래소 사건 하나 — 보통주 줄 하나가 한 거래소에서 빠지거나 등록된다(탐색 {@code US-PAST-LISTING-IMPL-1018.md} U2).
 *
 * <p>날짜는 그 양식이 효력을 내는 날이다 — {@code 25-NSE}는 제출일, 발행사 {@code 25}는 제출일 + 10일(탐색 ★12 TWOD: 제출 09-24 ·
 * 마지막 거래 10-02), {@code 8-A12B}는 제출일. 전이일의 정확도는 재지 않았다(R-D5).
 *
 * @param letter 보통주 줄의 클래스 글자(「Class A」 · 「Series C」) — 한 등록인에 보통주 행이 둘 이상이면 이것으로 행에 붙인다
 * @param transferredOn 제거 양식 설명문이 적은 이전일({@link ListingFormReader.Removal#transferredOn()})
 */
public record ListingEvent(LocalDate date, Venue venue, boolean registration, String line, Optional<String> letter,
                           String form, String accession, Optional<LocalDate> transferredOn) {

    /** 발행사 {@code 25}의 효력 지연(일) — 제출 뒤 10일에 효력이 난다(Rule 12d2-2(d)). */
    static final int ISSUER_REMOVAL_DELAY_DAYS = 10;

    /** 제거 양식 하나에서 보통주 줄마다 사건을 낸다. 거래소를 못 읽었으면 낼 것이 없다(부르는 쪽이 셈). */
    public static List<ListingEvent> ofRemoval(String form, LocalDate filed, String accession, ListingFormReader.Removal removal) {
        List<ListingEvent> out = new ArrayList<>();
        if (removal.unreadable() != null || removal.venue().isEmpty()) {
            return out;
        }
        LocalDate effective = "25".equals(form) ? filed.plusDays(ISSUER_REMOVAL_DELAY_DAYS) : filed;
        for (String line : removal.lines()) {
            if (ClassLines.isCommon(line)) {
                out.add(new ListingEvent(effective, removal.venue().get(), false, line, ClassLines.classLetter(line), form, accession,
                        removal.transferredOn()));
            }
        }
        return out;
    }

    /** 등록 양식 하나에서 거래소가 읽힌 보통주 줄마다 사건을 낸다. */
    public static List<ListingEvent> ofRegistration(String form, LocalDate filed, String accession,
                                                    ListingFormReader.Registration registration) {
        List<ListingEvent> out = new ArrayList<>();
        for (ListingFormReader.Registered row : registration.rows()) {
            if (row.venue().isPresent() && ClassLines.isCommon(row.line())) {
                out.add(new ListingEvent(filed, row.venue().get(), true, row.line(), ClassLines.classLetter(row.line()), form, accession,
                        Optional.empty()));
            }
        }
        return out;
    }
}
