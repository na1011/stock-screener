package com.stockscreener.screening.preset;

import com.stockscreener.indicator.PercentileSpec;
import com.stockscreener.indicator.SectorMetricSpec;
import com.stockscreener.indicator.calculator.AccrualRatioCalculator;
import com.stockscreener.indicator.calculator.Adx14Calculator;
import com.stockscreener.indicator.calculator.AdjCloseCalculator;
import com.stockscreener.indicator.calculator.AltmanZCalculator;
import com.stockscreener.indicator.calculator.AtrPct14Calculator;
import com.stockscreener.indicator.calculator.Beta104wCalculator;
import com.stockscreener.indicator.calculator.Cmf21Calculator;
import com.stockscreener.indicator.calculator.DebtRatioCalculator;
import com.stockscreener.indicator.calculator.Disparity200Calculator;
import com.stockscreener.indicator.calculator.EpsCagr5yCalculator;
import com.stockscreener.indicator.calculator.EpsGrowthTtmYoyCalculator;
import com.stockscreener.indicator.calculator.ExcessReturn52wCalculator;
import com.stockscreener.indicator.calculator.GrossMarginCalculator;
import com.stockscreener.indicator.calculator.GrossProfitToAssetsCalculator;
import com.stockscreener.indicator.calculator.MaAlignScoreCalculator;
import com.stockscreener.indicator.calculator.MarketCapCalculator;
import com.stockscreener.indicator.calculator.OperatingCashFlow5ySumCalculator;
import com.stockscreener.indicator.calculator.OperatingMarginCalculator;
import com.stockscreener.indicator.calculator.PbrCalculator;
import com.stockscreener.indicator.calculator.PctFrom52wHighCalculator;
import com.stockscreener.indicator.calculator.PctFrom52wLowCalculator;
import com.stockscreener.indicator.calculator.PegCalculator;
import com.stockscreener.indicator.calculator.PerCalculator;
import com.stockscreener.indicator.calculator.PerMedian5yCalculator;
import com.stockscreener.indicator.calculator.ProfitableQuarters4Calculator;
import com.stockscreener.indicator.calculator.Return52wCalculator;
import com.stockscreener.indicator.calculator.RevenueGrowthCalculator;
import com.stockscreener.indicator.calculator.RoaCalculator;
import com.stockscreener.indicator.calculator.RoeCalculator;
import com.stockscreener.indicator.calculator.FcfMarginCalculator;
import com.stockscreener.indicator.calculator.InventoryGrowthGapCalculator;
import com.stockscreener.indicator.calculator.FcfYieldCalculator;
import com.stockscreener.indicator.calculator.OperatingMarginMin5yCalculator;
import com.stockscreener.indicator.calculator.ProfitStreakYearsCalculator;
import com.stockscreener.indicator.calculator.RetainedToMarket4yCalculator;
import com.stockscreener.indicator.calculator.RoeMin3yCalculator;
import com.stockscreener.indicator.calculator.RoicPretaxCalculator;
import com.stockscreener.indicator.calculator.Rsi2Calculator;
import com.stockscreener.indicator.calculator.SharesGrowth3yCalculator;
import com.stockscreener.indicator.calculator.Sma150Calculator;
import com.stockscreener.indicator.calculator.Sma200Calculator;
import com.stockscreener.indicator.calculator.Sma200Momentum21Calculator;
import com.stockscreener.indicator.calculator.Sma200Slope60Calculator;
import com.stockscreener.indicator.calculator.Sma60Calculator;
import com.stockscreener.indicator.calculator.TradingDays750Calculator;
import com.stockscreener.indicator.calculator.Turnover60Calculator;
import com.stockscreener.indicator.calculator.VolumeRatio20Calculator;
import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.IndicatorCondition;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;

/**
 * 사전 정의 전략 선언. spec §9가 요구하는 "최소 1개 이상의 사전정의 전략 프리셋"이 여기 있다.
 *
 * <p>spec §3은 <b>"(예: Value, QVG형 전략)"</b>이라 적는다 — 괄호가 말하듯 예시이고 목록이
 * 아니다. {@code QVG_KR}은 2026-08-17 사용자 결정으로 폐기했고 그 자리를 린치 프리셋 둘이
 * 받는다(ADR-0066). <b>spec에 이름이 적힌 둘 중 하나가 사라지는 변경이라 승인을 받았다</b> —
 * CLAUDE.md의 기획 정본 조항이 그것을 요구한다.
 *
 * <p>{@code @Component} 하나씩이 아니라 {@code @Configuration}의 리스트 빈으로 두는 이유는
 * 프리셋이 <b>행위가 아니라 데이터</b>이기 때문이다({@code IndicatorCalculator}와 대비되고
 * {@link PercentileSpec}과 같은 성격이다). 나란히 보여야 가중치 균형을 비교하며 잡을 수 있다.
 *
 * <p><b>조건의 출처가 문헌이다</b>(ADR-0061). 임의로 조합하지 않고 각 조건이 어느 이론에서
 * 왔는지를 아래 각 메서드의 javadoc이 적는다. 셋을 갈라 적는다 — <b>원문 그대로 옮긴 것</b>,
 * <b>국내 분포로 재보정한 것</b>, <b>원문에 없는데 더한 것</b>. 갈리지 않으면 다음 세션이
 * 우리가 정한 값을 문헌이 검증한 값으로 읽는다.
 *
 * <p>IMPORTANT — <b>백분위 조건의 부등호 방향</b>이 이 파일에서 가장 틀리기 쉬운 부분이다.
 * 백분위는 예외 없이 오름차순이므로:
 * <ul>
 *   <li>PER처럼 <b>낮은 쪽이 좋은</b> 지표의 "하위 30%"는 {@code PER_PCTL <= 0.30}
 *   <li>ROE처럼 <b>높은 쪽이 좋은</b> 지표의 "상위 30%"는 {@code ROE_PCTL >= 0.70}
 * </ul>
 * 뒤집어 적으면 정반대 종목이 최상위로 올라오는데 점수도 결과도 그럴듯해서 눈으로는
 * 드러나지 않는다. {@code PresetCatalogTest}가 원본 지표의 방향과 대조해 이것을 잡는다.
 *
 * <p>IMPORTANT — <b>절대 임계값은 국내 분포를 재고 정한다</b>(ADR-0039·0061). 미국 문헌의
 * 값을 그대로 옮기면 그 값이 재는 위치가 달라진다. 실제로 2년 주간 베타 1.3은 국내에서
 * 82%가 통과해 <b>게이트가 아니라 상수</b>였다.
 *
 * <p>지표 코드를 문자열이 아니라 계산기의 {@code CODE} 상수로 참조한다 — 오타가 기동 시점의
 * FK 위반이 아니라 <b>컴파일 오류</b>가 되고, 지표 코드를 바꾸면 여기가 함께 깨진다.
 */
@Configuration
public class PresetCatalog {

    /** 백분위 지표 코드. 원본 코드에 접미사를 붙이는 규칙은 {@link PercentileSpec}이 정본이다. */
    private static String pctl(String sourceCode) {
        return sourceCode + PercentileSpec.SUFFIX;
    }

    /** 업종 내 백분위. 모집단이 유니버스가 아니라 업종이다(ADR-0044). */
    private static String sectorPctl(String sourceCode) {
        return sourceCode + SectorMetricSpec.PERCENTILE_SUFFIX;
    }

    /** 업종 중앙값 배수. 1.0이 업종 중앙값이며 백분위가 아니다. */
    private static String sectorRel(String sourceCode) {
        return sourceCode + SectorMetricSpec.MEDIAN_RATIO_SUFFIX;
    }

    // ── 9. 버핏 우량주 ────────────────────────────────────────────────────

    public static final String BUFFETT_QUALITY_KR = "BUFFETT_QUALITY_KR";

