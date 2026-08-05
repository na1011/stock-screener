package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 종목 작업 하나를 독립 트랜잭션에서 실행한다.
 *
 * <p>IMPORTANT — 이 클래스가 {@link StockTaskExecutor}에서 분리되어 있는 것은 스타일이
 * 아니라 필요다. Spring의 {@code @Transactional}은 프록시로 동작하므로 <b>같은 빈 안에서의
 * 자기 호출에는 걸리지 않는다.</b> 실행과 실패 처리를 한 클래스에 두면 {@code REQUIRES_NEW}가
 * 조용히 무시되고, 그러면 실패한 종목의 롤백이 같은 청크의 성공 종목까지 되돌린다 —
 * 격리가 사라졌는데 테스트는 통과할 수 있다.
 *
 * <p>{@code REQUIRES_NEW}인 이유는 롤백 범위를 종목 하나로 한정하기 위해서다. 바깥
 * 트랜잭션에 참여하면 한 종목의 실패가 그때까지 저장한 모든 종목을 되돌린다.
 *
 * <p>IMPORTANT — {@code rollbackFor = Exception.class}가 반드시 필요하다. Spring의 기본
 * 롤백 규칙은 {@code RuntimeException}과 {@code Error}만 되돌리고 <b>checked exception에는
 * 커밋한다.</b> {@link DataSourceException}이 checked이므로, 이것이 없으면 수집 도중 실패한
 * 종목의 부분 저장이 그대로 커밋된다 — 격리가 있다고 믿는 채로 반쪽짜리 데이터가 쌓이고,
 * 그 데이터는 아무 에러도 내지 않는다.
 */
@Component
public class StockTaskRunner {

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public long runInNewTransaction(SecurityRef security, StockTask task) throws DataSourceException {
        return task.run(security);
    }
}
