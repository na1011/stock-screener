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
import java.util.Optional;
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

    private SupplementReplay(Path dir, SupplementLedger ledger) throws IOException {
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
        supplement = new SecFilingSupplement(client, ledger);
    }

    /** 메모리 원장 — 한 JVM 안에서만 「한 번 받은 것」을 기억한다. */
    static SupplementReplay start(Path dir) {
        return start(dir, new MemorySupplementLedger());
    }

    /** 주어진 원장 — 사본 DB의 {@code sec_filing_supplement}를 주면 두 번째 재생이 저장분 경로를 탄다. */
    static SupplementReplay start(Path dir, SupplementLedger ledger) {
        try {
            return new SupplementReplay(dir, ledger);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 도구의 보충 설정을 읽는다 — <b>명시가 필수다</b>. 보충은 어댑터 출력의 일부라(ADR-0173) 안 주면 운영과 다른 출력을
     * 조용히 낸다. 관문 재고의 수를 그 출력으로 재면 재적재와 갈린다. 그래서 경로나 {@code off}를 반드시 받는다.
     */
    static Optional<Path> requireSetting(String variable) {
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + "가 없다 — 보충 원자료 경로(SecFilingSupplementCacheLiveTest가 모은 것)나 "
                    + "off를 준다. 안 주면 운영 어댑터와 다른 출력(보충 전)을 조용히 낸다(ADR-0173).");
        }
        return "off".equals(value) ? Optional.empty() : Optional.of(Path.of(value));
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
