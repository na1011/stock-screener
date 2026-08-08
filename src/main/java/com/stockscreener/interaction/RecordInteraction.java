package com.stockscreener.interaction;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 핸들러의 호출을 인터랙션으로 남긴다.
 *
 * <p>spec §8이 요구하는 "코드 상에서 명시적으로 표시하는 방식"의 그 표시다. 붙이는 비용이
 * 한 줄이고, 수집은 {@code InteractionInterceptor} 하나가 맡는다 — 컨트롤러 본문에는 기록
 * 코드가 한 줄도 들어가지 않는다(ADR-0028).
 *
 * <p>MUST NOT: 컨트롤러 안에서 {@link InteractionRecorder}를 직접 부르지 않는다. 손으로 넣는
 * 방식은 반드시 누락이 생기고, <b>누락된 사실은 그 행위를 조회하려 할 때에야 드러난다</b>
 * ({@code .claude/rules/backend.md}).
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RecordInteraction {

    /** 남길 행위. */
    InteractionAction value();

    /**
     * {@code detail}에 함께 담을 쿼리 파라미터 이름.
     *
     * <p>URI 템플릿 변수({@code /api/screens/{id}}의 {@code id})는 지정하지 않아도 담긴다 —
     * 그것은 언제나 그 행위의 대상이기 때문이다. 쿼리 파라미터는 대상일 수도 표시 옵션일 수도
     * 있어 화이트리스트로만 담는다.
     */
    String[] queryParams() default {};
}
