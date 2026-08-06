package com.stockscreener.web;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 상태와 오류 종류를 함께 들고 다니는 예외.
 *
 * <p>이 계층 구조를 두는 이유는 예외마다 {@code @ExceptionHandler}를 하나씩 만들지 않기
 * 위해서다. 핸들러가 늘어나면 어느 예외가 어떤 상태로 나가는지가 여러 파일에 흩어지고,
 * 새 예외를 추가하면서 핸들러를 빠뜨리면 <b>500으로 나간다</b> — 사용자 입력 문제가
 * 서버 버그처럼 보이는 상태다.
 *
 * <p>여기 담기지 않는 예외(진짜 버그)는 잡지 않고 흘려보낸다. {@code ApiExceptionHandler}에
 * catch-all이 없는 것이 의도다.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ApiProblemType type;

    /** 응답에 실을 추가 속성. 프론트가 화면을 구성하는 데 쓴다(어느 조건, 어느 지표, 보존 구간). */
    private final Map<String, Object> properties = new LinkedHashMap<>();

    protected ApiException(HttpStatus status, ApiProblemType type, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
    }

    protected ApiException(HttpStatus status, ApiProblemType type, String detail, Throwable cause) {
        super(detail, cause);
        this.status = status;
        this.type = type;
    }

    public HttpStatus status() {
        return status;
    }

    public ApiProblemType type() {
        return type;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    protected ApiException with(String key, Object value) {
        if (value != null) {
            properties.put(key, value);
        }
        return this;
    }

    /**
     * 자동 재시도가 의미 있는가.
     *
     * <p>기본은 상태 코드에서 유도한다 — 서버가 필드를 채우는 것을 <b>기억해야</b> 성립하는
     * 규칙은 핸들러 하나를 빠뜨리는 순간 프론트의 재시도 로직을 깨뜨린다. 서버가 더 잘 아는
     * 경우에만 하위 클래스가 이 값을 뒤집는다.
     */
    public boolean retryable() {
        return status.is5xxServerError()
                || status == HttpStatus.REQUEST_TIMEOUT
                || status == HttpStatus.TOO_MANY_REQUESTS;
    }
}
