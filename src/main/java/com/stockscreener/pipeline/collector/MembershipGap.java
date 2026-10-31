package com.stockscreener.pipeline.collector;

import java.util.Set;

/**
 * 편입 종목 하나에서 <b>비어 있는 축</b>.
 *
 * <p>편입은 「스크리닝 대상」이라는 뜻인데, 아래 축 중 하나만 비어도 그 종목의 지표가
 * 판정불가가 되어 <b>프리셋에서 조용히 빠지고 배치는 전부 SUCCESS다.</b> 그 침묵을 재는 것이
 * {@link StockRegistrar#membershipGaps()}이고 이것이 그 한 줄이다.
 *
 * @param missing 비어 있는 축. <b>면제된 축은 여기 없다.</b>
 * @param exempt  비어 있지만 <b>돌려도 안 닫히는</b> 축({@link KnownSourceGaps}).
 *                <b>MUST: 버리지 않고 함께 들고 다닌다</b> — 알림이 이름으로 적고 로그가 센다.
 *                사실을 지우는 것이 아니라 OK 문장으로 옮기는 것이다(ADR-0044 §펜스의 형태).
 */
public record MembershipGap(long stockId, String marketCode, String ticker, String name,
                            Set<Axis> missing, Set<Axis> exempt) {

    /**
     * 무엇이 비었는가.
     *
     * <p><b>앞의 다섯은 입력 축이고 뒤의 둘은 그 입력이 실제로 지표가 됐는지를 묻는다.</b>
     * 입력만 재면 「채웠으니 닫혔다」로 보고하게 되는데, 실측으로 최신일 지표 코드 수가 시장
     * 중앙값의 60% 미만인 편입 종목이 42인데 입력 넷이 잡는 것은 1이었다(2026-09-13).
     */
    public enum Axis {

        /**
         * 시세 이력이 <b>편입 이전으로 뻗어 있지 않다.</b>
         *
         * <p><b>MUST NOT: 이것을 {@code NOT EXISTS (price_daily)}로 바꾸지 않는다</b> —
         * 일일 갱신이 15일 창으로 봉을 몇 개 남기는 순간 그 술어가 영원히 거짓이 되고
         * 신호가 지워진다. 근거는 {@code StockRegistrar.activeUniverseMemberIdsNeedingHistory()}
         * javadoc의 §「이 술어는 일일 갱신이 지운다. 감시에 쓰지 마라」다.
         *
         * <p><b>오탐 하나를 알고 쓴다</b>: 상장하자마자 편입된 종목은 첫 봉이 영원히 편입일
         * 뒤라 매 실행 다시 걸린다. 끄려면 상장일이 필요한데 <b>소스가 없다</b>
         * ({@code stock.listed_on} 양 시장 전량 NULL · DART {@code est_dt}는 설립일).
         * 2026-09-13 실측으로 해당 0건이다.
         *
         * <p><b>재편입 종목은 이 축에 안 걸린다</b> — 옛 봉이 있어 첫 봉이 편입일보다 앞이다. 편출 동안 빈
         * 봉은 일일 갱신이 구멍 구간으로 받는다({@link StockRegistrar#priceGapRuns}).
         */
        PRICE_HISTORY,

        /**
         * 초기 적재의 <b>이력 받기가 실패한 채 남아 있다</b>(미해결 {@code CollectionPhase.PRICE_HISTORY} 실패).
         *
         * <p>{@link #PRICE_HISTORY}와 축을 가르는 이유: 첫 봉은 편입일 앞(평일 15일 창이 남긴 봉)이라 그 축에 안 걸리는데 이력은 짧을 수 있다 —
         * 「돌렸는데 소스가 안 줬다」는 기록이 상장일 없이 그것을 가른다(ADR-0141 「남는 구멍」). 문구가 다르고(「편입 이전으로 안 뻗었다」가
         * 거짓이다), 리밸런싱 꼬리의 자동 적재 상한 합집합 밖에서 센다 — 끝내 안 닫히는 실패가 상한을 밀어 신규 편입의 적재까지 막지 않게.
         */
        PRICE_HISTORY_RETRY,

        /** 업종 두 컬럼이 다 비었다. 면제는 {@link KnownSourceGaps#KNOWN_MISSING_SIC}. */
        INDUSTRY,

        /** 재무가 한 행도 없다. */
        FINANCIAL,

        /** 재무는 있는데 영업활동현금흐름이 한 행도 없다. */
        CASH_FLOW,

        /**
         * 거버넌스 <b>원문</b>이 한 행도 없다.
         *
         * <p><b>MUST NOT: {@code governance_fact}로 재지 않는다.</b> 무배당은 0으로 <b>담기고</b>
         * (ADR-0113), 완결 게이트에 걸린 회계연도는 정당하게 사실을 안 낸다 — 팩트로 재면 그 둘이
         * 결손으로 섞여 닫을 수 없는 것이 상시 붉는다. 원문이 0행인 것만이 <b>한 번도 안 받았다</b>는 뜻이다.
         *
         * <p><b>이 축은 신규 편입 전원을 잡는다.</b> {@code governance-load}가 현재 편입만 돌므로
         * 처음 편입되는 종목은 예외 없이 원문이 0행이다. 그래서 {@code AUTO_LOAD_STOCK_LIMIT}의
         * 합집합 기저가 「원자료가 빈 신규」에서 <b>「신규 전원」</b>으로 바뀐다 —
         * {@code MembershipGapFiller}가 그 수를 다시 잰 근거를 들고 있다.
         *
         * <p><b>그 시장에 거버넌스 원문이 하나도 없으면 이 축은 아무것도 신고하지 않는다.</b>
         * 소스를 아직 안 붙인 시장에서 편입 전원이 한꺼번에 붉으면 그것은 상시 붉는 펜스이고
         * (ADR-0141 §「상시 붉을 것을 감시 대상에 넣지 않는다」), 상한을 밀어
         * <b>시세·재무 적재까지 통째로 멈춘다.</b> 미국이 2026-09-01 전에 정확히 그 상태였다.
         */
        GOVERNANCE,

        /**
         * 시세에 <b>구멍</b>이 있다 — 첫 봉 이후 보존 창의 거래일 중 봉이 있는 비율이 임계 미만.
         *
         * <p><b>MUST NOT: 「마지막 봉이 시장 최신 대비 며칠 뒤졌는가」로 재지 않는다.</b>
         * {@code 046070}(코다코)이 2024-03-22~2026-09-09이 통째로 빈 채 2026-09-10 봉 하나를
         * 받자 그 술어가 <b>참이 됐다</b> — {@code NOT EXISTS}와 똑같이 지워진다.
         *
         * <p><b>분모가 시장 최신일이 아니라 「그 종목의 첫 봉」인 것이 요점이다.</b> 그래야
         * 신규 상장이 상시 경보가 되지 않는다 — {@code FDXF}·{@code WSE}는 원시 커버리지가
         * 27.8%·33.3%인데 첫 봉을 앵커로 잡으면 100%다.
         */
        PRICE_COVERAGE,

        /**
         * <b>어제 나던 지표가 오늘 안 난다.</b> 최신 거래일의 지표 코드 수가 직전 거래일 대비
         * 임계 미만.
         *
         * <p><b>MUST NOT: 「시장 중앙값 대비 절대 수」로 재지 않는다</b> — 신규 상장 42종목이
         * 상시 경보가 된다(2026-09-13 실측). 델타는 그 종목 자신을 기준선으로 삼으므로
         * 이력이 짧은 것에 안 걸리고, <b>「어제 되던 것이 오늘 안 된다」가 곧 조용한 실패의
         * 정의</b>다.
         */
        INDICATOR_DROP
    }

    /** 면제를 뺀 실제 결손이 하나라도 있는가. 알림 발화를 이것으로 가른다. */
    public boolean hasActionable() {
        return !missing.isEmpty();
    }

    /**
     * {@code full-statement-refill}이 닿는 시장인가. 그 잡은 <b>DART 전용</b>이라
     * 미국에는 한 행도 못 쓴다(ADR-0056).
     *
     * <p><b>MUST: 알림의 「닫는 법」과 실제 호출이 이 한 곳을 본다.</b> 두 곳에 따로 적으면
     * 갈리는데, 그 증상이 양쪽 다 조용하다 — 닫는 명령이라 적어 놓고 그 종목을 안 부르거나,
     * 반대로 안 닿는 시장에 콜을 태우고 매주 같은 결손을 다시 신고한다.
     */
    public boolean refillableStatements() {
        return !"US".equals(marketCode);
    }
}
