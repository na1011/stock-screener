package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 국내 실적 발표 이름 규칙(ADR-0195). 제목은 2026-10-30 DART 공시검색(I001 · I002 × 유가 · 코스닥, 2026-02 · 04-15~05-15 · 10월
 * 13,423행)에서 실적·손익·매출이 든 제목 전수에서 옮겼다 — 꼬리 공백까지 원장 그대로인 것이 하나 있다.
 */
class DartEarningsReleaseNamesTest {

    @ParameterizedTest
    @DisplayName("그 회사의 실적 발표다 — 잠정실적 · 결산 손익구조 변동 · 그 정정")
    @ValueSource(strings = {
            "영업(잠정)실적(공정공시)",
            "연결재무제표기준영업(잠정)실적(공정공시)              ",
            "[기재정정]연결재무제표기준영업(잠정)실적(공정공시)",
            "[첨부정정]영업(잠정)실적(공정공시)",
            "매출액또는손익구조30%(대규모법인은15%)이상변동",
            "매출액또는손익구조30%(대규모법인은15%)이상변경",
            "[기재정정]매출액또는손익구조30%(대규모법인은15%)이상변경",
            "매출액또는손익구조30%(대규모법인은15%)미만변동(자율공시)",
            "매출액또는손익구조30%(대규모법인15%)미만변경(자율공시)"})
    void earningsReleases(String name) {
        assertThat(DartEarningsReleaseNames.isEarningsRelease(name)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("실적 발표가 아니다 — 자회사 실적 · 일정 안내 · 전망 · 자본잠식 · 매출 미달")
    @ValueSource(strings = {
            "연결재무제표기준영업(잠정)실적(공정공시)(자회사의 주요경영사항)",
            "매출액또는손익구조30%(대규모법인은15%)이상변경(자회사의 주요경영사항)",
            "[기재정정]매출액또는손익구조30%(대규모법인은15%)이상변경(자회사의 주요경영사항)",
            "결산실적공시예고(안내공시)",
            "결산실적공시예고",
            "연결재무제표기준영업실적등에대한전망(공정공시)",
            "영업실적등에대한전망(공정공시)",
            "자본잠식50%이상또는매출액50억원미만사실발생",
            "반기또는분기매출액미달사실발생",
            "기타경영사항(자율공시) (2026년 연간 경영실적 발표 일정)"})
    void notEarningsReleases(String name) {
        assertThat(DartEarningsReleaseNames.isEarningsRelease(name)).isFalse();
    }
}
