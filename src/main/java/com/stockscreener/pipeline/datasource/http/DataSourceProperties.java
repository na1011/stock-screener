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

    /**
     * Alpaca <b>Trading API</b>. 자산 마스터({@code /v2/assets})와 마켓 캘린더가 여기에 있다.
     *
     * <p>IMPORTANT — 시세는 base URL이 다르다({@code https://data.alpaca.markets}). 섞으면
     * 404이고, 인증 헤더는 두 계열이 같아 <b>키가 틀린 것처럼 보이지 않는다.</b> 시세 어댑터를
     * 붙일 때는 이 {@code Source}의 키를 공유하고 base URL만 필드로 하나 더 둔다 —
     * {@link #getDataGoKrMarketIndexBaseUrl()}이 같은 이유로 그렇게 되어 있다.
     *
     * <p>이 소스만 <b>키가 둘이다</b>({@code api-key} = Key ID, {@code secret-key} = Secret).
     */
    private final Source alpaca = new Source("https://paper-api.alpaca.markets");

    /**
     * 지수시세정보 서비스의 base URL. 같은 제공기관(prefix {@code 1160100})의 <b>다른
     * 오퍼레이션 셋</b>이라 경로만 갈린다.
     *
     * <p>IMPORTANT — 키·타임아웃·한도를 {@link #getDataGoKr()}와 <b>공유한다.</b> 별도
     * {@code Source}로 두면 {@code api-key}를 두 벌 적게 되고, 한쪽만 비면
     * {@code @ConditionalOnApiKey}가 지수 어댑터만 등록하지 않는다 — 그러면 지수가 영영 안 들어오고
     * {@code EXCESS_RETURN_26W}가 비는데 <b>에러도 알림도 없다.</b> 키가 하나이므로 설정도 하나다.
     *
     * <p>활용신청은 서비스마다 별개이므로 한도는 실제로 따로 세어질 수 있다. 그래도 같은
     * 레이트리미터를 쓰는 이유는 지수 호출이 <b>하루 2콜</b>이라 어느 쪽으로 세든 차이가 없고,
     * 공유하는 편이 언제나 보수적이기 때문이다.
     */
    private String dataGoKrMarketIndexBaseUrl =
            "https://apis.data.go.kr/1160100/service/GetMarketIndexInfoService";

    public Source getDataGoKr() {
        return dataGoKr;
    }

    public String getDataGoKrMarketIndexBaseUrl() {
        return dataGoKrMarketIndexBaseUrl;
    }

    public void setDataGoKrMarketIndexBaseUrl(String dataGoKrMarketIndexBaseUrl) {
        this.dataGoKrMarketIndexBaseUrl = dataGoKrMarketIndexBaseUrl;
    }

    public Source getDart() {
        return dart;
    }

    public Source getAlpaca() {
        return alpaca;
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

        /**
         * 두 번째 자격증명. 지금은 Alpaca만 쓴다({@code APCA-API-SECRET-KEY}).
         *
         * <p>MUST NOT: 이것을 {@code @ConditionalOnApiKey}의 판정에 넣지 않는다. 키 하나만
         * 채워진 상태에서 어댑터를 등록하지 않으면 그것은 <b>조용한 미등록</b>이 되는데, 등록해
         * 두면 조회 시점에 {@code AUTH}로 실패하고 {@code fatal()}이 배치를 즉시 멈춘다 —
         * 그쪽이 훨씬 시끄럽다. 대신 어댑터가 생성 시점에 빈 값을 거부한다.
         *
         * <p>MUST NOT: 로그에 남기지 않는다. {@code apiKey}와 같은 이유다.
         */
        private String secretKey = "";

        /** 연결 타임아웃. 기본값(무한대)이면 응답하지 않는 소스가 배치 스레드를 영원히 붙잡는다. */
        private Duration connectTimeout = Duration.ofSeconds(5);

        /** 읽기 타임아웃. 재무 응답은 크고 느려 넉넉히 둔다. */
        private Duration readTimeout = Duration.ofSeconds(30);

        /** 분당 허용 호출 수. 0 이하면 속도 제한 없음. */
        private int permitsPerMinute;

        /** 일일 총량. 0 이하면 무제한. */
        private long dailyQuota;

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public boolean hasSecretKey() {
            return secretKey != null && !secretKey.isBlank();
        }

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
