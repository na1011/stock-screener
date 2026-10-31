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
 * 60일 평균 거래대금 — 최근 60거래일 거래대금의 산술평균.
 *
 * <p>MUST NOT: {@code 종가 × 거래량}으로 합성하지 않는다. 소스가 주는 {@code trade_value}만 쓴다
 * (V1 스키마의 같은 규칙). 합성값은 장중 체결가 분포를 무시해 실제와 다르고, 실측과 합성이
 * 섞이면 이 지표가 시장마다 미묘하게 달라진다 — 그 차이는 시장이 늘 때에야 드러난다.
 * 거래대금을 주지 않는 소스의 종목에서는 값을 내지 않는다.
 *
 * <p>이 지표의 실질 기능은 체결 가능성이 아니라 <b>관심도</b>다. 개인 투자금 규모에서 슬리피지는
 * 어지간해서는 문제가 되지 않지만, 거래대금이 얇은 종목은 뉴스 한 건에 크게 움직인다.
 *
 * <p>MUST NOT: {@code TURNOVER_60_PCTL}을 <b>유동성 하한 게이트로 쓰지 않는다.</b> 백분위는
 * 유니버스 안에서 매겨지므로(ADR-0036) 하나의 임계가 시장마다 다른 절대 높이가 된다 — 지금과
 * 같은 수를 남기는 분위(0.2905)에서 하한이 <b>KOSPI 139억 · KOSDAQ 73억</b>으로 갈린다
 * (2026-08-14 실측). 100억 미만 14종목이 새로 통과하고 100억 초과 14종목이 탈락한다.
 * <b>이 지표에 백분위가 실제로 있다는 것이 함정이다</b>({@code PercentileConfig}에 등록돼
 * 있다) — {@code MarketCapCalculator}는 아예 만들지 않아 시도조차 막히는데 여기는 걸린다.
 * 백분위는 종목 간 <b>상대 순위</b>용이고 하한용이 아니다(ADR-0072).
 *
 * <p><b>창 60봉 전부에 거래대금이 있어야 값을 낸다</b>(2026-10-31). 거래가 없던 날은 0으로 온다 — 국내는 정지 봉을 소스가 0으로
 * 주고(`PriceWriter.flattenToClose`), 미국은 거래량 0인 봉을 0으로 싣는다. 그래서 빈 칸은 「받지 못한 날」이고, 그 날을 빼고 평균하면
 * 짧은 창의 평균이 60일 평균으로 나간다.
 *
 * <p><b>값의 정의는 「소스가 주는 그날 체결 금액 총계」다</b>(ADR-0196) — 국내는 KRX {@code trPrc}, 미국은 SIP 일봉의
 * {@code vw × v}(그 봉에 든 체결 전부의 Σ가격×수량 · 관문 켬). 위 「합성하지 않는다」의 사유(장중 체결가 분포를 무시한다)는
 * {@code vw × v}에 서지 않는다 — VWAP이 그 분포 자체다.
 */
@Component
@IndicatorMeta(
        name = "60일 평균 거래대금",
        description = "최근 60거래일 동안 하루에 오간 대금의 평균. 소스가 준 그날 체결 금액 총계이며 종가 × 거래량으로 만든 값이 아니다.",
        interpretation = "값이 클수록 시장의 관심이 두텁다는 뜻이다. "
                + "얇은 종목은 뉴스 한 건에 크게 움직여, 장기 보유를 전제하면 그 진폭이 부담이 된다. "
                + "통화 단위라 다른 시장의 종목과 직접 비교하지 않는다.",
        category = IndicatorCategory.PRICE,
        valueType = IndicatorValueType.CURRENCY,
        unit = "원",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 3
)
public class Turnover60Calculator implements IndicatorCalculator {

    public static final String CODE = "TURNOVER_60";

    /** 약 3개월. 실적 발표 한 번을 품어 이벤트성 하루가 평균을 지배하지 않는다. */
    static final int WINDOW = 60;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(WINDOW);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        if (bars.size() < WINDOW) {
            return List.of();
        }

        List<IndicatorPoint> points = new ArrayList<>(bars.size() - WINDOW + 1);
        BigDecimal sum = BigDecimal.ZERO;
        int counted = 0;

        for (int i = 0; i < bars.size(); i++) {
            BigDecimal entering = bars.get(i).tradeValue();
            if (entering != null) {
                sum = sum.add(entering);
                counted++;
            }
            if (i >= WINDOW) {
                BigDecimal leaving = bars.get(i - WINDOW).tradeValue();
                if (leaving != null) {
                    sum = sum.subtract(leaving);
                    counted--;
                }
            }
            // 창 60봉 전부에 거래대금이 있어야 값을 낸다 — 빠진 날을 빼고 평균하지 않는다. 국내는 정지 봉도 0으로 오고 빈 칸이 없으며
            // (2026-10-31 · 729,605행 중 NULL 0), 미국의 빈 칸은 받지 못한 날(관문 전 · 소스가 `vw`를 안 준 날)이라 빼면 짧은 창의
            // 평균이 60일 평균으로 나간다.
            if (i < WINDOW - 1 || counted < WINDOW) {
                continue;
            }
            points.add(new IndicatorPoint(bars.get(i).tradeDate(),
                    sum.divide(BigDecimal.valueOf(counted), 2, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
