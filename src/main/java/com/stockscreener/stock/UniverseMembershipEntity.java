package com.stockscreener.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * {@code universe_membership} 테이블의 엔티티.
 *
 * <p>존재 이유는 하나다 — 스크리닝의 유니버스 필터가 상관 {@code EXISTS} 서브쿼리여야 하고,
 * QueryDSL이 그것을 만들려면 Q클래스가 필요하다. 편입 종목 id를 따로 조회해
 * {@code IN (...)}으로 넘기는 방법도 있지만, 그러면 쿼리가 하나 늘고 350개짜리 파라미터
 * 목록이 매 요청 오간다. ADR-0003의 "쿼리는 항상 2개"를 지키는 쪽을 택했다.
 *
 * <p>구간은 반열림 {@code [effective_from, effective_to)}이고 {@code effective_to}가 NULL이면
 * <b>현재 편입 중</b>이다. 파수꾼 값(9999-12-31) 대신 NULL을 쓰는 이유는 부분 인덱스
 * ({@code idx_membership_current})를 만들 수 있기 때문이다(V1 주석).
 *
 * <p>읽기 전용이다. 편입 이력을 쓰는 것은 {@code UniverseReconciler}이고 그쪽은 JdbcTemplate을
 * 쓴다 — 겹치는 구간을 막는 {@code EXCLUDE} 제약과 함께 다뤄야 해서 JPA로 표현할 이유가 없다.
 */
@Entity
@Table(name = "universe_membership")
public class UniverseMembershipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "universe_code", nullable = false, length = 32)
    private String universeCode;

    @Column(name = "stock_id", nullable = false)
    private Long stockId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** NULL이면 현재 편입 중이다. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    protected UniverseMembershipEntity() {
        // JPA 요구사항
    }

    public Long getId() {
        return id;
    }

    public String getUniverseCode() {
        return universeCode;
    }

    public Long getStockId() {
        return stockId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }
}
