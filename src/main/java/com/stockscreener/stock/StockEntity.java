package com.stockscreener.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code stock} 테이블의 엔티티.
 *
 * <p>스크리닝 쿼리의 구동 테이블이다. 티커가 아니라 {@code id}로 참조한다 —
 * 티커는 시장이 다르면 충돌할 수 있고 미국 티커는 상장폐지 후 재사용된다.
 */
@Entity
@Table(name = "stock")
public class StockEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "market_code", nullable = false, length = 16)
    private String marketCode;

    @Column(name = "ticker", nullable = false, length = 20)
    private String ticker;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected StockEntity() {
        // JPA 요구사항
    }

    public StockEntity(String marketCode, String ticker, String name) {
        this.marketCode = marketCode;
        this.ticker = ticker;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getMarketCode() {
        return marketCode;
    }

    public String getTicker() {
        return ticker;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }
}
