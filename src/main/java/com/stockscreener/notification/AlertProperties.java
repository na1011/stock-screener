package com.stockscreener.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 알림 채널 설정.
 *
 * <p>MUST NOT: 실제 웹훅 URL·메일 자격증명을 이 클래스의 기본값이나 yaml에 적지 않는다.
 * 웹훅 URL은 그 자체가 자격증명이다 — 아는 사람은 누구나 그 채널에 글을 쓸 수 있다.
 * yaml에는 환경변수 참조만 두고 값은 실행 시점에 주입된다.
 *
 * <p>{@code toString()}을 만들지 않는 것도 {@code DataSourceProperties}와 같은 이유다.
 * 프로퍼티 객체를 통째로 로그에 찍는 실수를 막는다.
 */
@ConfigurationProperties(prefix = "stockscreener.alert")
public class AlertProperties {

    private final Webhook webhook = new Webhook();
    private final Mail mail = new Mail();

    /**
     * 같은 사건을 다시 통지하기까지의 최소 간격.
     *
     * <p>일일 배치는 하루에 한 번이므로 이 창에 걸리지 않는다. 이것이 막는 것은 <b>짧은 간격의
     * 재시도 루프</b>다 — 관리자가 실패한 배치를 연속으로 재실행하면 같은 키의 알림이
     * 몇 분 안에 여러 번 나가고, 그 반복이 채널을 덮어 정작 다른 알림을 가린다.
     */
    private Duration suppressionWindow = Duration.ofMinutes(10);

    public Webhook getWebhook() {
        return webhook;
    }

    public Mail getMail() {
        return mail;
    }

    public Duration getSuppressionWindow() {
        return suppressionWindow;
    }

    public void setSuppressionWindow(Duration suppressionWindow) {
        this.suppressionWindow = suppressionWindow;
    }

    /** 메신저 웹훅. Slack 형식으로 보낸다 — Discord는 URL 끝에 {@code /slack}을 붙인다. */
    public static class Webhook {

        /** 비어 있으면 채널이 비활성이다. URL 자체가 자격증명이므로 로그에 남기지 않는다. */
        private String url = "";

        /**
         * 이 심각도 이상만 메신저로 보낸다.
         *
         * <p>기본값이 {@code WARNING}인 이유는 {@code INFO}가 정상 완료 통지이기 때문이다.
         * 매일 성공 알림이 오면 며칠 안에 채널을 음소거하게 되고, 그때부터 실패 알림도 함께 묻힌다.
         */
        private AlertSeverity minSeverity = AlertSeverity.WARNING;

        /** 기본값(무한대)이면 응답하지 않는 웹훅이 배치 스레드를 영원히 붙잡는다. */
        private Duration connectTimeout = Duration.ofSeconds(5);

        private Duration readTimeout = Duration.ofSeconds(10);

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public AlertSeverity getMinSeverity() {
            return minSeverity;
        }

        public void setMinSeverity(AlertSeverity minSeverity) {
            this.minSeverity = minSeverity;
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
    }

    /** 이메일. SMTP 접속 설정 자체는 {@code spring.mail.*}이 들고 있다. */
    public static class Mail {

        /** 발신 주소. 비어 있으면 채널이 비활성이다. Gmail은 인증 계정과 같아야 한다. */
        private String from = "";

        /** 운영자 수신 주소. 배치 실패가 여기로 간다. 비어 있으면 {@link #from}을 쓴다. */
        private String operatorAddress = "";

        /**
         * 운영자 알림의 심각도 임계.
         *
         * <p>IMPORTANT — 사용자 대상 알림(워치리스트)에는 적용되지 않는다. 그쪽은 심각도가
         * {@code INFO}이고, 임계를 적용하면 워치리스트 알림이 통째로 나가지 않는다.
         */
        private AlertSeverity minSeverity = AlertSeverity.ERROR;

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getOperatorAddress() {
            return operatorAddress;
        }

        public void setOperatorAddress(String operatorAddress) {
            this.operatorAddress = operatorAddress;
        }

        /** 설정된 운영자 주소. 비어 있으면 발신 주소로 대체한다. */
        public String resolvedOperatorAddress() {
            return operatorAddress == null || operatorAddress.isBlank() ? from : operatorAddress;
        }

        public AlertSeverity getMinSeverity() {
            return minSeverity;
        }

        public void setMinSeverity(AlertSeverity minSeverity) {
            this.minSeverity = minSeverity;
        }
    }
}
