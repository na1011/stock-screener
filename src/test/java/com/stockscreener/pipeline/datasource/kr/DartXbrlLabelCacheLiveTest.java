package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * <b>재료 받기</b> — 목록(회사 코드 · 접수번호)의 원본 ZIP에서 한국어 이름표만 꺼내 수집기와 같은 자리({@code <캐시>/<회사>/<접수>.lab-ko.xml.gz})에
 * 둔다. 대조 스윕({@code DartXbrlStatementSweepTest})의 이름표 경로 검증 재료다. 접수번호당 1콜 · 분당 400 아래로.
 *
 * <pre>
 * LABEL_TARGETS=&lt;tsv&gt; KR_XBRL_DIR=~/.stock-screener/kr-xbrl ./gradlew liveTest --tests "*DartXbrlLabelCacheLiveTest*"
 * </pre>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "LABEL_TARGETS", matches = ".+", disabledReason = "LABEL_TARGETS가 없어 건너뛴다")
class DartXbrlLabelCacheLiveTest {

    @Test
    @DisplayName("[live] 목록의 원본 ZIP에서 한국어 이름표를 캐시한다")
    void cacheLabels() throws Exception {
        List<String> lines = Files.readAllLines(Path.of(System.getenv("LABEL_TARGETS"))).stream().filter(l -> !l.isBlank()).toList();
        HttpClient http = HttpClient.newHttpClient();
        int saved = 0;
        int missing = 0;
        for (String line : lines) {
            String[] v = line.split("\t");
            Path target = Path.of(System.getenv("KR_XBRL_DIR"), v[0], v[1] + ".lab-ko.xml.gz");
            if (Files.exists(target)) {
                continue;
            }
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create("https://opendart.fss.or.kr/api/fnlttXbrl.xml?crtfc_key="
                    + System.getenv("DART_API_KEY") + "&rcept_no=" + v[1] + "&reprt_code=11011")).build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = res.body();
            byte[] labels = res.statusCode() == 200 && body.length > 2 && body[0] == 'P' && body[1] == 'K'
                    ? DartXbrlClient.entry(body, DartXbrlClient::koreanLabels, v[1]) : null;
            if (labels == null) {
                missing++;
                System.out.println("이름표 없음 " + v[1] + " status=" + res.statusCode());
            } else {
                Files.createDirectories(target.getParent());
                Path part = target.resolveSibling(target.getFileName() + ".part");
                try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(part))) {
                    out.write(labels);
                }
                Files.move(part, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                saved++;
            }
            Thread.sleep(150);
        }
        System.out.printf("targets=%d saved=%d missing=%d%n", lines.size(), saved, missing);
    }
}
