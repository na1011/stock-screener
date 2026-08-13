package com.stockscreener.pipeline.datasource;

/**
 * 소스에 요청할 정기보고서의 회계기간.
 *
 * <p>{@link FullStatementDataSource}가 <b>무엇을 부를지</b>를 호출자가 정하게 하려고 있다.
 * 어느 기간이 필요한지는 잡마다 다르고 — H-05는 연간만, §8의 이익잉여금은 분기까지 —
 * 그 차이가 곧 호출 수의 4배다. 어댑터 안의 상수로 두면 호출자가 그것을 줄일 방법이 없다.
 *
 * <p>Q4가 없는 것은 <b>4분기 보고서가 존재하지 않기 때문</b>이다. 사업보고서에서 3분기
 * 누적을 뺀 값이며, 소스가 주는 것이 아니라 우리가 유도한다.
 */
public enum StatementPeriod {

    /** 사업보고서. */
    FY,
    /** 1분기보고서. */
    Q1,
    /** 반기보고서. */
    Q2,
    /** 3분기보고서. */
    Q3;

    /** {@link RawFinancialReport#fiscalPeriod()}에 그대로 실리는 라벨. */
    public String label() {
        return name();
    }
}
