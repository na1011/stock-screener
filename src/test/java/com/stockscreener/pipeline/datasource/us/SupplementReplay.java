package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 같은 날 모은 공시 보충 원자료({@link SecFilingSupplementCacheLiveTest})를 SEC 두 호스트처럼 내주는 로컬 서버와 메모리
 * 원장 — 운영 {@link SecFilingSupplement}를 <b>한 줄도 안 바꾸고</b> 0콜로 태운다(ADR-0173 · RS0·RS1).
 *
 * <p>{@code <dir>/sec/<URL 경로>}가 있으면 200, {@code <경로>.status}가 있으면 그 코드, 둘 다 없으면 404이고 그 수를
 * {@link #missing()}에 센다 — 수집이 빠뜨린 원자료다(0이어야 한다).
 */
final class SupplementReplay implements AutoCloseable {

    private final MockWebServer server;
    private final SecFilingSupplement supplement;
    private final AtomicInteger missing = new AtomicInteger();

    private SupplementReplay(Path dir) throws IOException {
        Path root = dir.resolve("sec");
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                Path file = root.resolve(request.getPath().substring(1));
                Path status = file.resolveSibling(file.getFileName() + ".status");
                try {
                    if (Files.exists(status)) {
                        return new MockResponse().setResponseCode(Integer.parseInt(Files.readString(status).trim()));
                    }
                    if (Files.exists(file)) {
                        return new MockResponse().setResponseCode(200).setBody(new Buffer().write(Files.readAllBytes(file)));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                missing.incrementAndGet();
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();
        DataSourceProperties properties = new DataSourceProperties();
        properties.getSecEdgar().setApiKey("replay");
        String base = server.url("/").toString();
        SecFilingClient client = new SecFilingClient(new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("sec-edgar", 0, 0), base, base);
        supplement = new SecFilingSupplement(client, new MemorySupplementLedger());
    }

    static SupplementReplay start(Path dir) {
        try {
            return new SupplementReplay(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    SecFilingSupplement supplement() {
        return supplement;
    }

    int missing() {
        return missing.get();
    }

    @Override
    public void close() {
        try {
            server.shutdown();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
