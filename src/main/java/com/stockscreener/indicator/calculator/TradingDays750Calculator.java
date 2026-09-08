package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;
import com.stockscreener.indicator.PriceBar;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 저장된 일봉의 개수 — 원본 U-05(상장 경과 ≥ 3년)를 판정하는 값. 750에서 멈춘다.
 *
 * <p>원본 명세가 {@code ipoDate}가 아니라 <b>실제 가격 시계열의 길이</b>로 판정하라고 명시한다
 * (재상장·스핀오프에서 상장일이 부정확하기 때문이다). 우리에게는 그것이 더 강한 이유로 참이다 —
 * {@code stock.listed_on}이 전 종목 비어 있어 상장일 자체가 없다.
 *
 * <p>MUST NOT: 이 값을 "상장 경과"라고 부르지 않는다. 이것이 세는 것은 <b>우리가 수집해 저장한
 * 봉의 수</b>이고, 셋이 똑같이 이 값을 줄인다:
 * <ul>
 *   <li>실제로 상장한 지 얼마 안 된 종목 — 원본이 걸러내려는 것.
 *   <li><b>유니버스에 새로 편입되어 아직 과거 시세를 다 받지 못한 종목.</b> 2011년 상장사가
 *       봉 2개로 잡혀 있는 일이 실제로 있다. {@code initial-load}는 대상 선정이
 *       {@code NOT EXISTS(price_daily)}라 봉이 하나라도 있으면 건너뛴다.
 *   <li><b>거래정지 구간.</b> 소스가 OHLC 0으로 채운 행을 주고 {@code PriceWriter}가 정합성
 *       위반으로 버리므로 그 기간은 봉 자체가 없다.
 * </ul>
 * 이 값만으로 셋을 가를 수 없다. 그래서 이름과 설명이 "수집 기준"이라고 말하며, 그것을
 * "상장 3년 미만"으로 읽으면 <b>화면이 틀린 이유를 확신 있게 말하게 된다.</b>
 *
 * <p>IMPORTANT — <b>750에서 포화한다.</b> 러너는 종목당 {@code readWindowBars()}봉만 읽지만
 * 산출 일자 {@code i}의 값이 {@code min(i + 1, 750)}이라 <b>창이 넓어져도 불변</b>이다 —
 * P12가 창을 1,100으로 올린 뒤에도 1,620봉짜리 종목은 여전히 750이다. 게이트가 {@code >= 750}이라
 * 판정에는 정확히 충분하며, 포화를 정의에 넣지 않으면 화면의 값이 실제 이력으로 읽힌다.
 *
 * <p><b>창 확대가 U-05 게이트를 흔든다고 오해하지 않는다.</b> 포화 때문에 <b>값이 안 움직인다</b> —
 * 넓어지는 것은 값을 내는 <b>일자 수</b>뿐이고, 그것은 부피 변화이지 정확성 변화가 아니다.
 *
 * <p>{@code priceBars(750)}을 스스로 선언하는 이유는 <b>다른 지표에 얹히지 않기 위해서다.</b>
 * <b>창의 최댓값을 내는 지표는 시간이 지나며 바뀐다</b> — {@link SharesGrowth3yCalculator}였다가
 * {@code ICHI_*}였다가 지금은 또 다른 것이다. 어느 것도 "상장 경과"와 무관하므로, 선언하지
 * 않으면 그것들을 걷는 순간 이 지표가 <b>아무 증상 없이</b> 750을 판정할 수 없게 된다. 선언해도 전역 최대가
 * 더 커서 읽기 창은 그대로다.
 *
 * <p><b>창 안의 산출 일자마다 값을 낸다</b>(2026-09-08, ADR-0129). 그전에는 마지막 봉 하나만
 * 냈고 근거가 <i>"{@code i}번째 봉에 {@code i+1}을 세면 창 밖의 이력을 무시해 틀린 값이 된다"</i>
 * 였다. <b>그 실패가 값에 닿지 않는다</b> — 보존 창의 가장 이른 산출 일자는 창 인덱스
 * {@code readWindowBars() − RETAINED_TRADING_DAYS = maxEffectiveLookback()}에 앉고 그 값이
 * 1,261이라 {@code i + 1 >= 1262}인데, 이 지표는 <b>750에서 포화</b>한다. 창보다 이력이 짧은
 * 종목은 창이 그 종목의 전 이력이라 {@code i + 1}이 애초에 정확하다.
 *
 * <p><b>MUST: 그 논증은 {@code maxEffectiveLookback() >= THRESHOLD}에 얹혀 있다.</b> 이 계산기
 * 자신이 {@code priceBars(750)}을 선언하므로 등록돼 있는 한 산식으로 참이고
 * ({@code maxEffectiveLookback()}은 전 코드 최댓값이다), {@code IndicatorReadWindowTest}가 그
 * 최댓값을 등식으로 못박는다. <b>그 수가 750 아래로 내려가면 과거 일자 값이 조용히 틀린다.</b>
 *
 * <p>등가성은 선언이 아니라 값으로 쟀다 — 마지막 봉만 내던 시절에 쌓인 활성 유니버스
 * <b>172,614행</b>이 {@code min(rank, 750)}과 <b>차 0</b>이고, 그중 <b>9,545칸이 비포화</b>라
 * 그 갈래가 실제로 태워진 대조다(2026-09-08 실측).
 *
 * <p>분류가 {@link IndicatorCategory#PRICE}인 것은 자리가 넷뿐이라서다. 시세에서 나온 값이라
 * {@code TURNOVER_60}과 같은 칸에 두었고, 조건 빌더에서 "가격" 묶음에 뜬다.
 */
@Component
@IndicatorMeta(
        name = "거래일 수(수집 기준)",
        description = "이 종목에 대해 저장해 둔 일봉의 개수. 750에서 멈춘다. "
                + "상장 경과일이 아니라 우리가 수집한 범위이며, 거래정지 구간은 봉이 없어 빠진다.",
        interpretation = "750이면 원본이 요구하는 3년(750거래일) 이력을 채웠다는 뜻이다. "
                + "그보다 작으면 실제로 상장이 짧거나, 새로 편입되어 과거 시세를 아직 다 받지 "
                + "못했거나, 거래정지 구간이 빠진 것이다 — 이 값만으로는 셋을 가를 수 없다. "
                + "수십 일 이하로 작으면 상장이 짧아서가 아니라 수집이 덜 된 쪽을 먼저 의심한다.",
        category = IndicatorCategory.PRICE,
        valueType = IndicatorValueType.COUNT,
        unit = "거래일",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 5,
        // 거의 모든 종목이 같은 값이다 — 실측으로 345종목 중 **91%가 750**이다.
        // min(size, 750)이라 포화하기 때문이며, 카드가 종목을 구분하지 못한다.
        // 스윙 프리셋의 필수 조건이므로 조건·결과 표에는 그대로 남는다.
        detailDefault = false,
        bands = {
                @IndicatorMeta.Band(label = "3년 이상", from = 750),
                @IndicatorMeta.Band(label = "2~3년", from = 500, to = 750),
                @IndicatorMeta.Band(label = "1~2년", from = 250, to = 500),
                @IndicatorMeta.Band(label = "1년 미만", to = 250)
        }
)
public class TradingDays750Calculator implements IndicatorCalculator {

    public static final String CODE = "TRADING_DAYS_750";

    /** 원본 U-05의 임계값(약 3년)이자 이 값의 포화점. 둘이 같아야 게이트가 성립한다. */
    static final int THRESHOLD = 750;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(THRESHOLD);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        if (bars.isEmpty()) {
            return List.of();
        }
        // 봉이 하나라도 있으면 언제나 값이 나온다. 다른 지표와 달리 워밍업이 없다 —
        // 개수를 세는 데는 이력이 필요하지 않고, 이력이 짧다는 사실 자체가 이 지표의 답이다.
        List<IndicatorPoint> points = new ArrayList<>(bars.size());
        for (int i = 0; i < bars.size(); i++) {
            points.add(new IndicatorPoint(bars.get(i).tradeDate(),
                    BigDecimal.valueOf(Math.min(i + 1, THRESHOLD))));
        }
        return points;
    }
}
