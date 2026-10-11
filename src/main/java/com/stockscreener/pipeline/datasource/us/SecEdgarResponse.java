package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import org.springframework.http.HttpStatusCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SEC EDGAR 응답의 봉투 판별. <b>이 클래스의 매핑표가 정본이다</b> — 문서로 떼어 두면 갈려도
 * 아무도 모른다({@code DartResponse.kindOf}·{@code AlpacaResponse.kindOf}와 같은 취급).
 *
 * <p>DART와 다르게 <b>HTTP 상태코드가 실질이다.</b> DART는 인증 실패도 200으로 주지만 EDGAR는
 * 코드를 제대로 쓴다. 다만 <b>오류 본문이 JSON이 아니라 XML</b>이라(실측:
 * {@code <Error><Code>NoSuchKey</Code>…}) 정상 경로 파서에 넣으면 예외가 나거나 「0건」이 된다.
 * 그래서 판정을 상태코드로 하고 <b>본문은 메시지로만</b> 쓴다.
 *
 * <table border="1">
 *   <caption>HTTP → {@link DataSourceException.Kind}</caption>
 *   <tr><th>코드</th><th>Kind</th><th>근거</th></tr>
 *   <tr><td>200 + {@code facts} 객체</td><td>—</td><td>정상</td></tr>
 *   <tr><td>200 + {@code facts} 없음</td><td>MALFORMED</td><td>스펙 변경 신호다</td></tr>
 *   <tr><td>404</td><td>NOT_FOUND</td><td><b>실측</b>: 모르는 CIK와 zero-padding 누락이 둘 다 404 + XML</td></tr>
 *   <tr><td>200인데 JSON이 아니거나 본문 없음</td><td>TRANSIENT</td><td>받기 실패다(끊긴 전송 · 오류 페이지 · ADR-0174)</td></tr>
 *   <tr><td>403</td><td><b>RATE_LIMITED</b></td><td><b>미실측</b> — 아래 참조</td></tr>
 *   <tr><td>408</td><td>TRANSIENT</td><td>요청 시간 초과 — 일시 실패다</td></tr>
 *   <tr><td>429</td><td>RATE_LIMITED</td><td><b>미실측</b></td></tr>
 *   <tr><td>그 외 4xx</td><td>MALFORMED</td><td>우리 요청 문제로 본다</td></tr>
 *   <tr><td>5xx·그 외</td><td>TRANSIENT</td><td>모르는 코드는 재시도 쪽으로</td></tr>
 * </table>
 *
 * <p><b>IMPORTANT — 403을 {@code AUTH}로 접지 않는다. 그 판단이 미실측 위에 서 있다.</b>
 * SEC가 초당 한도를 넘겼을 때 무엇을 주는지 <b>재지 않았다</b> — 재려면 차단을 유발해야 하고,
 * 유발하면 IP가 일시 제한되어 그동안 아무것도 못 받는다. 모르는 채로 고를 때 두 갈래의 대가가
 * 대칭이 아니다:
 * <ul>
 *   <li>스로틀을 {@code AUTH}로 접으면 {@code fatal()}이라 <b>배치 전체가 죽는다</b> —
 *       미국이 일일 갱신에 들어온 뒤라면 국내 수집까지 함께(ADR-0088이 Alpaca에서 만난 자리다).</li>
 *   <li>진짜 권한 문제를 {@code RATE_LIMITED}로 접으면 그 실행이 <b>우아하게 멈추고</b>
 *       다음 실행이 같은 자리에서 다시 실패한다 — 시끄럽지만 되돌릴 수 있다.</li>
 * </ul>
 * <b>User-Agent가 비어 있는 경우는 이 매핑이 아니라 호출 전 검사가 덮는다</b>
 * ({@code SecEdgarFinancialAdapter.requireUserAgent}). 그것이 실제로 일어나는 유일한
 * 「권한 문제」이고, 그쪽은 {@code AUTH}로 즉시 멈추는 것이 옳다.
 *
 * <p>실측이 생기면 <b>이 표와 여기 붙은 「미실측」을 함께 고친다.</b>
 */
final class SecEdgarResponse {

    static final String SOURCE_NAME = "sec-edgar";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SecEdgarResponse() {
    }

