package com.stockscreener.pipeline.datasource.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 외부 소스 접근 설정.
 *
 * <p>MUST NOT: 실제 키를 이 클래스의 기본값이나 yaml에 적지 않는다. yaml은
 * {@code ${DATA_GO_KR_SERVICE_KEY:}} 형태의 환경변수 참조만 두고, 값은 실행 시점에 주입된다.
 *
 * <p>키가 비어 있어도 애플리케이션은 기동한다. 어댑터가 조회 시점에 {@code AUTH}로 실패하며,
 * 그것이 기동 실패보다 낫다 — 키 없이도 나머지 파이프라인을 개발·검증할 수 있어야 한다.
 */
@ConfigurationProperties(prefix = "stockscreener.datasource")
public class DataSourceProperties {

    private final Source dataGoKr = new Source(
            "https://apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService");
    private final Source dart = new Source("https://opendart.fss.or.kr/api");

    public Source getDataGoKr() {
        return dataGoKr;
    }

    public Source getDart() {
        return dart;
    }

    /** 소스 하나의 접속·한도 설정. */
    public static class Source {

        private String baseUrl;

        /**
         * API 키. 비어 있으면 어댑터가 조회 시점에 {@code AUTH}로 실패한다.
         *
         * <p>MUST NOT: 이 값을 로그에 남기지 않는다. {@code toString()}을 만들지 않은 것도
         * 같은 이유다 — 프로퍼티 객체를 통째로 로그에 찍는 실수를 막는다.
         */
        private String apiKey = "";

        /** 연결 타임아웃. 기본값(무한대)이면 응답하지 않는 소스가 배치 스레드를 영원히 붙잡는다. */
        private Duration connectTimeout = Duration.ofSeconds(5);

        /** 읽기 타임아웃. 재무 응답은 크고 느려 넉넉히 둔다. */
        private Duration readTimeout = Duration.ofSeconds(30);

        /** 분당 허용 호출 수. 0 이하면 속도 제한 없음. */
        private int permitsPerMinute;

        /** 일일 총량. 0 이하면 무제한. */
        private long dailyQuota;

        Source(String defaultBaseUrl) {
            this.baseUrl = defaultBaseUrl;
        }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public int getPermitsPerMinute() {
            return permitsPerMinute;
        }

        public void setPermitsPerMinute(int permitsPerMinute) {
            this.permitsPerMinute = permitsPerMinute;
        }

        public long getDailyQuota() {
            return dailyQuota;
        }

        public void setDailyQuota(long dailyQuota) {
            this.dailyQuota = dailyQuota;
        }
    }
}
