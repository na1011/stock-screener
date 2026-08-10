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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 3년 상장주식수 증가율 — {@code (지금 주식수 ÷ 3년 전 주식수 − 1) × 100}.
 *
 * <p>반복 증자로 기존 주주 지분을 희석하는 기업을 걸러낸다. 희석은 주가 상방을 직접 갉아먹지만
 * 손익계산서 어디에도 나타나지 않아, 이익 지표만 보면 끝까지 드러나지 않는다.
 *
 * <p>주식수를 재무제표가 아니라 <b>시세</b>에서 읽는다(ADR-0013). 날짜별 값이라 유상증자·분할
 * 시점부터 곧바로 반영되고, 분기 재무에 묶으면 다음 공시까지 낡은 수를 보게 된다. 국내 소스는
 * 우선주를 별도 종목으로 세므로 이 값이 보통주 수다.
 *
 * <p>액면분할은 주식수를 늘리지만 희석이 아니다. 그러나 {@code listed_shares}는 분할을 그대로
 * 반영하므로 분할 종목이 이 지표에서 큰 값을 갖는다. <b>지금은 그것을 보정하지 않는다</b> —
 * 보정하려면 분할 이력이 필요한데 국내 소스에 그 경로가 없다({@code adj_factor}가 전량 1이다).
 * 그 상태에서 지어낸 보정은 조용히 틀린 값을 만든다. 분할 종목이 이 조건에 걸리는 것은
 * 거짓 양성이며, 결과에서 빠진 종목을 확인할 때 함께 본다.
 *
 * <p>IMPORTANT — 이 지표가 <b>러너의 읽기 창을 751봉으로 끌어올린다.</b> 러너는 종목당 한 번
 * {@code maxEffectiveLookback}만큼 읽으므로 모든 지표가 그 창을 함께 쓴다. 그 부작용이 이
 * 지표의 존재 이유보다 클 수 있어 한동안 제거 후보였는데, <b>2026-08-10 실측으로 반대가
 * 확인됐다.</b>
 *
 * <p>그전까지 재귀 지표 넷({@code ATR_14}·{@code RSI_14}·{@code MACD}·{@code MACD_V})은
 * <b>같은 구간을 두 번 백필하면 매번 242,589행이 갱신됐다</b> — 평활 시드가 창의 앞쪽에서
 * 잘려 실행마다 다른 값이 나온 것이다. 필수 게이트가 그 값에 걸려 있으면 <b>결과가 백필
 * 횟수에 달리게 된다.</b> 창이 200 → 751로 길어진 뒤 같은 측정을 다시 하니
 * <b>1회차 282,189행 · 2회차 0행</b>으로 완전히 멱등해졌다(`batch_execution` 31·32).
 *
 * <p>MUST NOT: 이 지표를 빼거나 {@code LOOKBACK}을 줄이면서 그 사실을 함께 보지 않는다.
 * 창이 줄면 <b>이 지표와 무관한 재귀 지표 넷의 비결정성이 되돌아온다</b> — 컴파일도 되고
 * 테스트도 통과하며, 드러나는 것은 스크리닝 결과가 실행마다 달라지기 시작할 때다.
 */
@Component
@IndicatorMeta(
        name = "3년 주식수 증가율",
        description = "상장주식수가 3년(750거래일) 전보다 몇 퍼센트 늘었는지. "
                + "자사주 매입으로 줄어든 기업은 음수가 된다.",
        interpretation = "값이 클수록 기존 주주의 지분이 희석됐다는 뜻이다. "
                + "주식보상에 따른 자연 희석은 3년 누적 5% 안팎이며, "
                + "그보다 크면 자금조달 목적의 증자를 의심할 수 있다. "
                + "다만 액면분할도 주식수를 늘리므로 원인을 함께 확인해야 한다.",
        category = IndicatorCategory.PRICE,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        sortOrder = 4,
        bands = {
                @IndicatorMeta.Band(label = "희석 큼", from = 20),
                @IndicatorMeta.Band(label = "희석 진행", from = 5, to = 20),
                @IndicatorMeta.Band(label = "안정", from = 0, to = 5),
                @IndicatorMeta.Band(label = "감소(자사주 소각 등)", to = 0)
        }
)
public class SharesGrowth3yCalculator implements IndicatorCalculator {

    public static final String CODE = "SHARES_GROWTH_3Y";

    /** 약 3년. 거래일 기준이며 이 값이 러너의 읽기 창을 정한다. */
    static final int LOOKBACK = 750;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(LOOKBACK + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        if (bars.size() <= LOOKBACK) {
            return List.of();
        }

        List<IndicatorPoint> points = new ArrayList<>(bars.size() - LOOKBACK);
        for (int i = LOOKBACK; i < bars.size(); i++) {
            BigDecimal past = bars.get(i - LOOKBACK).listedShares();
            BigDecimal now = bars.get(i).listedShares();
            // 어느 한쪽이라도 없으면 증가율을 지어내지 않는다. 상장 3년 미만 종목이 여기서 빠진다.
            if (past == null || now == null || past.signum() <= 0) {
                continue;
            }
            points.add(new IndicatorPoint(bars.get(i).tradeDate(),
                    now.divide(past, MovingAverages.MC)
                            .subtract(BigDecimal.ONE)
                            .multiply(HUNDRED)
                            .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
