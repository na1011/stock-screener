package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 주봉 OBV 추세 강도. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다.
 *
 * <p><b>일봉판({@code OBV_T_13})과 같은 값이고 같은 공식이다</b>({@code SeriesMath.tStatistic}).
 * 그래서 표시도 같아야 한다 — 이름·구간이 갈려 있으면 한쪽만 초보가 읽을 수 있고, 실제로
 * 그랬다. 이 지표는 일목 점수화의 잔재가 <b>아니다</b>: ADR-0046 §2026-08-14가 삭제한 13개
 * 목록에 없고, {@code ICHI_DIVERGENCE}의 입력이자 {@code ICHIMOKU_SP_KR}의 하이라이트다.
 *
 * <p>MUST: 구간을 일봉판과 <b>같은 임계(±1.96)</b>로 유지한다. 같은 통계량인데 임계가 갈리면
 * 두 카드가 같은 종목에 다른 판정을 내고, 그 어긋남은 값이 그럴듯해 드러나지 않는다.
 */
@Component
@IndicatorMeta(
        name = "주봉 OBV 추세 강도",
        description = "13주 동안 OBV가 한 방향으로 쌓였는지를 통계적으로 잰 값. 일봉판(OBV 추세 강도)의 주봉 버전이다.",
        interpretation = "+1.96을 넘으면 매집이, −1.96 아래면 분산이 통계적으로 뚜렷하다. 그 사이는 방향을 말할 수 없다. 쌓인 절대량이 아니라 기울기의 유의성만 보므로 언제부터 셌는지와 무관하다.",
        category = IndicatorCategory.OSCILLATOR,
        // 일봉판과 맞춘다. 화면은 valueType으로 포맷을 분기하지 않으므로 보이는 변화는
        // 없지만, 같은 값이 서버에서 다른 종류로 불리는 것을 남겨 둘 이유도 없다.
        valueType = IndicatorValueType.RATIO,
        decimalScale = 2,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 245,
        bands = {
                @IndicatorMeta.Band(label = "매집 뚜렷", from = 1.96),
                @IndicatorMeta.Band(label = "방향 없음", from = -1.96, to = 1.96),
                @IndicatorMeta.Band(label = "분산 뚜렷", to = -1.96)
        })
public class IchimokuObvTCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_OBV_T";

    public IchimokuObvTCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return BigDecimal.valueOf(analysis.obvT());
    }
}
