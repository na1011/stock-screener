package com.stockscreener.pipeline.datasource.us.listing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 등록인 이름 대조 — 후보만 낸다(묶기는 표지 확인 · 탐색 ★6). */
class RegistrantNamesTest {

    private static final Map<String, Set<Long>> INDEX = RegistrantNames.index(List.of(
            Map.entry("ELECTRONIC ARTS INC.", 712515L),
            Map.entry("Veris Residential, Inc.", 924901L),
            Map.entry("Veris Residential, L.P.", 1067063L),
            Map.entry("Aris Mining Corp", 1654760L),
            Map.entry("WEBSTER FINANCIAL CORP /DE/", 801337L)));

    @Test
    void securityNameFindsTheRegistrant() {
        assertThat(RegistrantNames.candidates("Electronic Arts Inc. Common Stock", INDEX)).containsExactly(712515L);
        assertThat(RegistrantNames.candidates("Webster Financial Corporation Common Stock", INDEX)).containsExactly(801337L);
    }

    /** 모회사 + 운영 파트너십 — 둘 다 후보다(확인이 둘을 다 세우면 안 묶고 센다 · 구현 계획 §2.2). */
    @Test
    void parentAndOperatingPartnershipAreBothCandidates() {
        assertThat(RegistrantNames.candidates("Veris Residential, Inc. Common Stock", INDEX)).containsExactlyInAnyOrder(924901L, 1067063L);
    }

    /** 앞 낱말만 같은 다른 회사는 안 맞는다 — 끝 낱말 떼기는 한 낱말뿐이다(★6 ARIS 오대조는 이름 입력이 틀린 몫이라 표지 확인이 거른다). */
    @Test
    void companiesSharingOnlyAFirstWordDoNotMatch() {
        assertThat(RegistrantNames.candidates("Aris Water Solutions, Inc. Class A Common Stock", INDEX)).isEmpty();
        assertThat(RegistrantNames.candidates("Aris Mining Corporation Common Shares", INDEX)).containsExactly(1654760L);
    }

    @Test
    void trailingWordIsDroppedOnlyWhenNothingMatchesExactly() {
        assertThat(RegistrantNames.candidates("Electronic Arts Inc. Waterbury", INDEX)).containsExactly(712515L);
        assertThat(RegistrantNames.candidates("Common Stock", INDEX)).isEmpty();
    }
}
