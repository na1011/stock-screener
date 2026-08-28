package com.stockscreener.pipeline.datasource.http;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 외부 API용 {@link RestClient}를 만든다.
 *
 * <p>MUST: 타임아웃을 명시한다. 기본값은 무한대이며, 응답하지 않는 소스 하나가 배치 스레드를
 * 영원히 붙잡으면 그날 밤 배치가 통째로 사라진다. 그것은 실패로도 기록되지 않으므로
 * 아침에 원인을 알 수 없다.
 *
 * <p>상태코드 핸들러를 등록하지 <b>않는다</b>. 어댑터가 상태코드와 본문을 함께 보고
 * 판단해야 하기 때문이다 — 두 소스의 실패 표현이 정반대라(한쪽은 403, 다른 쪽은 200에
 * status 필드) 공통 핸들러로 뭉뚱그릴 수 없다. 대신 {@code toEntity}로 받아 어댑터가
 * 직접 분기한다.
 */
@Component
public class ExternalApiClientFactory {

    public RestClient create(DataSourceProperties.Source source) {
        return create(source, source.getBaseUrl());
    }

    /**
     * 타임아웃과 키는 {@code source}에서 가져오되 base URL만 갈아끼운다.
     *
     * <p>같은 발급 키로 <b>같은 제공기관의 다른 서비스</b>를 부를 때 쓴다 — 공공데이터포털은
     * prefix가 같아도 오퍼레이션 셋마다 경로가 다르다. 키를 두 벌로 두지 않으려는 것이므로,
     * 이 오버로드를 지우면 설정에 키가 하나 더 생긴다({@code DataSourceProperties} 참조).
     */
    public RestClient create(DataSourceProperties.Source source, String baseUrl) {
        return create(source, baseUrl, Map.of());
    }

    /**
     * 인증을 <b>헤더로</b> 거는 소스용.
     *
     * <p>국내 두 소스는 인증이 쿼리 파라미터라 이 경로를 쓰지 않는다. Alpaca가
     * ({@code APCA-API-KEY-ID}·{@code APCA-API-SECRET-KEY}) 첫 사례이고, SEC EDGAR의
     * {@code User-Agent}도 같은 문을 쓴다.
     *
     * <p>기본 헤더로 두는 이유는 <b>한 곳을 빠뜨린 순간 401</b>이고 그 401이 "키가 틀렸다"로
     * 읽히기 때문이다. 호출부마다 붙이면 그 실수가 언제든 다시 난다.
     *
     * <p>MUST NOT: 이 맵을 로그에 찍지 않는다. 시크릿이 값으로 들어온다 —
     * {@code Source}에 {@code toString()}을 만들지 않은 것과 같은 이유다.
     */
    public RestClient create(DataSourceProperties.Source source, String baseUrl,
                             Map<String, String> defaultHeaders) {
        // JDK HttpClient 기반 빌더 대신 단순 팩토리를 쓰는 이유는, 필요한 것이
        // 타임아웃 두 개뿐이고 이 API가 Spring 버전에 걸쳐 안정적이기 때문이다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) source.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) source.getReadTimeout().toMillis());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                // 4xx·5xx에 예외를 던지지 않게 한다. 어댑터가 본문까지 보고 Kind를 정한다.
                .defaultStatusHandler(status -> true, (request, response) -> { });
        defaultHeaders.forEach(builder::defaultHeader);
        return builder.build();
    }

    /**
     * 발급 키를 URI 빌더에 넘길 수 있는 형태로 정규화한다.
     *
     * <p>IMPORTANT — 공공데이터포털이 발급하는 키는 <b>이미 URL 인코딩된</b> 형태다
     * ({@code %2F}·{@code %3D} 포함). 이것을 그대로 쿼리 파라미터로 넘기면 URI 빌더가
     * 다시 인코딩해 {@code %252F}가 되고 인증에 실패한다. 실측에서 디코딩 후 넘겨야
     * 통과했다.
     *
     * <p>이미 디코딩된 키를 넣어도 안전하다 — {@code URLDecoder}는 인코딩되지 않은
     * 문자열을 그대로 돌려준다. 다만 {@code +}는 공백으로 바뀌므로, 키에 {@code +}가
     * 있으면 이 함수를 쓸 수 없다. 실측한 키에는 {@code +}가 없었고, 공공데이터포털은
     * Base64를 URL-safe하게 인코딩해 내보내므로 {@code +} 대신 {@code %2B}로 온다.
     */
    public static String normalizeServiceKey(String issuedKey) {
        if (issuedKey == null || issuedKey.isBlank()) {
            return "";
        }
        return URLDecoder.decode(issuedKey, StandardCharsets.UTF_8);
    }
}
