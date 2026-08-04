package com.stockscreener;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 애플리케이션 컨텍스트가 기동되는지 확인한다.
 *
 * <p>테스트 프로파일을 쓰는 이유는 이 테스트가 실제 DB 접속을 요구하기 때문이다.
 * JPA가 들어온 시점부터 컨텍스트 기동에 datasource가 필요하고, 개발 DB를 건드리지
 * 않으려면 전용 테스트 DB를 가리켜야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class StockScreenerApplicationTests {

    @Test
    void contextLoads() {
    }

}
