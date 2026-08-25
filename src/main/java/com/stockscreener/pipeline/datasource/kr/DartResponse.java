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
     * <b>평면 객체</b>다({@code docs/api-spec/dart-company-profile.md}). 그 엔드포인트에
     * {@link #requireList}를 쓰면 없는 {@code list}를 찾아 빈 결과가 되는데, 오류가 아니라
     * <b>"이 종목은 값이 없다"로 읽힌다.</b>
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
     * status 코드를 재시도 정책으로 옮긴다. <b>이 표의 정본이 여기다</b> — 해석 지점이
     * 저장소에 이곳 하나뿐이라 문서로 떼어 두면 갈려도 아무도 모른다.
     *
     * <pre>
     * 000 정상                            —
     * 010 등록되지 않은 인증키             AUTH
     * 011 사용할 수 없는 키(일시 중지)      AUTH
     * 012 접근할 수 없는 IP                AUTH
     * 013 조회된 데이터 없음               NOT_FOUND
     * 014 파일이 존재하지 않음             NOT_FOUND
     * 020 요청 제한 초과                   RATE_LIMITED
     * 021 조회 가능한 회사 개수 초과(최대 100)  MALFORMED
     * 100 필드의 부적절한 값               MALFORMED
     * 101 부적절한 접근                    AUTH
     * 800 시스템 점검 중                   TRANSIENT
     * 900 정의되지 않은 오류               TRANSIENT
     * 901 개인정보 보유기간 만료 키         AUTH
     * </pre>
     *
     * <p>실측: 없는 연도 조회 → HTTP 200 + {@code status 013}. 잘못된 키 → HTTP 200 + {@code status 010}.
     *
     * <p><b>MUST NOT: {@code status 013}을 「그런 사건이 없다」로 읽지 않는다.</b> 회사가 표를
     * {@code status 000} + 전 필드 {@code -}인 1행으로 낼 수도 있고, 없는 보고서를 부른 결과일
     * 수도 있다. 표마다 다른 그 함정은 {@code docs/api-spec/}의 축별 문서에 있다.
     *
     * <p><b>응답 공통</b>: {@code status}·{@code message}. 목록형은 {@code list} 배열로 오고
     * {@code company.json}만 평면 객체다({@link #requireSuccess}).
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
