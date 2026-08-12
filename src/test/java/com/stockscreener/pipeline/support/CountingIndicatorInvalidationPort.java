package com.stockscreener.pipeline.support;

import com.stockscreener.pipeline.collector.IndicatorInvalidationPort;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 무효화가 <b>몇 번</b> 어디서부터 불렸는지 세는 더블.
 *
 * <p>필요한 이유는 무효화가 그 종목의 {@code indicator_value}를 전량 지우기 때문이다.
 * 멱등한 재구축이 매번 무효화까지 부르면 값은 언제나 옳은데 <b>16만 행이 매 실행 지워지고
 * 그 종목이 다음 백필까지 화면에서 사라진다</b> — 아무 에러도 없다. 호출 여부만 보는 더블로는
 * 그것을 잴 수 없다.
 */
public class CountingIndicatorInvalidationPort implements IndicatorInvalidationPort {

    public record Call(long stockId, LocalDate fromDate, InvalidationReason reason) {
    }

    private final List<Call> calls = new ArrayList<>();

    @Override
    public void invalidate(long stockId, LocalDate fromDate, InvalidationReason reason) {
        calls.add(new Call(stockId, fromDate, reason));
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public void reset() {
        calls.clear();
    }
}
