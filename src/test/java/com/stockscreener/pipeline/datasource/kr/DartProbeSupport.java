package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.function.Function;

/**
 * DART 탐침이 공유하는 배선. 탐침마다 같은 세 줄(프로퍼티·클라이언트·corp_code 캐시)을
 * 다시 쓰던 것을 모았다.
 *
 * <p>탐침이 일곱 개가 되면서 만들었다. 복제본이 흩어져 있으면 레이트리밋 값 하나를 고칠 때
 * 일부만 고쳐지고, 어긋난 사실은 쿼터를 초과할 때에야 드러난다.
 *
 * <p><b>탐침 전용이다.</b> 운영 경로는 스프링이 이 배선을 대신하므로 여기 손대도 운영은
 * 바뀌지 않는다 — 반대로 여기 값을 운영과 맞추려 들지 않는다.
 */
final class DartProbeSupport {

    private DartProbeSupport() {
    }

    /**
     * IMPORTANT — DART는 인증 실패도 HTTP 200으로 답한다(api-spec §4).
     *
     * <p>탐침에서 이 구분이 특히 중요한 이유는 <b>{@code 013}(데이터 없음)과
     * {@code 100}(필드의 부적절한 값)이 둘 다 "빈 결과"로 보이기 때문</b>이다. 엔드포인트
     * 이름이나 파라미터를 틀린 것과 그 회사에 그 데이터가 없는 것이 같아 보이면, 탐침이
     * 존재할 이유 자체가 사라진다.
     */
    private static final Map<String, String> STATUS_MEANING = Map.ofEntries(
            Map.entry("000", "정상"),
            Map.entry("010", "등록되지 않은 인증키"),
            Map.entry("011", "사용할 수 없는 키(일시 중지)"),
            Map.entry("012", "접근할 수 없는 IP"),
            Map.entry("013", "조회된 데이터 없음 — 요청은 옳다"),
            Map.entry("014", "파일이 존재하지 않음"),
            Map.entry("020", "요청 제한 초과"),
            Map.entry("021", "조회 가능한 회사 개수 초과(최대 100)"),
            Map.entry("100", "필드의 부적절한 값 — 요청이 틀렸다"),
            Map.entry("101", "부적절한 접근"),
            Map.entry("800", "시스템 점검 중"),
            Map.entry("900", "정의되지 않은 오류"),
            Map.entry("901", "개인정보 보유기간 만료 키"));

    static DataSourceProperties properties() {
        DataSourceProperties properties = new DataSourceProperties();
        String key = System.getenv("DART_API_KEY");
        properties.getDart().setApiKey(key == null ? "" : key);
        return properties;
    }

    static String apiKey() {
        return System.getenv("DART_API_KEY");
    }

    static RestClient client() {
        return new ExternalApiClientFactory().create(properties().getDart());
    }

    static DartCorpCodeCache corpCodeCache() {
        return new DartCorpCodeCache(new ExternalApiClientFactory(), properties(),
                new TokenBucketRateLimiter("dart", 300, 0));
    }

    /** {@code status} 코드와 그 뜻. 코드만 찍으면 다음 세션이 표를 다시 찾아본다. */
    static String describeStatus(JsonNode body) {
        if (body == null) {
            return "(본문 없음)";
        }
        String status = body.path("status").asString("");
        return status + " " + STATUS_MEANING.getOrDefault(status, "(표에 없는 코드)");
    }

    /** 요청 하나. 호출부가 URI 조립만 신경 쓰도록 응답 본문까지 꺼내 준다. */
    static JsonNode get(RestClient client,
                        Function<org.springframework.web.util.UriBuilder,
                                java.net.URI> uri) {
        ResponseEntity<JsonNode> response = client.get().uri(uri::apply).retrieve()
                .toEntity(JsonNode.class);
        return response.getBody();
    }
}
