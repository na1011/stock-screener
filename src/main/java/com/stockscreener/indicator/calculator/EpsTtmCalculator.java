package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.FinancialFigures;
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
 * 주당순이익(EPS) = 최근 4개 분기 순이익 합(TTM) ÷ 그 시점의 발행주식수.
 *
 * <p>{@link PerCalculator}가 분모로 쓰려고 이미 만들던 값이고 저장만 하지 않았다. 나눗셈은
 * {@link FinancialMath#trailingEpsOn}에 두어 둘이 함께 쓴다 — 갈라 쓰면 화면에서
 * <b>EPS × PER이 주가와 맞지 않는</b> 형태로만 어긋남이 드러난다.
 *
 * <p>IMPORTANT — <b>PER과 달리 적자에도 값을 낸다.</b> 음수 PER은 "PER이 낮은 종목"을 찾는
 * 조건에 적자 기업을 최상위로 올려 뜻을 잃지만, 음수 EPS는 그 자체가 사실이고 사용자가 봐야
 * 하는 값이다. 착수 시점 실측으로 TTM 순이익을 낼 수 있는 340종목 중 <b>68종목이 적자</b>이며,
 * PER의 {@code signum() <= 0} 가지를 여기로 복사하면 그 68종목이 조용히 사라진다 —
 * 값이 나오는 종목에서는 정상으로 보이므로 화면에서 드러나지 않는다.
 *
 * <p>소스가 주는 EPS를 쓰지 않는다(spec §7). {@code financial_statement.eps} 컬럼은 수집값과
 * 자체 계산값을 <b>대조</b>하는 자리이며, 지금 국내 어댑터가 채우지 않아 전량 비어 있다.
 */
@Component
@IndicatorMeta(
        name = "주당순이익(EPS)",
        description = "최근 4개 분기 순이익을 발행주식수로 나눈 값. 1주가 벌어들인 이익이다.",
        interpretation = "높을수록 주식 한 주가 버는 이익이 크다. 적자면 음수가 된다. "
                + "주가와 나란히 보면 PER이 왜 그 값인지 알 수 있다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.CURRENCY,
        unit = "원",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 0,
        // 결과 표 기본 컬럼으로 두지 않는다. 금액이라 종목마다 자릿수가 달라 표가 시끄러워지고,
        // 규모 비교는 시가총액이 이미 한다. 조건과 종목 상세에서는 그대로 쓴다.
        defaultColumn = false,
        // PER(10) 바로 위. EPS가 PER의 분모이므로 읽는 순서가 그렇다.
        sortOrder = 9
        // bands 를 붙이지 않는다 — 금액은 종목마다 뜻이 달라 "3,000원 이상 = 높음"이 거짓이 된다
        // (ADR-0023). IndicatorExtensibilityTest 가 CURRENCY 에 구간이 붙는 것을 거절한다.
)
public class EpsTtmCalculator implements IndicatorCalculator {

    public static final String CODE = "EPS_TTM";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // PER과 같은 선언이다. 재무 1년은 분기로 정확히 4개이고 TTM이 요구하는 수와 같으며,
        // 그 여유 0은 IndicatorDataReader가 한 해치를 더 얹어 확보한다.
        return DataRequirement.priceAndFinancial(1, 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<FinancialFigures> quarters = context.quarterlyFigures();
        BigDecimal netIncome =
                FinancialMath.sumTrailingFourQuarters(quarters, FinancialFigures::netIncome);
        if (netIncome == null) {
            // 분기가 모자라거나 한 분기라도 순이익이 비면 계산하지 않는다. 부호는 보지 않는다.
            return List.of();
        }

        // 주식수를 봉마다 읽는 이유는 PER과 같다 — 유상증자·분할이 있으면 그날부터 값이 달라진다.
        List<IndicatorPoint> points = new ArrayList<>(context.priceBars().size());
        for (PriceBar bar : context.priceBars()) {
            BigDecimal eps = FinancialMath.trailingEpsOn(context, bar, netIncome, quarters);
            if (eps == null) {
                continue;
            }
            points.add(new IndicatorPoint(bar.tradeDate(), eps));
        }
        return points;
    }
}
