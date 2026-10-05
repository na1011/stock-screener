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
            String url = "https://opendart.fss.or.kr/api/list.json?crtfc_key="
                    + URLEncoder.encode(System.getenv("DART_API_KEY"), StandardCharsets.UTF_8) + "&" + query;
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            Files.writeString(out.resolve("q" + (++i) + ".json"), res.body());
            String head = res.body().length() > 160 ? res.body().substring(0, 160) : res.body();
            System.out.printf("PROBE q%d %d %s :: %s%n", i, res.statusCode(), query, head.replace('\n', ' '));
            Thread.sleep(700);
        }
    }
}
