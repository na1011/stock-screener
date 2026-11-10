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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DART 웹 공시 뷰어에서 공시 본문 한 장을 받는다(국내 잠정실적 · {@link DartProvisionalDocument}). 키가 필요 없는 공개 웹이라 1초에 1건이다
 * ({@link DartFilingTimeClient}와 같은 간격). 두 걸음이다 — {@code dsaf001/main.do?rcpNo=}의 첫 {@code viewDoc(...)} 호출이 본문 문서의
 * 좌표(접수번호 · 문서번호 · 요소 · 위치 · 길이 · 형식)를 주고, {@code report/viewer.do}가 그 좌표의 HTML을 준다(2026-11-10 실측 1,091건).
 * 좌표가 없으면 원문 이상이라 던진다 — 빈 본문을 「값 없음」으로 저장하지 않는다.
 */
@Component
public class DartViewerClient {

    public static final String SOURCE = "dart-viewer";

    private static final String BASE = "https://dart.fss.or.kr";
    private static final long SPACING_MILLIS = 1_000;
    private static final Pattern VIEW_DOC = Pattern.compile(
            "viewDoc\\(\\s*\"(\\d+)\"\\s*,\\s*\"(\\d+)\"\\s*,\\s*\"([^\"]*)\"\\s*,\\s*\"([^\"]*)\"\\s*,\\s*\"([^\"]*)\"\\s*,\\s*\"([^\"]*)\"");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private long lastRequestAt;

    /** 그 접수번호 본문의 원문 바이트 — 해석은 하지 않는다(캐시에 그대로 둔다). */
    public synchronized byte[] document(String rceptNo) throws DataSourceException {
        if (!rceptNo.matches("\\d{14}")) {
            throw new IllegalArgumentException("접수번호가 아니다: " + rceptNo);
        }
        String main = new String(get("/dsaf001/main.do?rcpNo=" + rceptNo, rceptNo + " 목차"), StandardCharsets.UTF_8);
        Matcher v = VIEW_DOC.matcher(main);
        if (!v.find()) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE, "본문 좌표(viewDoc)가 없다 " + rceptNo);
        }
        byte[] body = get("/report/viewer.do?rcpNo=%s&dcmNo=%s&eleId=%s&offset=%s&length=%s&dtd=%s".formatted(v.group(1), v.group(2),
                v.group(3), v.group(4), v.group(5), v.group(6)), rceptNo + " 본문");
        // 글자가 이미 대체 문자(U+FFFD)로 깨져 오는 응답이 있다(2026-11-10 · 같은 좌표를 curl 기본 머리로 받으면 1,531자가 깨졌다) —
        // 캐시에 두면 해석이 「서식 없음」으로 접혀 그 공시가 영영 못 읽음이 된다. 다음 실행이 다시 받는다.
        if (containsReplacementCharacter(body)) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE, "본문 글자가 깨져 왔다(U+FFFD) " + rceptNo);
        }
        return body;
    }

    static boolean containsReplacementCharacter(byte[] body) {
        for (int i = 0; i + 2 < body.length; i++) {
            if (body[i] == (byte) 0xEF && body[i + 1] == (byte) 0xBF && body[i + 2] == (byte) 0xBD) {
                return true;
            }
        }
        return false;
    }

    private byte[] get(String path, String context) throws DataSourceException {
        try {
            long wait = lastRequestAt + SPACING_MILLIS - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            lastRequestAt = System.currentTimeMillis();
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(30)).header("User-Agent", "Mozilla/5.0").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE, "HTTP " + response.statusCode() + " " + context);
            }
            return response.body();
        } catch (IOException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE, "받기 실패 " + context, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE, "받기가 중단됐다 " + context, e);
        }
    }
}
