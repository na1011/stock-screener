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
 * <h2>이름이 창을 밝힌다 — 같은 축에 다년 지표가 셋이다</h2>
 *
 * <p>{@code ROIC_MIN_3Y}·{@code ROIC_AFTERTAX_MIN_5Y}·{@code ROIC_AFTERTAX_AVG_5Y}가 옆에 있어
 * <b>창이 없는 이름은 어느 기간인지 말하지 않는다.</b> {@code EPS_GROWTH_TTM_YOY}(「TTM
 * 주당순이익 성장률」)가 같은 이유로 창을 이름에 넣은 선례다 — 그쪽도 {@code EPS_CAGR_5Y}가
 * 옆에 있다. 짝이 없는 {@code PER}·{@code ROE}에 안 붙이는 것과 갈리는 자리가 그것이다.
 *
 * <h2>MUST NOT: 이 값을 {@code ROIC}라 부르지 않는다 — 그 이름은 이제 임자가 있다</h2>
 *
 * <p>표준 ROIC는 <b>세후</b>(NOPAT) 기준이고, 그것은 {@link RoicAfterTaxCalculator}가 낸다
 * (2026-09-14에 법인세비용·세전이익을 적재하며 열렸다). <b>이 지표는 세전으로 남는다</b> —
 * 지우지 않는 이유는 세전·세후를 나란히 보면 그 차이가 유효세율 하나로 설명되기 때문이고,
 * 활성 프리셋이 안 쓴다는 이유로 지표를 지우지 않는다는 규칙도 걸린다
 * ({@code .claude/rules/data-pipeline.md}).
 *
 * <p>유효세율을 <b>순이익에서 역산</b>하는 것은 여전히 하지 않는다 — 소스가 주지 않는 값을
 * 합성하는 것이기 때문이고, 근거는 spec §7(<i>"지표는 원자료를 수집한 뒤 자체 계산"</i>)과
 * {@code data-pipeline.md}다. <b>세후는 역산이 아니라 실측한 두 컬럼으로 낸다.</b>
 *
 * <p>세전이므로 <b>원문의 임계값 12를 그대로 쓸 수 없다.</b> 세율만큼 값이 높게 나오고, 임계는
 * 국내 분포로 다시 정한다(ADR-0061 §규칙 — 절대 임계값은 국내 분포를 재고 정한다).
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
        name = "TTM 투하자본이익률(세전)",
        description = "최근 4개 분기 영업이익을 영업에 묶인 자본(자산총계 − 유동부채)으로 나눈 비율. "
                + "법인세를 빼기 전 기준이다 — 세금까지 뺀 값은 투하자본이익률(세후)이 따로 있다. "
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
        return FinancialMath.byPublication(context, RoicPretaxCalculator::valueOf);
    }

    /** 그 봉 시점에 공시돼 있던 재무로 낸 값. 없으면 {@code null}이다(ADR-0128). */
    private static BigDecimal valueOf(IndicatorContext context) {
        List<FinancialFigures> quarters = context.quarterlyFigures();
        BigDecimal operatingIncome =
                FinancialMath.sumTrailingFourQuarters(quarters, FinancialMath::operatingIncomeOf);
        if (operatingIncome == null) {
            return null;
        }

        // 잔액이므로 최근 분기 하나를 쓴다. 합산하면 네 배가 되는데 값이 그럴듯해 드러나지 않는다.
        FinancialFigures latest = quarters.get(0);
        BigDecimal assets = latest.totalAssets();
        BigDecimal currentLiabilities = latest.currentLiabilities();
        if (assets == null || currentLiabilities == null) {
            // 금융업은 유동부채 자체가 오지 않는다. 위 javadoc 참조.
            return null;
        }

        BigDecimal investedCapital = assets.subtract(currentLiabilities);
        if (investedCapital.signum() <= 0) {
            return null;
        }
        return FinancialMath.percentage(operatingIncome, investedCapital);
    }
}
