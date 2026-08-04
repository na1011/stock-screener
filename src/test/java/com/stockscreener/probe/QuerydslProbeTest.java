package com.stockscreener.probe;

import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.stockscreener.support.JpaSliceTest;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueryDSL이 실제 PostgreSQL에 쿼리를 실행하는지 증명한다. Phase 1의 하드 게이트다.
 *
 * <p>이 테스트가 증명하는 것은 두 가지이며, 둘 다 다른 방법으로는 증명되지 않는다.
 *
 * <ul>
 *   <li><b>생성된 Q클래스를 쓴다</b> — annotationProcessor가 올바른 classifier
 *       (querydsl-apt:jakarta)로 동작했음을 증명한다.
 *   <li><b>{@code fetch()}를 호출한다</b> — AbstractHibernateQuery가 Hibernate 7에서
 *       실제로 실행됨을 증명한다. mainline(com.querydsl)이었다면 여기서
 *       NoSuchMethodError가 난다.
 * </ul>
 *
 * <p>쿼리를 조립만 하고 실행하지 않는 테스트는 아무것도 증명하지 못한다 —
 * 컴파일·Q클래스 생성·컨텍스트 기동은 깨진 조합에서도 모두 성공하기 때문이다.
 */
@JpaSliceTest
class QuerydslProbeTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("QueryDSL이 생성된 Q클래스로 실제 쿼리를 실행한다")
    void executesQueryThroughGeneratedQType() {
        entityManager.persist(new QuerydslProbeEntity("alpha"));
        entityManager.persist(new QuerydslProbeEntity("beta"));
        entityManager.flush();

        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);
        QQuerydslProbeEntity probe = QQuerydslProbeEntity.querydslProbeEntity;

        // fetch()가 핵심이다. 이 호출이 Hibernate 7 위에서 성공해야 한다.
        List<QuerydslProbeEntity> all = queryFactory
                .selectFrom(probe)
                .orderBy(probe.label.asc())
                .fetch();

        assertThat(all).extracting(QuerydslProbeEntity::getLabel)
                .containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("where 술어가 SQL로 번역되어 실행된다")
    void appliesPredicate() {
        entityManager.persist(new QuerydslProbeEntity("alpha"));
        entityManager.persist(new QuerydslProbeEntity("beta"));
        entityManager.flush();

        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);
        QQuerydslProbeEntity probe = QQuerydslProbeEntity.querydslProbeEntity;

        List<QuerydslProbeEntity> filtered = queryFactory
                .selectFrom(probe)
                .where(probe.label.eq("beta"))
                .fetch();

        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).getLabel()).isEqualTo("beta");
    }

    @Test
    @DisplayName("상관 서브쿼리 EXISTS가 실행된다 — 조건 번역이 이 형태에 의존한다")
    void executesExistsSubquery() {
        entityManager.persist(new QuerydslProbeEntity("alpha"));
        entityManager.flush();

        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);
        // 별칭에 outer/inner를 쓰지 않는다 — HQL 예약어라 구문 오류가 난다.
        // 조건 번역기가 별칭을 자동 생성할 때도 같은 함정이 있으므로 여기서 못박아둔다.
        QQuerydslProbeEntity probe0 = new QQuerydslProbeEntity("probe0");
        QQuerydslProbeEntity probe1 = new QQuerydslProbeEntity("probe1");

        List<QuerydslProbeEntity> matched = queryFactory
                .selectFrom(probe0)
                .where(JPAExpressions.selectOne()
                        .from(probe1)
                        .where(probe1.id.eq(probe0.id).and(probe1.label.eq("alpha")))
                        .exists())
                .fetch();

        assertThat(matched).extracting(QuerydslProbeEntity::getLabel)
                .containsExactly("alpha");
    }
}
