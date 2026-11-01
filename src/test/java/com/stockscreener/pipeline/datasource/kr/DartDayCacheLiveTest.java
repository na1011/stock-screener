package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>국내 공시 게시 시각 캐시 채우기</b>(관문 전 · ADR-0204) — DART 웹 「오늘의 공시」를 날짜마다 끝 쪽까지 받아 {@code DART_DAY_DIR/<yyyyMMdd>.tsv}에
 * 쓴다(첫 줄 {@code total=<n>} · 줄마다 접수번호 · 기업 고유번호 · 회사명 · 보고서명 · 접수일자 · 게시 시각). <b>모은 고유 접수번호 수 = 총 건수</b>일
 * 때만 쓴다(원자적) — 아니면 그 날짜는 실패로 남기고 다음 날짜로 간다. 이미 있는 날짜 파일은 건너뛴다(끊겨도 이어 받는다). 오늘(KST)과 그 뒤는
 * 안 받는다 — 그날 목록은 계속 늘어난다. 1초에 1건.
 *
 * <pre>
 * DART_DAY_DIR=~/.stock-screener/dart-day DART_DAY_DATES=&lt;yyyy-MM-dd 한 줄에 하나&gt; ./gradlew liveTest --tests "*DartDayCacheLiveTest*"
 * </pre>
 */
@Tag("live")
class DartDayCacheLiveTest {

    private static final String BASE = "https://dart.fss.or.kr";

    @Test
    @DisplayName("날짜마다 「오늘의 공시」 전 쪽을 받아 캐시에 쓴다(완결일 때만)")
    void fillCache() throws Exception {
        String dir = System.getenv("DART_DAY_DIR");
        String dates = System.getenv("DART_DAY_DATES");
        Assumptions.assumeTrue(dir != null && dates != null, "DART_DAY_DIR · DART_DAY_DATES가 없어 건너뛴다");
        Files.createDirectories(Path.of(dir));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        int written = 0;
        int failed = 0;
        int skipped = 0;
        for (String line : Files.readAllLines(Path.of(dates))) {
            if (line.isBlank()) {
                continue;
            }
            LocalDate day = LocalDate.parse(line.strip());
            Path file = Path.of(dir, day.format(DateTimeFormatter.BASIC_ISO_DATE) + ".tsv");
            if (!day.isBefore(today) || Files.exists(file)) {
                skipped++;
                continue;
            }
            String selectDate = day.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
            try {
                Map<String, DartDayList.Row> rows = new LinkedHashMap<>();
                DartDayList.Page first = DartDayList.parse(post(http, selectDate, 1), selectDate + " 1쪽");
                first.rows().forEach(r -> rows.put(r.rceptNo(), r));
                for (int page = 2; page <= first.pages(); page++) {
                    DartDayList.Page next = DartDayList.parse(post(http, selectDate, page), selectDate + " " + page + "쪽");
                    next.rows().forEach(r -> rows.put(r.rceptNo(), r));
                }
                if (rows.size() != first.total()) {
                    System.out.printf("미완결 %s — 고유 %d 대 총 %d%n", day, rows.size(), first.total());
                    failed++;
                    continue;
                }
                StringBuilder body = new StringBuilder("total=" + first.total() + "\n");
                for (DartDayList.Row r : rows.values()) {
                    body.append(String.join("\t", r.rceptNo(), r.corpCode() == null ? "" : r.corpCode(), r.corpName().replace('\t', ' '),
                            r.reportName().replace('\t', ' '), r.receiptDate().toString(), r.time().toString())).append('\n');
                }
                Path part = file.resolveSibling(file.getFileName() + ".part");
                Files.writeString(part, body, StandardCharsets.UTF_8);
                Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                written++;
            } catch (Exception e) {
                System.out.printf("실패 %s — %s%n", day, e.getMessage());
                failed++;
            }
        }
        System.out.printf("written=%d failed=%d skipped=%d%n", written, failed, skipped);
    }

    private static String post(HttpClient http, String selectDate, int page) throws Exception {
        String form = "selectDate=" + selectDate + "&mdayCnt=0&currentPage=" + page + "&maxResults=100&maxLinks=10&sort=&series=&pageGrouping=";
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            Thread.sleep(1_000);
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(BASE + "/dsac001/search.ax"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        .header("User-Agent", "Mozilla/5.0")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) {
                    return response.body();
                }
                last = new IllegalStateException("HTTP " + response.statusCode());
            } catch (java.io.IOException e) {
                last = e;
            }
            Thread.sleep(5_000L * attempt);
        }
        throw last;
    }
}
