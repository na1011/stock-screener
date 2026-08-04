package com.stockscreener.support;

import javax.sql.DataSource;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 테스트가 전용 테스트 DB가 아닌 곳을 가리키면 기동을 실패시킨다.
 *
 * <p>테스트 스위트는 데이터를 지우고 되돌린다. 그 대상이 개발 DB였던 것을 나중에 알게 되면
 * 되돌릴 방법이 없다. 다섯 줄로 막을 수 있는 사고다.
 */
@TestConfiguration
public class TestDatabaseGuard {

    @Bean
    public InitializingBean assertTestDatabase(DataSource dataSource) {
        return () -> {
            String url;
            try (Connection connection = dataSource.getConnection()) {
                url = connection.getMetaData().getURL();
            } catch (SQLException e) {
                throw new IllegalStateException("테스트 DB 접속에 실패했다. 로컬 PostgreSQL이 구동 중인지 확인하라.", e);
            }
            if (url == null || !url.contains("_test")) {
                throw new IllegalStateException(
                        "테스트가 전용 테스트 DB를 가리키고 있지 않다: " + url
                                + " — 이름에 '_test'가 포함된 DB만 허용한다.");
            }
        };
    }
}
