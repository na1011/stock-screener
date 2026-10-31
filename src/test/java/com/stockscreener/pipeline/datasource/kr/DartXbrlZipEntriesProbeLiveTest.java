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
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * <b>탐침</b> — 재무제표 원본 ZIP({@code fnlttXbrl.xml})이 인스턴스 밖에 무엇을 싣는가(이름표 링크베이스가 있으면 표준계정코드 없는 줄의 계정명을
 * 인스턴스로 다시 지을 수 있다 · ADR-0201 「못 옮기는 것」). 1콜. {@code XBRL_PROBE_RCEPT}의 접수번호 하나를 묻는다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+", disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartXbrlZipEntriesProbeLiveTest {

    @Test
    @DisplayName("[live][probe] 원본 ZIP의 항목과 한국어 이름표의 회사 확장 개념 수")
    void listEntries() throws Exception {
        String rcept = System.getenv().getOrDefault("XBRL_PROBE_RCEPT", "20251114000000");
        HttpResponse<byte[]> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                "https://opendart.fss.or.kr/api/fnlttXbrl.xml?crtfc_key=" + System.getenv("DART_API_KEY") + "&rcept_no=" + rcept
                        + "&reprt_code=11011")).build(), HttpResponse.BodyHandlers.ofByteArray());
        System.out.println("status=" + res.statusCode() + " bytes=" + res.body().length);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(res.body()))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                byte[] body = zip.readAllBytes();
                String text = new String(body, StandardCharsets.UTF_8);
                long entityLabels = text.split("xlink:label=\"[^\"]*entity", -1).length - 1;
                long ko = text.split("xml:lang=\"ko", -1).length - 1;
                System.out.println("entry " + e.getName() + " bytes=" + body.length + " ko=" + ko + " entityRefs=" + entityLabels);
                String keep = System.getenv("XBRL_PROBE_SAVE_DIR");
                if (keep != null && DartXbrlClient.koreanLabels(e.getName())) {
                    java.nio.file.Files.write(java.nio.file.Path.of(keep, rcept + ".lab-ko.xml"), body);
                }
            }
        }
    }
}
