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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 3년 상장주식수 증가율 — {@code (지금 주식수 ÷ 3년 전 주식수 − 1) × 100}.
 *
 * <p>반복 증자로 기존 주주 지분을 희석하는 기업을 걸러낸다. 희석은 주가 상방을 직접 갉아먹지만
 * 손익계산서 어디에도 나타나지 않아, 이익 지표만 보면 끝까지 드러나지 않는다.
 *
 * <p>주식수를 재무제표가 아니라 <b>시세</b>에서 읽는다(ADR-0013). 날짜별 값이라 유상증자·분할
 * 시점부터 곧바로 반영되고, 분기 재무에 묶으면 다음 공시까지 낡은 수를 보게 된다. 국내 소스는
 * 우선주를 별도 종목으로 세므로 이 값이 보통주 수다.
 *
 * <p>액면분할·무상증자는 주식수를 늘리지만 <b>희석이 아니다.</b> {@code listed_shares}는 그것을
 * 그대로 반영하므로 <b>원장의 배수를 나눠서 걷어낸다</b>({@link IndicatorContext#corporateActionMultiple},
 * ADR-0051). 실측: 리노공업 5:1 분할이 걷어내기 전 400%, 현대글로비스 1:1 무상증자가 100%였고
 * 둘 다 중장기 스윙 프리셋의 주식수 조건({@code <= 5})을 그 값 때문에 못 넘고 있었다.
 *
 * <p>MUST: 걷어내는 것은 <b>원장에 있는 사건뿐이다.</b> 원장 커버리지가 곧 보정 커버리지이므로,
 * 수집되지 않았거나 사람이 아직 판정하지 않은 분할은 여전히 거짓 양성으로 남는다 —
 * <b>"보정한다"를 "전량 보정됐다"로 읽지 않는다.</b> 결과에서 빠진 종목을 확인할 때 함께 본다.
 *
 * <p>IMPORTANT — 이 지표는 한동안 <b>러너의 읽기 창을 751봉으로 끌어올리는 장본인</b>이었다.
 * 창은 {@code IndicatorRegistry.readWindowBars()}가 매번 계산하고
 * 이 지표의 750은 그 아래로 내려갔다. 그래도 아래 사실은 그대로 유효하다 — <b>창이 짧아지면
 * 재귀 지표 넷의 비결정성이 되돌아온다.</b>
 *
 * <p><b>창이 넓어지면서 산출 일자가 1일치가 아니게 됐다</b> — {@code min(창 − 워밍업 + 1, 보존 일수)}
 * 이고 보존 경계가 먼저 자른다. 실측 종목당 평균 <b>177.8일자</b>(317종목, 2026-08-20 · 보존 180일 때).
 * 값은 같고 쓰기 부피와 백필 특성만 달라진다 — "실행당 1일치"로 적어 둔 서술이 이 지표에는
 * 더 이상 맞지 않는다. {@link TradingDays750Calculator}도 2026-09-08에 같은 성질이 됐다(ADR-0129).
 *
 * <p>그전까지 재귀 지표 넷({@code ATR_14}·{@code RSI_14}·{@code MACD}·{@code MACD_V})은
 * <b>같은 구간을 두 번 백필하면 매번 242,589행이 갱신됐다</b> — 평활 시드가 창의 앞쪽에서
 * 잘려 실행마다 다른 값이 나온 것이다. 필수 게이트가 그 값에 걸려 있으면 <b>결과가 백필
 * 횟수에 달리게 된다.</b> 창이 200 → 751로 길어진 뒤 같은 측정을 다시 하니
 * <b>1회차 282,189행 · 2회차 0행</b>으로 완전히 멱등해졌다(`batch_execution` 31·32).
 *
 * <p>MUST NOT: 이 지표를 빼거나 {@code LOOKBACK}을 줄이면서 그 사실을 함께 보지 않는다.
 * 창이 줄면 <b>이 지표와 무관한 재귀 지표 넷의 비결정성이 되돌아온다</b> — 컴파일도 되고
 * 테스트도 통과하며, 드러나는 것은 스크리닝 결과가 실행마다 달라지기 시작할 때다.
 * 지금은 다른 지표가 더 큰 창을 요구해 이 지표를 빼도 창이 안 줄지만, <b>창의 최댓값을
 * 내는 지표가 함께 빠지면 창이 200 + 보존 일수 부근으로 돌아간다.</b> 무엇이 최댓값인지는
 * 시간이 지나며 바뀌므로 여기 적지 않는다 — {@code readWindowBars()}에 물어라.
 */
@Component
@IndicatorMeta(
        name = "3년 주식수 증가율",
        description = "상장주식수가 3년(750거래일) 전보다 몇 퍼센트 늘었는지. "
                + "자사주를 소각한 기업은 음수가 된다 — 사들이기만 하면 상장주식수가 "
                + "줄지 않아 이 값에 잡히지 않는다.",
        interpretation = "값이 클수록 기존 주주의 지분이 희석됐다는 뜻이다. "
                + "주식보상에 따른 자연 희석은 3년 누적 5% 안팎이며, "
                + "그보다 크면 자금조달 목적의 증자를 의심할 수 있다. "
                + "다만 액면분할도 주식수를 늘리므로 원인을 함께 확인해야 한다.",
        category = IndicatorCategory.PRICE,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        sortOrder = 4,
        bands = {
                @IndicatorMeta.Band(label = "희석 큼", from = 20),
                @IndicatorMeta.Band(label = "희석 진행", from = 5, to = 20),
                @IndicatorMeta.Band(label = "안정", from = 0, to = 5),
                @IndicatorMeta.Band(label = "감소(자사주 소각 등)", to = 0)
        }
)
public class SharesGrowth3yCalculator implements IndicatorCalculator {

    public static final String CODE = "SHARES_GROWTH_3Y";

    /** 약 3년. 거래일 기준이며 이 값이 러너의 읽기 창을 정한다. */
    static final int LOOKBACK = 750;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // MUST NOT: 재무를 선언하지 않는다 — 사유는 MarketCapCalculator와 같다(ADR-0097).
        return DataRequirement.priceBars(LOOKBACK + 1);
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
        if (bars.size() <= LOOKBACK) {
            return List.of();
        }

        List<IndicatorPoint> points = new ArrayList<>(bars.size() - LOOKBACK);
        for (int i = LOOKBACK; i < bars.size(); i++) {
            PriceBar pastBar = bars.get(i - LOOKBACK);
            PriceBar nowBar = bars.get(i);
            // 양 끝을 같은 축척으로 옮긴다. 권리락 구간에 걸린 끝점은 종가와 짝이 맞는 수여야
            // 아래 배수와 정확히 상쇄된다.
            BigDecimal past = FinancialMath.endpointShares(context, pastBar, FinancialMath.seriesOf(context));
            BigDecimal now = FinancialMath.endpointShares(context, nowBar, FinancialMath.seriesOf(context));
            // 어느 한쪽이라도 없으면 증가율을 지어내지 않는다. 상장 3년 미만 종목이 여기서 빠진다.
            if (past == null || now == null || past.signum() <= 0) {
                continue;
            }
            // 분할·무상증자가 만든 배수는 희석이 아니므로 나눠서 걷어낸다.
            BigDecimal corporateAction =
                    context.corporateActionMultiple(pastBar.tradeDate(), nowBar.tradeDate());
            points.add(new IndicatorPoint(nowBar.tradeDate(),
                    now.divide(past.multiply(corporateAction), MovingAverages.MC)
                            .subtract(BigDecimal.ONE)
                            .multiply(HUNDRED)
                            .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
