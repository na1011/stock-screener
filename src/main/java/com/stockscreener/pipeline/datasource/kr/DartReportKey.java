package com.stockscreener.pipeline.datasource.kr;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 목록의 정기보고서 한 줄이 재무 API의 어느 (사업연도, 보고서 코드)인가 — 소비 단계의 탐침 키다(ADR-0176).
 *
 * <p>규칙(2026-10-12 원장 실측 · 세 회사 다섯 사례가 재무 API 응답과 일치):
 * <ul>
 *   <li>사업연도 = 보고서 이름 「(yyyy.mm)」의 연도 — 3월 결산 001720의 「사업보고서 (2026.03)」은 2026/11011이다.</li>
 *   <li>A001(사업) 11011 · A002(반기) 11012.</li>
 *   <li>A003(분기)은 결산월부터 몇 달째인지로 갈린다 — (보고서 월 − 결산월) mod 12가 3이면 1분기 11013, 9면 3분기 11014.
 *       결산월은 {@code stock.accounting_month}다.</li>
 * </ul>
 * 못 정하는 줄은 키 없이 사유를 낸다 — 장부에는 남고 소비가 그 사유로 멈춘다.
 *
 * @param bsnsYear  못 정하면 {@code null}
 * @param reprtCode 못 정하면 {@code null}
 * @param reason    둘 다 정했으면 {@code null}
 */
public record DartReportKey(Integer bsnsYear, String reprtCode, String reason) {

    /** 이름 끝의 기간 표기. 정정 접두(「[기재정정]」)가 앞에 붙어도 괄호는 하나다. */
    private static final Pattern PERIOD = Pattern.compile("\\((\\d{4})\\.(\\d{2})\\)");

    public static DartReportKey of(String detailType, String reportName, Integer accountingMonth) {
        Matcher m = PERIOD.matcher(reportName == null ? "" : reportName);
        Integer year = null;
        int month = 0;
        while (m.find()) {
            if (year != null) {
                return unresolved("보고서 이름에 기간 표기가 둘 이상이다: " + reportName);
            }
            year = Integer.parseInt(m.group(1));
            month = Integer.parseInt(m.group(2));
        }
        if (year == null || month < 1 || month > 12) {
            return unresolved("보고서 이름에서 기간 「(yyyy.mm)」을 못 읽었다: " + reportName);
        }
        return switch (detailType) {
            case "A001" -> new DartReportKey(year, "11011", null);
            case "A002" -> new DartReportKey(year, "11012", null);
            case "A003" -> quarterly(year, month, accountingMonth, reportName);
            default -> unresolved("정기보고서 상세유형이 아니다: " + detailType);
        };
    }

    private static DartReportKey quarterly(int year, int month, Integer accountingMonth, String reportName) {
        if (accountingMonth == null || accountingMonth < 1 || accountingMonth > 12) {
            return new DartReportKey(year, null, "결산월을 몰라 분기 보고서 코드를 못 정한다: " + reportName);
        }
        int offset = Math.floorMod(month - accountingMonth, 12);
        return switch (offset) {
            case 3 -> new DartReportKey(year, "11013", null);
            case 9 -> new DartReportKey(year, "11014", null);
            default -> new DartReportKey(year, null,
                    "분기 보고서 월이 결산월(%d)에서 %d달째다 — 1분기(3)·3분기(9)가 아니다: %s".formatted(accountingMonth, offset, reportName));
        };
    }

    private static DartReportKey unresolved(String reason) {
        return new DartReportKey(null, null, reason);
    }
}
