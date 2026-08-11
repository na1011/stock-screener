package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;

import tools.jackson.databind.JsonNode;

/**
 * DART 응답의 {@code status} 필드를 판별한다.
 *
 * <p>IMPORTANT — DART는 <b>인증 실패도 HTTP 200으로 응답한다</b>(실측 확인).
 * 공공데이터포털이 403을 주는 것과 정반대다. HTTP 상태코드만 보면 잘못된 키로 조회한 결과를
 * 정상으로 오인하므로, {@code status} 파싱이 <b>필수</b>다.
 *
 * <p>두 소스의 실패 표현이 반대라 공통 처리로 뭉뚱그릴 수 없다는 것이 ADR-0009의 요지다.
 */
final class DartResponse {

    static final String SOURCE_NAME = "dart";

    private DartResponse() {
    }

    /**
     * status를 검사하고 실패면 예외를 던진다. 정상이면 {@code list} 배열 노드를 준다.
     *
     * @return 결과 배열. {@code status 013}(데이터 없음)은 예외로 던진다 —
     *         "조회할 보고서가 없다"는 종목 단위 실패이며 호출부가 구분해 처리한다.
     */
    static JsonNode requireList(JsonNode root, String context) throws DataSourceException {
        return requireSuccess(root, context).path("list");
    }

    /**
     * status만 검사하고 <b>루트를 그대로</b> 준다.
     *
     * <p>IMPORTANT — 기업개황({@code company.json})은 응답이 {@code list} 배열이 아니라
     * <b>평면 객체</b>다(§10-A). 그 엔드포인트에 {@link #requireList}를 쓰면 없는 {@code list}를
     * 찾아 빈 결과가 되는데, 오류가 아니라 <b>"이 종목은 값이 없다"로 읽힌다.</b>
     * status 판정을 두 곳에 복사하지 않으려고 이쪽을 정본으로 두고 저쪽이 부른다.
     */
    static JsonNode requireSuccess(JsonNode root, String context) throws DataSourceException {
        if (root == null || root.isMissingNode()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "응답 본문이 비어 있다. " + context);
        }
        String status = root.path("status").asString("");
        if (status.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "status 필드가 없다. " + context);
        }
        if (!"000".equals(status)) {
            throw new DataSourceException(kindOf(status), SOURCE_NAME,
                    "DART 오류 [" + status + "] " + root.path("message").asString("")
                            + " — " + context);
        }
        return root;
    }

    /** ZIP이 아닌 본문(주로 인증 실패 JSON)에서 예외를 만든다. */
    static DataSourceException errorFromBody(String body, String context) {
        String status = extract(body, "status");
        String message = extract(body, "message");
        if (status != null) {
            return new DataSourceException(kindOf(status), SOURCE_NAME,
                    "DART 오류 [" + status + "] " + message + " — " + context);
        }
        return new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                "예상하지 못한 응답이다. " + context + " body=" + truncate(body));
    }

    /**
     * status 코드를 재시도 정책으로 옮긴다.
     *
     * <p>전체 표는 {@code docs/api-spec/dart-open-api.md}에 있다.
     */
    private static DataSourceException.Kind kindOf(String status) {
        return switch (status) {
            case "010", "011", "012", "101", "901" -> DataSourceException.Kind.AUTH;
            case "013", "014" -> DataSourceException.Kind.NOT_FOUND;
            case "020" -> DataSourceException.Kind.RATE_LIMITED;
            case "021", "100" -> DataSourceException.Kind.MALFORMED;
            // 800(점검), 900(미정의)은 일시적 문제로 보고 재시도한다.
            default -> DataSourceException.Kind.TRANSIENT;
        };
    }

    /** JSON 파싱 없이 값 하나를 꺼낸다. ZIP 응답 자리에 온 오류 본문에만 쓴다. */
    private static String extract(String body, String field) {
        String needle = "\"" + field + "\"";
        int at = body.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int colon = body.indexOf(':', at + needle.length());
        int start = body.indexOf('"', colon + 1);
        int end = start < 0 ? -1 : body.indexOf('"', start + 1);
        return (start < 0 || end < 0) ? null : body.substring(start + 1, end);
    }

    private static String truncate(String text) {
        String stripped = text.strip();
        return stripped.length() <= 200 ? stripped : stripped.substring(0, 200) + "...";
    }
}
