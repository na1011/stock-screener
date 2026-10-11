package com.stockscreener.pipeline.datasource;

/**
 * 외부 소스 접근 실패. 어댑터가 상위로 던지는 <b>유일한</b> 예외 타입이다.
 *
 * <p>IMPORTANT — 벤더 예외({@code RestClientException}, JSON 파싱 실패 등)가 이것을 통과해
 * 상위로 새면 안 된다. 새는 순간 {@code collector}·{@code batch}가 벤더를 알게 되어
 * "소스 교체 영향의 국소화"({@code data-pipeline.md})가 깨진다. 어댑터는 자신이 아는 모든
 * 예외를 여기로 감싼다.
 *
 * <p>{@link Kind}가 재시도 정책과 실패 기록을 가르는 축이다. 실패를 하나의 덩어리로 다루면
 * 재시도할 가치가 없는 실패(없는 종목)에 백오프를 낭비하고, 재시도해야 할 실패(429)를
 * 영구 실패로 기록하게 된다.
 *
 * <p>checked exception인 이유는 호출부가 실패 처리를 강제로 마주하게 하기 위해서다.
 * unchecked면 종목 단위 실패 격리를 빠뜨린 코드가 컴파일된다.
 */
public class DataSourceException extends Exception {

    private final Kind kind;
    private final String sourceName;

    /**
     * 소스가 <b>결정적으로 답했는가</b> — 「그 종목을 소스에 물어 답을 받았고, 다시 물어도 같은 답이다」(ADR-0174).
     *
     * <p>종류({@link Kind})로는 못 가른다. 같은 {@code NOT_FOUND}·{@code MALFORMED}가 결정적인 답(신선한 매핑 판에 그 종목이
     * 없다)일 수도, 끊긴 전송이나 우리 요청 오류(DART {@code 021}·{@code 100} — 답 못 받음)일 수도 있다. 그래서 그 사실을 아는
     * <b>던지는 자리</b>가 {@link #answered}로 명시하고, <b>기본은 거짓(「답 못 받음」)</b>이다 — 모르는 실패를 「답함」으로 접으면
     * 못 받은 시장이 신선도 시계를 민다. 지금 표지를 다는 자리는 신선한 매핑 판의 부재 둘(SEC CIK · DART 고유번호 — 재무·전체
     * 재무제표)뿐이다. 저장되는 실패 행(종류 · 소스 · 메시지)은 이것과 무관하다.
     */
    private final boolean sourceAnswered;

    public DataSourceException(Kind kind, String sourceName, String message) {
        this(kind, sourceName, message, null);
    }

    public DataSourceException(Kind kind, String sourceName, String message, Throwable cause) {
        this(kind, sourceName, message, cause, false);
    }

    /** 하위형({@code DartResponse}의 「데이터 없음」 등)이 표지까지 정해 만든다. */
    protected DataSourceException(Kind kind, String sourceName, String message, Throwable cause,
                                  boolean sourceAnswered) {
        super(message, cause);
        if (kind == null) {
            throw new IllegalArgumentException("kind가 null이다. 실패의 종류를 알 수 없으면 재시도 판단이 불가능하다.");
        }
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException(
                    "sourceName이 비어 있다 — 어느 벤더가 깨졌는지 모르면 소스 교체 판단을 할 수 없다.");
        }
        this.kind = kind;
        this.sourceName = sourceName;
        this.sourceAnswered = sourceAnswered;
    }

    /**
     * 소스가 결정적으로 답한 실패를 만든다({@link #sourceAnswered()}) — {@code NOT_FOUND}·{@code MALFORMED}만 된다. 한도·일시
     * 장애·인증은 정의상 답을 못 받은 것이라 받지 않는다.
     */
    public static DataSourceException answered(Kind kind, String sourceName, String message) {
        if (kind != Kind.NOT_FOUND && kind != Kind.MALFORMED) {
            throw new IllegalArgumentException("소스가 답한 실패는 NOT_FOUND·MALFORMED뿐이다: " + kind);
        }
        return new DataSourceException(kind, sourceName, message, null, true);
    }

    /** 소스가 결정적으로 답한 실패인가. 기본은 거짓이다 — {@link #answered}로 만든 것만 참이다. */
    public boolean sourceAnswered() {
        return sourceAnswered;
    }

    public Kind kind() {
        return kind;
    }

    /** 실패를 일으킨 소스의 이름. {@code batch_execution_failure.source_name}에 기록된다. */
    public String sourceName() {
        return sourceName;
    }

    /** 백오프 후 다시 시도할 가치가 있는가. */
    public boolean retryable() {
        return kind == Kind.RATE_LIMITED || kind == Kind.TRANSIENT;
    }

    /**
     * 이 실패로 배치 전체를 중단해야 하는가.
     *
     * <p>키가 틀렸는데 850종목을 모두 시도할 이유가 없다. 쿼터만 태우고 850건의 동일한
     * 실패 기록을 남길 뿐이다.
     */
    public boolean fatal() {
        return kind == Kind.AUTH;
    }

    public enum Kind {
        /** 429 등 호출 한도 초과. 백오프 후 재시도한다. */
        RATE_LIMITED,

        /** 타임아웃·5xx·연결 실패. 소스의 일시적 문제이므로 재시도한다. */
        TRANSIENT,

        /** 해당 종목에 데이터가 없다. 재시도해도 결과가 같으므로 종목 실패로 기록만 한다. */
        NOT_FOUND,

        /**
         * 응답을 해석할 수 없다. 필수 필드 누락, 파싱 실패.
         *
         * <p>IMPORTANT — 이것은 우리 코드가 아니라 <b>벤더 스펙이 바뀌었다는 신호</b>이고,
         * spec §7이 우려한 바로 그 사건이다. 한 종목이면 데이터 문제지만 대량 발생이면
         * 소스 교체 검토 시점이므로 별도 심각도로 알린다.
         */
        MALFORMED,

        /** 인증 실패. 키가 없거나 틀렸거나 만료됐다. 배치를 즉시 중단한다. */
        AUTH
    }
}
