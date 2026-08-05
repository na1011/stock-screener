package com.stockscreener.pipeline.batch;

/**
 * 재실행 대상 한 건. {@code batch_execution_failure}에서 읽어온다.
 *
 * <p>관리자가 "실패한 부분만 재실행"을 누르면 이 목록이 잡의 대상 종목이 된다.
 * 단계까지 담는 이유는 이미 성공한 시세를 다시 받지 않기 위해서다.
 */
public record StockFailure(long stockId, CollectionPhase phase) {
}
