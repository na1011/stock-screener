package com.stockscreener.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * API 오류를 RFC 7807 {@code application/problem+json}으로 낸다.
 *
 * <p>IMPORTANT — <b>{@code @ExceptionHandler(Exception.class)}를 두지 않는다.</b> 전역
 * CLAUDE.md의 catch-all 금지이기도 하지만, 더 실질적인 이유가 있다. catch-all이 있으면
 * Phase 6에서 Sentry를 붙였을 때 무엇이 수집되고 무엇이 삼켜지는지가 핸들러 등록 순서에
 * 좌우되는 상태가 되고, 그것은 확인하기 어렵다. 알려지지 않은 예외는 여기서 잡지 않고
 * Boot 기본 경로로 흘러가 스택트레이스와 함께 로그에 남는다.
 *
 * <p>{@link ResponseEntityExceptionHandler}를 상속해 프레임워크 예외(잘못된 JSON, 지원하지
 * 않는 메서드 등)의 처리를 물려받는다. 그것들까지 손으로 다시 쓰면 빠뜨리는 것이 생긴다.
 *
 * <p><b>재시도 판별.</b> 프론트는 두 단계로 본다 — ① 상태 코드(5xx·408·429는 자동 재시도,
 * 그 외 4xx는 사용자 조치, 401은 재인증), ② 바디의 {@code retryable}이 있으면 그것이 이긴다.
 * 이 순서가 중요하다. 서버가 필드를 채우는 것을 기억해야만 성립하는 규칙이면 핸들러 하나를
 * 빠뜨린 응답이 프론트의 재시도를 깨뜨린다. 지금 구조에서는 Boot 기본 오류 바디로 떨어져도
 * 프론트가 계속 동작한다.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setType(e.type().type());
        problem.setTitle(e.type().title());
        problem.setProperty("retryable", e.retryable());
        e.properties().forEach(problem::setProperty);

        // 5xx는 우리 쪽 문제다. 사용자에게는 짧게 알리되 로그에는 원인을 남긴다 —
        // 삼키지 않는다는 것이 "조용히 통과시키지 않는다"와 "로그에 남긴다" 둘 다를 뜻한다.
        if (e.status().is5xxServerError()) {
            log.error("API 오류 {} {}: {}", e.status().value(), e.type().name(), e.getMessage(), e);
        } else {
            log.debug("API 거절 {} {}: {}", e.status().value(), e.type().name(), e.getMessage());
        }

        return ResponseEntity.status(e.status()).body(problem);
    }

    /**
     * 쓰기 잠금 거절은 어느 컨트롤러에서 나든 409다(ADR-0175) — 잠금을 쥐는 자리(관리자 launch · 원장 판정)가 컨트롤러마다
     * 옮겨 적으면 한 곳이 500으로 샌다.
     */
    @ExceptionHandler(com.stockscreener.pipeline.lock.WriteLockHeldException.class)
    public ResponseEntity<ProblemDetail> handleWriteLockHeld(com.stockscreener.pipeline.lock.WriteLockHeldException e) {
        return handleApiException(new ApiExceptions.WriteLockHeldApiException(e));
    }

    /**
     * {@code @Valid} 위반. 어느 필드가 왜 틀렸는지를 목록으로 준다.
     *
     * <p>기본 구현은 상태와 제목만 준다. 필드를 알려주지 않으면 조건 빌더가 어느 입력을
     * 강조할지 알 수 없고, 사용자는 폼 전체를 훑어야 한다.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "요청 값이 올바르지 않다");
        problem.setType(ApiProblemType.INVALID_REQUEST.type());
        problem.setTitle(ApiProblemType.INVALID_REQUEST.title());
        problem.setProperty("retryable", false);

        List<Map<String, Object>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::describe)
                .toList();
        problem.setProperty("errors", errors);

        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    private static Map<String, Object> describe(FieldError error) {
        return Map.of(
                "field", error.getField(),
                "message", error.getDefaultMessage() == null ? "올바르지 않은 값" : error.getDefaultMessage());
    }

    /**
     * 프레임워크 예외(잘못된 JSON, 지원하지 않는 메서드 등)에도 우리 규약의 최소 형태를 씌운다.
     *
     * <p>{@code retryable}만 채운다. 종류별 {@code type}까지 만들면 프론트가 분기하지도 않을
     * 상수가 늘어난다 — 이 경우들은 전부 "요청이 잘못됐다"로 같게 다뤄진다.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

        if (body instanceof ProblemDetail problem && problem.getProperties() == null) {
            problem.setProperty("retryable",
                    statusCode.is5xxServerError() || statusCode.value() == HttpStatus.TOO_MANY_REQUESTS.value());
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }
}
