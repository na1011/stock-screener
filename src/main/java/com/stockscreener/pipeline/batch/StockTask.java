package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;

/**
 * 한 종목에 대해 수행하는 작업.
 *
 * <p>{@link DataSourceException}을 던질 수 있게 선언한 이유는, 수집 실패가 예상되는 사건이며
 * 호출부가 그것을 마주하도록 강제하기 위해서다. {@code Runnable}로 두면 어댑터의 checked
 * exception을 작업 안에서 잡아 삼키는 코드가 자연스러워진다.
 *
 * @return 이 작업이 저장한 행 수. 관리자 화면의 "처리 건수"가 이 합계다.
 */
@FunctionalInterface
public interface StockTask {

    long run(SecurityRef security) throws DataSourceException;
}
