package com.stockscreener.pipeline.datasource.kr;

import java.util.regex.Pattern;

/**
 * DART 공시 이름이 <b>그 회사의 실적 발표</b>인가(ADR-0195). I001 수시공시 · I002 공정공시는 상세유형이 더 잘게 나뉘지 않아 거래소 표준
 * 서식의 제목으로 가른다 — 제목은 서식마다 고정이다.
 *
 * <p>2026-10-30 실측(2026-02 · 04-15~05-15 · 10월, I001·I002 × 유가·코스닥 13,423행에서 실적·손익·매출이 든 제목 전수):
 * <ul>
 *   <li><b>넣는다</b>: {@code 영업(잠정)실적(공정공시)} · {@code 연결재무제표기준영업(잠정)실적(공정공시)} — 분기 잠정실적.
 *       {@code 매출액또는손익구조30%(대규모법인은15%)이상변동}·{@code …이상변경}·{@code …미만변동(자율공시)}·{@code …미만변경(자율공시)}
 *       — 결산 실적(사업보고서 전). 각각의 {@code [기재정정]}·{@code [첨부정정]}도 같은 발표다.</li>
 *   <li><b>뺀다</b>: 제목 끝의 {@code (자회사의 주요경영사항)} — 자회사의 실적을 모회사가 낸 것이다. {@code 결산실적공시예고}(일정 안내) ·
 *       {@code 영업실적등에대한전망}(전망) · {@code 자본잠식…} · {@code 반기또는분기매출액미달…}은 위 둘 꼴에 안 걸린다.</li>
 * </ul>
 */
public final class DartEarningsReleaseNames {

    /** 제목 앞의 정정 표지 — {@code [기재정정]} · {@code [첨부정정]} · {@code [첨부추가]}. 여럿일 수 있다. */
    private static final Pattern AMENDMENT_PREFIX = Pattern.compile("^(\\[[^\\]]*\\])+");

    private DartEarningsReleaseNames() {
    }

    /** 공백을 다 지우고 정정 표지를 뗀 제목으로 판정한다(원장 제목에 꼬리 공백이 붙어 온다 — 2026-10-30 실측). */
    public static boolean isEarningsRelease(String reportName) {
        if (reportName == null) {
            return false;
        }
        String name = AMENDMENT_PREFIX.matcher(reportName.replaceAll("\\s", "")).replaceFirst("");
        if (name.contains("자회사")) {
            return false;
        }
        return name.contains("(잠정)실적") || name.startsWith("매출액또는손익구조");
    }
}
