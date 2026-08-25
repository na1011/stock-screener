package com.stockscreener.pipeline.datasource.kr;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 「증자(감자) 현황」을 어느 정기보고서에서 받아야 {@code [from, to]}가 덮이는가.
 *
 * <p>IMPORTANT — api-spec dart-corporate-action.md의 <i>"최근 사업보고서 1콜로 5년 이력이 끝난다"</i>는
 * <b>깊이의 주장이지 최신성의 주장이 아니다.</b> 2025 사업보고서는 2026-03에 나오고
 * 2025-12-31까지만 담는다. 1콜로 고정하면 <b>직전 회계연도 말 이후의 사건을 원리적으로
 * 못 본다</b> — ADR-0049가 배경으로 든 루닛 2026-05 무상증자가 정확히 그 자리다.
 *
 * <h2>이 함수가 빈 목록을 내는 것이 정상이다</h2>
 * <p>기업 행위는 정기보고서에만 실리므로 <b>소스가 최대 넉 달 늦다.</b> 오늘로부터
 * 며칠짜리 창을 물으면 그 기간을 담는 보고서가 아직 제출되지 않아 고를 것이 없다.
 * 그것은 실패가 아니라 소스의 성질이고, 그 사이의 사건은
 * {@code AdjustmentRecalculator.detectCandidates}가 후보로 올린다.
 *
 * <p>MUST NOT: 덮이지 않는 구간을 "덮었다"로 읽지 않는다. 최소 집합을 고르는 아래 탐욕
 * 알고리즘은 <b>못 덮는 구간을 조용히 건너뛴다</b> — 커버리지를 성립으로 읽으면
 * phase 기준(*"소스가 설명하지 못한 불연속이 '그런 사건이 없다'로 읽히지 않는다"*)이 깨진다.
 */
final class DartIrdsReports {

    /** dart-corporate-action.md의 「증자(감자) 현황」 절 — {@code bsns_year}는 2015년 이후만 받는다. */
    private static final int EARLIEST_BUSINESS_YEAR = 2015;

    /**
     * 사업보고서 하나가 담는 이력의 길이(햇수).
     *
     * <p>실측 근거는 dart-corporate-action.md의 「조용히 틀리는 자리」 절이다 — 카카오 2021 사업보고서 하나가
     * 2017~2021의 128행을 준다.
     */
    private static final int ANNUAL_HISTORY_YEARS = 5;

    /**
     * 분기·반기보고서가 담는 이력의 길이(햇수).
     *
     * <p><b>분기보고서도 자기 기간만 담지 않는다</b> — 실측(2026-08-12,
     * {@code DartCorporateActionAdapterLiveTest}): 루닛 2026 1분기보고서가 67행을 주고
     * 가장 이른 행이 2022.07.16이다. 다만 그 회사가 2022-07 상장이라 <b>관측된 깊이의
     * 하한이 3.7년</b>이고 5년인지는 확인되지 않았다. 그래서 <b>실측 안쪽인 3으로 잡는다</b> —
     * 넓게 가정하면 콜은 줄지만 <b>덮이지 않은 구간을 덮었다고 읽게 된다.</b>
     */
    private static final int QUARTERLY_HISTORY_YEARS = 3;

    private DartIrdsReports() {
    }

    /**
     * @param businessYear {@code bsns_year}
     * @param reportCode   {@code reprt_code}
     * @param coversFrom   이 보고서가 담는 가장 이른 사건 일자
     * @param coversTo     가장 늦은 사건 일자. 회계기간의 끝이다
     * @param filedFrom    이 날부터 제출되어 있다고 본다. 법정 제출기한이며 실제는 더 이를 수 있다
     */
    record Report(int businessYear, String reportCode,
                  LocalDate coversFrom, LocalDate coversTo, LocalDate filedFrom) {
    }

    /**
     * {@code [from, to]}를 덮는 보고서의 <b>최소 집합</b>. 못 덮는 구간은 그냥 빠진다.
     *
     * <p>겹치는 보고서가 같은 행을 두 번 주므로 호출자가 행을 접어야 한다 — 최소 집합이어도
     * 사업보고서 둘의 이력은 4년이 겹친다.
     */
    static List<Report> covering(LocalDate from, LocalDate to, LocalDate today) {
        List<Report> available = new ArrayList<>();
        for (Report report : candidates(from, to)) {
            boolean filed = !today.isBefore(report.filedFrom());
            boolean overlaps = !report.coversTo().isBefore(from) && !report.coversFrom().isAfter(to);
            if (filed && overlaps) {
                available.add(report);
            }
        }

        // 구간 덮기 탐욕. 지금 위치를 덮는 것 중 가장 멀리 가는 것을 고른다. 덮는 것이 없으면
        // 위치를 다음 보고서의 시작으로 건너뛴다 — 그 사이가 소스가 답하지 못하는 구간이다.
        List<Report> chosen = new ArrayList<>();
        LocalDate position = from;
        while (!position.isAfter(to)) {
            LocalDate cursor = position;
            List<Report> reachable = available.stream()
                    .filter(r -> !r.coversTo().isBefore(cursor))
                    .toList();
            if (reachable.isEmpty()) {
                break;
            }
            List<Report> startingHere = reachable.stream()
                    .filter(r -> !r.coversFrom().isAfter(cursor))
                    .toList();
            if (startingHere.isEmpty()) {
                position = reachable.stream()
                        .map(Report::coversFrom)
                        .min(Comparator.naturalOrder())
                        .orElseThrow();
                continue;
            }
            Report best = startingHere.stream()
                    .max(Comparator.comparing(Report::coversTo))
                    .orElseThrow();
            chosen.add(best);
            available.remove(best);
            position = best.coversTo().plusDays(1);
        }
        return chosen;
    }

    /**
     * 후보 전부. 제출 여부와 겹침은 {@link #covering}이 거른다.
     *
     * <p>{@code filedFrom}은 <b>법정 제출기한</b>이다. 실제로는 그보다 이를 수 있어
     * (실측: 루닛 2026 반기가 기한 08-14 전인 08-12에 이미 있었다) 이 모델은 소스 지연을
     * 며칠 길게 잡는다. <b>반대 방향으로 틀리는 것이 안전하다</b> — 없는 보고서를 부르면
     * 종목마다 헛콜이 하나씩 늘고, 그 013을 "사건이 없다"로 읽게 된다.
     */
    private static List<Report> candidates(LocalDate from, LocalDate to) {
        List<Report> all = new ArrayList<>();
        for (int year = Math.max(from.getYear(), EARLIEST_BUSINESS_YEAR); year <= to.getYear(); year++) {
            LocalDate annualFrom = LocalDate.of(year - ANNUAL_HISTORY_YEARS + 1, 1, 1);
            LocalDate quarterlyFrom = LocalDate.of(year - QUARTERLY_HISTORY_YEARS + 1, 1, 1);
            all.add(new Report(year, "11011",
                    annualFrom, LocalDate.of(year, 12, 31), LocalDate.of(year + 1, 3, 31)));
            all.add(new Report(year, "11013",
                    quarterlyFrom, LocalDate.of(year, 3, 31), LocalDate.of(year, 5, 15)));
            all.add(new Report(year, "11012",
                    quarterlyFrom, LocalDate.of(year, 6, 30), LocalDate.of(year, 8, 14)));
            all.add(new Report(year, "11014",
                    quarterlyFrom, LocalDate.of(year, 9, 30), LocalDate.of(year, 11, 14)));
        }
        return all;
    }
}
