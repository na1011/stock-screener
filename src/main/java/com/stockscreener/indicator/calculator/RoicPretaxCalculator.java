package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 세전 투하자본이익률 = {@code 영업이익(TTM) ÷ (자산총계 − 유동부채) × 100}.
 *
 * <p>버핏 문서가 1.3(프랜차이즈)·1.5(인플레이션 전가력)·1.6(자본 소요)·3.6(유형자본 대비
 * 수익률) <b>네 자리에서</b> ROIC를 프록시로 든다. 자기자본이익률과 달리 <b>레버리지로 부풀릴
 * 수 없다</b> — 분모가 자기자본이 아니라 영업에 묶인 자본이기 때문이고, 그것이 문서 0.3이
 * "부채 없이 좋은 ROE"를 한 문장으로 묶어 둔 이유와 같은 방향이다.
 *
 * <h2>MUST NOT: 이 값을 {@code ROIC}라 부르지 않는다</h2>
 *
 * <p>표준 ROIC는 <b>세후</b>(NOPAT) 기준인데 우리는 법인세비용 컬럼이 없다. 유효세율을
 * 순이익에서 역산하는 것은 <b>소스가 주지 않는 값을 합성하는 것</b>이라 하지 않는다(ADR-0061).
 * 이름이 세전임을 말하게 두고, 법인세비용을 적재하면 그때 세후로 승격한다 —
 * {@code dart-financial.md}의 「계정별 가용성」 절이 이미 <b>8/8</b>로 확인해 뒀다.
 *
 * <p>세전이므로 <b>원문의 임계값 12를 그대로 쓸 수 없다.</b> 세율만큼 값이 높게 나오고, 임계는
 * 국내 분포로 다시 정한다(ADR-0061).
 *
 * <h2>금융업에서는 값을 내지 않는다 — 결손이 아니라 정의역이다</h2>
 *
 * <p>K-IFRS 금융업은 <b>유동/비유동을 가르지 않아</b> 유동부채가 아예 오지 않는다. 실측으로
 * 최근 분기 유동부채가 비어 있는 33종목이 전부 KSIC 64·65·66이거나 업종 미상이다. 분모를
 * "자산총계"로 대체하면 그것은 총자산이익률이지 투하자본이익률이 아니므로 하지 않는다 —
 * 뜻이 다른 값을 같은 이름으로 부르지 않는다(ADR-0039).
 */
@Component
@IndicatorMeta(
        name = "투하자본이익률(세전)",
        description = "최근 4개 분기 영업이익을 영업에 묶인 자본(자산총계 − 유동부채)으로 나눈 비율. "
                + "법인세를 빼기 전 기준이다 — 법인세비용이 적재돼 있지 않아 세후로 계산할 수 없다. "
                + "유동부채를 구분하지 않는 금융업에서는 값이 나오지 않는다.",
        interpretation = "높을수록 사업에 묶어 둔 자본 1원이 벌어들이는 영업이익이 크다. "
                + "자기자본이익률과 달리 빚을 늘려 부풀릴 수 없어, 둘을 함께 보면 "
                + "수익성이 사업에서 온 것인지 레버리지에서 온 것인지 갈린다. "
                + "세전 기준이라 세후 기준으로 쓰인 외부 문헌의 임계값과 직접 견주면 안 된다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 54,
        bands = {
                @IndicatorMeta.Band(label = "매우 높음", from = 20),
                @IndicatorMeta.Band(label = "높음", from = 12, to = 20),
                @IndicatorMeta.Band(label = "보통", from = 6, to = 12),
                @IndicatorMeta.Band(label = "낮음", from = 0, to = 6),
                @IndicatorMeta.Band(label = "영업적자", to = 0)
        }
)
public class RoicPretaxCalculator implements IndicatorCalculator {

    public static final String CODE = "ROIC_PRETAX";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceAndFinancial(1, 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<FinancialFigures> quarters = context.quarterlyFigures();
        BigDecimal operatingIncome =
                FinancialMath.sumTrailingFourQuarters(quarters, FinancialFigures::operatingIncome);
        if (operatingIncome == null) {
            return List.of();
        }

        // 잔액이므로 최근 분기 하나를 쓴다. 합산하면 네 배가 되는데 값이 그럴듯해 드러나지 않는다.
        FinancialFigures latest = quarters.get(0);
        BigDecimal assets = latest.totalAssets();
        BigDecimal currentLiabilities = latest.currentLiabilities();
        if (assets == null || currentLiabilities == null) {
            // 금융업은 유동부채 자체가 오지 않는다. 위 javadoc 참조.
            return List.of();
        }

        BigDecimal investedCapital = assets.subtract(currentLiabilities);
        if (investedCapital.signum() <= 0) {
            return List.of();
        }
        return FinancialMath.spreadOverTradeDates(
                context.priceBars(), FinancialMath.percentage(operatingIncome, investedCapital));
    }
}
