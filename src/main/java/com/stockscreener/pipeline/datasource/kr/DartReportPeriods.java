package com.stockscreener.pipeline.datasource.kr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * DART 정기보고서의 회계기간과 공시일을 유도한다. 주요계정·전체 재무제표 두 어댑터가 공유한다.
 *
 * <p>IMPORTANT — <b>공유하는 것이 DRY가 아니라 정합성 때문이다.</b> 두 어댑터가 같은 보고서를
 * 서로 다른 {@code publishedAt}으로 옮기면, 나중에 온 쪽이 {@code FinancialWriter}에서
 * <b>정정공시로 읽힌다.</b> 그 판별의 유일한 근거가 {@code published_at} 하나이고
 * (테이블에 {@code rcept_no} 컬럼이 없다), 정정으로 읽히면 그 종목의 지표가 보존 창째
 * 지워진다. 두 어댑터가 같은 함수를 부르는 것이 그 사고를 구조적으로 막는 수단이다.
 *
 * <p>같은 이유로 {@link #periodRange}도 공유한다. 저장된 {@code period_end}가 읽기 정렬의
 * 기준이라, 어댑터마다 다른 규약으로 쓰면 <b>한 종목 안에서 행마다 기준이 갈린다.</b>
 */
final class DartReportPeriods {

    private static final Logger log = LoggerFactory.getLogger(DartReportPeriods.class);

    private static final DateTimeFormatter RCEPT_DT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private DartReportPeriods() {
    }

    /**
     * 공시일을 {@code rcept_no} 앞 8자리에서 유도한다.
     *
     * <p>정정공시 판별의 근거다 — 같은 기간에 대해 {@code rcept_no}가 큰 쪽이 최신이고,
     * 그 사실이 {@code publishedAt}의 대소로 그대로 옮겨진다.
     *
     * <p>IMPORTANT — 폴백이 <b>회계연도 말일</b>인 것은 위험한 쪽으로 기운 선택이다. 이미
     * 저장된 행보다 늦은 날짜가 되기 쉬워 정정공시 경로를 부른다. 그럼에도 남겨 두는 이유는
     * {@code publishedAt}이 포트 계약상 null일 수 없기 때문이고, 이 값이 나왔다는 사실은
     * 아래 {@code WARN}으로만 드러난다.
     */
    static LocalDate publishedAt(JsonNode row, int fallbackYear) {
        String rceptNo = row.path("rcept_no").asString("");
        if (rceptNo.length() >= 8) {
            try {
                return LocalDate.parse(rceptNo.substring(0, 8), RCEPT_DT);
            } catch (RuntimeException e) {
                log.warn("rcept_no에서 공시일을 얻지 못했다: '{}'", rceptNo);
            }
        }
        log.warn("rcept_no가 없어 공시일을 회계연도 말일로 대신한다. fiscalYear={}", fallbackYear);
        return LocalDate.of(fallbackYear, 12, 31);
    }

    /**
     * 회계기간. <b>12월 결산을 전제한 근사값이다.</b>
     *
     * <p><b>「정확한 기간은 소스가 주지 않는다」는 틀렸다</b>(2026-09-16 실측) —
     * 주요계정({@code fnlttSinglAcnt})은 {@code thstrm_dt}로 <b>기간을 준다</b>
     * (사업보고서 IS가 {@code 2025.01.01 ~ 2025.12.31} · BS가 {@code 2025.12.31 현재}).
     * <b>안 주는 것은 전체 재무제표({@code fnlttSinglAcntAll})다</b> — 4호출 전부 필드가 없다.
     *
     * <p><b>그래서 아직 못 고친다.</b> 이 함수를 <b>두 어댑터가 공유하는데</b>
     * ({@code DartFinancialAdapter} · {@code DartFullStatementAdapter}) 한쪽만 실제 기간을 쓰면
     * <b>수요일 {@code full-statement-refill}과 목요일 {@code financial-refill}이 매주
     * 서로를 되돌린다</b> — 그때마다 그 종목의 지표가 보존 창째 지워졌다 다시 채워진다.
     *
     * <p><b>그동안 값이 틀리는 것은 따로 막았다</b> — 유도 4분기가 회계연도를 건너뛰는 것은
     * {@code DartFinancialAdapter.samePeriodTerm}이 <b>기수</b>로 판정한다(기수는 두
     * 엔드포인트가 모두 준다).
     *
     * <p>MUST NOT: 전체 재무제표의 {@code thstrm_dt}(실제 기간)로 이 자리를 대체하지 않는다.
     * 이미 저장된 FY 행이 예외 없이 {@code 01-01 ~ 12-31}이므로, 새로 쓰는 행만 실제 기간을
     * 가지면 <b>같은 종목 안에서 두 규약이 섞인다.</b> 그러면 {@code period_end} 정렬과
     * 연차 연속성 판정이 그 종목에서만 어긋나는데, 값이 그럴듯해 드러나지 않는다.
     */
    static LocalDate[] periodRange(int year, String period) {
        return switch (period) {
            case "Q1" -> new LocalDate[]{LocalDate.of(year, 1, 1), LocalDate.of(year, 3, 31)};
            case "Q2" -> new LocalDate[]{LocalDate.of(year, 4, 1), LocalDate.of(year, 6, 30)};
            case "Q3" -> new LocalDate[]{LocalDate.of(year, 7, 1), LocalDate.of(year, 9, 30)};
            case "Q4" -> new LocalDate[]{LocalDate.of(year, 10, 1), LocalDate.of(year, 12, 31)};
            default -> new LocalDate[]{LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)};
        };
    }
}
