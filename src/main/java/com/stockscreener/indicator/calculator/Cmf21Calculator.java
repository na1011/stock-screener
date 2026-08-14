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
 * 차이킨 자금흐름(CMF, 21일) — 거래량이 <b>매수 쪽에 실렸는지 매도 쪽에 실렸는지</b>.
 *
 * <p>봉마다 종가가 고저 범위의 어디에 닫혔는지로 거래량에 부호를 준 뒤(자금흐름량),
 * 21일치를 거래량 합으로 나눈다. 결과가 <b>−1~+1로 정규화</b>되어 종목 간 비교가 되고
 * 0이 기준선이라 구간이 자연스럽다 — 이 저장소가 거래량 지표에 요구하는 성질이다
 * (거래대금처럼 원 단위인 값은 절대 크기가 규모를 따라간다).
 *
 * <p>{@code MFI_14}와 겹치지 않는다. MFI는 전형가격의 <b>방향</b>으로 자금을 가르고 0~100으로
 * 과열·침체를 보는 반면, 이쪽은 <b>봉 안에서의 종가 위치</b>로 가르고 매집·분산의 부호를 본다.
 */
@Component
@IndicatorMeta(
        name = "차이킨 자금흐름(21일)",
        description = "21일 동안 거래량이 매수 쪽에 실렸는지 매도 쪽에 실렸는지를 −1~+1로 나타낸 값.",
        interpretation = "0보다 크면 사자 쪽에 거래량이 실렸다는 뜻이고 작으면 반대다. "
                + "0.1을 넘으면 매집이, −0.1 아래면 분산이 뚜렷한 편이다.",
        category = IndicatorCategory.OSCILLATOR,
        valueType = IndicatorValueType.RATIO,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 3,
        sortOrder = 213,
        bands = {
                @IndicatorMeta.Band(label = "매집 뚜렷", from = 0.1),
                @IndicatorMeta.Band(label = "매집 우위", from = 0.05, to = 0.1),
                @IndicatorMeta.Band(label = "중립", from = -0.05, to = 0.05),
                @IndicatorMeta.Band(label = "분산 우위", from = -0.1, to = -0.05),
                @IndicatorMeta.Band(label = "분산 뚜렷", to = -0.1)
        })
public class Cmf21Calculator implements IndicatorCalculator {

    public static final String CODE = "CMF_21";
    private static final int PERIOD = 21;
    private static final int SCALE = 8;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(PERIOD);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        if (bars.size() < PERIOD) {
            return List.of();
        }

        List<BigDecimal> moneyFlow = new ArrayList<>(bars.size());
        List<BigDecimal> volumes = new ArrayList<>(bars.size());
        for (PriceBar bar : bars) {
            BigDecimal volume = PriceScaling.adjustedVolume(bar);
            volumes.add(volume);
            moneyFlow.add(volume.multiply(multiplier(bar)));
        }

        List<BigDecimal> flowSums = MovingAverages.rollingSum(moneyFlow, PERIOD);
        List<BigDecimal> volumeSums = MovingAverages.rollingSum(volumes, PERIOD);

        List<IndicatorPoint> points = new ArrayList<>(bars.size());
        for (int i = 0; i < bars.size(); i++) {
            BigDecimal flow = flowSums.get(i);
            BigDecimal volume = volumeSums.get(i);
            if (flow == null || volume == null || volume.signum() == 0) {
                // 창이 덜 찼거나 21일 내내 거래가 없었다(장기 거래정지). 나눌 수 없으므로
                // 행을 쓰지 않는다 — 0을 쓰면 "매수·매도가 팽팽했다"는 다른 사실이 된다.
                continue;
            }
            points.add(new IndicatorPoint(
                    bars.get(i).tradeDate(), flow.divide(volume, SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }

    /**
     * 자금흐름 승수 — 종가가 그 봉의 고저 범위 어디에 닫혔는가. {@code -1}(저가)~{@code +1}(고가).
     *
     * <p>IMPORTANT — <b>원주가 셋을 그대로 쓴다.</b> 저장된 수정값은 {@code adjClose}뿐이라
     * ({@code high}·{@code low}는 원주가다) {@code close}만 수정가로 바꾸면 분할 구간에서
     * 승수가 {@code [-1, 1]}을 벗어난다. 셋이 같은 축척이면 <b>비율이라 축척에 불변</b>이므로
     * 원값으로 일관되는 것이 옳다.
     *
     * <p>IMPORTANT — <b>고가와 저가가 같으면 0을 돌려준다.</b> 이것은 지어낸 값이 아니라
     * 사실이다 — 가격이 전혀 움직이지 않았으니 어느 쪽으로도 실리지 않았다. 같은 조건에서
     * {@code StochasticK14Calculator}가 행을 쓰지 않는 것과 <b>갈리는 지점이 여기다</b>:
     * 거기서는 0도 50도 발명이고 과열·침체 판정을 뒤집지만, 여기서는 그 봉의 기여가 0인 것이
     * 정의 그대로다. 21봉 합이라 한 봉을 버리면 <b>그 종목이 21거래일치를 잃는다.</b>
     */
    private static BigDecimal multiplier(PriceBar bar) {
        BigDecimal range = bar.high().subtract(bar.low());
        if (range.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal closeToLow = bar.close().subtract(bar.low());
        BigDecimal highToClose = bar.high().subtract(bar.close());
        return closeToLow.subtract(highToClose).divide(range, SCALE, RoundingMode.HALF_UP);
    }
}
