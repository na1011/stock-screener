package com.stockscreener.pipeline.datasource.http;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;

/**
 * API 키가 <b>실제로 설정되어 있을 때만</b> 빈을 등록한다.
 *
 * <p>IMPORTANT — {@code @ConditionalOnProperty}로는 이것을 표현할 수 없다.
 * {@code api-key: ${DATA_GO_KR_SERVICE_KEY:}}처럼 기본값이 빈 문자열이면 <b>속성은 존재하므로</b>
 * 조건이 참이 된다. 값의 내용까지 봐야 한다.
 *
 * <p>이 조건이 두 가지를 동시에 해결한다.
 * <ul>
 *   <li>키가 없는 환경(개발·CI)에서 애플리케이션이 정상 기동한다. 어댑터가 아예 없으면
 *       {@code DataSourceRegistry}는 빈 채로 뜨고, 실제 조회 시점에 명확히 실패한다.
 *   <li>테스트가 Fake 소스를 등록할 때 같은 시장에 두 소스가 걸리는 충돌이 생기지 않는다.
 *       그 충돌은 레지스트리가 기동 시점에 잡아내므로 안전하지만, 테스트가 매번 걸린다.
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Conditional(ConditionalOnApiKey.ApiKeyCondition.class)
public @interface ConditionalOnApiKey {

    /** {@code stockscreener.datasource.<value>.api-key}를 검사한다. */
    String value();

    class ApiKeyCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Map<String, Object> attributes =
                    metadata.getAnnotationAttributes(ConditionalOnApiKey.class.getName());
            if (attributes == null) {
                return false;
            }
            String property = "stockscreener.datasource." + attributes.get("value") + ".api-key";
            String value = context.getEnvironment().getProperty(property);
            return value != null && !value.isBlank();
        }
    }
}
