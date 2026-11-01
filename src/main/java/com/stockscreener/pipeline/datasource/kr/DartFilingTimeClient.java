package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DART 웹 「오늘의 공시」 한 날짜를 끝 쪽까지 받는다(ADR-0204 · {@link DartDayList}). 키가 필요 없는 공개 웹이라 1초에 1건으로 둔다. 모은 고유
 * 접수번호 수가 총 건수와 다르면 그 날짜는 실패다(쪽 넘김이 안 먹었거나 받는 사이 목록이 바뀌었다) — 반쪽 날짜를 「완결」로 쓰지 않는다.
 */
@Component
public class DartFilingTimeClient {

    private static final String BASE = "https://dart.fss.or.kr";
    private static final long SPACING_MILLIS = 1_000;
    private static final DateTimeFormatter DOTTED = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private long lastRequestAt;

    /** 그 날짜의 공시 줄 전부 — 완결일 때만 낸다. */
    public synchronized List<DartDayList.Row> day(LocalDate day) throws DataSourceException {
        String selectDate = day.format(DOTTED);
        Map<String, DartDayList.Row> rows = new LinkedHashMap<>();
        DartDayList.Page first = DartDayList.parse(post(selectDate, 1), selectDate + " 1쪽");
        first.rows().forEach(r -> rows.put(r.rceptNo(), r));
        for (int page = 2; page <= first.pages(); page++) {
            DartDayList.parse(post(selectDate, page), selectDate + " " + page + "쪽").rows().forEach(r -> rows.put(r.rceptNo(), r));
        }
        if (rows.size() != first.total()) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DartDayList.SOURCE,
                    "날짜 목록이 완결되지 않았다 — 고유 %d 대 총 %d · %s".formatted(rows.size(), first.total(), selectDate));
        }
        return new ArrayList<>(rows.values());
    }

    private String post(String selectDate, int page) throws DataSourceException {
        String form = "selectDate=" + selectDate + "&mdayCnt=0&currentPage=" + page + "&maxResults=100&maxLinks=10&sort=&series=&pageGrouping=";
        String context = selectDate + " " + page + "쪽";
        try {
            long wait = lastRequestAt + SPACING_MILLIS - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            lastRequestAt = System.currentTimeMillis();
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(BASE + "/dsac001/search.ax"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("User-Agent", "Mozilla/5.0")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DartDayList.SOURCE,
                        "HTTP " + response.statusCode() + " " + context);
            }
            return response.body();
        } catch (IOException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DartDayList.SOURCE, "받기 실패 " + context, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DartDayList.SOURCE, "받기가 중단됐다 " + context, e);
        }
    }
}
