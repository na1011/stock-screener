package com.stockscreener.explore;

import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import com.stockscreener.pipeline.datasource.kr.DartCorpCodeCache;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 일회용 탐침 — 재무 동기 2부 「불변성 관찰 규칙 v2」의 원시 응답 저장(어댑터를 거치지 않는다). 표본 계획 TSV
 * (티커 · 사업연도 · 보고서코드 · 층 · DB 공시일)마다 {@code fnlttSinglAcnt}(FR) 한 콜과 {@code fnlttSinglAcntAll}(FSR) CFS·OFS
 * 두 콜을 순차로 불러 바이트 그대로 저장하고, 색인 TSV에 HTTP 상태 · DART status · 접수번호 집합 · 행 수를 적는다. 키는 어디에도
 * 안 찍는다. 같은 계획으로 40일 이상 뒤 다시 돌려 원시 행 전 칸을 비교한다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+")
class DartRawSnapshotLiveTest {

    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"(\\d+)\"");
    private static final Pattern RCEPT = Pattern.compile("\"rcept_no\"\\s*:\\s*\"(\\d+)\"");

    @Test
    void snapshot() throws Exception {
        Path plan = Path.of(System.getenv("PROBE_PLAN"));
        Path out = Path.of(System.getenv("PROBE_OUT"));
        Files.createDirectories(out);
        String key = System.getenv("DART_API_KEY");
        DataSourceProperties properties = new DataSourceProperties();
        properties.getDart().setApiKey(key);
        DartCorpCodeCache corpCodes = new DartCorpCodeCache(new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("dart", 300, 0));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

        List<String> index = new ArrayList<>();
        index.add("ticker\tyear\treprt\tstratum\tendpoint\tfs_div\thttp\tstatus\trows\trcept_nos\tbytes\tat");
        int calls = 0;
        for (String line : Files.readAllLines(plan)) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\t");
            String ticker = f[0], year = f[1], reprt = f[2], stratum = f[3];
            String corp = corpCodes.findCorpCode(ticker).orElse(null);
            if (corp == null) {
                index.add(String.join("\t", ticker, year, reprt, stratum, "-", "-", "-", "corp_code 없음", "", "", "", ""));
                continue;
            }
            for (String[] call : List.of(new String[] {"fnlttSinglAcnt", ""}, new String[] {"fnlttSinglAcntAll", "CFS"},
                    new String[] {"fnlttSinglAcntAll", "OFS"})) {
                String query = "crtfc_key=" + key + "&corp_code=" + corp + "&bsns_year=" + year + "&reprt_code=" + reprt
                        + (call[1].isEmpty() ? "" : "&fs_div=" + call[1]);
                HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(
                        URI.create("https://opendart.fss.or.kr/api/" + call[0] + ".json?" + query))
                        .timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                calls++;
                byte[] body = response.body();
                String name = "%s_%s_%s_%s%s.json".formatted(ticker, year, reprt, call[0], call[1].isEmpty() ? "" : "_" + call[1]);
                Files.write(out.resolve(name), body);
                String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                Matcher status = STATUS.matcher(text);
                TreeSet<String> rcepts = new TreeSet<>();
                Matcher r = RCEPT.matcher(text);
                int rows = 0;
                while (r.find()) {
                    rcepts.add(r.group(1));
                    rows++;
                }
                index.add(String.join("\t", ticker, year, reprt, stratum, call[0], call[1], String.valueOf(response.statusCode()),
                        status.find() ? status.group(1) : "?", String.valueOf(rows), String.join(",", rcepts),
                        String.valueOf(body.length), LocalDateTime.now().toString()));
                Thread.sleep(250);
            }
        }
        Files.write(out.resolve("index.tsv"), index);
        System.out.println("PROBE snapshot calls=" + calls + " rows=" + (index.size() - 1) + " out=" + out);
    }
}