    /**
     * 버핏 우량주 — {@code docs/external/워렌버핏-방법론.md} §8 복사용 스크린의 이식.
     *
     * <p>원저는 버핏의 주주서한(1977·1986·1992·2007·2019)과 해그스트롬 <i>The Warren Buffett
     * Way</i>, 메리 버핏 <i>Buffettology</i>, AAII Buffett Hagstrom Screen이다. 저장소의 문서는
     * 그것을 <b>TradingView 문법으로 옮긴 이식본</b>이며 조건의 출처로만 쓴다(ADR-0067).
     * MUST NOT: 그 문서를 프리셋 조건셋의 정본이라 적지 않는다.
     *
     * <p><b>원문 그대로</b>: {@code PROFIT_STREAK_Y >= 7}(0.2·1.2·5.1 — AAII가 요구하는 7개
     * 회계연도 연속 흑자), {@code DEBT_RATIO <= 50}(0.3·3.2 — D/E 0.5),
     * {@code GROSS_MARGIN >= 40}(1.3·5.2), {@code RETAINED_TO_MARKET_4Y >= 1.0}(3.5의 1달러
     * 유보 원칙), {@code FCF_MARGIN >= 8}·{@code FCF_YIELD >= 5}(3.3 대체·4.2),
     * {@code ACCRUAL_RATIO <= 0}(5.5), {@code SHARES_GROWTH_3Y <= 0}(2.1).
     * <br><b>국내 분포로 재보정한 것</b>: 필수 게이트의 {@code ROE_MIN_3Y >= 10}(원문 15는
     * 19종목/5%)과 {@code DEBT_RATIO <= 100}. {@code ROIC_PRETAX >= 15}는 <b>분포가 아니라
     * 기저 때문에</b> 원문 12에서 옮겼다 — 원문은 세후이고 우리 지표는 세전이라 같은 수를 쓰면
     * 원문보다 느슨해진다.
     *
     * <p><b>{@code ROIC_PRETAX}가 게이트에 있는 것이 이 프리셋의 해자 축이다</b>(ADR-0068).
     * 2019년 주주서한이 <i>"영업에 투입된 순유형자본 대비 좋은 수익률"</i>을 <b>첫 번째</b>
     * 기준으로 든다. 이 게이트 하나가 통과를 19 → 14로 자르면서 <b>매출총이익률 중앙값을
     * 44.8 → 50.5로 올린다</b>(유니버스 22.2) — 프랜차이즈가 남고 코모디티가 빠진다.
     * <br><b>원문보다 약하게 옮긴 것</b>: 3.5를 5년이 아니라 <b>4년</b>으로 잰다. 읽기 창
     * 1,261봉 전이 2021-06인데 FY−5 기말은 1,377봉 전이라, 5년으로 두면 주가와 이익잉여금의
     * 기준점이 7개월 어긋난 채 남는다(ADR-0067 §배경).
     * <br><b>원문에 없는데 더한 것</b>: {@code OPM_MIN_5Y}(3.4의 「일관성」을 최솟값으로 옮긴
     * 형태), {@code PER_SECTOR_PCTL}(AAII 원 구현이 밸류에이션을 <b>업종 기준값 대비</b>로
     * 판정하는데 이식본이 TradingView의 한계로 절대값으로 바꿔 놓은 것을 실제 업종 모집단으로
     * 되돌린 것이다).
     * <br><b>원문에 있는데 뺀 것</b>: {@code ROE >= 15}·{@code ROA >= 8}·
     * {@code OPERATING_MARGIN >= 15}·{@code NET_MARGIN >= 10}·{@code PER <= 25}.
     * <b>게이트가 이미 재는 것과 순위상관 ρ ≥ 0.8이면 점수에서 뺀다</b>는 잣대를 전 조건에
     * 같게 적용한 결과다(ADR-0068) — {@code ROIC_PRETAX}와 ρ가 각각 0.870·0.832·0.836이고,
     * {@code NET_MARGIN}은 {@code OPERATING_MARGIN}과 0.857이다. 축에 이름을 여섯 개 붙이는
     * 것은 측정이 아니다.
     *
     * <p><b>질은 문이고 가격은 저울이다</b>(ADR-0068). 게이트 여섯은 전부 사업의 질과 규모이고,
     * 가격은 게이트가 아니라 <b>배점 16 중 7(44%)</b>로 순위를 정한다. AAII Buffett-Hagstrom도
     * {@code P/FCF ÷ FCF 성장률}이 낮은 순으로 뽑지 문턱으로 쓰지 않는다. <b>가격을 게이트로
     * 올리는 안은 실측이 무너뜨렸다</b> — 통과 5종목 중 셋이 매출총이익률 9~20%짜리 자동차
     * 체인이 되고 금융·보험 통과율이 48%로 뛴다(ADR-0068 §배경).
     *
     * <p><b>필수 게이트를 단일 시점 ROE가 아니라 3년 연속 ROE에 두는 것이 이 프리셋의 성격을
     * 정한다.</b> 문서 §11이 <i>"가장 중요한 건 3.1의 ROE 시계열 확인 — TTM 단일 시점만 보므로
     * 일회성 이익으로 ROE가 튄 해에 걸린 종목이 반드시 섞여 들어온다"</i>고 적고 그것을
     * <b>사람이 눈으로 하라</b>고 미룬다. 우리는 그 확인을 지표로 만들 수 있으므로 게이트로
     * 옮겼다. 원문 임계({@code ROE >= 15})는 배점이 가장 높은 점수 조건으로 남으므로,
     * §8이 <i>"이 둘을 분리하는 순간 버핏 기준이 아니게 된다"</i>고 못박은 「ROE와 저부채를
     * 함께 둔다」는 지켜진다.
     *
     * <p><b>게이트끼리 같은 방향으로 겹치는 쌍이 둘 있다.</b> {@code ROE_MIN_3Y >= 10}(53) ∧
     * {@code PROFIT_STREAK_Y >= 7}(156) = 37로 독립 가정(23.8)의 <b>1.55배</b>, 그리고
     * {@code ROE_MIN_3Y >= 10} ∧ {@code ROIC_PRETAX >= 15}(69) = 35로 독립 가정(10.5)의
     * <b>3.3배</b>다. <b>단독 통과율만으로는 이 형태가 안 보인다.</b> 그럼에도 셋을 다 두는
     * 이유는 각각이 실제로 자르기 때문이며(ROIC가 19 → 14), <b>게이트 여섯을 안전장치 여섯으로
     * 읽지 않도록 그 배수를 여기 적는다.</b> 여섯을 전부 걸면 14종목이 남는다.
     *
     * <p>MUST NOT: 이 프리셋이 <b>지주회사를 걸러낸다고 적지 않는다.</b> 문서 §10이 지주회사를
     * <i>"버핏식 스크린에서 가장 오염이 심한 구간"</i>으로 지목한다 — 지분법이익 때문에 ROE가
     * 실체 없이 높게 잡히기 때문이고, <b>이 프리셋은 ROE 계열을 필수 게이트에 둔다.</b> 조건
     * 좌변이 지표 FK라 섹터 범주 제외를 표현할 수 없고, KSIC 중분류 64가 금융업과 지주회사를
     * 겸해 코드로도 못 가른다(ADR-0066이 그 길을 명시적으로 버렸다).
     *
     * <p>MUST NOT: {@code PROFIT_STREAK_Y}를 <b>금융업 배제로 읽지 않는다.</b> 일부 금융지주의
     * FY2021·2022가 소스 정책상 오지 않아 통과율이 낮지만(실측 KSIC 64·65·66의 50종목 중 14),
     * 그것은 <b>설계가 아니라 부작용</b>이고 열넷은 그대로 통과한다.
     *
     * <p>MUST NOT: {@code FCF_MARGIN}·{@code FCF_YIELD}를 <b>오너 어닝이라 적지 않는다.</b>
     * 오너 어닝은 운전자본 증분까지 차감하며 감가상각비가 필요한데 그것은 닫혀 있다. 문서 3.3이
     * 스스로 <i>"잉여현금흐름과 유사하나 운전자본 증분까지 차감한다는 점이 다르다"</i>고 적는다.
     *
     * <p>MUST NOT: 수익률을 주장하지 않는다. 조건의 출처로만 인용한다.
     */
    private static ScreenPreset buffettQualityKr() {
        return new ScreenPreset(
                BUFFETT_QUALITY_KR,
                "버핏 우량주",
                "여러 해에 걸쳐 꾸준히 잘 벌면서 빚이 적은 회사를 점수로 줄 세운다. "
                        + "판별의 중심은 한 시점의 수익성이 아니라 그것이 이어져 왔는가다 — "
                        + "최근 3개 회계연도가 각각 자기자본이익률 10%를 넘어야 하고, "
                        + "최근 7개 회계연도가 모두 영업흑자여야 한다. "
                        + "한 해 일회성 이익으로 수익성이 튄 종목은 여기서 빠진다. "
                        + "부채 조건을 수익성과 반드시 함께 거는 것이 이 방식의 요구다 — "
                        + "빚을 늘리면 자기자본이익률이 올라가므로 둘을 나누면 뜻이 사라진다. "
                        + "사업에 묶인 자본이 벌어들이는 수익률도 필수다 — 자기자본이익률과 달리 "
                        + "빚을 늘려 부풀릴 수 없어, 수익성이 사업에서 온 것인지 레버리지에서 온 "
                        + "것인지를 가른다. 이 조건 하나가 매출총이익률 중앙값을 45%에서 50%로 "
                        + "올린다. "
                        + "가격은 걸러내는 조건이 아니라 순위를 정하는 몫이다 — 배점의 44%가 "
                        + "가격에 걸려 있다. 좋은 회사를 비싸게 사지 않는다는 뜻이지만, "
                        + "통과 목록 자체는 시장보다 비싼 편이다. 좋은 회사는 비싸게 거래되기 "
                        + "때문이며, 이 화면은 '살 회사'가 아니라 '살 만한지 볼 회사'를 "
                        + "순서대로 세운 것이다. "
                        + "필요한 이력이 길어 편입 종목의 4분의 1이 판정 자체를 받지 못한다 — "
                        + "7개 회계연도가 연속으로 있어야 하기 때문이며, 상장한 지 얼마 안 된 종목이 "
                        + "이력이 짧다는 이유로 유리해지지 않게 한 결과다. "
                        + "옮기지 못한 조건이 많다. 기대수익률 모형은 배당 데이터가 없어 통째로 "
                        + "빠졌고, 이자보상배율과 순부채·EBITDA는 이자비용과 감가상각비를 소스가 "
                        + "주지 않아 빠졌으며, 유형자본 대비 수익률은 무형자산과 영업권을 아직 "
                        + "가려 받지 못해 빠졌다. "
                        + "잉여현금흐름은 주주이익이 아니다 — 운전자본 증가분을 빼지 않았고, "
                        + "그것을 계산할 감가상각비가 없다. 원문이 지시한 대용이다. "
                        + "유보 대비 시가 조건은 원문의 5년이 아니라 4년을 재며, 유상증자를 한 "
                        + "회사는 실제보다 좋게 나온다 — 주식수만 늘고 이익잉여금은 늘지 않기 때문이다. "
                        + "금융업과 지주회사를 제외하지 못한다. 원문은 지주회사를 이 방식에서 가장 "
                        + "왜곡이 심한 구간으로 지목하는데, 자회사 지분법이익으로 자기자본이익률이 "
                        + "실체 없이 높게 잡히고 이 화면은 그 지표를 필수로 건다. "
                        + "발생액 비율과 3년 연속 조건이 부분적으로만 막아 준다. "
                        + "주식수 조건은 자사주 매입을 보지 못한다 — 국내에서 자사주는 소각해야 "
                        + "상장주식수가 줄어든다. "
                        + "매출원가 개념이 없는 업종은 매출총이익률에서, 유동부채를 구분하지 않는 "
                        + "금융업은 투하자본이익률에서 점수를 얻지 못한다. "
                        + "투하자본이익률은 법인세를 빼기 전 기준이라 세후로 쓰인 외부 문헌의 "
                        + "숫자와 직접 견주면 안 된다. "
                        + "다년 지표와 매출총이익률·잉여현금흐름은 연간 기준이라 분기 기준 지표와 "
                        + "기준 시점이 다르다. "
                        + "잉여현금흐름 수익률은 한 해의 설비투자에 흔들린다 — 큰 투자를 한 해에는 "
                        + "좋은 회사도 비싸 보인다. 다섯 해 평균으로 재는 편이 낫지만 그만큼의 "
                        + "이력이 있는 종목이 셋 중 둘뿐이라 만들지 않았다. "
                        + "종목마다 가장 최근 회계연도가 달라, 새 연간 재무가 들어오는 3~4월에 "
                        + "순위가 크게 바뀐다. "
                        + "업종 비교 조건은 같은 업종 종목이 5개에 못 미치면 값이 없다 — "
                        + "그 종목은 사업 성적이 아니라 표본 크기 때문에 배점을 잃는다. "
                        + "사업을 이해할 수 있는가, 경영진이 정직한가, 해자가 실재하는가 같은 "
                        + "이 방식의 앞선 기준들은 스크리너 밖에 있다.",
                EvaluationMode.SCORING,
                null,
                ConditionGroup.and(List.of(
                        // ── 필수 게이트 ──
                        // 3.1 — 원문 15는 19종목(5%)이라 국내 분포로 재보정했다. 53종목(15%).
                        required(RoeMin3yCalculator.CODE, ComparisonOperator.GTE, "10"),
                        // 0.3·3.2 — ROE와 반드시 함께 건다. §8이 분리를 금지한 자리다. 196종목(57%).
                        required(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "100"),
                        // 0.2·1.2·5.1 — AAII 원문의 7개 회계연도다. 156종목(45%).
                        required(ProfitStreakYearsCalculator.CODE, ComparisonOperator.GTE, "7"),
                        // 0.1 충분한 규모. 275종목(79%).
                        required(MarketCapCalculator.CODE, ComparisonOperator.GTE, "1000000000000"),
                        // 2019 주주서한의 첫 번째 기준 —「영업에 투입된 순유형자본 대비 좋은
                        // 수익률」. 세전이라 원문 12가 아니라 15다(ADR-0067). 69/347(20%)이
                        // 통과하고 이 게이트가 19 → 14로 자른다.
                        required(RoicPretaxCalculator.CODE, ComparisonOperator.GTE, "15"),
                        // §7 유동성. 대형 지향이라 린치 고속성장주의 50억을 승계하지 않는다.
                        required(Turnover60Calculator.CODE, ComparisonOperator.GTE, "10000000000"),

                        // ── 점수: 가격 — 배점 16 중 7이다 ──
                        // 4.2 안전마진. P/FCF의 역수라 같은 조건이며 음수에서도 뜻이 산다.
                        // 99/347(29%) 통과, 커버리지 323.
                        weighted(FcfYieldCalculator.CODE, ComparisonOperator.GTE, "5", "3"),
                        // 4.3의 업종 상대판. AAII 원 구현이 요구한 비교이고, 절대 PER과 달리
                        // 국면이 바뀌어도 뜻이 유지된다. 115/347(33%), 커버리지 215.
                        weighted(sectorPctl(PerCalculator.CODE),
                                ComparisonOperator.LTE, "0.50", "2"),
                        // 3.5 1달러 유보 → 1달러 시가. 원문 그대로의 기준이 1.0이다.
                        // 144/347(41%), 커버리지 224 — 이 프리셋에서 가장 낮다.
                        weighted(RetainedToMarket4yCalculator.CODE,
                                ComparisonOperator.GTE, "1.0", "2"),

                        // ── 점수: 이익의 질 ──
                        // 5.5 회계 장난 경계 — 순이익과 영업현금흐름의 괴리. 217/347(63%).
                        weighted(AccrualRatioCalculator.CODE, ComparisonOperator.LTE, "0", "2"),
                        // 3.3의 대용·5.4. 오너 어닝이 아니다. 100/347(29%).
                        // FCF_YIELD와 ρ 0.737이라 배점을 낮게 둔다.
                        weighted(FcfMarginCalculator.CODE, ComparisonOperator.GTE, "8", "1"),

                        // ── 점수: 해자 강도 — 게이트 위에서 더 재는 것만 ──
                        // 1.3·5.2 코모디티 배제. ROIC 게이트와 ρ 0.332로 낮아 축이 따로 선다.
                        // 67/347(19%), 커버리지 286.
                        weighted(GrossMarginCalculator.CODE, ComparisonOperator.GTE, "40", "2"),
                        // 3.4 「일관성」. 나쁜 해에도 얼마나 남았는가. 58/347(17%).
                        weighted(OperatingMarginMin5yCalculator.CODE,
                                ComparisonOperator.GTE, "8", "2"),

                        // ── 점수: 자본배분과 부채 여유 ──
                        // 2.1 자본배분. 소각만 잡고 자사주 취득은 못 잡는다. 201/347(58%).
                        weighted(SharesGrowth3yCalculator.CODE, ComparisonOperator.LTE, "0", "1"),
                        // 0.3·3.2 원문의 D/E 0.5. 게이트(100)보다 조인 값이다. 124/347(36%).
                        weighted(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "50", "1"))));
    }

