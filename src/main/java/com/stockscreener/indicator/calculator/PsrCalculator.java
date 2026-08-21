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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 주가매출비율(PSR) = 주가 ÷ 주당매출(TTM).
 *
 * <p><b>출처는 Ken Fisher <i>Super Stocks</i>(1984)이고, 계량 근거는 O'Shaughnessy
 * <i>What Works on Wall Street</i>다</b> — 초판에서 단일 팩터 가치 지표 중 가장 성과가 좋았고
 * 최신판에서도 EBITDA/EV와 1위를 다툰다. <b>버핏·린치 원문에는 없다</b>(그 둘은 PER·PEG·PBR을
 * 쓴다). 프리셋 조건으로 옮길 때는 그 사실을 함께 적는다.
 *
 * <p>이 지표의 존재 이유는 <b>PER이 값을 내지 못하는 구간에서도 밸류에이션을 잰다</b>는 것이다.
 * 매출은 원리적으로 음수가 없어 적자 기업에도 값이 난다 — 활성 편입 350종목에서 {@code PER}이
 * 271이고 그 5년 이력({@code PER_MEDIAN_5Y})은 218인데, 그 결손의 대부분이 적자 구간이다.
 * 매출이 이익보다 <b>회계 재량이 적다</b>는 것도 원저가 든 근거다.
 *
 * <p>IMPORTANT — <b>{@code MARKET_CAP}에 의존하지 않고 주당으로 접는다.</b> 값의 정의는
 * 「시가총액 ÷ 매출」이 맞고 그렇게 구현할 수도 있지만, 그러면 이 지표만 <b>다른 주식수 경로</b>를
 * 타게 된다. {@code MarketCapCalculator}는 재무 폴백을 <b>일부러 거부</b>하고
 * ({@code context.sharesOn}만 쓴다) {@code PerCalculator}·{@code PbrCalculator}는
 * {@link FinancialMath#sharesOf}로 폴백까지 쓴다. PSR은 그 둘의 <b>세 번째 형제</b>이므로 같은
 * 경로여야 한다 — 특히 자기 이력과 견주는 파생 지표가 붙으면 형제끼리 기저가 갈리는 것이 곧
 * 거짓 비교가 된다.
 *
 * <p><b>그 선택의 대가를 적어 둔다</b>: {@code listed_shares}가 없는 봉에서 {@code MARKET_CAP}은
 * 값을 내지 않는데 이 지표는 재무 폴백으로 값을 낸다. 그 봉에서 <b>{@code PSR × 매출 ≠
 * MARKET_CAP}</b>이다.
 *
 * <p>계산 규칙은 {@code PER}과 같다 — <b>원주가</b>({@link PriceBar#close()})를 쓰고 매출은
 * <b>최근 4개 분기 합</b>이다. 매출은 유량이므로 4분기를 더한다. 저량으로 다루면 4배가 되는데
 * 값이 그럴듯해 드러나지 않는다.
 *
 * <p>매출이 0 이하면 값을 내지 않는다. 음수는 원리적으로 없고 0은 <b>매출 개념이 없는 업종</b>
 * (금융업 등)에서 온다 — 실측으로 TTM 매출이 서는 종목이 활성 350 중 316이다.
 */
@Component
@IndicatorMeta(
        name = "PSR",
        description = "주가를 주당매출로 나눈 값. 이익이 아니라 매출에 견줘 주가가 비싼지 본다.",
        interpretation = "낮을수록 매출 대비 주가가 싸다. 적자 기업에도 값이 나오는 것이 PER과 "
                + "다른 점이라 이익이 들쭉날쭉한 회사에 쓸 만하다. 다만 매출은 이익으로 이어지지 "
                + "않을 수 있어 수익성 지표와 함께 읽어야 하고, 업종마다 정상 수준이 크게 달라 "
                + "같은 업종 안에서 비교하는 것이 좋다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.RATIO,
        unit = "배",
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 25
)
public class PsrCalculator implements IndicatorCalculator {

    public static final String CODE = "PSR";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // PerCalculator와 같은 근거다 — 재무 1년은 분기로 정확히 4개이고 TTM이 요구하는 수와
        // 같다. 소스 누락에 대비한 여유는 IndicatorDataReader가 한 해치를 더 얹어 확보한다.
        return DataRequirement.priceAndFinancial(1, 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<FinancialFigures> quarters = context.quarterlyFigures();
        BigDecimal revenue =
                FinancialMath.sumTrailingFourQuarters(quarters, FinancialFigures::revenue);
        if (revenue == null || revenue.signum() <= 0) {
            return List.of();
        }

        // IMPORTANT — 주식수를 봉마다 읽는다. 루프 밖에서 하나로 고정하면 유상증자·분할 구간이
        // 통째로 틀린다. 나눗셈은 PER·PBR과 공유한다(FinancialMath.perShareOn).
        List<IndicatorPoint> points = new ArrayList<>(context.priceBars().size());
        for (PriceBar bar : context.priceBars()) {
            BigDecimal salesPerShare = FinancialMath.perShareOn(context, bar, revenue, quarters);
            if (salesPerShare == null || salesPerShare.signum() <= 0) {
                continue;
            }
            points.add(new IndicatorPoint(bar.tradeDate(),
                    bar.close().divide(salesPerShare, FinancialMath.SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
