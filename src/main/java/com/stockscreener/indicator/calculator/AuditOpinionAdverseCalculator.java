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
 * 그 회계연도의 감사의견이 <b>비적정</b>이면 1, 적정이면 0. 모르는 표기면 값을 내지 않는다.
 *
 * <p>린치 §8이 관리종목·감사의견 거절이 저PBR 스크린 상위에 상습 등장한다고 적는다.
 * 비적정은 상장폐지 사유이기도 하다.
 *
 * <p>이 지표가 존재하는 근거는 실측이다. {@code docs/api-spec/dart-governance.md}의 「회계감사인의 명칭 및
 * 감사의견」 절가
 * <i>"활성 유니버스 안에서는 원리적으로 안 나온다"</i>고 적었으나 <b>350종목 × 6개년 적재가 그것을
 * 뒤집었다</b> — 044490 태웅의 FY2020이 {@code 감사범위 제한으로 인한 한정}이다(dart-governance.md의
 * 「회계감사인의 명칭 및 감사의견」 절).
 * 표본 여덟으로는 원리적으로 못 보던 것이고, {@code phase-criteria} §P18 축②가
 * <i>「분포를 센 뒤에 판정 지표를 만든다」</i>로 이 순서를 미리 적어 두었다.
 *
 * <p>MUST: 판정은 관측된 문자열의 <b>완전일치 허용목록</b>이고 목록 밖은 판정불가다.
 * {@code contains("적정")}으로 물으면 <b>{@code 부적정의견}이 「적정」으로 분류된다.</b>
 */
@Component
@IndicatorMeta(
        name = "감사의견 비적정",
        description = "그 회계연도의 감사의견이 비적정(한정·부적정·의견거절)이면 1, 적정이면 0. "
                + "관측된 표기의 허용목록으로 판정하고 목록 밖은 값을 내지 않는다.",
        interpretation = "1이면 그 해 감사의견이 적정이 아니었다는 뜻이고 상장폐지 사유에 닿는다. "
                + "값이 없는 것은 「적정」이 아니라 「소스 표기를 판정하지 못했다」는 뜻이다.",
        category = IndicatorCategory.GOVERNANCE,
        valueType = IndicatorValueType.COUNT,
        unit = "",
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        decimalScale = 0,
        sortOrder = 430,
        bands = {
                @IndicatorMeta.Band(label = "비적정", from = 1),
                @IndicatorMeta.Band(label = "적정", to = 1)
        })
public class AuditOpinionAdverseCalculator implements IndicatorCalculator {

    public static final String CODE = "AUDIT_OPINION_ADVERSE";

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
        return GovernanceMath.overBars(context, GovernanceMetric.AUDIT_OPINION_ADVERSE,
                (figures, bar) ->
                        GovernanceMath.valueOf(figures, GovernanceMetric.AUDIT_OPINION_ADVERSE));
    }
}