    @Bean
    public List<ScreenPreset> screenPresets() {
        return List.of(valueKr(), lynchFastGrowerKr(), lynchStalwartKr(),
                momentumKr(), swingTrendKr(), stage2BreakoutKr(),
                pullbackUptrendKr(), lowvolQualityKr(), buffettQualityKr());
    }

    /**
     * 프리셋별 <b>추가 컬럼</b>. 조건에 쓰이지 않지만 그 화면에서만 함께 보여야 하는 지표다.
     *
     * <p>MUST NOT: {@code defaultColumn}으로 띄우지 않는다. 그것은 <b>전역</b>이라 모든 화면이
     * 시끄러워진다.
     *
     * <p>MUST NOT: 더미 조건({@code ATR_PCT_14 >= -999})으로 대신하지 않는다. 조건 내역 화면에
     * 아무 뜻 없는 줄이 남고, 사용자는 그것을 걸러내는 기준으로 읽는다.
     *
     * <p>MUST NOT: 이미 {@code defaultColumn = true}인 지표를 여기 넣지 않는다. <b>아무 일도
     * 일어나지 않는다</b> — {@code ScreeningResponseAssembler}가 이 목록을 합친 <b>직후에</b>
     * 기본 컬럼을 넣으므로 결과가 같다. {@code PER}·{@code PBR}·{@code ROE}·{@code DEBT_RATIO}
     * 넷이 그렇고, 넣어도 어떤 테스트도 깨지지 않아 <b>했다고 믿게 된다.</b>
     */
    public static List<String> extraColumns(String presetCode) {
        if (BUFFETT_QUALITY_KR.equals(presetCode)) {
            // 버핏 프리셋의 가격 조건 셋은 각각 다른 것에 견준다 — 잉여현금흐름 수익률은
            // 절대 기준, PER 업종 백분위는 같은 업종 안, 유보 대비 시가는 자기 이익잉여금이다.
            // 「시장 전체에 견줘 싼가」는 어느 조건도 묻지 않으며, 그것이 설계다(ADR-0068) —
            // 버핏의 안전마진은 시장 평균이 아니라 내재가치 대비이고, 유니버스 백분위를
            // 조건으로 넣으면 업종별 밸류에이션 수준이라는 혼란 요인이 도로 들어온다.
            //
            // 그러나 「묻지 않는 것」과 「보여 주지 않는 것」은 다르다. 사용자가 통과 종목을
            // 시장과 나란히 놓고 볼 수 없으면, 이 화면이 우량주를 뽑는 것인지 싼 주식을 뽑는
            // 것인지 화면만 봐서는 갈리지 않는다. 그래서 컬럼으로만 띄운다 — 순위에 영향을
            // 주지 않고 결과에서 아무도 빼지 않는다.
            return List.of(pctl(PerCalculator.CODE), pctl(PbrCalculator.CODE));
        }
        return List.of();
    }

    // ── 1. 저평가 가치주 ──────────────────────────────────────────────────

    public static final String VALUE_KR = "VALUE_KR";

