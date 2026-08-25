package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.GovernanceMetric;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 최근 3개 회계연도에 <b>최대주주가 바뀐 횟수</b>.
 *
 * <p>IMPORTANT — 소스 표의 <b>행 수가 아니다.</b> 「최대주주 변동현황」은 최대주주의 <b>지분 변동
 * 내역</b>이라 KB금융의 28행이 전부 같은 {@code 국민연금공단}이고 원인이 전부 {@code 장내매매}였다
 * (dart-governance.md의 「350종목 전수 적재가 답한 것」 절). 행 수를 세면 「자주 바뀌는 회사」와 「연기금이 매
 * 분기 조금씩 사고파는 회사」가 같아진다.
 * 세는 것은 최대주주 <b>이름이 바뀐 전이</b>이며 창은 유도 단계가 자른다(원자료가 누적이라
 * 보고서 하나가 창 전체를 안다).
 *
 * <p>방향을 주지 않는다. 잦은 지배권 이전이 적신호라는 것은 흔한 통념이지만 <b>이 유니버스에서
 * 재본 적이 없고</b> 지주회사 전환이나 정상적인 인수도 같은 모양이 된다.
 */
@Component
@IndicatorMeta(
        name = "3년 최대주주 교체 횟수",
        description = "최근 3개 회계연도에 최대주주가 바뀐 횟수. 소스 표의 행 수가 아니라 "
                + "최대주주 이름이 바뀐 전이를 센다.",
        interpretation = "0이면 그 기간에 지배주주가 그대로였다는 뜻이다. 잦은 교체가 적신호인지는 "
                + "재본 적이 없어 방향을 주지 않는다 — 지주회사 전환도 같은 모양이 된다.",
        category = IndicatorCategory.GOVERNANCE,
        valueType = IndicatorValueType.COUNT,
        unit = "건",
        decimalScale = 0,
        sortOrder = 422,
        bands = {
                @IndicatorMeta.Band(label = "2회 이상", from = 2),
                @IndicatorMeta.Band(label = "1회", from = 1, to = 2),
                @IndicatorMeta.Band(label = "없음", to = 1)
        })
public class ControlTransfer3yCalculator implements IndicatorCalculator {

    public static final String CODE = "CONTROL_TRANSFER_3Y";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.governance(1, 0, 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return GovernanceMath.overBars(context, GovernanceMetric.CONTROL_TRANSFERS_3Y,
                (figures, bar) ->
                        GovernanceMath.valueOf(figures, GovernanceMetric.CONTROL_TRANSFERS_3Y));
    }
}
