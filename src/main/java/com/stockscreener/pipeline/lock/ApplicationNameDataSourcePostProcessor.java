package com.stockscreener.pipeline.lock;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 앱 데이터소스의 모든 연결에 쓰기 신원({@link WriteIdentity#applicationName()})을 싣는다(ADR-0175).
 *
 * <p>가드 트리거는 연결의 {@code application_name}으로 보유자를 알아본다. 풀이 연결을 만들 때 실어야 하므로 데이터소스가
 * 초기화되기 <b>전에</b> 넣는다 — Hikari는 첫 연결을 얻을 때 풀을 시작하고 그 뒤로는 설정을 못 바꾼다.
 *
 * <p>pgjdbc는 {@code PGAPPNAME}을 안 읽는다(42.7.11 실측) — 환경변수로는 JVM의 신원을 못 정한다. psql·pg_restore는 libpq라
 * {@code PGAPPNAME}을 읽고, {@code ops/write-session.sh exec}가 그쪽에 준다.
 */
@Component
class ApplicationNameDataSourcePostProcessor implements BeanPostProcessor {

    static final String PROPERTY = "ApplicationName";

    private final Environment environment;

    ApplicationNameDataSourcePostProcessor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource dataSource) {
            dataSource.addDataSourceProperty(PROPERTY, WriteIdentity.resolve(environment).applicationName());
        }
        return bean;
    }
}
