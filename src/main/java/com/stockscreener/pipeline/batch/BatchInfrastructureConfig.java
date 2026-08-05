package com.stockscreener.pipeline.batch;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JdbcJobRepositoryFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Spring Batch가 실행 이력을 <b>DB에 저장하도록</b> 강제한다.
 *
 * <p>IMPORTANT — 이 설정이 없으면 Spring Batch 6은 {@code ResourcelessJobRepository}(인메모리)로
 * 조용히 동작한다. 잡은 정상적으로 실행되고 로그도 깨끗하며 {@code BATCH_*} 테이블도 존재하지만,
 * <b>아무것도 기록되지 않아 재시작이 불가능하다.</b> 중단된 초기 적재를 다시 돌리면 처음부터
 * 시작하고, 쿼터가 있는 소스에서 그것은 그대로 낭비다.
 *
 * <p>Boot 4.1은 JDBC {@code JobRepository}를 자동 구성하지 않는다 —
 * {@code BatchProperties}에 {@code job} 항목만 있고 {@code jdbc} 항목 자체가 없어,
 * {@code spring.batch.jdbc.initialize-schema} 같은 속성은 존재하지도 않는다.
 * Boot 3 예제를 따라 그 속성을 yaml에 적으면 조용히 무시된다.
 *
 * <p>스키마는 Flyway {@code V6}가 만든다. 이 팩토리는 테이블을 생성하지 않고 쓰기만 한다 —
 * 스키마의 정본이 두 곳이 되지 않게 하기 위함이다. ADR-0005 참조.
 */
@Configuration
@EnableBatchProcessing
public class BatchInfrastructureConfig {

    /**
     * 애플리케이션 DataSource를 그대로 쓴다.
     *
     * <p>배치 메타데이터를 별도 DB에 두지 않는 이유는, 같은 트랜잭션 안에서 도메인 쓰기와
     * 배치 상태 갱신이 함께 커밋되어야 재시작 지점이 실제 저장 결과와 어긋나지 않기 때문이다.
     *
     * <p>{@code databaseType}을 명시하는 이유는 자동 감지가 실패하면 기동이 아니라 첫 잡
     * 실행에서 터지기 때문이다. 새벽 배치에서 드러나는 것보다 낫다.
     */
    @Bean
    public JobRepository jobRepository(DataSource dataSource,
                                       PlatformTransactionManager transactionManager) throws Exception {
        JdbcJobRepositoryFactoryBean factory = new JdbcJobRepositoryFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTransactionManager(transactionManager);
        factory.setDatabaseType("POSTGRES");
        factory.afterPropertiesSet();
        return factory.getObject();
    }
}
