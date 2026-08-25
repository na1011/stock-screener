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
 * 감사보고서에 <b>강조사항</b>이 있으면 1, 없으면 0.
 *
 * <p>강조사항은 적정 의견이어도 붙는다 — 계속기업 불확실성이나 재무제표 재작성이 그 자리다.
 * 실측에서 오리온 전기가 {@code 재무제표 재작성}, 다른 회사가 {@code 증권선물위원회의 조치에
 * 대한 행정소송}이었다.
 *
 * <p>MUST NOT: <b>핵심감사사항을 세지 않는다.</b> 감사기준의 필수 기재사항이라 표본 48행이
 * <b>전부</b> 값을 갖는다(dart-governance.md의 「350종목 전수 적재가 답한 것」 절) — 세면 이 지표가 전 종목
 * 1인 상수가 되고, 그것은 아무도 못
 * 자르는 게이트다. 강조사항은 41/48이 「없음」이라 실제로 가른다.
 *
 * <p>「없음」의 표기가 둘 이상이라({@code 해당사항 없음}·빈 문자열) 허용목록은 <b>「없음」 쪽</b>이고
 * 그 밖은 강조사항의 <b>내용 자체</b>다 — 「모르는 토큰」이 아니므로 판정불가로 두지 않는다.
 */
@Component
@IndicatorMeta(
        name = "감사 강조사항",
        description = "감사보고서에 강조사항이 있으면 1, 없으면 0. 핵심감사사항은 필수 기재라 "
                + "세지 않는다 — 세면 전 종목이 1이 된다.",
        interpretation = "1이면 감사인이 적정 의견을 내면서도 따로 짚은 사항이 있다는 뜻이다. "
                + "계속기업 불확실성·재무제표 재작성이 그 자리다.",
        category = IndicatorCategory.GOVERNANCE,
        valueType = IndicatorValueType.COUNT,
        unit = "",
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        decimalScale = 0,
        sortOrder = 431,
        bands = {
                @IndicatorMeta.Band(label = "있음", from = 1),
                @IndicatorMeta.Band(label = "없음", to = 1)
        })
public class AuditEmphasisCalculator implements IndicatorCalculator {

    public static final String CODE = "AUDIT_EMPHASIS";

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
        return GovernanceMath.overBars(context, GovernanceMetric.AUDIT_EMPHASIS_PRESENT,
                (figures, bar) ->
                        GovernanceMath.valueOf(figures, GovernanceMetric.AUDIT_EMPHASIS_PRESENT));
    }
}
