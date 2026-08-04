package com.stockscreener.probe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * QueryDSL이 런타임에 실제로 동작하는지 증명하기 위한 프로브 엔티티.
 *
 * <p>도메인 엔티티가 아니다. 일부러 최소한으로 만들었고, 도메인 설계가 바뀌어도
 * 이 검증이 영향받지 않아야 하기 때문에 실제 테이블과 분리되어 있다.
 *
 * <p>MUST NOT: 이 클래스를 "쓰이지 않는 코드"로 보고 지우지 않는다.
 * QueryDSL mainline(com.querydsl)과 Hibernate 7의 비호환은 컴파일·Q클래스 생성·
 * 컨텍스트 기동을 모두 통과하고 쿼리를 실행하는 순간에만 NoSuchMethodError로 드러난다.
 * 이 프로브는 그 회귀를 잡는 유일한 수단이며, Boot·Hibernate 업그레이드마다 다시 필요해진다.
 * 자세한 배경은 ADR-0001에 있다.
 */
@Entity
@Table(name = "querydsl_probe")
public class QuerydslProbeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String label;

    protected QuerydslProbeEntity() {
        // JPA 요구사항
    }

    public QuerydslProbeEntity(String label) {
        this.label = label;
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }
}
