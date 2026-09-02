package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.PriceBar;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;

/**
 * 계산기 18개가 <b>분석 하나</b>를 나눠 쓰게 하는 메모이저(ADR-0054).
 *
 * <p>{@code DataRequirement.dependsOn}으로는 안 된다 — 그 채널이 나르는 것은 부모가 자기 코드로
 * 낸 <b>스칼라 시계열 하나</b>다. 일목 지표 하나를 의존으로 받으면 그 값만 오고 거기서 나머지
 * 수식자를 복원할 방법이 없으며, {@code code()}도 단수다.
 *
 * <p><b>크기 1 캐시로 충분하다.</b> 러너가 한 종목의 계산기들을 연속으로 부르고
 * ({@code IndicatorRegistry.executionOrder()}), 잡은 종목을 순차로 돈다
 * ({@code DailyUpdateJob}·{@code IndicatorBackfillJob}의 for 루프). 여기서 새면 값이 맞아도
 * 백필이 실용 시간 안에 끝나지 않는다 — 30번 재계산은 시간 예산이 허락하지 않는다.
 *
 * <p>MUST NOT: 저장소를 조회하지 않는다. 계산기 계약({@code IndicatorContext}에 담긴 것만 쓴다)은
 * 그대로이고, 깨지는 것은 <b>"계산기끼리 중간 구조체를 공유하지 않는다"는 암묵 규약</b>뿐이다.
 * 그것이 ADR-0054를 남긴 자리다.
 */
@Component
public class IchimokuAnalyses {

    /** 어떤 창이었는지. 같은 종목이라도 창이 다르면 결과가 다르므로 셋을 다 본다. */
    private record Key(long stockId, LocalDate first, LocalDate last, int bars) {
    }

    private Key cachedKey;
    private NavigableMap<LocalDate, IchimokuAnalysis> cachedValue;

    /**
     * 이 종목·이 창의 산출 가능한 모든 거래일의 분석.
     *
     * <p>{@code synchronized}인 이유는 정확성이 아니라 <b>캐시가 찢어지지 않게</b> 하기
     * 위해서다. 배치 경로는 순차라 경합이 없지만, 관리자 화면이 잡을 띄우는 경로가 있어
     * (ADR-0029) 같은 JVM에서 두 잡이 겹칠 수 있다.
     */
    public synchronized NavigableMap<LocalDate, IchimokuAnalysis> of(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        if (bars.isEmpty()) {
            return Collections.emptyNavigableMap();
        }
        Key key = new Key(context.stockId(), bars.getFirst().tradeDate(),
                bars.getLast().tradeDate(), bars.size());
        if (key.equals(cachedKey)) {
            return cachedValue;
        }
        NavigableMap<LocalDate, IchimokuAnalysis> value =
                IchimokuAnalyzer.analyze(bars, context.marketIndex());
        cachedKey = key;
        cachedValue = value;
        return value;
    }
}
