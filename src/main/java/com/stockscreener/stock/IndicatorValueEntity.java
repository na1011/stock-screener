package com.stockscreener.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * {@code indicator_value} EAV 테이블의 엔티티.
 *
 * <p>복합 자연키를 쓴다. 대리키를 두면 8바이트와 인덱스 하나가 행마다 늘어나는데,
 * 이 테이블은 행 수가 가장 많고 접근 패턴이 (일자, 지표) 슬라이스라 대리키를 쓸 일이 없다.
 */
@Entity
@Table(name = "indicator_value")
@IdClass(IndicatorValueEntity.Key.class)
public class IndicatorValueEntity {

    @Id
    @Column(name = "stock_id", nullable = false)
    private Long stockId;

    @Id
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Id
    @Column(name = "indicator_code", nullable = false, length = 40)
    private String indicatorCode;

    @Column(name = "value", nullable = false, precision = 24, scale = 8)
    private BigDecimal value;

    protected IndicatorValueEntity() {
        // JPA 요구사항
    }

    public IndicatorValueEntity(Long stockId, LocalDate tradeDate, String indicatorCode, BigDecimal value) {
        this.stockId = stockId;
        this.tradeDate = tradeDate;
        this.indicatorCode = indicatorCode;
        this.value = value;
    }

    public Long getStockId() {
        return stockId;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public String getIndicatorCode() {
        return indicatorCode;
    }

    public BigDecimal getValue() {
        return value;
    }

    /** 복합키. */
    public static class Key implements Serializable {
        private Long stockId;
        private LocalDate tradeDate;
        private String indicatorCode;

        public Key() {
        }

        public Key(Long stockId, LocalDate tradeDate, String indicatorCode) {
            this.stockId = stockId;
            this.tradeDate = tradeDate;
            this.indicatorCode = indicatorCode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(stockId, key.stockId)
                    && Objects.equals(tradeDate, key.tradeDate)
                    && Objects.equals(indicatorCode, key.indicatorCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stockId, tradeDate, indicatorCode);
        }
    }
}
