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
 * 회전율 — 그날 <b>발행주식의 몇 %가 손바뀜했는가</b>.
 *
 * <p>거래대금({@code TURNOVER_60})과 답하는 질문이 다르다. 거래대금은 "얼마어치 거래됐나"라
 * 규모가 큰 종목이 늘 위에 오지만, 회전율은 <b>자기 주식 수로 나눠 규모에 중립</b>이다 —
 * 소형주가 크게 손바뀜하면 대형주보다 높게 나온다.
 *
 * <p>IMPORTANT — <b>{@link PriceScaling#adjustedVolume}을 쓰지 않는다.</b> 그 함수는 과거 봉을
 * 전부 <b>분할 후 단위</b>로 올리는데({@code volume ÷ adj_factor}), 분모인
 * {@link IndicatorContext#sharesOn}은 <b>권리락 구간에서만</b> 계단을 올리고 그 밖에서는
 * 그날의 주식 수를 그대로 준다. 둘을 나누면 축척이 어긋나 <b>분할 이전 구간의 회전율이 사건
 * 배수만큼 커지고, 최신 봉만 맞아 보인다.</b>
 *
 * <p>비율은 애초에 축척에 불변이다 — 분할이면 거래량과 주식 수가 함께 커진다. 그래서 보정할
 * 것이 <b>권리락~신주상장 14~16거래일 하나</b>이고 그것을 {@code sharesOn}이 한다.
 * {@code MarketCapCalculator}가 같은 짝을 쓴다 — 다만 <b>둘 다 이제 {@code sharesOn}이 아니라
 * {@link FinancialMath#sharesOf}를 부른다</b>(ADR-0097). 봉에 주식수가 있으면 그것이 이기므로
 * 국내 동작은 그대로이고, 없으면(미국) 재무에서 폴백해 봉 시점으로 옮긴다.
 *
 * <p><b>IMPORTANT — 재무 폴백에서는 위 축척 논증의 전제가 성립하지 않는다.</b> 그 논증은
 * <i>"분모인 {@code sharesOn}이 그날의 주식 수를 그대로 준다"</i>에 서 있는데, 폴백에는
 * <b>「그날의 주식 수」가 없다</b> — 분모가 <b>공시된 주식수 × 그 뒤 원장 사건의 배수</b>이고
 * 분자는 보정하지 않은 {@code bar.volume()}이다. 따라서 <b>원장에 없는 분할은 그 구간의
 * 회전율을 배수만큼 틀리게 한다.</b> 오차 창은 {@code sharesOf}가 봉 시점 분기를 고르므로
 * <b>분할일~다음 공시(약 63거래일)</b>로 좁혀지지만 없어지지는 않는다 —
 * <b>원장 커버리지가 곧 이 지표의 정확도다.</b>
 */
@Component
@IndicatorMeta(
        name = "회전율",
        description = "그날 거래된 주식 수가 발행주식 총수의 몇 %인지. 손바뀜이 얼마나 활발한지를 본다.",
        interpretation = "높을수록 그날 주인이 많이 바뀌었다는 뜻이다. "
                + "거래대금과 달리 회사 규모로 나눈 값이라 대형주와 소형주를 나란히 견줄 수 있다.",
        category = IndicatorCategory.PRICE,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        // 활발한 것이 좋다고 말할 수 없다. 유동성은 조건의 재료이지 우열이 아니다.
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 2,
        sortOrder = 6,
        bands = {
                @IndicatorMeta.Band(label = "매우 활발", from = 5),
                @IndicatorMeta.Band(label = "활발", from = 2, to = 5),
                @IndicatorMeta.Band(label = "보통", from = 0.5, to = 2),
                @IndicatorMeta.Band(label = "한산", to = 0.5)
        })
public class TurnoverRatioCalculator implements IndicatorCalculator {

    public static final String CODE = "TURNOVER_RATIO";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // MUST NOT: 재무를 선언하지 않는다 — 사유는 MarketCapCalculator와 같다(ADR-0097).
        return DataRequirement.priceBars(1);
    }

    /**
     * <p><b>{@link FinancialMath#bySegment}를 거친다</b>(ADR-0128 §규칙 · ADR-0132). 이 계산기는
     * 봉 루프를 스스로 돌지만 재무를 {@code context}에서 <b>한 번</b> 읽으므로, 감싸지 않으면
     * 그 컨텍스트가 <b>오늘 기준으로 접힌 최신 개정본</b>을 들고 있어 과거 봉이 <b>미래의
     * 판(revision)</b>을 쓴다. {@code sharesOf}의 {@code publishedIndexOn}은 <b>기(期)</b>만
     * 봉 시점으로 고르고 판은 못 고른다 — 실측으로 저장된 {@code MARKET_CAP}이
     * {@code LATEST}와 정확히 일치했고 최대 <b>19.4B 대 32.0B</b>였다.
     */
    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.bySegment(context, this::computeSegment);
    }

    private List<IndicatorPoint> computeSegment(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        List<IndicatorPoint> points = new ArrayList<>(bars.size());
        for (PriceBar bar : bars) {
            BigDecimal shares = FinancialMath.sharesOf(context, bar, FinancialMath.seriesOf(context));
            // MUST: 가드는 sharesOf **뒤**다. 앞에 두면 봉의 listedShares가 null인 순간
            // continue가 걸려 재무 폴백에 영영 도달하지 못한다 — 미국 봉이 전량 NULL이라
            // ADR-0097이 열었다고 적은 이 지표가 실제로는 0행이었다. MarketCapCalculator가
            // 같은 커밋에서 이 형태를 썼고 여기만 안 고쳐졌다.
            //
            // 판정 자체는 그대로다. 0으로 두면 회전율이 무한이 되고, 임의의 큰 수를 쓰면
            // "가장 활발한 종목"으로 올라온다.
            if (shares == null || shares.signum() <= 0) {
                continue;
            }
            BigDecimal ratio =
                    FinancialMath.percentage(BigDecimal.valueOf(bar.volume()), shares);
            if (ratio == null) {
                continue;
            }
            points.add(new IndicatorPoint(bar.tradeDate(), ratio));
        }
        return points;
    }
}
