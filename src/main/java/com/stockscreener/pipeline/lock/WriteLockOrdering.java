package com.stockscreener.pipeline.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 쓰기 잠금 게이트를 <b>쓰기보다 앞</b>, DB 가드보다 <b>뒤</b>에 놓는다(ADR-0175).
 *
 * <ul>
 *   <li>{@code flywayInitializer} → 게이트에 매단다(마이그레이션의 DML이 잠금 안에서 돈다).</li>
 *   <li>{@code entityManagerFactory} → 게이트와 {@link WriteLockAfterFlyway}에 매단다 — JPA를 쓰는 모든 빈(시더 포함)이 그 뒤다.</li>
 *   <li>게이트 → DB 가드들 중 <b>있는 것만</b>({@code bootstrapDatabaseGuard}는 bootstrap 프로파일에만, {@code assertTestDatabase}·
 *       {@code assertReadOnlyProductionDatabase}는 시험에만 있다). 이름을 그냥 박으면 없는 프로파일에서
 *       {@code NoSuchBeanDefinitionException}으로 기동이 죽는다(잠금 v4 반증 L1-8).</li>
 * </ul>
 *
 * <p>{@code spring.flyway.enabled}와 무관하다 — Flyway 빈이 없으면 그 줄만 건너뛴다. 매달 빈이 하나도 없으면 WARN을 남긴다:
 * 그때 게이트는 쓰기보다 늦게 돌 수 있다.
 */
@Component
class WriteLockOrdering implements BeanFactoryPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(WriteLockOrdering.class);

    static final String FLYWAY_INITIALIZER = "flywayInitializer";
    static final String ENTITY_MANAGER_FACTORY = "entityManagerFactory";

    static final List<String> DATABASE_GUARDS = List.of(
            "bootstrapDatabaseGuard", "gateSettingGuard", "assertTestDatabase", "assertReadOnlyProductionDatabase");

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (!beanFactory.containsBeanDefinition(WriteLockGate.BEAN)) {
            return;
        }
        for (String guard : DATABASE_GUARDS) {
            if (beanFactory.containsBeanDefinition(guard)) {
                dependOn(beanFactory, WriteLockGate.BEAN, guard);
            }
        }
        boolean anchored = false;
        if (beanFactory.containsBeanDefinition(FLYWAY_INITIALIZER)) {
            dependOn(beanFactory, FLYWAY_INITIALIZER, WriteLockGate.BEAN);
            dependOn(beanFactory, WriteLockAfterFlyway.BEAN, FLYWAY_INITIALIZER);
            anchored = true;
        }
        if (beanFactory.containsBeanDefinition(ENTITY_MANAGER_FACTORY)) {
            dependOn(beanFactory, ENTITY_MANAGER_FACTORY, WriteLockGate.BEAN);
            dependOn(beanFactory, ENTITY_MANAGER_FACTORY, WriteLockAfterFlyway.BEAN);
            anchored = true;
        }
        if (!anchored) {
            log.warn("flywayInitializer·entityManagerFactory가 둘 다 없다 — 쓰기 잠금 게이트가 쓰기보다 늦게 돌 수 있다.");
        }
    }

    private static void dependOn(ConfigurableListableBeanFactory beanFactory, String bean, String dependency) {
        BeanDefinition definition = beanFactory.getBeanDefinition(bean);
        Set<String> dependsOn = new LinkedHashSet<>();
        if (definition.getDependsOn() != null) {
            dependsOn.addAll(List.of(definition.getDependsOn()));
        }
        dependsOn.add(dependency);
        definition.setDependsOn(dependsOn.toArray(String[]::new));
    }
}