    /**
     * 저평가 가치주 — Piotroski F-Score(2000)와 Sloan 발생액(1996), Novy-Marx 총이익성(2013).
     *
     * <p>F-Score의 핵심은 <b>싼 종목 안에서 재무 건전성으로 가르는 것</b>이다. 싸다는 것만으로는
     * 가치함정과 구분되지 않는다.
     *
     * <p><b>원문 그대로</b>: {@code ROA >= 0}(F-Score ①), {@code ACCRUAL_RATIO <= 0}
     * (F-Score ④ — 영업현금흐름이 순이익보다 많은가), {@code SHARES_GROWTH_3Y <= 0}
     * (F-Score ⑦ 주식 미발행).
     * <br><b>국내 사정으로 바꾼 것</b>: 수익성 축을 {@code ROE}가 아니라 총이익성(GP/A)으로
     * 둔다 — 한국 시장에서 수익성 이상현상의 재현율이 5%인 반면(2000–2019, 148개 중) 총이익성은
     * 별개 연구에서 유의하게 나왔다.
     * <br><b>원문에 없는데 더한 것</b>: {@code DEBT_RATIO <= 150}(레버리지 가드),
     * 200일선 10% 아래까지 허용하는 추세 조건(가치함정 방어).
     *
     * <p>MUST NOT: F-Score를 옮겼다고 적지 않는다. <b>아홉 중 셋만</b> 옮겼고 나머지는
     * 전년 대비 변화(ΔROA·Δ레버리지·Δ유동비율·Δ자산회전율·Δ매출총이익률)라 지표가 다섯 더
     * 필요하다. 그 결핍은 {@code description}에 있다.
     */
    private static ScreenPreset valueKr() {
        return new ScreenPreset(
                VALUE_KR,
                "저평가 가치주",
                "이익·자산 대비 주가가 낮으면서 재무 건전성을 갖춘 종목을 점수로 줄 세운다. "
                        + "업종별 편차를 피하려 절대 PER이 아니라 유니버스 내 상대 순위를 쓴다. "
                        + "재무 건전성은 Piotroski가 제안한 아홉 항목 중 셋만 옮겼다 — "
                        + "총자산이익률이 양수인가, 영업활동현금흐름이 순이익보다 많은가, "
                        + "주식수가 늘지 않았는가. 나머지 여섯은 전년 대비 변화라 아직 지표가 없다. "
                        + "발생액 비율은 연간 재무만 쓰므로 1년에 한 번 바뀌고, 같은 화면의 "
                        + "총자산이익률은 분기 기준이라 두 값의 기준 시점이 다르다. "
                        + "총자산이익률 조건은 편입 종목의 80%가 통과해 순위를 크게 가르지 않는다 — "
                        + "적자 기업을 걸러내는 몫이다. "
                        + "총이익성은 매출원가 개념이 없는 업종(게임·바이오·금융·통신·인터넷)에서 "
                        + "값이 아예 없어, 그 종목들은 이 조건에서 점수를 얻지 못한다.",
                EvaluationMode.SCORING,
                null,
                ConditionGroup.and(List.of(
                        // 하위 30% — PER은 낮은 쪽이 좋으므로 <=
                        weighted(pctl(PerCalculator.CODE), ComparisonOperator.LTE, "0.30", "3"),
                        // 하위 30% — PBR도 낮은 쪽이 좋으므로 <=
                        weighted(pctl(PbrCalculator.CODE), ComparisonOperator.LTE, "0.30", "3"),
                        // 총이익성. 가치와 상보적인 축이며 업종에 따라 원본이 아예 없다.
                        weighted(pctl(GrossProfitToAssetsCalculator.CODE),
                                ComparisonOperator.GTE, "0.50", "2"),
                        // 이익의 질. 음수면 벌어들인 이익보다 들어온 현금이 많다는 뜻이다.
                        weighted(AccrualRatioCalculator.CODE, ComparisonOperator.LTE, "0", "2"),
                        // 가치함정 방어: 최소 수익성. 부호 조건이라 변별력은 낮다.
                        weighted(RoaCalculator.CODE, ComparisonOperator.GTE, "0", "2"),
                        // 주식 미발행. 자사주를 소각한 기업은 음수다.
                        weighted(SharesGrowth3yCalculator.CODE, ComparisonOperator.LTE, "0", "1"),
                        // 부채로 부풀린 수익성 배제. 부채비율은 낮은 쪽이 좋으므로 <=
                        weighted(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "150", "1"),
                        // 추세가 완전히 무너지지는 않았을 것 — 200일선 10% 아래까지 허용
                        IndicatorCondition.of(
                                AdjCloseCalculator.CODE, ComparisonOperator.GTE,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE, "0.90"))
                                .withWeight(new BigDecimal("1")))));
    }

    // ── 2. 린치 고속성장주 ────────────────────────────────────────────────

    public static final String LYNCH_FAST_GROWER_KR = "LYNCH_FAST_GROWER_KR";

    /**
     * 린치 고속성장주 — {@code docs/external/피터린치-방법론.md} §7 복사용 스크린의 이식.
     *
     * <p>원저는 Peter Lynch, <i>One Up on Wall Street</i>(Ch.10·13·15)와 AAII Lynch Screen이다.
     * 저장소의 문서는 그것을 <b>TradingView 문법으로 옮긴 이식본</b>이며 조건의 출처로만 쓴다
     * (ADR-0066). MUST NOT: 그 문서를 프리셋 조건셋의 정본이라 적지 않는다.
     *
     * <p><b>원문 그대로</b>: {@code PEG <= 1.0}(3.3 — 린치의 판별 도구이자 그가 "마지막까지
     * 고정"이라 한 둘 중 하나), {@code PER <= 20}(3.2), {@code REVENUE_GROWTH > 15}(§7),
     * {@code RETURN_52W < 100}(5.1 뜨거운 종목 배제), {@code SHARES_GROWTH_3Y <= 0}(4.5).
     * <br><b>원문의 밴드를 다른 축으로 옮긴 것</b>: {@code EPS_CAGR_5Y 20~50}이 <b>유형 분류</b>이자
     * 이 프리셋의 필수 게이트다. 하한 20은 §2.3, 상한 50은 §3.17에서 왔는데 <b>§2.3의 축은
     * {@code EPS Diluted Growth (TTM YoY)}이고 5년 CAGR이 아니다.</b> 축을 옮기면 모집단이
     * 달라지므로 「원문 그대로」에 넣지 않는다(ADR-0070). 옮긴 근거는 실측이다 — TTM YoY는
     * 사분위가 −16.7~79.4로 넓어 <b>밴드가 잡음보다 좁고</b>, 린치의 「연 20~25% 성장」은 다년
     * 지속률이지 TTM이 아니다. ρ(TTM, 5년CAGR) = 0.229로 둘은 사실상 다른 축이다.
     * <br><b>원문에 있는데 뺀 것</b>: {@code PEG_PCTL}. {@code PEG}와 순위상관 <b>0.996</b>이라
     * 배점 최대 가중치가 게이트와 같은 축을 두 번 세고 있었다(ADR-0068의 잣대를 그대로 적용).
     * <br><b>자리를 옮긴 것</b>: {@code PEG}가 필수 게이트에서 <b>배점</b>으로 내려왔다. 린치의
     * 판별 도구인 것은 맞지만 <b>가격은 문이 아니라 저울이다</b>(ADR-0070) — 게이트로 둘 때
     * 자르는 일의 <b>74%가 임계값이 아니라 결측 배제</b>였다(대형 87 → PEG값 55 → 통과 44).
     * 적자·역성장 배제는 이제 분류 게이트가 직접 한다.
     * <br><b>국내 분포로 재보정한 것</b>: {@code DEBT_RATIO <= 80}(§8 한국 조정값 표). 원문 D/E
     * 0.33은 국내에서 23%만 통과해 제조업 중심 구조에 과하고, 0.8이 절반을 가른다(174/346).
     * 유동성 하한은 {@code SWING_TREND_KR}의 100억이 아니라 <b>50억</b>이다 — 아래 참조.
     * <br><b>원문에 없는데 더한 것</b>: {@code PER_SECTOR_PCTL}·{@code DEBT_RATIO_SECTOR_REL}
     * (3.2·3.9의 업종 상대 요구를 실제 업종 모집단으로 옮긴 것), {@code ACCRUAL_RATIO},
     * {@code OPERATING_MARGIN}(3.18 세전이익률의 대용), {@code INVENTORY_GROWTH_GAP}(3.14 —
     * ADR-0069가 원자료만 적재해 둔 자리이고 이 프리셋이 그 소비자다).
     * <br><b>원문보다 약하게 옮긴 것</b>: {@code FCF_MARGIN > 0}. §3.13의 1차 조건은
     * {@code Free Cash Flow Yield > 5}이고 마진은 보조인데, 밸류에이션 계열을 쓰면 성패 기준과
     * 축이 겹쳐 부호 조건으로 옮겼다.
     *
     * <p><b>{@code EPS_CAGR_5Y}를 문에 세운 대가가 있다.</b> 값이 있는 종목이 170/347이라
     * 절반이 <b>사업 성적이 아니라 데이터 사유로</b> 빠진다. 배제 사유는 셋이고 뭉뚱그리면 안
     * 된다 — ① 상장 5년 미만 ② 5년 전이 적자 ③ <b>미보정 기업행위</b>(1,261봉 창, ADR-0055,
     * 현재 6종목). ADR-0066이 바로 이 이유로 이 지표를 필수에서 점수로 내렸고 ADR-0070이 그
     * 결정을 뒤집었다 — <b>분류가 문에 없으면 통과 집합의 66%가 급반등주가 되기 때문</b>이다.
     *
     * <p><b>유동성 하한을 50억으로 내린 이유</b> — 시총 중앙값 이하 부분집합(173종목)에서
     * 거래대금 p25가 53억이라 {@code >= 100억}은 <b>정확히 절반만 남긴다.</b> 그것은 유동성
     * 하한이 아니라 <b>두 번째 규모 필터</b>이고, 시총 상한과 같은 방향으로 겹쳐 게이트
     * 교집합을 7종목까지 떨어뜨렸다. 대형주 프리셋의 값을 그대로 승계하면 안 되는 자리다.
     *
     * <p><b>{@code PEG}를 필수로 두는 것이 이 프리셋의 성격을 정한다.</b> 값이 있는 종목이
     * 163/347(47%)이므로 유니버스의 절반 이상이 이 게이트에서 탈락한다. 그것을
     * {@code OCF_SUM_5Y}를 점수로 내린 이유("현금흐름이 나빠서가 아니라 이력이 짧아서")와
     * <b>같이 읽지 않는다</b> — PEG가 없다는 것은 적자이거나 이익이 역성장한다는 뜻이고,
     * 그것은 린치가 실제로 배제하려는 종목이다.
     *
     * <p>MUST NOT: §6의 「흑자 확인」을 게이트로 더하지 않는다. {@code PER}이 적자에 값을 내지
     * 않으므로 {@code PEG}를 필수로 건 순간 흑자가 이미 강제된다 — 실측으로
     * {@code EPS_TTM > 0}인 종목 수와 {@code PER} 값이 있는 종목 수가 269로 정확히 같다.
     * 더하면 통과율 100%짜리 게이트가 하나 생긴다.
     *
     * <p>MUST NOT: 이 프리셋을 「텐배거를 찾는다」로 설명하지 않는다. 린치의 고속성장주는
     * <b>소형주</b>에서 나오는데 우리 유니버스 하한이 5,100억이다. "중소형"은 유니버스 내
     * 상대 규모로 치환한 것이지 린치가 말한 규모가 아니다.
     */
    private static ScreenPreset lynchFastGrowerKr() {
        return new ScreenPreset(
                LYNCH_FAST_GROWER_KR,
                "린치 고속성장주",
                "이익이 빠르게 느는데 그 속도에 견줘 주가가 싼 종목을 점수로 줄 세운다. "
                        + "판별 도구는 PEG다 — 주가수익비율을 이익 성장률로 나눈 값이며, "
                        + "1보다 작으면 성장 속도만큼 값이 매겨지지 않았다는 뜻이다. "
                        + "이익이 줄고 있는 종목은 PEG 값이 아예 없어 결과에서 빠진다. "
                        + "편입 종목의 절반 남짓(163/347)에만 값이 있으며, 그것은 적자이거나 "
                        + "이익이 역성장하는 종목을 이 방식이 의도적으로 배제하기 때문이다. "
                        + "시가총액 상한을 두어 이미 큰 회사는 빼고, 1년에 두 배 넘게 오른 "
                        + "종목도 뺀다. "
                        + "5년 주당순이익 성장률은 필수가 아니라 점수 조건이다 — 다섯 해치 "
                        + "재무가 있어야 계산되어, 필수로 걸면 성장이 과해서가 아니라 이력이 "
                        + "짧아서 빠지는 종목이 생기기 때문이다. "
                        + "옮기지 못한 조건이 많다. 배당 관련 셋(배당수익률·배당성향·연속 배당)은 "
                        + "배당 데이터가 없어 통째로 빠졌고, 순부채/EBITDA는 감가상각비와 "
                        + "총차입금을 소스가 주지 않아 빠졌다. 잉여현금흐름은 계산할 수 있게 "
                        + "됐고 이 프리셋도 쓴다 — 발생액 비율이 그 자리를 대신한다고 적어 "
                        + "두었으나 둘의 순위상관이 -0.18이라 서로를 대신하지 않는다. "
                        + "재고와 매출의 증가 속도 비교도 넣었다. 재고가 매출보다 빨리 늘면 "
                        + "팔리지 않은 물건이 쌓이는 중이고 손익계산서에는 아직 안 나타난다 — "
                        + "편입 종목 284개에 값이 있다. "
                        + "세전이익률은 영업이익률로 대신했다 — 다른 값이다. "
                        + "금융·부동산 업종을 제외하지 못한다. 다만 저평가와 부채를 같은 업종 "
                        + "안에서 견주므로 금융업은 금융업끼리 비교된다. "
                        + "주식수 조건은 자사주 매입을 보지 못한다 — 상장주식수가 줄어야 잡히는데 "
                        + "국내에서 자사주는 소각해야 줄어든다. "
                        + "업종 표본이 5종목에 못 미치면 업종 상대 조건을 판정할 수 없고, "
                        + "적자라 PER이 없는 종목도 그 조건에서 점수를 얻지 못한다 — "
                        + "둘을 합쳐 132종목이 그렇다. "
                        + "린치의 고속성장주는 본래 소형주에서 찾는 것인데 편입 종목의 하한이 "
                        + "5,100억원이라 '중소형'은 이 목록 안에서의 상대 규모다.",
                EvaluationMode.SCORING,
                null,
                ConditionGroup.and(List.of(
                        // ── 필수 게이트 ──
                        // ★ 유형 분류. 이 프리셋을 「고속성장주」라 부를 수 있게 하는 조건이다.
                        // 하한 20은 §2.3, 상한 50은 §3.17 — 둘 다 원문이나 §2.3은 TTM YoY 축이라
                        // 밴드를 5년 CAGR로 옮긴 것이다(ADR-0070). 88 → 7종목.
                        requiredBetween(EpsCagr5yCalculator.CODE, "20", "50"),
                        // 2.3 "중소형"의 유니버스 내 치환. 시총 중앙값이다.
                        required(MarketCapCalculator.CODE, ComparisonOperator.LTE,
                                "2616000000000"),
                        // §6 유동성. 100억을 승계하지 않는 이유는 위 javadoc에 있다.
                        required(Turnover60Calculator.CODE, ComparisonOperator.GTE, "5000000000"),
                        // 5.1 가장 인기 있는 종목은 사지 않는다.
                        required(Return52wCalculator.CODE, ComparisonOperator.LT, "100"),

                        // ── 점수 ──
                        // 3.3 린치의 판별 도구. 가격은 문이 아니라 저울이다(ADR-0070) — 게이트로
                        // 두면 자르는 일의 74%가 임계값이 아니라 결측 배제였다. PEG_PCTL은
                        // PEG와 순위상관 0.996이라 뺐다. 새 게이트축과는 ρ −0.010으로 직교한다.
                        weighted(PegCalculator.CODE, ComparisonOperator.LTE, "1.0", "3"),
                        // 3.2 업종 전체의 PER과 비교해서 봐야 한다 — 원문이 요구한 비교다.
                        weighted(sectorPctl(PerCalculator.CODE),
                                ComparisonOperator.LTE, "0.50", "2"),
                        weighted(PerCalculator.CODE, ComparisonOperator.LTE, "20", "2"),
                        weighted(RevenueGrowthCalculator.CODE, ComparisonOperator.GT, "15", "2"),
                        weightedBetween(EpsGrowthTtmYoyCalculator.CODE, "10", "30", "2"),
                        weighted(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "80", "2"),
                        // 3.9 AAII 원문이 요구한 업종 상대 비교다. 1.0이 업종 중앙값이다.
                        weighted(sectorRel(DebtRatioCalculator.CODE),
                                ComparisonOperator.LTE, "1.0", "1"),
                        // 3.5 자기 5년 PER 중앙값보다 낮은가. 원본 명세가 구현 불가로 둔 조건이다.
                        IndicatorCondition.of(
                                PerCalculator.CODE, ComparisonOperator.LT,
                                ConditionOperand.IndicatorRef.of(PerMedian5yCalculator.CODE))
                                .withWeight(new BigDecimal("1")),
                        weighted(OperatingMarginCalculator.CODE, ComparisonOperator.GT, "10", "1"),
                        // 3.13 린치는 이익보다 현금흐름을 신뢰했다. 발생액이 이 자리를 지킨다고
                        // 적어 두었으나 ρ(ACCRUAL_RATIO, FCF_MARGIN) = −0.184로 지키지 않는다 —
                        // 둘 다 둔다. 원문 1차 조건은 FCF 수익률이라 약하게 옮긴 것이다.
                        weighted(FcfMarginCalculator.CODE, ComparisonOperator.GT, "0", "2"),
                        weighted(AccrualRatioCalculator.CODE, ComparisonOperator.LTE, "0", "1"),
                        // 3.14 재고가 매출보다 빨리 늘면 팔리지 않은 것이 쌓이는 중이다.
                        // 커버 284/347.
                        weighted(InventoryGrowthGapCalculator.CODE,
                                ComparisonOperator.LTE, "0", "1"),
                        weighted(SharesGrowth3yCalculator.CODE, ComparisonOperator.LTE, "0", "1"))));
    }

    // ── 3. 린치 대형우량주 ────────────────────────────────────────────────

    public static final String LYNCH_STALWART_KR = "LYNCH_STALWART_KR";

    /**
     * 린치 대형우량주 — 같은 문서 2.2의 이식. 여섯 유형 중 「대형이면서 여전히 성장하는 회사」다.
     *
     * <p>{@link #lynchFastGrowerKr()}와 <b>시가총액 방향이 반대</b>라 실제로 다른 종목을 낸다
     * (유니버스 시총 0.51조~1,566.8조, p25 1.43조 · p50 2.62조 · p75 6.77조).
     *
     * <p><b>원문 그대로</b>: {@code MARKET_CAP} 대형, {@code EPS_GROWTH_TTM_YOY 10~20},
     * {@code ROE > 12}, {@code PEG <= 1.5}, {@code DEBT_RATIO <= 60}(D/E 0.6).
     * <br><b>원문의 밴드를 다른 축으로 옮긴 것</b>: {@code EPS_CAGR_5Y 10~20}이 <b>유형 분류</b>
     * 이자 필수 게이트다. 2.2의 밴드를 그대로 썼지만 <b>원문의 축은 TTM YoY다</b> — 그 축으로
     * 걸면 대형 87종목 중 6이 남고 질 게이트를 하나만 더해도 0이 된다(ADR-0070).
     * <br><b>국내 분포로 재보정한 것</b>: "대형"의 경계를 유니버스 상위 25%(6.77조)로 둔다 —
     * 절대 금액으로는 시장마다 뜻이 달라진다.
     * <br><b>자리를 옮긴 것</b>: {@code PEG <= 1.5}가 게이트에서 배점으로 내려왔다(ADR-0070).
     * <br><b>원문에 있는데 뺀 것</b>: {@code TURNOVER_60 >= 100억}. 대형 87종목 중 <b>0종목</b>을
     * 자른다 — 게이트가 아니라 상수이고, 대형 게이트가 유동성을 이미 함의한다.
     * <br><b>원문에 없는데 더한 것</b>: {@code OPM_MIN_5Y > 0}(<b>유일한 질 게이트</b>),
     * {@code PROFITABLE_QUARTERS_4}(4.1 이익 성장의 일관성), {@code ACCRUAL_RATIO},
     * {@code SHARES_GROWTH_3Y}, {@code PER_SECTOR_PCTL}, {@code FCF_MARGIN}(3.13),
     * {@code INVENTORY_GROWTH_GAP}(3.14).
     *
     * <p><b>질 게이트를 원문에서 가져오지 못했다.</b> §2.2의 질 조항은 {@code ROE > 12}와
     * {@code D/E 0~0.6} 둘인데 대형+CAGR 11종목에 걸면 <b>각각 1종목</b>만 남아 게이트로 쓸 수
     * 없다. 그래서 「나쁜 해에도 영업흑자인가」로 대신하고 원문 둘은 저울에 남겼다.
     * MUST NOT: 이것을 원문 조항이라 읽지 않는다 — <b>부호 조건이라 변별력도 낮다</b>(통과
     * 9종목의 값이 0.69~11.66).
     *
     * <p><b>부채는 이 배치로 고쳐지지 않는다.</b> 통과 집합의 부채비율 중앙값이 149.5 → 117.6일
     * 뿐 유니버스 77.9의 <b>1.5배</b>이고, 9종목 중 원문 {@code D/E <= 0.6}을 충족하는 것은
     * 하나다. <b>지주회사도 못 가른다</b> — §8이 금융·부동산과 함께 지주회사 제외를 명시하는데
     * 통과 9종목 중 둘(GS·LS)이 KSIC 64992다.
     *
     * <p><b>{@code ROE}를 쓰는 것에 단서가 붙는다.</b> ADR-0061이 「한국거래소 2000-01~2019-06
     * 표본에서 수익성 이상현상의 재현율이 5%」를 근거로 {@code ROE}를 조건에서 뺐고, 지금
     * {@code ROE}·{@code ROE_PCTL}을 쓰는 프리셋이 하나도 없다. 여기서는 <b>린치 2.2 원문이
     * 명시하므로 원문 그대로 옮기되 그 재현율을 {@code description}에 함께 적는다.</b>
     * MUST NOT: 이것을 「수익성 축이 되살아났다」로 읽지 않는다 — 되살린 것이 아니라
     * 원문을 옮긴 것이고, 그 원문이 감사된 근거를 갖는다는 뜻도 아니다.
     */
    private static ScreenPreset lynchStalwartKr() {
        return new ScreenPreset(
                LYNCH_STALWART_KR,
                "린치 대형우량주",
                "크면서도 아직 성장이 남아 있는 회사를 점수로 줄 세운다. "
                        + "고속성장주와 시가총액 조건이 반대라 서로 다른 종목이 나온다 — "
                        + "이쪽은 편입 종목 중 큰 편에 드는 회사만 본다. "
                        + "성장 구간을 10~20%로 좁게 잡는 것은 의도다. 그보다 빠르면 대형우량주가 "
                        + "아니고, 그런 종목은 고속성장주 쪽에서 다룬다. "
                        + "자기자본이익률 조건은 원문에 있어 그대로 옮겼다. 다만 한국 시장을 "
                        + "2000년부터 2019년까지 본 연구에서 수익성 지표로 초과수익을 설명하려는 "
                        + "시도의 재현율이 5%에 그쳤다 — 이 조건을 근거로 수익률을 기대하지 않는다. "
                        + "PEG 값이 없는 종목은 결과에서 빠진다. 적자이거나 이익이 줄고 있다는 뜻이다. "
                        + "옮기지 못한 조건이 있다. 배당 관련 셋은 배당 데이터가 없어 빠졌고, "
                        + "순부채/EBITDA는 감가상각비와 총차입금을 소스가 주지 않아 빠졌다. "
                        + "잉여현금흐름과 재고-매출 속도 차를 조건에 넣었다. "
                        + "세전이익률은 이 프리셋에 넣지 않았다. "
                        + "금융·부동산 업종을 제외하지 못한다 — 저평가를 같은 업종 안에서 견주는 "
                        + "것으로 일부만 대신한다. "
                        + "주식수 조건은 자사주 매입이 아니라 상장주식수 감소를 본다.",
                EvaluationMode.SCORING,
                null,
                ConditionGroup.and(List.of(
                        // ── 필수 게이트 ──
                        // "대형"의 경계. 유니버스 상위 25%다.
                        required(MarketCapCalculator.CODE, ComparisonOperator.GTE,
                                "6773000000000"),
                        // ★ 유형 분류. 2.2의 밴드를 5년 CAGR로 옮겼다(ADR-0070). 54 → 9종목.
                        requiredBetween(EpsCagr5yCalculator.CODE, "10", "20"),
                        // ★ 질. 「나쁜 해에도 영업흑자인가」 — 대형우량주가 약속하는 성질이다.
                        // 원문에 없는데 더한 것이다: §2.2의 질 조항 둘(ROE > 12 · D/E 0~0.6)은
                        // 대형+CAGR 11종목에 걸면 각각 1종목만 남아 게이트로 쓸 수 없다.
                        // 부호 조건이라 변별력은 낮다(통과 9종목이 0.69~11.66). 11 → 9종목.
                        required(OperatingMarginMin5yCalculator.CODE, ComparisonOperator.GT, "0"),
                        // TURNOVER_60을 뺀다 — 대형 87종목 중 0종목을 자른다. 게이트가 아니라
                        // 상수이고, 대형 게이트가 유동성을 이미 함의한다(ADR-0070).

                        // ── 점수 ──
                        // 2.2 원문. 고속성장주보다 느슨하다. 가격은 저울이다(ADR-0070).
                        weighted(PegCalculator.CODE, ComparisonOperator.LTE, "1.5", "3"),
                        weightedBetween(EpsGrowthTtmYoyCalculator.CODE, "10", "20", "3"),
                        weighted(RoeCalculator.CODE, ComparisonOperator.GT, "12", "3"),
                        weighted(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "60", "2"),
                        weighted(sectorPctl(PerCalculator.CODE),
                                ComparisonOperator.LTE, "0.50", "2"),
                        // 4.1 이익 성장의 일관성. 넷 중 넷이 흑자인가.
                        weighted(ProfitableQuarters4Calculator.CODE,
                                ComparisonOperator.GTE, "4", "1"),
                        // 3.13 이익보다 현금흐름. 발생액과 ρ −0.184라 서로를 대신하지 않는다.
                        weighted(FcfMarginCalculator.CODE, ComparisonOperator.GT, "0", "2"),
                        weighted(AccrualRatioCalculator.CODE, ComparisonOperator.LTE, "0", "1"),
                        // 3.14 재고-매출 속도 차.
                        weighted(InventoryGrowthGapCalculator.CODE,
                                ComparisonOperator.LTE, "0", "1"),
                        weighted(SharesGrowth3yCalculator.CODE, ComparisonOperator.LTE, "0", "1"))));
    }
    // ── 4. 추세 상승 ──────────────────────────────────────────────────────

    public static final String MOMENTUM_KR = "MOMENTUM_KR";

    /**
     * 추세 상승 — Minervini 트렌드 템플릿 여덟 조건의 이식.
     *
     * <p>원문(『Think &amp; Trade Like a Champion』)이 완전히 기계적이라 1대1로 옮긴다.
     * <b>여덟 조건을 그대로 옮기는 것이 목적</b>이므로 원문에 없는 조건을 더하지 않는다 —
     * 더하는 순간 이것을 「트렌드 템플릿」이라 부를 수 없다.
     *
     * <p><b>치환 둘</b>: 50일선이 없어 {@code SMA_60}을 쓰고(ADR-0039 T-03과 같은 치환),
     * 상대강도 등급은 지수 대비 52주 초과수익률의 유니버스 백분위로 대신한다.
     * <b>MUST NOT: 후자를 IBD의 RS 등급이라 부르지 않는다</b> — IBD는 분기별 2-1-1-1 가중을
     * 쓰고 우리는 단일 구간 수익률을 쓴다.
     *
     * <p>{@code SMA_60 > SMA_200}은 다른 두 조건에서 추이律로 자동 성립하는 <b>중복이지만
     * 원문 그대로 남긴다</b> — 원문 이탈이 조건 한 칸보다 비싸다.
     *
     * <p>MUST NOT: 이 프리셋을 「알파의 원천」으로 설명하지 않는다. 한국 시장에서 모멘텀
     * 이상현상은 코스피만 보면 재현율이 20%로 떨어지고 우리 유니버스는 대형주뿐이다.
     * 재는 것은 <b>지금 어느 국면에 있는가</b>이지 앞으로 오를 종목이 아니다.
     */
    private static ScreenPreset momentumKr() {
        return new ScreenPreset(
                MOMENTUM_KR,
                "추세 상승",
                "장기 상승 추세가 확인된 종목만 남긴다. 널리 쓰이는 여덟 조건을 그대로 옮겼다 — "
                        + "주가가 150일선·200일선 위에 있고, 150일선이 200일선 위에 있으며, "
                        + "200일선 자체가 한 달 이상 오르고 있고, 52주 최저가보다 30% 이상 올라와 "
                        + "있으면서 52주 최고가에서 25% 이내에 있고, 시장 지수보다 1년 수익률이 "
                        + "좋은 상위 30%에 든다. 점수가 아니라 충족/불충족으로 걸러낸다. "
                        + "원문의 50일선은 계산기가 없어 60일선으로 대신했고, 상대강도 등급은 "
                        + "지수 대비 52주 초과수익률의 순위로 대신했다 — 분기별 가중을 쓰는 "
                        + "미국식 등급과는 계산이 다르다. "
                        + "재무 조건이 하나도 없다 — 원문이 추세만 보기 때문이며, 그래서 "
                        + "적자 기업도 추세가 살아 있으면 결과에 나온다. 개편 전에는 "
                        + "자기자본이익률이 양수일 것을 함께 걸었으나 원문에 없어 뺐다. "
                        + "이 조건들은 지금 어느 국면에 있는지를 보여줄 뿐 앞으로 오를 종목을 "
                        + "가려내지 않는다.",
                EvaluationMode.FILTER,
                null,
                ConditionGroup.and(List.of(
                        // ① 주가가 150일선·200일선 위 — 수정종가끼리 비교해야 분할에서 어긋나지 않는다
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma150Calculator.CODE)),
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE)),
                        // ② 150일선 > 200일선
                        IndicatorCondition.of(Sma150Calculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE)),
                        // ③ 200일선이 한 달 이상 상승
                        literal(Sma200Momentum21Calculator.CODE, ComparisonOperator.GT, "0"),
                        // ④ 50일선(→60일선) > 150·200일선
                        IndicatorCondition.of(Sma60Calculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma150Calculator.CODE)),
                        IndicatorCondition.of(Sma60Calculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE)),
                        // ⑤ 주가 > 50일선(→60일선)
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma60Calculator.CODE)),
                        // ⑥ 52주 최저가 대비 +30% 이상
                        literal(PctFrom52wLowCalculator.CODE, ComparisonOperator.GTE, "30"),
                        // ⑦ 52주 최고가 −25% 이내. 값은 항상 0 이하다.
                        literal(PctFrom52wHighCalculator.CODE, ComparisonOperator.GTE, "-25"),
                        // ⑧ 상대강도 상위 30%
                        literal(pctl(ExcessReturn52wCalculator.CODE),
                                ComparisonOperator.GTE, "0.70"))));
    }

    // ── 5. 중장기 스윙 ────────────────────────────────────────────────────

    public static final String SWING_TREND_KR = "SWING_TREND_KR";

    /**
     * 중장기 스윙 — 추세 게이트와 재무 생존을 함께 거는 게이트+점수 프리셋.
     *
     * <p>폐기한 {@code SWING_MIDTERM_KR}의 자리를 받는다(ADR-0061). 그 프리셋의 외부 명세는
     * 「추세는 그대로, 생존은 얇은」 쪽으로 기울었다고 스스로 판정했고(ADR-0039 총량 판정),
     * 여기서는 그 얇아진 축을 검증된 조건으로 메운다.
     *
     * <p><b>원문에서 되살린 것</b>: 2년 주간 베타(T-08)는 「시장 지수 시계열이 없다」는 이유로
     * 버려졌는데 지수는 ADR-0053으로 적재됐다. 업종 상대 부채비율(H-02)과 업종 상대
     * 영업이익률(S-02)도 되살린다.
     * <br><b>국내 분포로 재보정한 것</b>: 베타 임계는 원문 1.3이 아니라 <b>1.2</b>다 — 1.3은
     * 국내에서 82%가 통과해 게이트가 아니었고, 1.2가 상위 23%를 자른다.
     * <br><b>원문보다 약하게 옮긴 것</b>: 5년 누적 영업현금흐름(H-05)과 3년 주식수 증가율
     * (H-06)을 필수 게이트가 아니라 점수 조건에 둔다. 둘 다 값이 없는 종목이 많아
     * (283종목·314종목) 게이트로 걸면 <b>현금흐름이 나빠서가 아니라 이력이 짧아서</b>
     * 탈락하는 종목이 60~70개 생긴다.
     *
     * <p>{@code DISPARITY_200 <= 1.25}가 이 프리셋의 성격을 정한다 — 급등 종목이 구조적으로
     * 나오지 않으며 <b>그것은 부작용이 아니라 설계다.</b> {@code ADJ_CLOSE > SMA_200}과
     * 중복이 아니라 같은 값의 하한과 상한이다.
     */
    private static ScreenPreset swingTrendKr() {
        return new ScreenPreset(
                SWING_TREND_KR,
                "중장기 스윙",
                "3~6개월 보유를 전제로, 장기 추세가 살아 있으면서 아직 과열되지 않은 종목만 남긴 뒤 "
                        + "저평가·수익성·성장으로 줄 세운다. 필수 조건을 하나라도 채우지 못하면 "
                        + "결과에서 빠진다. 200일선 대비 이격도를 1.25로 제한하므로 최근 급등한 "
                        + "종목은 구조적으로 나오지 않는다 — 의도된 설계다. "
                        + "부채비율·저평가·수익성은 같은 업종 종목과 견준다 — 업종 표본이 5종목에 "
                        + "못 미치면 판정할 수 없어 그 종목은 결과에서 빠진다. "
                        + "2년 주간 베타는 104주치 주간 등락이 있어야 계산되며, 그만큼의 이력이 "
                        + "없는 종목은 결과에서 빠진다. "
                        + "옮기지 못한 조건이 있다 — 순부채/EBITDA와 이자보상배율(감가상각비·이자비용을 "
                        + "소스가 주지 않는다), 피인수 종목 제외(공시 소스가 없다), "
                        + "해자 지표 가운데 투하자본이익률(법인세비용이 적재돼 있지 않아 "
                        + "세후 기준으로 계산할 수 없다). "
                        + "5년 누적 영업현금흐름과 3년 주식수 증가율은 필수 조건이 아니라 점수 조건이다 — "
                        + "다섯 회계연도가 모두 있어야 판정할 수 있어, 필수로 걸면 상장한 지 얼마 되지 "
                        + "않은 종목이 현금흐름과 무관하게 빠지기 때문이다. "
                        + "Altman Z는 보험·금융서비스 종목에서 계산하지 않는다 — 자본구조가 달라 "
                        + "점수가 뜻을 잃기 때문이다. 다만 지주회사는 업종 코드가 금융업으로 오는 "
                        + "경우가 있어 코드만으로는 가려지지 않는다.",
                EvaluationMode.SCORING,
                null,
                ConditionGroup.and(List.of(
                        // ── 규모와 유동성 ──
                        required(MarketCapCalculator.CODE, ComparisonOperator.GTE,
                                "1000000000000"),
                        required(Turnover60Calculator.CODE, ComparisonOperator.GTE, "10000000000"),
                        required(TradingDays750Calculator.CODE, ComparisonOperator.GTE, "750"),

                        // ── 추세 국면 ──
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE))
                                .asRequired(),
                        IndicatorCondition.of(Sma150Calculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE))
                                .asRequired(),
                        IndicatorCondition.of(Sma60Calculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE))
                                .asRequired(),
                        required(Sma200Momentum21Calculator.CODE, ComparisonOperator.GT, "0"),
                        required(Sma200Slope60Calculator.CODE, ComparisonOperator.GT, "0"),
                        // 추격매수 차단. 이 한 줄이 이 프리셋의 성격을 정한다.
                        required(Disparity200Calculator.CODE, ComparisonOperator.LTE, "1.25"),
                        required(PctFrom52wLowCalculator.CODE, ComparisonOperator.GTE, "30"),
                        required(PctFrom52wHighCalculator.CODE, ComparisonOperator.GTE, "-25"),
                        required(AtrPct14Calculator.CODE, ComparisonOperator.LTE, "6"),
                        // 원문 1.3은 국내에서 82%가 통과해 게이트가 아니다. 1.2가 상위 23%를 자른다.
                        required(Beta104wCalculator.CODE, ComparisonOperator.LTE, "1.2"),

                        // ── 생존 ──
                        required(ProfitableQuarters4Calculator.CODE, ComparisonOperator.GTE, "3"),
                        required(sectorRel(DebtRatioCalculator.CODE), ComparisonOperator.LTE, "1.5"),
                        required(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "150"),
                        required(AltmanZCalculator.CODE, ComparisonOperator.GTE, "2.6"),

                        // ── 점수 ──
                        weighted(OperatingCashFlow5ySumCalculator.CODE,
                                ComparisonOperator.GT, "0", "1"),
                        weighted(SharesGrowth3yCalculator.CODE, ComparisonOperator.LTE, "5", "1"),
                        weighted(AccrualRatioCalculator.CODE, ComparisonOperator.LTE, "0", "1"),
                        weighted(sectorPctl(PerCalculator.CODE), ComparisonOperator.LTE, "0.50", "1"),
                        weighted(sectorPctl(OperatingMarginCalculator.CODE),
                                ComparisonOperator.GTE, "0.50", "1"),
                        weighted(EpsGrowthTtmYoyCalculator.CODE, ComparisonOperator.GTE, "15", "1"),
                        weighted(pctl(ExcessReturn52wCalculator.CODE),
                                ComparisonOperator.GTE, "0.70", "1"))));
    }

    // ── 6. 2국면 돌파 ─────────────────────────────────────────────────────

    public static final String STAGE2_BREAKOUT_KR = "STAGE2_BREAKOUT_KR";

    /**
     * 2국면 돌파 — Weinstein 국면 분석의 1국면→2국면 전환 정의.
     *
     * <p>원문은 셋이 <b>동시에</b> 성립해야 전환으로 본다 — 30주선 상향 돌파, 그 이동평균 자체의
     * 상승 전환, 그리고 거래량 확장이다. 거래량 없는 돌파는 흔히 실패한다는 것이 원문의 요지다.
     *
     * <p><b>국내 분포로 재보정한 것</b>: 거래량 확장 임계. 원문은 「평소의 2~3배」인데 국내에서
     * 2.0배는 상위 10% 미만이라 과도하다. <b>1.5배</b>가 상위 약 22%에 앉는다.
     * <br><b>원문에 없는데 더한 것</b>: {@code ADX_14 >= 20}(추세가 실재하는가),
     * {@code CMF_21 > 0}(거래량이 매수 쪽에 실렸는가), 52주 고점 10% 이내(돌파 확인).
     *
     * <p>돌파는 <b>그날 일어난 것</b>을 잡는 조건이라 결과 수가 날마다 크게 흔들린다.
     */
    private static ScreenPreset stage2BreakoutKr() {
        return new ScreenPreset(
                STAGE2_BREAKOUT_KR,
                "2국면 돌파",
                "바닥 다지기를 끝내고 상승 국면으로 넘어가는 신호가 한꺼번에 나온 종목을 남긴다. "
                        + "주가가 30주선(150일선) 위로 올라섰고, 그 이동평균 자체가 상승으로 돌아섰으며, "
                        + "거래량이 평소의 1.5배 이상 실렸고, 지수보다 1년 수익률이 좋은 상위 30%에 들며, "
                        + "52주 최고가에서 10% 이내에 있는 상태다. "
                        + "원문은 거래량을 평소의 2~3배로 보지만 국내에서 그 기준은 상위 10%에도 "
                        + "못 미쳐 1.5배로 낮췄다. "
                        + "돌파는 그날 일어난 일을 잡는 조건이라 결과 수가 날마다 크게 달라지며, "
                        + "0건인 날도 정상이다. 점수가 아니라 충족/불충족으로 걸러낸다.",
                EvaluationMode.FILTER,
                null,
                ConditionGroup.and(List.of(
                        // 30주선 상향 돌파
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma150Calculator.CODE)),
                        IndicatorCondition.of(Sma150Calculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE)),
                        // 이동평균 자체가 상승 전환
                        literal(Sma200Momentum21Calculator.CODE, ComparisonOperator.GT, "0"),
                        literal(MaAlignScoreCalculator.CODE, ComparisonOperator.GTE, "2"),
                        // 추세가 실재하는가. 방향이 아니라 유무를 잰다.
                        literal(Adx14Calculator.CODE, ComparisonOperator.GTE, "20"),
                        // 거래량 확장. 원문 2~3배를 국내 분포로 옮긴 값이다.
                        literal(VolumeRatio20Calculator.CODE, ComparisonOperator.GTE, "1.5"),
                        // 거래량이 매수 쪽에 실렸는가
                        literal(Cmf21Calculator.CODE, ComparisonOperator.GT, "0"),
                        // 지수 대비 상대강도
                        literal(pctl(ExcessReturn52wCalculator.CODE),
                                ComparisonOperator.GTE, "0.70"),
                        // 신고가 근접. 값은 항상 0 이하다.
                        literal(PctFrom52wHighCalculator.CODE, ComparisonOperator.GTE, "-10"))));
    }

    // ── 7. 상승추세 눌림목 ────────────────────────────────────────────────

    public static final String PULLBACK_UPTREND_KR = "PULLBACK_UPTREND_KR";

    /**
     * 상승추세 눌림목 — Connors의 평균회귀 규칙.
     *
     * <p>원문은 둘뿐이다 — <b>200일선 위에 있을 것</b>, 그리고 <b>RSI(2)가 10 미만일 것</b>.
     * 앞의 것이 이 전략의 유일한 구조적 보호 장치이고, 뒤의 것이 「단기 패닉」의 정의다.
     *
     * <p><b>원문 그대로</b>: 두 조건 다.
     * <br><b>원문에 없는데 더한 것</b>: 중기 추세 확인({@code SMA_60 > SMA_200},
     * {@code SMA_200_SLOPE_60 > 0}), 60일선 8% 이탈 배제(눌림목과 추세 붕괴를 가른다),
     * {@code CMF_21 >= -0.10}(분산 국면 배제).
     *
     * <p><b>{@code RSI_2}를 위해 지표를 새로 만들었다.</b> {@code RSI_14}로 대신하면 실측
     * 최솟값이 28이라 「10 미만」이 성립하는 날이 사실상 없고, 스토캐스틱으로 근사하면
     * 원문보다 훨씬 좁은 조건을 원문 이식이라 부르게 된다.
     *
     * <p><b>이 프리셋은 시장 국면에 가장 민감하다.</b> 강세장 한복판에서는 상승 추세와 극단
     * 과매도가 함께 나오는 종목이 드물어 0건이 정상이다. MUST NOT: 결과 수를 근거로 임계값을
     * 풀지 않는다 — 원인을 조건·데이터·국면으로 가른 뒤 사용자가 정한다.
     */
    private static ScreenPreset pullbackUptrendKr() {
        return new ScreenPreset(
                PULLBACK_UPTREND_KR,
                "상승추세 눌림목",
                "장기 상승 추세를 유지한 채 단기적으로만 급하게 밀린 종목을 남긴다. "
                        + "주가가 200일선 위에 있고 중기 추세도 상승인 상태에서, 2일 RSI가 10 미만으로 "
                        + "떨어졌으며, 60일선에서 8% 넘게 이탈하지 않았고, 자금이 빠져나가는 국면이 "
                        + "아닌 종목이다. "
                        + "2일 RSI는 이틀치만 보므로 0이나 100에 자주 붙는다 — 그것이 이 조건이 "
                        + "노리는 단기 극단이며, 반드시 추세 조건과 함께 읽는다. "
                        + "시장 국면에 가장 민감한 조건 묶음이라 강세장 한복판에서는 0건인 날이 "
                        + "흔하고, 그것은 조건이 잘못된 것이 아니다. "
                        + "점수가 아니라 충족/불충족으로 걸러낸다.",
                EvaluationMode.FILTER,
                null,
                ConditionGroup.and(List.of(
                        // 원문의 유일한 구조적 보호 장치
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE)),
                        IndicatorCondition.of(Sma60Calculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE)),
                        literal(Sma200Slope60Calculator.CODE, ComparisonOperator.GT, "0"),
                        // 원문 그대로. 근사가 아니다.
                        literal(Rsi2Calculator.CODE, ComparisonOperator.LT, "10"),
                        // 눌림목과 추세 이탈을 가른다
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.GTE,
                                ConditionOperand.IndicatorRef.of(Sma60Calculator.CODE, "0.92")),
                        // 분산 국면 배제
                        literal(Cmf21Calculator.CODE, ComparisonOperator.GTE, "-0.10"))));
    }

    // ── 8. 저변동 우량 ────────────────────────────────────────────────────

    public static final String LOWVOL_QUALITY_KR = "LOWVOL_QUALITY_KR";

    /**
     * 저변동 우량 — 저변동성 이상현상.
     *
     * <p>변동성이 낮은 종목이 높은 종목보다 위험 대비 수익이 낫다는 관측은 신흥국에서 더 강하게
     * 나타난다. 여기서는 변동성을 두 축으로 본다 — <b>절대 변동폭</b>({@code ATR_PCT_14})과
     * <b>시장 동조</b>({@code BETA_104W})다.
     *
     * <p><b>국내 분포로 재보정한 것</b>: 베타 임계 <b>0.40</b>. 국내 베타 중앙값이 0.748이라
     * 미국식 「1 미만이면 저변동」을 그대로 쓰면 60%가 걸려 <b>축이 아니라 상수</b>가 된다.
     * 두 변동성 축을 같은 분위(하위 약 30%)에 맞춰 배점 3끼리 대등하게 만든다.
     *
     * <p>MUST NOT: 추세 조건을 더하지 않는다. 저변동성 이상현상은 추세와 무관한 축이고,
     * 섞으면 두 효과를 구분할 수 없다.
     */
    private static ScreenPreset lowvolQualityKr() {
        return new ScreenPreset(
                LOWVOL_QUALITY_KR,
                "저변동 우량",
                "적게 흔들리면서 이익과 현금흐름이 꾸준한 종목을 점수로 줄 세운다. "
                        + "흔들림은 두 가지로 본다 — 하루 평균 변동폭이 작은가, 그리고 시장이 움직일 때 "
                        + "덜 따라 움직이는가. "
                        + "베타 기준을 0.4로 둔 것은 편입 종목의 베타 중앙값이 0.75 근처이기 때문이다 — "
                        + "지수를 소수의 대형주가 끌어서 그렇고, 1 미만을 저변동으로 보면 절반 이상이 "
                        + "걸려 기준 구실을 못 한다. "
                        + "2년 주간 베타는 104주치 이력이 있어야 계산되며, 그만큼의 이력이 없는 종목은 "
                        + "그 조건에서 점수를 얻지 못한다. "
                        + "추세 조건은 넣지 않았다 — 적게 흔들리는 것과 오르는 것은 다른 축이라 "
                        + "섞으면 어느 쪽으로 뽑힌 종목인지 알 수 없다.",
                EvaluationMode.SCORING,
                null,
                ConditionGroup.and(List.of(
                        // 절대 변동폭 하위 약 30%
                        weighted(AtrPct14Calculator.CODE, ComparisonOperator.LTE, "5.9", "3"),
                        // 시장 동조 하위 약 30%. 국내 중앙값이 0.75라 0.9로는 축이 되지 않는다.
                        weighted(Beta104wCalculator.CODE, ComparisonOperator.LTE, "0.40", "3"),
                        weighted(ProfitableQuarters4Calculator.CODE,
                                ComparisonOperator.GTE, "4", "2"),
                        weighted(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "100", "2"),
                        weighted(AccrualRatioCalculator.CODE, ComparisonOperator.LTE, "0", "1"),
                        weighted(RoaCalculator.CODE, ComparisonOperator.GTE, "0", "1"),
                        weighted(OperatingCashFlow5ySumCalculator.CODE,
                                ComparisonOperator.GT, "0", "1"),
                        weighted(Turnover60Calculator.CODE,
                                ComparisonOperator.GTE, "10000000000", "1"))));
    }

    // ── 조건 생성 헬퍼 ────────────────────────────────────────────────────

    private static IndicatorCondition required(String code, ComparisonOperator operator,
                                               String value) {
        return literal(code, operator, value).asRequired();
    }

    private static IndicatorCondition literal(String code, ComparisonOperator operator,
                                              String value) {
        return IndicatorCondition.of(code, operator, ConditionOperand.Literal.of(value));
    }

    private static IndicatorCondition weighted(String code, ComparisonOperator operator,
                                               String value, String weight) {
        return literal(code, operator, value).withWeight(new BigDecimal(weight));
    }

    /**
     * 필수 구간 조건. 린치의 <b>유형 분류</b>가 이 형태다 — 성장률이 어느 구간에 있는가가
     * 유형을 정하고, 그 판정이 문에 서야 한다(ADR-0070).
     *
     * <p>MUST NOT: 상한을 떼고 {@code required(..., GTE, ...)}로 바꾸지 않는다. 상한이 없으면
     * 「고속성장주」와 「대형우량주」의 구간이 겹쳐 <b>분류가 성립하지 않는다.</b>
     */
    private static IndicatorCondition requiredBetween(String code, String lower, String upper) {
        return IndicatorCondition.of(code, ComparisonOperator.BETWEEN,
                        ConditionOperand.Literal.between(
                                new BigDecimal(lower), new BigDecimal(upper)))
                .asRequired();
    }

    /**
     * 구간 조건. 위 셋이 전부 단일 값 전용이라 따로 둔다.
     *
     * <p>{@code BETWEEN}은 <b>양쪽을 자른다</b> — 린치의 성장률 조건이 상한을 갖는 것이 요점이고
     * (3.16·3.17), 하한만 거는 {@code GTE}로 바꾸면 「지속 불가능한 성장 배제」가 통째로 사라진다.
     *
     * <p>MUST NOT: 우변을 {@link ConditionOperand.IndicatorRef}로 바꾸지 않는다 —
     * {@code ScreenDefinitionValidator}가 저장 시점에 막고 번역기도 던진다.
     */
    private static IndicatorCondition weightedBetween(String code, String lower, String upper,
                                                      String weight) {
        return IndicatorCondition.of(code, ComparisonOperator.BETWEEN,
                        ConditionOperand.Literal.between(
                                new BigDecimal(lower), new BigDecimal(upper)))
                .withWeight(new BigDecimal(weight));
    }
}
