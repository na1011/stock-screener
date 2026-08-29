package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;

import org.springframework.http.HttpStatusCode;
import tools.jackson.databind.JsonNode;

/**
 * Alpaca 응답의 봉투 판별. <b>이 클래스의 매핑표가 정본이다</b> — 문서로 떼어 두면 갈려도
 * 아무도 모른다({@code DartResponse.kindOf}·{@code DataGoKrResponse.kindOf}와 같은 취급).
 *
 * <p>국내 두 소스와 다르게 <b>HTTP 상태코드가 실질이다.</b> DART는 인증 실패도 200으로 주고
 * 공공데이터포털은 오류에 봉투를 바꾸지만, Alpaca는 코드를 제대로 쓴다. 그래서 판정을
 * 상태코드로 하고 본문은 메시지로만 쓴다 — <b>본문 모양을 아직 실측하지 않았으므로 파싱
 * 실패가 판정을 바꾸지 않게 짠다.</b>
 *
 * <table border="1">
 *   <caption>HTTP → {@link DataSourceException.Kind}</caption>
 *   <tr><th>코드</th><th>Kind</th><th>근거</th></tr>
 *   <tr><td>200 + 배열</td><td>—</td><td>정상</td></tr>
 *   <tr><td>200 + 배열 아님</td><td>MALFORMED</td><td>스펙 변경 신호다</td></tr>
 *   <tr><td>401</td><td>AUTH</td><td><b>실측</b>: 틀린 키 → {@code {"message": "unauthorized."}}</td></tr>
 *   <tr><td>403</td><td>AUTH</td><td>권한 부족. 시세의 "subscription does not permit …"도 여기다</td></tr>
 *   <tr><td>404</td><td>NOT_FOUND</td><td>단건 조회에서만 뜻이 있다</td></tr>
 *   <tr><td>422</td><td>MALFORMED</td><td>우리 요청이 틀린 것이라 재시도가 무의미하다</td></tr>
 *   <tr><td>429</td><td>RATE_LIMITED</td><td></td></tr>
 *   <tr><td>그 외 4xx</td><td>MALFORMED</td><td>우리 요청 문제로 본다</td></tr>
 *   <tr><td>5xx·그 외</td><td>TRANSIENT</td><td>모르는 코드는 재시도 쪽으로 — {@code DataGoKrResponse} 선례</td></tr>
 * </table>
 *
 * <p><b>401과 403을 같은 {@code AUTH}로 접되 메시지에는 코드를 남긴다.</b> 개발가이드는
 * "자격증명 오류"를 403으로 적었으나 실측은 401이었다. 둘을 갈라 다르게 처리하면 그 문서
 * 오류가 코드로 옮겨 온다.
 */
final class AlpacaResponse {

    private AlpacaResponse() {
    }

