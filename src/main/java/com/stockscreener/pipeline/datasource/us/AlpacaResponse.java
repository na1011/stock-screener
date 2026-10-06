package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;

import org.springframework.http.HttpStatusCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Alpaca 응답의 봉투 판별. <b>이 클래스의 매핑표가 정본이다</b> — 문서로 떼어 두면 갈려도
 * 아무도 모른다({@code DartResponse.kindOf}·{@code DataGoKrResponse.kindOf}와 같은 취급).
 *
 * <p>국내 두 소스와 다르게 <b>HTTP 상태코드가 실질이다.</b> DART는 인증 실패도 200으로 주고
 * 공공데이터포털은 오류에 봉투를 바꾸지만, Alpaca는 코드를 제대로 쓴다. 그래서 판정을
 * 상태코드로 하고 본문은 메시지로만 쓴다.
 *
 * <p><b>MUST: 본문을 문자열로 받는다.</b> 실측(2026-08-29)으로 <b>같은 벤더 안에서 오류 본문의
 * 형식이 갈린다</b> — Trading {@code /v2/assets}는 401에 {@code application/json}으로
 * {@code {"message": "unauthorized."}}를 주지만, Trading {@code /v3/calendar}와 Market Data
 * {@code /v1/corporate-actions}는 <b>nginx HTML</b>({@code text/html})을 준다.
 * {@code toEntity(JsonNode.class)}로 받으면 <b>상태코드를 보기도 전에</b>
 * {@code UnknownContentTypeException}이 나고, 그 예외는 어댑터의 일반 {@code catch}에 걸려
 * <b>인증 실패가 {@code TRANSIENT}가 된다</b> — 키가 틀린 것이 원인인데 종목마다 「일시 오류」로
 * 기록되고 배치는 부분 성공으로 끝나, ADR-0088이 MUST NOT으로 막아 둔 상태와 같은 모양이 된다.
 * {@code SecEdgarResponse}가 같은 이유로 같은 형태다.
 *
 * <table border="1">
 *   <caption>HTTP → {@link DataSourceException.Kind}</caption>
 *   <tr><th>코드</th><th>Kind</th><th>근거</th></tr>
 *   <tr><td>200 + 기대한 봉투</td><td>—</td><td>정상</td></tr>
 *   <tr><td>200인데 본문이 없거나 JSON이 아님</td><td>TRANSIENT</td><td>받기 실패다 — 끊긴 전송 · 오류 페이지(ADR-0174)</td></tr>
 *   <tr><td>200 JSON인데 봉투·모양이 다름</td><td>MALFORMED</td><td>스펙 변경 신호다 — 파싱된 JSON의 모양 이상만 여기다</td></tr>
 *   <tr><td>400</td><td>MALFORMED</td><td><b>실측</b>: 캘린더의 모르는 거래소·뒤집힌 창이 여기다</td></tr>
 *   <tr><td>401</td><td>AUTH</td><td><b>실측</b>: 엔드포인트마다 본문이 JSON이거나 HTML이다(위 참조)</td></tr>
 *   <tr><td>403</td><td>AUTH</td><td>권한 부족. 시세의 "subscription does not permit …"도 여기다</td></tr>
 *   <tr><td>404</td><td>NOT_FOUND</td><td><b>실측</b>: base URL을 섞으면 여기다</td></tr>
 *   <tr><td>408</td><td>TRANSIENT</td><td>요청 시간 초과 — 우리 요청의 문제가 아니라 일시 실패다</td></tr>
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 봉투 안의 값이 무엇이어야 하는가. 엔드포인트마다 다르다. */
    private enum Shape {
        OBJECT("객체"),
        ARRAY("배열");

        private final String label;

        Shape(String label) {
            this.label = label;
        }

        boolean matches(JsonNode node) {
            return this == OBJECT ? node.isObject() : node.isArray();
        }
    }

    /**
     * 봉투 하나를 통째로 들고 다닌다.
     *
     * <p>호출자가 <b>봉투 안의 값</b>과 <b>다음 페이지 토큰</b>을 둘 다 필요로 하는데, 토큰은
     * 봉투 밖(최상위)에 있다. 값만 돌려주면 호출자가 원문을 따로 들고 있어야 하고, 그러면
     * <b>상태코드를 보기 전에 파싱된 원문</b>이 다시 생겨 이 클래스가 막으려는 순서가 무너진다.
     * 토큰의 「없음」 판정(null·빈 문자열)이 어댑터마다 사본이던 것도 여기로 모은다.
     */
    record Envelope(JsonNode root, JsonNode value) {

        /** 다음 페이지가 없으면 {@code null}. 키가 없는 것과 빈 문자열을 같게 본다. */
        String nextPageToken() {
            JsonNode next = root.path("next_page_token");
            String token = next.isNull() ? "" : next.asString("");
            return token.isBlank() ? null : token;
        }
    }

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
    static JsonNode requireArray(HttpStatusCode status, String body, String context)
            throws DataSourceException {
        String sourceName = AlpacaUniverseAdapter.SOURCE_NAME;
        JsonNode parsed = decode(status, body, sourceName, context);
        if (!parsed.isArray()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, sourceName,
                    "배열이 아닌 응답이다 — 스펙이 바뀌었을 수 있다. " + context);
        }
        if (parsed.isEmpty()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, sourceName,
                    "빈 배열이 왔다 — 전 종목 제외로 읽히므로 반영하지 않는다. " + context);
        }
        return parsed;
    }

    /**
     * 봉투 없이 객체 하나가 오는 응답(단건 조회)을 읽거나 던진다.
     *
     * <p>{@code /v2/assets/{symbol}}이 그 모양이다. 호출자가 상태코드를 먼저 걸러도
     * <b>본문 파싱은 여기서 해야</b> 「200인데 JSON이 아니다」가 {@code MALFORMED}로 남는다.
     */
    static JsonNode requireObject(HttpStatusCode status, String body, String sourceName,
                                  String context) throws DataSourceException {
        JsonNode parsed = decode(status, body, sourceName, context);
        if (!parsed.isObject()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, sourceName,
                    "객체가 아닌 응답이다 — 스펙이 바뀌었을 수 있다. " + context);
        }
        return parsed;
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
    static Envelope requireBars(HttpStatusCode status, String body, String sourceName,
                                String context) throws DataSourceException {
        return requireEnvelope(status, body, "bars", Shape.OBJECT, sourceName, context);
    }

    /**
     * 기업 행위 봉투에서 {@code corporate_actions} 객체를 꺼내거나 던진다(ADR-0090).
     *
     * <p><b>내용물의 계약이 시세와 다르다.</b> 이쪽은 <b>온 타입의 키만</b> 들어 있어
     * {@code {"corporate_actions":{}}}가 정상적인 「그 창에 사건이 없다」이고(실측), 없는 타입은
     * 빈 배열이 아니라 키 자체가 없다. 그래서 호출자가 타입 키를 하나씩 꺼내 본다.
     */
    static Envelope requireCorporateActions(HttpStatusCode status, String body, String sourceName,
                                            String context) throws DataSourceException {
        return requireEnvelope(status, body, "corporate_actions", Shape.OBJECT, sourceName, context);
    }

    /**
     * 마켓 캘린더 봉투에서 {@code calendar} <b>배열</b>을 꺼내거나 던진다(ADR-0091).
     *
     * <p>앞의 둘과 값의 종류가 다르다 — 여기는 {@code {"calendar": [...], "market": {...}}}라
     * 봉투 안이 배열이다. 그래서 기대 종류를 인자로 뺐다. <b>새 판정을 쓰지 않은 이유</b>는
     * ADR-0090이 그 형태를 기각했기 때문이다 — 같은 판정이 여러 벌이 된다.
     *
     * <p><b>빈 배열을 던지지 않는다.</b> 휴일 하루만 조회하면 정상적으로 빈 배열이 온다.
     */
    static JsonNode requireCalendar(HttpStatusCode status, String body, String sourceName,
                                    String context) throws DataSourceException {
        return requireEnvelope(status, body, "calendar", Shape.ARRAY, sourceName, context).value();
    }

    /**
     * {@code {"<key>": …, …}} 봉투에서 {@code key}를 꺼낸다.
     *
     * <p><b>키가 없는 것과 0건은 다르다.</b> 전자는 스펙 변경 신호이므로 던지고, 후자는
     * 빈 객체·빈 배열로 정상 반환한다. {@code sourceName}을 인자로 받는 이유는 실패가
     * {@code batch_execution_failure.source_name}에 <b>부른 어댑터의 이름</b>으로 남아야
     * 관리자 화면의 소스별 집계가 안 엉키기 때문이다.
     */
    private static Envelope requireEnvelope(HttpStatusCode status, String body, String key,
                                            Shape shape, String sourceName, String context)
            throws DataSourceException {
        JsonNode parsed = decode(status, body, sourceName, context);
        if (!parsed.isObject()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, sourceName,
                    "객체가 아닌 응답이다 — 스펙이 바뀌었을 수 있다. " + context);
        }
        JsonNode node = parsed.path(key);
        if (node.isMissingNode() || node.isNull()) {
            // 0건인 것과 키 자체가 없는 것은 다르다. 후자는 스펙 변경 신호다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, sourceName,
                    key + " 키가 없다 — 0건과 다른 사건이다. " + context);
        }
        if (!shape.matches(node)) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, sourceName,
                    key + "가 " + shape.label + "가 아니다. " + context);
        }
        return new Envelope(parsed, node);
    }

    /**
     * 2xx가 아니면 상태코드로 판정해 던지고, 2xx면 본문을 JSON으로 읽는다.
     *
     * <p>MUST: <b>상태코드를 먼저 본다.</b> 오류 본문이 JSON이 아닌 엔드포인트가 실재하고
     * (위 javadoc), 파싱을 먼저 하면 <b>인증 오류가 「호출 실패」로 접힌다.</b>
     * 403 본문에 키가 없어 「0건」으로 읽히던 옛 오독도 같은 자리다
     * ({@code .claude/rules/data-pipeline.md}).
     *
     * <p><b>2xx인데 본문이 없거나 JSON이 아니면 {@code TRANSIENT}다</b> — 끊긴 전송(본문 전 끊김은 본문 없음 · 고정 길이 도중
     * 끊김은 잘린 JSON)과 오류 페이지가 이 모양이다. 예전에는 「스펙 변경」(MALFORMED)으로 봤는데, 그 사유는 <b>파싱된 JSON의
     * 모양이 다를 때</b>에만 선다(호출부의 봉투 검사). 받기 실패를 소스의 답으로 섞으면 시장 판정이 못 받은 시장을 끝난 것으로
     * 읽는다(ADR-0174).
     */
    private static JsonNode decode(HttpStatusCode status, String body, String sourceName,
                                   String context) throws DataSourceException {
        if (!status.is2xxSuccessful()) {
            throw new DataSourceException(kindOf(status.value()), sourceName,
                    "HTTP %d — %s. %s".formatted(status.value(), message(body), context));
        }
        try {
            JsonNode parsed = body == null || body.isBlank() ? null : MAPPER.readTree(body);
            if (parsed == null || parsed.isMissingNode()) {
                throw new DataSourceException(DataSourceException.Kind.TRANSIENT, sourceName,
                        "200인데 본문이 없다. " + context);
            }
            return parsed;
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, sourceName,
                    "200인데 JSON이 아니다(끊긴 전송이거나 오류 페이지) — %s. %s".formatted(message(body), context), e);
        }
    }

    static DataSourceException.Kind kindOf(int status) {
        return switch (status) {
            case 401, 403 -> DataSourceException.Kind.AUTH;
            case 404 -> DataSourceException.Kind.NOT_FOUND;
            // 요청 시간 초과는 일시 실패다 — 「그 밖 4xx = 우리 요청 문제」에 넣으면 재시도 대상에서 빠진다.
            case 408 -> DataSourceException.Kind.TRANSIENT;
            case 422 -> DataSourceException.Kind.MALFORMED;
            case 429 -> DataSourceException.Kind.RATE_LIMITED;
            default -> status >= 400 && status < 500
                    ? DataSourceException.Kind.MALFORMED
                    : DataSourceException.Kind.TRANSIENT;
        };
    }

    /**
     * 로그와 예외 메시지에 남길 본문 조각. 모양이 달라도 판정에 영향을 주지 않는다.
     *
     * <p>JSON이면 {@code message} 필드를, 아니면(HTML·XML) <b>앞부분을 잘라</b> 그대로 쓴다.
     * 자르는 이유는 nginx HTML 한 장이 통째로 예외 메시지에 실리는 것을 막기 위해서다.
     */
    private static String message(String body) {
        if (body == null || body.isBlank()) {
            return "(본문 없음)";
        }
        try {
            JsonNode parsed = MAPPER.readTree(body);
            String message = parsed.path("message").asString("");
            if (!message.isBlank()) {
                return message;
            }
        } catch (RuntimeException ignored) {
            // JSON이 아니다. 아래에서 원문을 잘라 쓴다 — 판정은 이미 상태코드가 끝냈다.
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
