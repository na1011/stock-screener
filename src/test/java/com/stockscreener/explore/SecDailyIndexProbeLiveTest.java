package com.stockscreener.explore;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/** 일회용 탐침 — SEC 일일 색인이 언제 뜨고 무엇을 싣는가(재무 동기 2부 실측). UA는 출력에 안 찍는다. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SEC_EDGAR_USER_AGENT", matches = ".+")
class SecDailyIndexProbeLiveTest {

    @Test
    void fetch() throws Exception {
        Path out = Path.of(System.getenv("PROBE_OUT"));
        Files.createDirectories(out);
        HttpClient http = HttpClient.newHttpClient();
        for (String url : System.getenv("PROBE_URLS").split(",")) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT")).GET().build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            String name = url.substring(url.lastIndexOf('/') + 1);
            if (name.isEmpty()) {
                name = "listing.html";
            }
            Files.write(out.resolve(name), res.body());
            System.out.printf("PROBE %d %s bytes=%d last-modified=%s%n", res.statusCode(), url, res.body().length,
                    res.headers().firstValue("last-modified").orElse("-"));
            Thread.sleep(300);
        }
    }
}