    /**
     * 정상 배열을 꺼내거나 던진다.
     *
     * <p>빈 배열도 던진다. 유니버스에서 빈 목록은 "전 종목 제외"를 뜻하고, 리밸런싱이
     * "목록에 없으면 제외"로 동작하므로 그대로 반영하면 대량 오탈락이다(ADR-0008).
     * {@code UniverseSnapshot} 생성자도 막지만 그쪽은 {@code IllegalArgumentException}이라
     * 잡이 <b>"갱신 거부"</b>로 보고한다 — 소스 문제라는 것을 남기려면 여기서 걸러야 한다.
     */
    static JsonNode requireArray(HttpStatusCode status, JsonNode body, String context)
            throws DataSourceException {
        if (!status.is2xxSuccessful()) {
            throw new DataSourceException(kindOf(status.value()), AlpacaUniverseAdapter.SOURCE_NAME,
                    "HTTP %d — %s. %s".formatted(status.value(), message(body), context));
        }
        if (body == null || !body.isArray()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                    AlpacaUniverseAdapter.SOURCE_NAME,
                    "배열이 아닌 응답이다 — 스펙이 바뀌었을 수 있다. " + context);
        }
        if (body.isEmpty()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                    AlpacaUniverseAdapter.SOURCE_NAME,
                    "빈 배열이 왔다 — 전 종목 제외로 읽히므로 반영하지 않는다. " + context);
        }
        return body;
    }

    /**
     * 시세 봉투에서 {@code bars} 객체를 꺼내거나 던진다.
     *
     * <p>{@link #requireArray}와 갈라놓은 이유가 둘이다. 시세 응답은 배열이 아니라
     * {@code {"bars": {...}, "next_page_token": ...}} 객체이고, 저쪽은 {@code sourceName}을
     * 유니버스 어댑터의 것으로 <b>하드코딩</b>한다 — 그대로 쓰면 시세 실패가
     * {@code batch_execution_failure.source_name}에 유니버스 소스 이름으로 기록되고
     * 관리자 화면의 소스별 집계가 그것으로 묶인다.
     *
     * <p><b>빈 {@code bars}를 던지지 않는다.</b> 유니버스에서 빈 목록은 "전 종목 제외"라
     * 대량 오탈락이지만, 시세에서 빈 구간은 <b>정상</b>이다 — 상장 전·거래정지·무거래.
     * 포트가 그것을 빈 목록으로 정의한다.
     */
    static JsonNode requireBars(HttpStatusCode status, JsonNode body, String sourceName,
                                String context) throws DataSourceException {
        return requireEnvelope(status, body, "bars", sourceName, context);
    }

    /**
     * 기업 행위 봉투에서 {@code corporate_actions} 객체를 꺼내거나 던진다(ADR-0090).
     *
     * <p><b>내용물의 계약이 시세와 다르다.</b> 이쪽은 <b>온 타입의 키만</b> 들어 있어
     * {@code {"corporate_actions":{}}}가 정상적인 「그 창에 사건이 없다」이고(실측), 없는 타입은
     * 빈 배열이 아니라 키 자체가 없다. 그래서 호출자가 타입 키를 하나씩 꺼내 본다.
     */
    static JsonNode requireCorporateActions(HttpStatusCode status, JsonNode body, String sourceName,
                                            String context) throws DataSourceException {
        return requireEnvelope(status, body, "corporate_actions", sourceName, context);
    }

    /**
     * {@code {"<key>": {...}, "next_page_token": …}} 봉투에서 {@code key}를 꺼낸다.
     *
     * <p>MUST: <b>상태코드를 먼저 본다.</b> 403 본문에는 그 키가 없어, 순서를 뒤집으면
     * 권한 오류가 <b>「0건」으로 접힌다</b> — 실제로 탐침에서 그 오독이 났고 정반대 결론이
     * 나왔다({@code .claude/rules/data-pipeline.md}).
     *
     * <p><b>키가 없는 것과 0건은 다르다.</b> 전자는 스펙 변경 신호이므로 던지고, 후자는
     * 빈 객체로 정상 반환한다. {@code sourceName}을 인자로 받는 이유는 실패가
     * {@code batch_execution_failure.source_name}에 <b>부른 어댑터의 이름</b>으로 남아야
     * 관리자 화면의 소스별 집계가 안 엉키기 때문이다.
     */
    private static JsonNode requireEnvelope(HttpStatusCode status, JsonNode body, String key,
                                            String sourceName, String context)
            throws DataSourceException {
        if (!status.is2xxSuccessful()) {
            throw new DataSourceException(kindOf(status.value()), sourceName,
                    "HTTP %d — %s. %s".formatted(status.value(), message(body), context));
        }
        if (body == null || !body.isObject()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, sourceName,
                    "객체가 아닌 응답이다 — 스펙이 바뀌었을 수 있다. " + context);
        }
        JsonNode node = body.path(key);
        if (node.isMissingNode() || node.isNull()) {
            // 0건인 것과 키 자체가 없는 것은 다르다. 후자는 스펙 변경 신호이고,
            // 실제로 403 본문이 정확히 그 모양이다 — 상태코드를 안 보면 「0건」으로 접힌다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, sourceName,
                    key + " 키가 없다 — 0건과 다른 사건이다. " + context);
        }
        if (!node.isObject()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, sourceName,
                    key + "가 객체가 아니다. " + context);
        }
        return node;
    }

    static DataSourceException.Kind kindOf(int status) {
        return switch (status) {
            case 401, 403 -> DataSourceException.Kind.AUTH;
            case 404 -> DataSourceException.Kind.NOT_FOUND;
            case 422 -> DataSourceException.Kind.MALFORMED;
            case 429 -> DataSourceException.Kind.RATE_LIMITED;
            default -> status >= 400 && status < 500
                    ? DataSourceException.Kind.MALFORMED
                    : DataSourceException.Kind.TRANSIENT;
        };
    }

    /** 본문에서 사람이 읽을 조각만. 모양이 달라도 판정에 영향을 주지 않는다. */
    private static String message(JsonNode body) {
        if (body == null) {
            return "(본문 없음)";
        }
        String message = body.path("message").asString("");
        return message.isBlank() ? body.toString() : message;
    }
}
