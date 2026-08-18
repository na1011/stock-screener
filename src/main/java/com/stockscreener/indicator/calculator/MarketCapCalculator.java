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
import java.util.ArrayList;
import java.util.List;

/**
 * 시가총액 — {@code 종가 × 상장주식수}.
 *
 * <p>IMPORTANT — <b>원주가</b>({@link PriceBar#close()})를 쓴다. 수정종가로 계산하면 분할 이전
 * 구간의 시가총액이 분할비율만큼 어긋난다. 같은 봉의 {@code listedShares}와 짝이 맞아야 하고,
 * 그 값은 그 시점의 실제 주식수이기 때문이다(ADR-0002의 PER·PBR과 같은 규칙).
 *
 * <p>유니버스가 이미 시가총액 상위 N으로 정의돼 있는데도(ADR-0012) 이것을 지표로 두는 이유는
 * 둘이다. 하나는 유니버스가 <b>순위</b>만 알고 절대 크기를 화면에 내놓지 못한다는 것이고,
 * 다른 하나는 시총 하한을 조건으로 걸 수 있어야 한다는 것이다 — 유니버스 안에서도 코스피
 * 대형주와 코스닥 중형주의 시총 차이가 한 자릿수 배를 넘는다.
 *
 * <p>MUST NOT: 백분위({@code _PCTL})를 만들지 않는다. 백분위는 유니버스 안에서 매겨지므로
 * (ADR-0036) "시총 상위 30%"가 코스닥150 안의 상위 30%도 포함하게 되어, 규모로 거르려는
 * 의도와 정반대가 된다. 규모는 절대값으로 건다. <b>실측이 그 수다</b> — 지금과 같은 수를
 * 남기는 분위(0.285)에서 하한이 <b>KOSPI 2조 7,666억 · KOSDAQ 7,863억</b>으로 갈리고,
 * 1조 미만 <b>30종목</b>이 새로 통과한다(2026-08-14). 같은 이유가
 * {@code TURNOVER_60}에는 더 세게 걸린다(ADR-0072).
 *
 * <p>MUST NOT: {@code bands}를 붙이지 않는다. 통화 금액이라 무차원이 아니고, 시장이 늘면
 * "1조 이상 = 대형"이 거짓이 된다(ADR-0023).
 */
@Component
@IndicatorMeta(
        name = "시가총액",
        description = "종가에 상장주식수를 곱한 값. 그 시점에 시장이 이 회사 전체에 매긴 가격이다.",
        interpretation = "규모가 클수록 거래가 두텁고 정보가 많아 개별 종목 리스크가 낮은 편이다. "
                + "다만 규모 자체는 수익성이나 저평가 여부를 말해 주지 않는다. "
                + "통화 단위라 다른 시장의 종목과 직접 비교하지 않는다.",
        category = IndicatorCategory.PRICE,
        valueType = IndicatorValueType.CURRENCY,
        unit = "원",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 2
)
public class MarketCapCalculator implements IndicatorCalculator {

    public static final String CODE = "MARKET_CAP";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        List<IndicatorPoint> points = new ArrayList<>(bars.size());

        for (PriceBar bar : bars) {
            // MUST: 원값이 아니라 종가와 짝이 맞는 주식수다. 무상증자 권리락~신주상장 구간은
            // 이미 희석된 종가를 희석 전 주식수와 짝지어 시가총액이 배수만큼 작게 나온다
            // (루닛 2026-05-04 1.21조 → 0.61조, ADR-0051).
            // MUST NOT: FinancialMath.sharesOf로 갈아타지 않는다 — 그쪽 재무 폴백은 이 지표가
            // 일부러 값을 내지 않는 봉에 값을 만든다.
            BigDecimal shares = context.sharesOn(bar.tradeDate(), bar.listedShares());
            // 소스가 주식수를 주지 않은 봉은 건너뛴다. 0으로 두면 시가총액 0인 회사가 되어
            // 어떤 하한 조건에도 걸리지 않고 조용히 결과에서 빠진다.
            if (shares == null || shares.signum() <= 0) {
                continue;
            }
            points.add(new IndicatorPoint(bar.tradeDate(), bar.close().multiply(shares)));
        }
        return points;
    }
}
