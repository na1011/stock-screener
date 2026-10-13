package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * DART 응답의 {@code status} 필드를 판별한다.
 *
 * <p>IMPORTANT — DART는 <b>인증 실패도 HTTP 200으로 응답한다</b>(실측 확인).
 * 공공데이터포털이 403을 주는 것과 정반대다. HTTP 상태코드만 보면 잘못된 키로 조회한 결과를
 * 정상으로 오인하므로, {@code status} 파싱이 <b>필수</b>다.
 *
 * <p>두 소스의 실패 표현이 반대라 공통 처리로 뭉뚱그릴 수 없다는 것이 ADR-0009의 요지다.
 *
 * <h2>그래도 HTTP 상태를 먼저 본다 — 본문은 바이트로 받는다({@link #read})</h2>
 * <p>200의 {@code status} 판별이 필수라는 것과, 200이 아닌 응답(점검 페이지 · 프록시 오류 · 한도)을 본문으로 판정하는 것은
 * 다르다. 상태를 안 보면 5xx HTML이 「본문 해석 실패」가 되어 소스의 답을 받은 것처럼 섞인다(ADR-0174). 그리고 <b>본문을
 * 문자열로 받지 않는다</b> — {@code toEntity(String)}은 JSON이 아닌 content-type에 charset이 없으면 ISO-8859-1로 읽어 한글
 * 계정명을 조용히 깨뜨리고, 깨진 문자열도 JSON으로는 읽혀 계정 매칭이 하나도 안 맞은 채 종목이 성공한다(2026-10-07 반증 실측).
 * 바이트로 받아 <b>응답이 선언한 charset이 있으면 그것으로, 없으면 Jackson의 자동 감지(UTF 계열 · BOM)로</b> 판다 — 옛 Spring
 * 변환기가 하던 두 가지다. UTF-8로 못박으면 BOM이 붙은 본문은 파싱이 터지고(국내 전 종목 TRANSIENT) {@code charset=EUC-KR}
 * 선언은 U+FFFD로 깨져 같은 조용한 결손이 다른 입구로 들어온다(구현 반증). DART가 실제로 어느 헤더를 내는지는 재지 않았다 —
 * 두 갈래가 어느 쪽이든 같은 값을 내므로 잴 필요가 없다.
 */
final class DartResponse {

    static final String SOURCE_NAME = "dart";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DartResponse() {
    }

    /**
     * 경로 하나의 콜 앞에서 그 경로의 일일 총량을 보고 토큰을 얻는다 — DART 어댑터 전부가 이 자리를 지난다.
     *
     * <p>총량은 경로마다({@link DartDailyQuota} · ADR-0176), 속도는 키 합({@code dartRateLimiter})으로 센다. 소진은
     * {@code RATE_LIMITED}(다음 실행이 이어 간다)이고, 대기 중 인터럽트는 인터럽트 상태를 되살린 뒤 {@code TRANSIENT}로 던진다.
     *
     * @param path 요청 URL의 경로 그대로({@code /fnlttSinglAcnt.json})
     */
    static void acquire(RateLimiter rateLimiter, DartDailyQuota quota, String path, String context)
            throws DataSourceException {
        if (!quota.has(path) || !rateLimiter.hasDailyQuota()) {
            throw new DataSourceException(DataSourceException.Kind.RATE_LIMITED, SOURCE_NAME,
                    "일일 호출 한도를 소진했다(" + path + " 사용 " + quota.used(path) + "회). " + context);
        }
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "호출 대기 중 인터럽트됐다. " + context, e);
        }
        quota.count(path);
    }

    /**
     * {@code status 013}·{@code 014} — 그 조회에 데이터가 없다는 <b>JSON 상태</b>. 어댑터가 「없음」으로 삼키는 유일한 실패다.
     *
     * <p>{@code NOT_FOUND} 종류만 보고 삼키면 다른 자리의 {@code NOT_FOUND}(소스 미등록 등)까지 「빈 결과」가 된다. HTTP 404는
     * 여기서 아예 {@code NOT_FOUND}가 아니다({@link #read}) — 경로 변경·점검의 404가 전 종목 빈 성공이 되는 자리였다.
     */
    static final class NoData extends DataSourceException {
        NoData(String message) {
            super(Kind.NOT_FOUND, SOURCE_NAME, message);
        }
    }

    /** 어댑터가 「데이터 없음」으로 삼켜도 되는 실패인가 — JSON {@code status 013}·{@code 014}일 때만 참이다. */
    static boolean isNoData(DataSourceException e) {
        return e instanceof NoData;
    }

    /**
     * HTTP 상태를 먼저 보고, 2xx면 본문 바이트를 UTF-8 JSON으로 판다. 판 결과의 {@code status}는 {@link #requireList}·
     * {@link #requireSuccess}가 본다.
     *
     * <ul>
     *   <li>429 → {@code RATE_LIMITED}</li>
     *   <li>그 밖 비2xx(5xx · 408 · 404를 포함한 4xx) → {@code TRANSIENT} — DART의 「데이터 없음」은 HTTP가 아니라 JSON
     *       {@code status}로 온다. HTTP 404를 {@code NOT_FOUND}로 매기면 어댑터들이 「없음」으로 삼켜 전 종목이 빈 성공이 된다.</li>
     *   <li>2xx인데 본문이 없거나 JSON이 아님 → {@code TRANSIENT} — 받기 실패다(끊긴 전송 · 오류 페이지). 소스가 답한 것이 아니다.</li>
     * </ul>
     */
    static JsonNode read(ResponseEntity<byte[]> response, String context) throws DataSourceException {
        int status = response.getStatusCode().value();
        byte[] body = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful()) {
            DataSourceException.Kind kind = status == 429
                    ? DataSourceException.Kind.RATE_LIMITED : DataSourceException.Kind.TRANSIENT;
            throw new DataSourceException(kind, SOURCE_NAME,
                    "HTTP %d — %s. %s".formatted(status, snippet(body), context));
        }
        if (body == null || body.length == 0) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "HTTP %d인데 본문이 없다. %s".formatted(status, context));
        }
        JsonNode root;
        try {
            java.nio.charset.Charset declared = declaredCharset(response);
            root = declared == null
                    ? MAPPER.readTree(body)
                    : MAPPER.readTree(stripBom(new String(body, declared)));
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "HTTP %d인데 JSON이 아니다 — %s. %s".formatted(status, snippet(body), context), e);
        }
        if (root == null || root.isMissingNode()) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "HTTP %d인데 본문이 비어 있다. %s".formatted(status, context));
        }
        return root;
    }

    /**
     * 응답이 선언한 charset. 선언이 없으면 {@code null}이다. 헤더를 못 읽거나(모양 이상) 모르는 charset이면 던진다 — 그 본문을
     * 어느 인코딩으로 읽어도 맞다는 보장이 없어, 추측해 읽으면 조용한 결손이 된다(받기 실패로 돌린다).
     */
    private static java.nio.charset.Charset declaredCharset(ResponseEntity<byte[]> response) {
        org.springframework.http.MediaType type = response.getHeaders().getContentType();
        return type == null ? null : type.getCharset();
    }

    /** 선언 charset으로 바꾼 문자열의 BOM(U+FEFF)을 뗀다 — Jackson은 문자열 앞의 BOM을 토큰으로 읽어 터진다. */
    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private static String snippet(byte[] body) {
        return body == null || body.length == 0 ? "(본문 없음)" : truncate(new String(body, StandardCharsets.UTF_8));
    }

    /**
     * status를 검사하고 실패면 예외를 던진다. 정상이면 {@code list} 배열 노드를 준다.
     *
     * @return 결과 배열. {@code status 013}(데이터 없음)은 예외로 던진다 —
     *         "조회할 보고서가 없다"는 종목 단위 실패이며 호출부가 구분해 처리한다.
     */
    static JsonNode requireList(JsonNode root, String context) throws DataSourceException {
        JsonNode list = requireSuccess(root, context).path("list");
        if (!list.isArray()) {
            // status 000인데 list가 없거나 배열이 아니다 — 빈 결과로 읽으면 기간이 조용히 빠지고 거버넌스는 「그 회사에 그 표가
            // 없다」(noData)를 틀린 사실로 남긴다. 스펙 변경 꼴이라 MALFORMED다(답함 표지 없음 · 다발 경보가 읽는 신호).
            // 저장한 원응답 180에서 000이면 list가 있었다(168/168 · 재무 엔드포인트 — 2026-10-07 주인 실측).
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "status 000인데 list 배열이 없다 — " + context);
        }
        return list;
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
            // 본문이 없는 것은 받기 실패다 — 소스가 답한 것이 아니다({@link #read}가 먼저 거른다).
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "응답 본문이 비어 있다. " + context);
        }
        String status = root.path("status").asString("");
        if (status.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "status 필드가 없다. " + context);
        }
        if (!"000".equals(status)) {
            String message = "DART 오류 [" + status + "] " + root.path("message").asString("") + " — " + context;
            if ("013".equals(status) || "014".equals(status)) {
                throw new NoData(message);
            }
            throw new DataSourceException(kindOf(status), SOURCE_NAME, message);
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
        // ZIP도 DART 오류 JSON도 아닌 본문(오류 페이지 · 끊긴 전송)은 받기 실패다 — 소스가 답한 것이 아니다.
        return new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
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
     * 021 조회 가능한 회사 개수 초과(최대 100)  MALFORMED — 우리 요청 오류 · 「답 못 받음」(ADR-0174)
     * 100 필드의 부적절한 값               MALFORMED — 우리 요청 오류 · 「답 못 받음」
     * 101 부적절한 접근                    AUTH
     * 800 시스템 점검 중                   TRANSIENT
     * 900 정의되지 않은 오류               TRANSIENT
     * 901 개인정보 보유기간 만료 키         AUTH
     * </pre>
     *
     * <p>실측: 없는 연도 조회 → HTTP 200 + {@code status 013}. 잘못된 키 → HTTP 200 + {@code status 010}.
     *
     * <p>{@code 013}·{@code 014}는 {@link NoData}로 던진다 — 어댑터가 삼키는 것은 그 형뿐이다. HTTP 비2xx는 이 표를 안
     * 지난다({@link #read}).
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
