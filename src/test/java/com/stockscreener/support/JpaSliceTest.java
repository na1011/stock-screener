package com.stockscreener.support;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 실제 PostgreSQL과 Flyway 마이그레이션을 쓰는 JPA 슬라이스 테스트.
 *
 * <p>세 가지를 한데 묶는다. 각각을 매 테스트에 반복해서 붙이면 하나를 빠뜨린 테스트가
 * 다른 스키마를 보게 되고, 그 차이는 실패가 아니라 조용한 오검증으로 나타난다.
 *
 * <ul>
 *   <li><b>임베디드 DB로 교체하지 않는다</b> — EAV 설계가 PostgreSQL 고유 기능(INCLUDE 인덱스,
 *       PERCENT_RANK, ON CONFLICT, EXCLUDE 제약)에 의존한다. 임베디드로 돌리면 초록불인 채로
 *       실제 쿼리 플랜과 제약을 검증하지 못한다.
 *   <li><b>Flyway를 명시적으로 켠다</b> — {@code @DataJpaTest} 슬라이스는 Flyway 자동설정을
 *       제외하므로, 마이그레이션이 실행되지 않은 채 {@code ddl-auto: validate}가 실패한다.
 *       테스트가 운영과 같은 마이그레이션을 실행해야 엔티티/스키마 어긋남이 여기서 잡힌다.
 *   <li><b>테스트 DB를 단언한다</b> — 이름에 {@code _test}가 없는 DB를 가리키면 기동을 실패시킨다.
 * </ul>
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
public @interface JpaSliceTest {
}