    /**
     * {@code companyfacts}의 {@code facts} 노드. 아니면 던진다.
     *
     * <p>{@code facts}가 비어 있는 것은 던지지 않는다 — XBRL을 낸 적이 없는 발행사가 실재하고
     * 그것은 오류가 아니다. <b>키가 아예 없는 것</b>만 스펙 변경으로 본다.
     */
    static JsonNode requireFacts(HttpStatusCode status, String body, String context)
            throws DataSourceException {
        JsonNode parsed = requireJson(status, body, context);
        if (!parsed.has("facts")) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "200인데 facts 키가 없다. %s — %s".formatted(context, message(body)));
        }
        return parsed.path("facts");
    }

    /**
     * 상태코드를 먼저 보고 본문을 JSON으로 판다. <b>엔드포인트에 무관한 부분이다.</b>
     *
     * <p>{@code submissions}가 {@code companyfacts}와 <b>같은 실패 계약</b>이라는 것을 실측했다
     * (둘 다 404 + XML {@code NoSuchKey}, {@code sec-edgar-company-profile.md}). 그래서 판정을
     * 여기 하나로 둔다 — 엔드포인트마다 사본을 두면 한쪽만 고쳐도 아무 데서도 티가 안 난다.
     */
    static JsonNode requireJson(HttpStatusCode status, String body, String context)
            throws DataSourceException {
        if (!status.is2xxSuccessful()) {
            // MUST: 파싱하지 않고 상태코드로만 판정한다. 오류 본문이 XML이라 JSON으로 읽으면
            // 여기 오기 전에 터지고, 그 예외는 404를 TRANSIENT로 만든다.
            throw new DataSourceException(kindOf(status.value()), SOURCE_NAME,
                    "HTTP %d. %s — %s".formatted(status.value(), context, message(body)));
        }
        // 2xx인데 JSON으로 안 읽히거나 본문이 없으면 받기 실패다(TRANSIENT) — 끊긴 전송과 200 오류 페이지가 이 모양이고, 소스가
        // 답한 것이 아니다(ADR-0174). 받기 쪽이 같은 실행 안에서 이미 다시 받았다(SecFilingClient.fetch).
        JsonNode parsed;
        try {
            parsed = body == null || body.isBlank() ? null : MAPPER.readTree(body);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "200인데 JSON이 아니다. %s — %s".formatted(context, message(body)), e);
        }
        if (parsed == null || parsed.isMissingNode()) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "200인데 본문이 없다. %s".formatted(context));
        }
        return parsed;
    }

    /**
     * 2xx 본문이 JSON으로 안 읽히면 그 사유, 읽히면 {@code null} — 받기 쪽이 「덜 온 응답」으로 보고 같은 실행 안에서 다시 받는다.
     * 지금 전송은 본문 도중 끊겨도 받은 만큼 예외 없이 넘기므로(2026-10-06 반증), 깨진 JSON의 대부분은 해석 실패가 아니라 끊긴
     * 전송이다. 끝까지 안 읽히면 {@link #requireJson}이 TRANSIENT로 던진다.
     */
    static String unreadableJson(String body) {
        // 공백만 든 본문도 본문이 없는 것이다 — isEmpty로 물으면 그것만 다시 안 받고 바로 MALFORMED로 끝났다(v11 반증).
        if (body == null || body.isBlank()) {
            return "200인데 본문이 없다.";
        }
        try {
            return MAPPER.readTree(body) == null ? "200인데 본문이 없다." : null;
        } catch (RuntimeException e) {
            return "200인데 JSON이 아니다(끊긴 전송일 수 있다).";
        }
    }

    static DataSourceException.Kind kindOf(int status) {
        return switch (status) {
            case 404 -> DataSourceException.Kind.NOT_FOUND;
            // MUST NOT: 403을 AUTH로 올리지 않는다. 위 javadoc이 그 근거다.
            case 403, 429 -> DataSourceException.Kind.RATE_LIMITED;
            // 요청 시간 초과는 일시 실패다 — 「그 밖 4xx = 우리 요청 문제」에 넣으면 재시도 대상에서 빠진다.
            case 408 -> DataSourceException.Kind.TRANSIENT;
            default -> status >= 400 && status < 500
                    ? DataSourceException.Kind.MALFORMED
                    : DataSourceException.Kind.TRANSIENT;
        };
    }

    /**
     * 로그에 남길 본문 조각.
     *
     * <p>오류 본문이 XML이라 필드로 꺼낼 수 없다. 앞부분을 그대로 자른다 — 모양이 달라도
     * 판정에 영향을 주지 않는 자리이기 때문이다.
     */
    private static String message(String body) {
        if (body == null || body.isBlank()) {
            return "(본문 없음)";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
