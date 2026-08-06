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

import java.util.ArrayList;
import java.util.List;

/**
 * 수정종가를 지표로 노출한다.
 *
 * <p>존재 이유는 하나다 — <b>"주가가 200일선 위인가" 같은 조건의 좌변이 되기 위해서다.</b>
 * 조건의 좌변은 언제나 {@code indicator_value}에 있는 지표 코드이므로, 종가가 지표로
 * 없으면 이동평균 돌파류 조건을 표현할 방법이 아예 없고, 그때마다 하드코딩 분기로
 * 우회하게 된다({@code backend.md}가 금지한 것이다).
 *
 * <p>IMPORTANT — 원주가({@link PriceBar#close()})가 아니라 {@link PriceBar#adjClose()}다.
 * SMA_20·60·120·200이 예외 없이 수정종가로 계산되므로, 원주가를 좌변에 두면 액면분할
 * 종목에서 두 값의 축척이 어긋난다. 그 결과는 오류가 아니라 <b>그럴듯하게 틀린 값</b>이며,
 * 분할 이후 200거래일간 조용히 잘못된 종목을 고른다.
 *
 * <p>PER·PBR이 원주가를 쓰는 것은 그대로 둔다 — 그쪽은 그 시점의 발행주식수와 짝이 맞아야 한다.
 * 화면에 보여주는 실제 거래가도 원주가다. 이 지표는 <b>지표끼리 비교하기 위한 축척</b>이다.
 *
 * <p>백분위를 만들지 않는다. 가격 단위 지표의 종목 간 순위는 "주가가 비싼 종목이 항상 위"라는
 * 뜻밖에 되지 않는다({@code PercentileConfig} 참조).
 */
@Component
@IndicatorMeta(
        name = "수정종가",
        description = "액면분할·병합을 반영해 과거와 연속적으로 비교할 수 있게 조정한 종가. "
                + "화면에 표시되는 실제 거래가와는 다를 수 있다.",
        interpretation = "이동평균과 같은 축척이므로 '주가가 200일선 위인가' 같은 조건에 이 값을 쓴다. "
                + "값 자체의 높낮이는 종목마다 달라 유불리를 뜻하지 않는다.",
        category = IndicatorCategory.PRICE,
        valueType = IndicatorValueType.PRICE,
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 2,
        sortOrder = 1
)
public class AdjCloseCalculator implements IndicatorCalculator {

    public static final String CODE = "ADJ_CLOSE";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // 봉 하나면 그 날의 값이 나온다. 워밍업 구간이 없는 유일한 지표다.
        return DataRequirement.priceBars(1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        List<IndicatorPoint> points = new ArrayList<>(bars.size());
        for (PriceBar bar : bars) {
            // 소스가 수정종가를 주지 않은 봉은 건너뛴다. 원주가로 대체하면 그 하루만
            // 다른 축척의 값이 섞여 들어가고, 돌파 조건이 그날 거짓 신호를 낸다.
            if (bar.adjClose() != null) {
                points.add(new IndicatorPoint(bar.tradeDate(), bar.adjClose()));
            }
        }
        return points;
    }
}
