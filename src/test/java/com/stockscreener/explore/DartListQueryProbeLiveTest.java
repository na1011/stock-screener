package com.stockscreener.explore;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 일회용 탐침 — DART 공시검색(list.json)이 날짜 구간으로 무엇을 주는가(재무 동기 2부 실측). 키는 출력에 안 찍는다. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+")
class DartListQueryProbeLiveTest {

    @Test
    void fetch() throws Exception {
        Path out = Path.of(System.getenv("PROBE_OUT"));
        Files.createDirectories(out);
        HttpClient http = HttpClient.newHttpClient();
        int i = 0;
        for (String query : System.getenv("PROBE_QUERIES").split(";")) {
            // "경로?쿼리"면 그 엔드포인트, 아니면 list.json.
            String path = query.contains("?") ? query.substring(0, query.indexOf('?')) : "list.json";
            String rest = query.contains("?") ? query.substring(query.indexOf('?') + 1) : query;
            String url = "https://opendart.fss.or.kr/api/" + path + "?crtfc_key="
                    + URLEncoder.encode(System.getenv("DART_API_KEY"), StandardCharsets.UTF_8) + "&" + rest;
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            Files.write(out.resolve("q" + (++i) + (path.endsWith(".xml") ? ".zip" : ".json")), res.body());
            String text = new String(res.body(), StandardCharsets.UTF_8);
            String head = path.endsWith(".xml") ? "bytes=" + res.body().length
                    : text.length() > 160 ? text.substring(0, 160) : text;
            System.out.printf("PROBE q%d %d %s :: %s%n", i, res.statusCode(), query, head.replace('\n', ' '));
            Thread.sleep(700);
        }
    }
}
