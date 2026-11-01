package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * <b>재료 받기</b> — SEC Financial Statement Data Sets(분기 ZIP · {@code pre.txt}가 공시마다 개념이 실린 재무제표 {@code stmt}를 준다)를
 * {@code FSDS_DIR}에 둔다. {@code FSDS_QUARTERS}(예 {@code 2023q1,2023q2}) · 분기당 1콜. 이미 있으면 안 받는다.
 *
 * <p>MUST NOT: {@code SEC_EDGAR_USER_AGENT}를 출력에 찍지 않는다 — 이메일이 들어 있다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FSDS_QUARTERS", matches = ".+", disabledReason = "FSDS_QUARTERS가 없어 건너뛴다")
class SecFinancialStatementDataSetLiveTest {

    @Test
    @DisplayName("[live] Financial Statement Data Sets 분기 ZIP을 받는다")
    void download() throws Exception {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        Path dir = Path.of(System.getenv("FSDS_DIR"));
        Files.createDirectories(dir);
        for (String quarter : System.getenv("FSDS_QUARTERS").split(",")) {
            Path target = dir.resolve(quarter + ".zip");
            if (Files.exists(target)) {
                continue;
            }
            Thread.sleep(500);
            HttpResponse<Path> res = http.send(HttpRequest.newBuilder(URI.create(
                            "https://www.sec.gov/files/dera/data/financial-statement-data-sets/" + quarter + ".zip"))
                    .header("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT")).build(),
                    HttpResponse.BodyHandlers.ofFile(dir.resolve(quarter + ".zip.part")));
            if (res.statusCode() != 200) {
                Files.deleteIfExists(dir.resolve(quarter + ".zip.part"));
                System.out.println("받기 실패 " + quarter + " HTTP " + res.statusCode());
                continue;
            }
            Files.move(dir.resolve(quarter + ".zip.part"), target);
            System.out.println("받음 " + quarter + " bytes=" + Files.size(target));
        }
    }
}
