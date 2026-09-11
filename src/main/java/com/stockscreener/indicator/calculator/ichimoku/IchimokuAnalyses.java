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
 * <p><b>스레드당 크기 1 캐시로 충분하다.</b> 러너가 한 종목의 계산기들을 <b>같은 스레드에서
 * 연속으로</b> 부른다({@code IndicatorRegistry.executionOrder()}). 여기서 새면 값이 맞아도
 * 백필이 실용 시간 안에 끝나지 않는다 — 31번 재계산은 시간 예산이 허락하지 않는다.
 *
 * <p><b>캐시가 싱글턴 필드가 아니라 스레드별인 이유는 {@code IndicatorBackfillJob}이 종목
 * 루프를 팬아웃하기 때문이다</b>(ADR-0138). 필드 하나로 두면 워커들이 서로 다른 종목을 돌며
 * 키를 매번 어긋내 <b>전부 캐시 미스</b>가 되고, 계산이 {@code synchronized} 블록 안이라 그
 * 재계산이 전 워커에 걸쳐 직렬화된다. <b>실측으로 확장이 4워커 2.56배에서 멈추고 8워커에서
 * 1.85배로 되돌아갔다</b>(2026-09-11 · 160종목 × 1거래일).
 *
 * <p><b>MUST: 키 대조를 빼지 않는다.</b> 스레드별이 됐다고 "한 스레드는 한 종목만 본다"에
 * 기대면 ADR-0054 §버린 대안이 기각한 그 물건이 된다 — 같은 스레드가 다음 종목·다음 날짜로
 * 넘어가고, 틀렸을 때 증상은 <b>값이 하루 낡은 것</b>뿐이다.
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

    /** 한 스레드가 들고 있는 마지막 분석. 키가 함께 살아야 창 판별이 성립한다. */
    private static final class Memo {
        private Key key;
        private NavigableMap<LocalDate, IchimokuAnalysis> value;
    }

    private final ThreadLocal<Memo> memo = ThreadLocal.withInitial(Memo::new);

    /**
     * 이 종목·이 창의 산출 가능한 모든 거래일의 분석.
     *
     * <p>{@code synchronized}가 없는 이유는 <b>공유하는 상태가 없기 때문</b>이다. 옛 판은
     * 싱글턴 필드를 락으로 지켰는데, 그 락이 지키려던 것(관리자 화면이 띄운 잡과 배치가 같은
     * JVM에서 겹치는 것 · ADR-0029)을 <b>스레드별 캐시가 더 강하게 만족시킨다</b> — 겹쳐도
     * 서로의 캐시를 밀어내지 않는다.
     */
    public NavigableMap<LocalDate, IchimokuAnalysis> of(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        if (bars.isEmpty()) {
            return Collections.emptyNavigableMap();
        }
        Key key = new Key(context.stockId(), bars.getFirst().tradeDate(),
                bars.getLast().tradeDate(), bars.size());
        Memo cached = memo.get();
        if (key.equals(cached.key)) {
            return cached.value;
        }
        NavigableMap<LocalDate, IchimokuAnalysis> value =
                IchimokuAnalyzer.analyze(bars, context.marketIndex());
        cached.key = key;
        cached.value = value;
        return value;
    }
}
