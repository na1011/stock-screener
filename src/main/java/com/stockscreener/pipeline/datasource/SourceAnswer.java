package com.stockscreener.pipeline.datasource;

/**
 * 실패가 <b>소스의 결정적 답</b>인가 — 「그 종목을 소스에 물어 답을 받았고, 다시 물어도 같은 답이다」(ADR-0174).
 *
 * <p>종류({@link DataSourceException.Kind})로는 못 가른다. 같은 {@code NOT_FOUND}·{@code MALFORMED}가 결정적인 답(SEC facts 키
 * 없음 · DART 021·100 · 신선한 매핑에서의 부재)일 수도, 끊긴 전송이나 우리 인자 오류일 수도 있다. 그래서 그 사실을 아는
 * <b>던지는 자리</b>가 {@link #answered}로 표지를 달고, <b>기본은 「답 못 받음」</b>이다 — 모르는 실패를 「답함」으로 접으면 못 받은
 * 시장이 신선도 시계를 민다. 저장되는 실패 행(종류 · 소스 · 메시지)은 이것과 무관하다.
 *
 * <h2>왜 {@link DataSourceException}의 필드가 아닌가</h2>
 * <p>그 클래스는 SEC 공시 보충의 판 고정 범위가 닿는 형이다 — 범위 소스가 그 생성자를 부르므로 판 고정 시험
 * ({@code ReaderVersionPinTest})이 클래스 전체를 해시에 싣고, 필드 하나만 더해도 판을 올려야 한다(실측 2026-10-07). 판 올림은
 * 이 결정의 몫이 아니라 따로 반증을 받는다. 그래서 표지를 예외의 <b>억제 목록</b>({@link Throwable#addSuppressed})에 붙는 별도
 * 형으로 운반한다 — 예외의 종류 · 메시지 · 원인은 그대로이고, 실패 행의 스택에는 「Suppressed: …소스가 결정적으로 답했다」 한
 * 줄이 남는다(그것도 사실이다).
 */
public final class SourceAnswer {

    private SourceAnswer() {
    }

    /** 소스가 결정적으로 답한 실패를 만든다 — {@code NOT_FOUND}·{@code MALFORMED}만 된다. */
    public static DataSourceException answered(DataSourceException.Kind kind, String sourceName, String message) {
        return answered(kind, sourceName, message, null);
    }

    public static DataSourceException answered(DataSourceException.Kind kind, String sourceName, String message,
                                               Throwable cause) {
        return mark(new DataSourceException(kind, sourceName, message, cause));
    }

    /**
     * 이미 만든 실패에 표지를 단다. 한도·일시 장애·인증은 정의상 답을 못 받은 것이라 받지 않는다.
     *
     * @return 같은 예외 — {@code throw SourceAnswer.mark(e)}로 쓴다
     */
    public static DataSourceException mark(DataSourceException exception) {
        DataSourceException.Kind kind = exception.kind();
        if (kind != DataSourceException.Kind.NOT_FOUND && kind != DataSourceException.Kind.MALFORMED) {
            throw new IllegalArgumentException("소스가 답한 실패는 NOT_FOUND·MALFORMED뿐이다: " + kind);
        }
        if (!isAnswered(exception)) {
            exception.addSuppressed(new Marker());
        }
        return exception;
    }

    /** 표지가 달린 실패인가. 기본은 거짓이다 — {@link #answered}·{@link #mark}를 거친 것만 참이다. */
    public static boolean isAnswered(DataSourceException exception) {
        for (Throwable suppressed : exception.getSuppressed()) {
            if (suppressed instanceof Marker) {
                return true;
            }
        }
        return false;
    }

    /** 억제 목록에 붙는 표지. 스택을 안 남긴다 — 위치가 아니라 사실 하나를 나른다. */
    private static final class Marker extends Throwable {

        private Marker() {
            super("소스가 결정적으로 답했다 — 다시 물어도 같은 답이다(ADR-0174)", null, false, false);
        }
    }
}
