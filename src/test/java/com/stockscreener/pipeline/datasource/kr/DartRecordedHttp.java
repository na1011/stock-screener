package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * DART 응답을 <b>녹화 · 재생</b>하는 클라이언트 팩토리 — 국내 0콜 스윕의 재료(미국 {@code .factcache}에 대응). 실제 어댑터를 그대로 돌리므로 호출
 * 논리를 복제하지 않는다. 키는 경로 + 정렬한 쿼리(인증키 {@code crtfc_key} 제외)다.
 *
 * <p>MUST: 재생에서 저장본이 없는 키는 큰 소리로 실패한다 — 「응답 없음」을 지어내면 소스의 {@code 013}(자료 없음)과 구분되지 않는다. 녹화는
 * {@code 013}도 그대로 저장한다.
 */
final class DartRecordedHttp extends ExternalApiClientFactory {

    enum Mode { RECORD, REPLAY }

    private final Path dir;
    private final Mode mode;

    DartRecordedHttp(Path dir, Mode mode) {
        this.dir = dir;
        this.mode = mode;
    }

    @Override
    public RestClient create(DataSourceProperties.Source source) {
        RestClient base = super.create(source);
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            Path file = fileFor(request.getURI());
            if (mode == Mode.REPLAY) {
                if (!Files.exists(file)) {
                    throw new IllegalStateException("녹화본이 없다: " + dir.relativize(file));
                }
                return response(Files.readAllBytes(file));
            }
            ClientHttpResponse live = execution.execute(request, body);
            byte[] bytes;
            try (InputStream in = live.getBody()) {
                bytes = in.readAllBytes();
            }
            if (live.getStatusCode().is2xxSuccessful()) {
                Files.createDirectories(file.getParent());
                Files.write(file, bytes);
            }
            HttpStatusCode status = live.getStatusCode();
            HttpHeaders headers = live.getHeaders();
            return new ClientHttpResponse() {
                @Override public HttpStatusCode getStatusCode() { return status; }
                @Override public String getStatusText() { return ""; }
                @Override public void close() { }
                @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
                @Override public HttpHeaders getHeaders() { return headers; }
            };
        };
        return base.mutate().requestInterceptor(interceptor).build();
    }

    Path fileFor(URI uri) {
        Map<String, String> params = new TreeMap<>();
        UriComponentsBuilder.fromUri(uri).build().getQueryParams().forEach((k, v) -> {
            if (!"crtfc_key".equals(k) && !v.isEmpty()) {
                params.put(k, v.get(0));
            }
        });
        String path = uri.getPath().replaceAll(".*/", "");
        StringBuilder name = new StringBuilder(path);
        params.forEach((k, v) -> name.append('_').append(k).append('=').append(v));
        String corp = params.getOrDefault("corp_code", "_");
        return dir.resolve(corp).resolve(name.toString().replaceAll("[^A-Za-z0-9_=.-]", "-"));
    }

    private static ClientHttpResponse response(byte[] bytes) {
        HttpHeaders headers = new HttpHeaders();
        boolean zip = bytes.length > 1 && bytes[0] == 'P' && bytes[1] == 'K';
        headers.setContentType(zip ? MediaType.APPLICATION_OCTET_STREAM : MediaType.APPLICATION_JSON);
        return new ClientHttpResponse() {
            @Override public HttpStatusCode getStatusCode() { return HttpStatus.OK; }
            @Override public String getStatusText() { return "OK"; }
            @Override public void close() { }
            @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
            @Override public HttpHeaders getHeaders() { return headers; }
        };
    }

    static void requireDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
