package com.stockscreener.pipeline.datasource.kr;

import tools.jackson.databind.JsonNode;
import com.stockscreener.pipeline.datasource.DataSourceException;

import org.springframework.http.HttpStatusCode;

/**
 * 공공데이터포털 응답의 <b>두 가지 봉투</b>를 판별하고 실패를 분류한다.
 *
 * <p>IMPORTANT — 실측으로 확인한 사실이다. 정상 응답은 {@code response.header.resultCode}이고,
 * 인증 실패는 <b>HTTP 403 + 완전히 다른 최상위 키</b>
 * ({@code OpenAPI_ServiceResponse.cmmMsgHeader.returnReasonCode})다. 하나의 DTO로 파싱할 수 없어
 * 이 클래스가 봉투를 먼저 판별한다.
 *
 * <p>자세한 실측 결과는 {@code docs/api-spec/data-go-kr-stock-price.md}에 있다.
 */
final class DataGoKrResponse {

    static final String SOURCE_NAME = "data-go-kr";

    private DataGoKrResponse() {
    }

    /**
     * 응답을 검사하고, 실패면 예외를 던진다. 정상이면 {@code items.item} 배열 노드를 준다.
     *
     * @return 결과 항목 배열. 결과가 없으면 빈 배열 노드(예외가 아니다 — 휴장·미상장이 정상이다)
     */
    static JsonNode requireItems(HttpStatusCode status, JsonNode root, String context)
            throws DataSourceException {
        return requireBody(status, root, context).path("items").path("item");
    }

    /**
     * {@link #requireItems}와 같은 검사를 하되 {@code response.body} 전체를 준다.
     *
     * <p>{@code totalCount}·{@code numOfRows}·{@code pageNo}가 {@code items}의 <b>형제</b>라
     * 항목만 꺼내면 페이징 판단 근거가 사라진다. 실제로 그것이 시세 어댑터가 한 페이지만 읽고
     * 나머지를 조용히 버리던 원인이었다 — 6.5년을 요청하면 {@code totalCount=1618}인데
     * 1,000행만 저장되고 <b>아무 경고도 나지 않았다</b>.
     *
     * @return {@code response.body} 노드
     */
    static JsonNode requireBody(HttpStatusCode status, JsonNode root, String context)
            throws DataSourceException {
        if (root == null || root.isMissingNode()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "응답 본문이 비어 있다. " + context + " status=" + status);
        }

        // 봉투 1 — 오류 응답(주로 403). 최상위 키부터 다르다.
        JsonNode errorHeader = root.path("OpenAPI_ServiceResponse").path("cmmMsgHeader");
        if (!errorHeader.isMissingNode()) {
            String code = errorHeader.path("returnReasonCode").asText("");
            String message = errorHeader.path("errMsg").asText("")
                    + " / " + errorHeader.path("returnAuthMsg").asText("");
            throw new DataSourceException(kindOf(code), SOURCE_NAME,
                    "공공데이터포털 오류 [" + code + "] " + message + " — " + context);
        }

        // 봉투 2 — 정상 형태. resultCode가 00이 아니면 실패다.
        JsonNode header = root.path("response").path("header");
        if (header.isMissingNode()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "알 수 없는 응답 형태다(response.header 없음). " + context
                            + " status=" + status + " root=" + fieldNames(root));
        }
        String resultCode = header.path("resultCode").asText("");
        if (!"00".equals(resultCode)) {
            throw new DataSourceException(kindOf(resultCode), SOURCE_NAME,
                    "공공데이터포털 오류 [" + resultCode + "] "
                            + header.path("resultMsg").asText("") + " — " + context);
        }

        if (status.isError()) {
            // resultCode는 정상인데 HTTP가 오류인 경우. 실측되지 않았으나 조용히 넘기지 않는다.
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "HTTP 오류이나 resultCode는 00이다. " + context + " status=" + status);
        }

        return root.path("response").path("body");
    }

    /**
     * 에러 코드를 재시도 정책으로 옮긴다.
     *
     * <p>공식 가이드 "2. OpenAPI 에러 코드정리" 기준이다.
     */
    private static DataSourceException.Kind kindOf(String code) {
        return switch (code) {
            case "20", "30", "31", "32" -> DataSourceException.Kind.AUTH;
            case "22" -> DataSourceException.Kind.RATE_LIMITED;
            case "10", "12" -> DataSourceException.Kind.MALFORMED;
            // 1(어플리케이션 에러), 99(기타), 그 외 알려지지 않은 코드는 일시적 문제로 보고 재시도한다.
            default -> DataSourceException.Kind.TRANSIENT;
        };
    }

    /** 알 수 없는 봉투를 만났을 때 최상위 키를 로그에 남긴다 — 스펙 변경 진단의 출발점이다. */
    private static String fieldNames(JsonNode node) {
        return String.join(" ", node.propertyNames());
    }
}
