package com.stockscreener.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code universe} 테이블의 엔티티.
 *
 * <p>존재 이유는 {@link UniverseMembershipEntity}와 같은 자리다 — 스크리닝이 <b>유니버스를
 * 지정하지 않았을 때</b>의 모집단이 "활성 유니버스 중 하나에라도 편입된 종목"이어야 하고
 * (ADR-0087), 그것을 상관 {@code EXISTS} 안에서 표현하려면 Q클래스가 필요하다. 활성 코드를
 * 따로 조회해 {@code IN (...)}으로 넘기면 쿼리가 하나 늘어 ADR-0003의 "쿼리는 항상 2개"가
 * 깨진다.
 *
 * <p><b>읽기 전용이다.</b> 유니버스 행을 만들고 켜고 끄는 것은 마이그레이션이며, 그것이
 * 운영과 테스트에서 서로 다른 값을 갖는 것이 정상이다(ADR-0012).
 *
 * <p>자연키를 그대로 PK로 쓴다. 행이 몇 개뿐이고 코드 문자열이 곧 식별자라 대리키를 둘 이유가
 * 없다(V1 주석).
 */
@Entity
@Table(name = "universe")
public class UniverseEntity {

    @Id
    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "market_code", nullable = false, length = 16)
    private String marketCode;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected UniverseEntity() {
        // JPA 요구사항
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getMarketCode() {
        return marketCode;
    }

    public boolean isActive() {
        return active;
    }
}
