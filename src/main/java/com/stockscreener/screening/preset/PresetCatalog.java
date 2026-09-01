package com.stockscreener.screening.preset;

import com.stockscreener.indicator.PercentileSpec;
import com.stockscreener.indicator.SectorMetricSpec;
import com.stockscreener.indicator.calculator.AccrualRatioCalculator;
import com.stockscreener.indicator.calculator.Adx14Calculator;
import com.stockscreener.indicator.calculator.AdjCloseCalculator;
import com.stockscreener.indicator.calculator.AtrPct14Calculator;
import com.stockscreener.indicator.calculator.Beta104wCalculator;
import com.stockscreener.indicator.calculator.Cmf21Calculator;
import com.stockscreener.indicator.calculator.DebtRatioCalculator;
import com.stockscreener.indicator.calculator.EpsCagr5yCalculator;
import com.stockscreener.indicator.calculator.EpsGrowthTtmYoyCalculator;
import com.stockscreener.indicator.calculator.ExcessReturn52wCalculator;
import com.stockscreener.indicator.calculator.GrossMarginCalculator;
import com.stockscreener.indicator.calculator.GrossProfitToAssetsCalculator;
import com.stockscreener.indicator.calculator.MaAlignScoreCalculator;
import com.stockscreener.indicator.calculator.MacdVCalculator;
import com.stockscreener.indicator.calculator.MacdVHistCalculator;
import com.stockscreener.indicator.calculator.MacdVHistChange5Calculator;
import com.stockscreener.indicator.calculator.MacdVHistChangeWeeklyCalculator;
import com.stockscreener.indicator.calculator.MacdVWeeklyCalculator;
import com.stockscreener.indicator.calculator.MarketCapCalculator;
import com.stockscreener.indicator.calculator.ObvT13Calculator;
import com.stockscreener.indicator.calculator.OperatingCashFlow5ySumCalculator;
import com.stockscreener.indicator.calculator.OperatingMarginCalculator;
import com.stockscreener.indicator.calculator.PbrCalculator;
import com.stockscreener.indicator.calculator.PctFrom52wHighCalculator;
import com.stockscreener.indicator.calculator.PctFrom52wLowCalculator;
import com.stockscreener.indicator.calculator.PegCalculator;
import com.stockscreener.indicator.calculator.PbrDiscount5yCalculator;
import com.stockscreener.indicator.calculator.PerCalculator;
import com.stockscreener.indicator.calculator.PerDiscount5yCalculator;
import com.stockscreener.indicator.calculator.PsrDiscount5yCalculator;
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
import com.stockscreener.indicator.calculator.RoicMin3yCalculator;
import com.stockscreener.indicator.calculator.Rsi2Calculator;
import com.stockscreener.indicator.calculator.SharesGrowth3yCalculator;
import com.stockscreener.indicator.calculator.Sma150Calculator;
import com.stockscreener.indicator.calculator.Sma200Calculator;
import com.stockscreener.indicator.calculator.Sma200Momentum21Calculator;
import com.stockscreener.indicator.calculator.Sma200Slope60Calculator;
import com.stockscreener.indicator.calculator.Sma60Calculator;
import com.stockscreener.indicator.calculator.TurnoverRatio60Calculator;
import com.stockscreener.indicator.calculator.VolumeRatio20Calculator;
import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.IndicatorCondition;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * <p>IMPORTANT — <b>이 파일의 통과 수·커버리지·분위는 전부 2026-08-20 단면이다.</b> 하루에 한
 * 종목씩 움직이므로 <b>날짜 없이 인용하면 반드시 낡는다</b>(ADR-0070이 자기 수를 2026-08-13으로
 * 못박은 것과 같은 이유다). 다시 잴 때는 {@code max(trade_date)}가 아니라 <b>그 날짜를 고정</b>
 * 한다 — 그러지 않으면 시장이 아니라 재무 빈티지가 만든 차이를 시장 탓으로 읽는다.
 * <br><b>분모가 지표마다 다르다</b>: 게이트 통과율은 유니버스 347, 다년 지표는 값이 있는 종목
 * 수(예 {@code ROIC_MIN_3Y} 300, {@code DEBT_RATIO} 346)다. <b>분모를 함께 적지 않으면 같은
 * 개수가 다른 퍼센트로 읽힌다.</b>
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
 * <p>IMPORTANT — <b>MUST NOT: 국내 분포로 정한 임계값을 다른 시장의 프리셋에 옮기지 않는다.
 * 분위도 같다</b>(ADR-0072). 금액은 원화라 옮기면 즉시 틀린 것이 드러나지만 <b>분위는
 * 무차원이라 아무 증상이 없다</b> — 「상위 30%」는 어느 시장에서나 문장이 성립한다.
 * 경로가 실재한다: {@link ScreenPreset#universeCode()}가 대부분 {@code null}이고 실행 요청이
 * 그것을 덮어쓰므로, 코드명의 {@code _KR}은 아무것도 강제하지 않는다. 미국 유니버스가
 * 활성이 되면(v1.1, ADR-0010) 그 프리셋의 임계는 <b>미국 분포를 다시 재고</b> 정한다.
 *
 * <p>IMPORTANT — <b>MUST NOT: 백분위({@code *_PCTL})를 규모·유동성 하한 게이트로 쓰지
 * 않는다</b>(ADR-0072). 모집단이 유니버스별이라 한 임계가 코스피와 코스닥에서 다른 절대
 * 높이가 된다. 상대 조건(「업종 대비 싼가」)에는 그대로 쓴다 — 아래 {@code sectorPctl}이 그것이다.
 *
 * <p>지표 코드를 문자열이 아니라 계산기의 {@code CODE} 상수로 참조한다 — 오타가 기동 시점의
 * FK 위반이 아니라 <b>컴파일 오류</b>가 되고, 지표 코드를 바꾸면 여기가 함께 깨진다.
 */
@Configuration
public class PresetCatalog {

    /**
     * 이 절의 절대 임계가 나온 시장의 통화. KRW 하나가 코스피·코스닥 둘을 덮으므로
     * 시장이 늘어도 이 상수는 안 바뀐다(ADR-0099).
     */
    private static final String KRW = "KRW";

    /** 미국 임계는 국내 임계가 앉은 분위를 미국 분포에서 다시 잰 값이다. */
    private static final String USD = "USD";

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

    // ── 8. 버핏 우량주 ────────────────────────────────────────────────────

    public static final String BUFFETT_QUALITY = "BUFFETT_QUALITY";

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
     * 19종목/5%), {@code DEBT_RATIO <= 100}, {@code ROIC_MIN_3Y >= 10}(60/300, 20%).
     * 마지막 것은 <b>두 번 옮긴 값이다</b> — 원문 12(세후)를 세전 기저에서 15로 올렸고
     * (ADR-0067), 다시 단년 TTM에서 연간 3년 최저로 기저를 바꾸며 10으로 내렸다. 그 둘은
     * 방향이 반대이므로 <b>어느 한쪽만 알고 수를 읽으면 반드시 틀린다.</b>
     *
     * <p><b>MUST: 이 절의 절대 임계는 전부 국내 분포에서 나온 값이다.</b> 미국 프리셋을 만들
     * 때 이 메서드를 복사하면 임계가 그대로 따라가는데, <b>같은 수가 다른 시장에서 다른 위치를
     * 잰다</b> — 원문 값을 그대로 옮기면 안 되는 이유(ADR-0061)가 시장 사이에도 그대로 걸린다.
     * 프리셋 코드의 {@code _KR} 접미사가 구조를 갈라 두었으므로 <b>갈리지 않는 것은 수뿐이고,
     * 그것은 복사할 때 사람이 지켜야 한다.</b> 지표 계산기는 시장 중립이라 고칠 것이 없다.
     *
     * <p><b>ROIC가 게이트에 있는 것이 이 프리셋의 해자 축이다</b>(ADR-0068).
     * 2019년 주주서한이 <i>"영업에 투입된 순유형자본 대비 좋은 수익률"</i>을 <b>첫 번째</b>
     * 기준으로 든다. 이 게이트 하나가 통과를 <b>18 → 15</b>로 자른다(2026-08-20).
     * <br><b>원문보다 약하게 옮긴 것</b>: 3.5를 5년이 아니라 <b>4년</b>으로 잰다. 읽기 창
     * 1,261봉 전이 2021-06인데 FY−5 기말은 1,377봉 전이라, 5년으로 두면 주가와 이익잉여금의
     * 기준점이 7개월 어긋난 채 남는다(ADR-0067 §배경).
     * <br><b>원문에 없는데 더한 것</b>: {@code OPM_MIN_5Y}(3.4의 「일관성」을 최솟값으로 옮긴
     * 형태), {@code PER_SECTOR_PCTL}(AAII 원 구현이 밸류에이션을 <b>업종 기준값 대비</b>로
     * 판정하는데 이식본이 TradingView의 한계로 절대값으로 바꿔 놓은 것을 실제 업종 모집단으로
     * 되돌린 것이다), {@code SHARES_GROWTH_3Y <= -0.760}(주주환원의 둘째 계단).
     *
     * <p><b>2.1이 배점 둘을 갖고, 둘 다 같은 변수의 계단이다.</b> {@code SHARES_GROWTH_3Y <= 0}은
     * 원문이 지시한 우회(<i>"발행주식수 추이를 컬럼으로 띄워 분기별 감소 여부 확인"</i>)로
     * <b>희석하지 않았는가</b>를 묻고, {@code <= -0.760}은 <b>의미 있게 줄였는가</b>를 묻는다 —
     * 2.1 원문이 <i>"… 아니면 <b>주주에게 돌려주는지</b>. 버핏은 이 중 자사주 매입을 선호한다"</i>로
     * 둘을 함께 적는다. 점수는 0/1/2이고, 같은 변수에 계단 둘을 얹는 것은 이 저장소에 이미 있다
     * ({@code DEBT_RATIO}가 필수 {@code <= 100} + 배점 {@code <= 50}이다).
     *
     * <p><b>이 자리는 {@code PROFIT_RETIREMENT_3Y > 0}이었다</b>(ADR-0083). 그 지표가 국내에서
     * 소각을 <b>정확히</b> 집는다는 것은 지금도 참이고(실측 2026-08-20: 희석 없음을 통과하면서
     * 소각 값도 있는 188종목 중 130이 소각을 한 번도 안 했고, 반대로 16종목은 소각했는데 순증이라
     * 빠진다 · 순위상관 −0.432), <b>그럼에도 바꾼 이유는 미국 커버리지가 0이라는 것</b>이다 —
     * 만점 분모에만 남아 체계적으로 불리했고, 분모에서 빼는 것은 데이터 없는 종목의 달성률을
     * 올린다(ADR-0112 · P27 축③). <b>잃는 것을 수로 적는다</b>: 최신 일자 국내 활성 편입에서
     * 소각 배점을 받던 78종목 중 <b>25가 빠지고</b>(순증 16 · 감소가 0.760% 미만 9) 새로 21이 든다.
     * <br>MUST NOT: 이 조건을 「소각을 본다」로 읽지 않는다 — {@code SHARES_GROWTH_3Y}는
     * <b>유상감자·자본감소도 함께</b> 잡는다. 1:1 교체가 아니다.
     * <br>{@code PROFIT_RETIREMENT_3Y} 지표 자체는 지우지 않는다(ADR-0061 §규칙) — 조건 빌더와
     * 상세 화면에서 국내 종목에 여전히 쓸 수 있다.
     * <br>MUST NOT: {@code @IndicatorMeta.direction}을 주지 않는다 — 배점 조건을 거는 것과 방향을
     * 주는 것은 다른 일이고, 이 저장소는 방향이 NULL인 지표 열셋을 이미 배점으로 쓴다.
     * <br><b>원문에 있는데 뺀 것</b>: {@code ROE >= 15}·{@code ROA >= 8}·
     * {@code OPERATING_MARGIN >= 15}·{@code NET_MARGIN >= 10}·{@code PER <= 25}.
     * <b>게이트가 이미 재는 것과 순위상관 ρ ≥ 0.8이면 점수에서 뺀다</b>는 잣대를 전 조건에
     * 같게 적용한 결과다(ADR-0068) — {@code ROIC_PRETAX}와 ρ가 각각 0.870·0.832·0.836이고,
     * {@code NET_MARGIN}은 {@code OPERATING_MARGIN}과 0.857이다. 축에 이름을 여섯 개 붙이는
     * 것은 측정이 아니다.
     * <br>게이트를 {@code ROIC_MIN_3Y}로 바꾼 뒤 같은 잣대를 다시 댔다 — 남은 점수 조건과의
     * ρ는 {@code OPM_MIN_5Y} <b>0.786</b>, {@code FCF_MARGIN} 0.425, {@code FCF_YIELD} 0.354,
     * {@code GROSS_MARGIN} <b>0.242</b>로 전부 0.8 미만이라 <b>뺄 것이 없다.</b>
     * {@code OPM_MIN_5Y}가 경계에 가까운 것은 둘 다 다년 최솟값 수익성이기 때문이고, 다음에
     * 게이트를 만질 때 먼저 볼 자리다.
     *
     * <p><b>질은 문이고 가격은 저울이다</b>(ADR-0068). 게이트 여섯은 전부 사업의 질과 규모이고,
     * 가격은 게이트가 아니라 <b>배점 17 중 7(41%)</b>로 순위를 정한다(ADR-0083이 소각 배점 1을
     * 더해 16 → 17이 됐다. <b>ADR-0068 §결정의 「16 중 7(44%)」은 그 문서 시점의 조성이라 고치지
     * 않는다</b> — 고치면 그 문서가 자기가 안 한 결정을 주장하게 된다). AAII Buffett-Hagstrom도
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
     * <p><b>게이트끼리 같은 방향으로 겹치는 쌍이 둘 있다</b>(2026-08-20). {@code ROE_MIN_3Y >= 10}
     * (53) ∧ {@code PROFIT_STREAK_Y >= 7}(159) = 37로 독립 가정(24.3)의 <b>1.52배</b>, 그리고
     * {@code ROE_MIN_3Y >= 10} ∧ {@code ROIC_MIN_3Y >= 10}(60) = 41로 독립 가정(9.2)의
     * <b>4.47배</b>다. <b>단독 통과율만으로는 이 형태가 안 보인다.</b> 그럼에도 셋을 다 두는
     * 이유는 각각이 실제로 자르기 때문이며(ROIC가 18 → 15), <b>게이트 여섯을 안전장치 여섯으로
     * 읽지 않도록 그 배수를 여기 적는다.</b> 여섯을 전부 걸면 <b>15종목</b>이 남는다.
     *
     * <p><b>MUST: 게이트와 배점 사이에도 같은 잣대가 걸린다는 것을 안다.</b> ADR-0068 §규칙이
     * <i>"게이트가 재는 것과 ρ ≥ 0.8이면 뺀다"</i>를 MUST로 걸었는데, 게이트
     * {@code DEBT_RATIO <= 100}과 배점 {@code DEBT_RATIO <= 50}은 <b>같은 지표</b>라 ρ = 1.0이고
     * 통과 집합이 <b>완전히 포함된다</b>(a=131 · 게이트만 66 · 배점만 <b>0</b>). <b>열린 위반이고
     * 이번에 안 건드렸다</b> — 빼면 배점 총합이 17 → 16이 되어 위 41% 표기와 그 사본 전부에
     * 파급되므로 별도 판단이 필요하다. 경위는 ADR-0068에 있다.
     *
     * <p><b>그 4.47배가 임계 10을 고른 이유이기도 하다.</b> ROIC 게이트를 연간 3년 최저로 옮기면
     * {@code ROE_MIN_3Y}와 <b>기저가 같아져</b>(둘 다 연간 3개년 최솟값) 겹침이 커진다 —
     * ρ가 0.870({@code ROIC_PRETAX}~{@code ROE})에서 <b>0.882</b>로 올랐다. 임계를 12로 두면
     * 5.61배, 15면 6.30배로 <b>ADR-0068이 건 5.0 펜스를 넘는다.</b> 회전만 보면 12가 더 낮지만
     * (연간 임계 교차율 5.7/9.8/5.6/4.5% 대 9.4/13.1/7.4/5.5%), 두 게이트가 같은 것을 잰다는
     * 신호를 보고도 잣대를 느슨하게 하는 것은 <b>실패할 수 없는 펜스를 만드는 일</b>이다.
     * MUST NOT: 임계를 올리려고 그 펜스의 5.0을 손대지 않는다.
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
                BUFFETT_QUALITY,
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
                        + "것인지를 가른다. 이 조건 하나가 매출총이익률 중앙값을 46.8%에서 "
                        + "48.8%로 올린다(유니버스는 21.9%다). "
                        + "가격은 걸러내는 조건이 아니라 순위를 정하는 몫이다 — 배점의 41%가 "
                        + "가격에 걸려 있다. 좋은 회사를 비싸게 사지 않는다는 뜻이지만, "
                        + "통과 목록 자체는 시장보다 비싼 편이다. 좋은 회사는 비싸게 거래되기 "
                        + "때문이며, 이 화면은 '살 회사'가 아니라 '살 만한지 볼 회사'를 "
                        + "순서대로 세운 것이다. "
                        + "필요한 이력이 길어 편입 종목의 4분의 1이 판정 자체를 받지 못한다 — "
                        + "7개 회계연도가 연속으로 있어야 하기 때문이며, 상장한 지 얼마 안 된 종목이 "
                        + "이력이 짧다는 이유로 유리해지지 않게 한 결과다. "
                        + "옮기지 못한 조건이 많다. 기대수익률 모형은 여전히 빠져 있는데, "
                        + "이유가 데이터가 아니라 이 화면의 원칙이다 — 그 모형은 미래 주가를 "
                        + "추정해 기대수익률을 내고, 이 화면은 수익률을 주장하지 않는다. "
                        + "필요한 입력 넷(자기자본이익률·배당성향·주가순자산비율·주가수익비율)은 "
                        + "이제 다 있어 직접 계산해 볼 수는 있다. "
                        + "이자보상배율과 순부채·EBITDA는 이자비용과 감가상각비를 소스가 "
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
                        + "주식수 조건은 자사주 취득을 보지 못한다 — 국내에서 자사주는 소각해야 "
                        + "상장주식수가 줄어든다. 그래서 소각한 몫을 따로 재는 조건을 함께 건다"
                        + "(3년 이익소각 비율). 둘은 같은 조항의 다른 반쪽이다 — 앞의 것은 "
                        + "희석하지 않았는가를, 뒤의 것은 주주에게 돌려줬는가를 본다. "
                        + "실제로 주식수가 줄지 않은 조건을 통과한 종목의 대부분은 소각을 한 번도 "
                        + "하지 않았고, 반대로 소각했는데 유상증자로 주식수가 늘어 앞 조건에서 "
                        + "빠지는 종목도 있다. "
                        + "다만 소각 조건은 편입 종목 47개에서 값이 없다 — 그 종목은 사업 성적이 "
                        + "아니라 자료 사유로 이 배점을 얻지 못한다. "
                        + "지배구조 지표는 사업보고서가 나오는 날 한꺼번에 바뀐다 — "
                        + "3월 공시철에 값이 무더기로 움직일 수 있다. "
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
                        requiredIn(RoeMin3yCalculator.CODE, ComparisonOperator.GTE, "10", "22.6"),
                        // 0.3·3.2 — ROE와 반드시 함께 건다. §8이 분리를 금지한 자리다. 197/346(57%).
                        requiredIn(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "100", "183"),
                        // 0.2·1.2·5.1 — AAII 원문의 7개 회계연도다. 159/347(46%).
                        required(ProfitStreakYearsCalculator.CODE, ComparisonOperator.GTE, "7"),
                        // 0.1 충분한 규모. 274/347(79%).
                        requiredIn(MarketCapCalculator.CODE, ComparisonOperator.GTE,
                                "1000000000000", "6500000000"),
                        // 2019 주주서한의 첫 번째 기준 —「영업에 투입된 순유형자본 대비 좋은
                        // 수익률」. 60/300(20%)이 통과하고 이 게이트가 18 → 15로 자른다.
                        //
                        // 기저가 단년 TTM이 아니라 연간 3년 최저다. 단년은 분기 공시마다
                        // 뒤집혀 목록의 회전을 만들었다 — 같은 임계에서 종목·연 임계 교차율이
                        // 12~20%(단년) 대 9.4/13.1/7.4/5.5%(3년 최저)다.
                        //
                        // MUST NOT: 이 10을 세전 보정값 15에서 이어진 수로 읽지 않는다.
                        // 3년 최저는 단년보다 구조적으로 낮아 같은 수가 같은 엄격도를 뜻하지
                        // 않는다 — 15를 그대로 옮기면 통과가 8.7%로 조이고 겹침 배수가 6.3이
                        // 되어 ADR-0068의 펜스를 깬다. 10은 국내 분포에서 다시 잰 값이고,
                        // ROE_MIN_3Y가 원문 15를 10으로 내린 것과 같은 종류의 재보정이다.
                        requiredIn(RoicMin3yCalculator.CODE, ComparisonOperator.GTE, "10", "15.8"),
                        // §7 유동성 — 문헌은 「Average Volume(30D) · 하한 설정 · 거래 불가
                        // 종목 유입 차단」이다. 조건과 목적은 문헌이 주고 수는 구현에 맡긴다.
                        //
                        // 원화 금액에서 회전율로 갈아탄 이유가 대칭이다. 같은 100억원이
                        // 국내에서는 통과율 67.7%인데 미국에서는 98.9%라 같은 조건이 시장마다
                        // 다른 것을 쟀고, 미국은 소스가 trade_value를 안 줘 아예 0행이었다.
                        // 0.15%가 두 시장에서 같은 것을 자른다 — 잘리는 집합의 일평균
                        // 체결금액 중앙값이 국내 24.6억원 · 미국 $5.9M이고 남는 집합은
                        // 241억원 · $220M이다. 통과율이 비슷하다는 것은 근거가 아니다
                        // (아무도 안 자르는 값에서는 언제나 비슷하다 — ADR-0112).
                        //
                        // 크기 축은 이 조건이 아니라 MARKET_CAP이 든다 — 100억 게이트가
                        // 국내에서 자르던 32%는 「거래 불가」가 아니라 대형주 선호였다.
                        required(TurnoverRatio60Calculator.CODE,
                                ComparisonOperator.GTE, "0.15"),

                        // ── 점수: 가격 — 배점 16 중 7이다 ──
                        // 4.2 안전마진. P/FCF의 역수라 같은 조건이며 음수에서도 뜻이 산다.
                        // 109/347(31%) 통과, 커버리지 326.
                        weighted(FcfYieldCalculator.CODE, ComparisonOperator.GTE, "5", "3"),
                        // 4.3의 업종 상대판. AAII 원 구현이 요구한 비교이고, 절대 PER과 달리
                        // 국면이 바뀌어도 뜻이 유지된다. 117/347(34%), 커버리지 217.
                        weighted(sectorPctl(PerCalculator.CODE),
                                ComparisonOperator.LTE, "0.50", "2"),
                        // 3.5 1달러 유보 → 1달러 시가. 원문 그대로의 기준이 1.0이다.
                        // 141/347(41%), 커버리지 224 — 이 프리셋에서 가장 낮다.
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
                        // 67/347(19%), 커버리지 287.
                        weighted(GrossMarginCalculator.CODE, ComparisonOperator.GTE, "40", "2"),
                        // 3.4 「일관성」. 나쁜 해에도 얼마나 남았는가. 58/347(17%).
                        weightedIn(OperatingMarginMin5yCalculator.CODE,
                                ComparisonOperator.GTE, "8", "17.8", "2"),

                        // ── 점수: 자본배분과 부채 여유 ──
                        // 2.1 자본배분. 소각만 잡고 자사주 취득은 못 잡는다. 201/347(58%).
                        weighted(SharesGrowth3yCalculator.CODE, ComparisonOperator.LTE, "0", "1"),
                        // 4.5의 다른 반쪽 — 희석하지 않았는가가 아니라 **의미 있게 줄였는가**다.
                        // 같은 변수의 둘째 계단이라 점수는 0/1/2다(DEBT_RATIO가 필수 100 +
                        // 배점 50으로 같은 모양이다).
                        //
                        // 이 자리는 PROFIT_RETIREMENT_3Y > 0이었다(ADR-0083). 그 지표가 미국
                        // 커버리지 0이라 만점 분모에만 남아 체계적으로 불리했고, 분모에서
                        // 빼는 것은 데이터 없는 종목의 달성률을 올린다(ADR-0112 · P27 축③).
                        // 계기를 시장마다 가르는 대신 하나로 두고 임계를 시장별로 옮겼다 —
                        // IndicatorContext에 시장이 없어 계기를 가르면 파급이 크고, 그것이
                        // 「같은 이름이 시장마다 다른 것을 잰다」의 형태이기도 하다.
                        //
                        // 임계는 그 배점의 국내 수여율을 승계한다: 보존 창 전체에서
                        // PROFIT_RETIREMENT_3Y > 0이 21.87%였고 SHARES_GROWTH_3Y <= -0.760이
                        // 21.87% · USD -7.86이 21.85%다(2026-09-01).
                        weightedIn(SharesGrowth3yCalculator.CODE, ComparisonOperator.LTE,
                                "-0.760", "-7.86", "1"),
                        // 0.3·3.2 원문의 D/E 0.5. 게이트(100)보다 조인 값이다. 131/347(38%).
                        weighted(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "50", "1"))));
    }

    @Bean
    public List<ScreenPreset> screenPresets() {
        return List.of(valueKr(), lynchFastGrowerKr(), lynchStalwartKr(),
                momentumKr(), stage2BreakoutKr(),
                pullbackUptrendKr(), lowvolQualityKr(), buffettQualityKr(),
                tripleScreenRebound());
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
        if (BUFFETT_QUALITY.equals(presetCode)) {
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
            //
            // 할인율 셋을 함께 띄운다. 위 조건 셋은 「싼가」를 묻되 전부 충족/불충족이라
            // **얼마나** 싼지가 값으로 나오지 않는다 — 5% 싼 종목과 40% 싼 종목이 같은
            // 점수를 받는다. 그것이 이 화면의 실제 물음("좋은 회사를 지금 사도 되는가")에
            // 답하지 못하던 자리다.
            //
            // 셋을 다 띄우는 이유는 서로 어긋나는 것이 신호이기 때문이다. 실측에서
            // 파크시스템스가 PER −80.5% / PBR +36.7%로 갈렸다 — 이익이 튄 해가 PER 이력을
            // 왜곡한 자리이고, 하나만 띄우면 그 사실이 화면에서 사라진다.
            //
            // MUST NOT: 이것을 조건이나 배점으로 올리지 않는다. ADR-0068이 가격을 게이트로
            // 올리면 해자가 무너진다는 것을 실측으로 보였고, 배치를 바꾸려면 통과율·겹침
            // 배수·순위상관을 다시 재야 한다. 컬럼은 순위에 영향을 주지 않고 결과에서
            // 아무도 빼지 않는다.
            return List.of(pctl(PerCalculator.CODE), pctl(PbrCalculator.CODE),
                    PerDiscount5yCalculator.CODE,
                    PbrDiscount5yCalculator.CODE,
                    PsrDiscount5yCalculator.CODE);
        }
        if (TRIPLE_SCREEN_REBOUND.equals(presetCode)) {
            // 조건 넷은 전부 부호 판정이라 「통과했다」만 말하고 <b>얼마나</b>를 말하지 않는다.
            // 이 프리셋이 겨누는 것이 「도는 순간」이므로 그 둘이 화면에 없으면 사용자가
            // 갓 돌아선 것과 한참 전에 돌아선 것을 가릴 수 없다.
            //
            // 일봉 히스토그램의 5일 변화량은 「어느 쪽으로 얼마나 빨리 가고 있는가」이고,
            // 주봉 MACD-V는 「주 단위로 지금 어느 높이인가」다 — 조건은 주봉의 *기울기*만
            // 묻고 높이는 안 묻는데, 같은 기울기라도 -100에서 오르는 것과 +40에서 오르는
            // 것은 뜻이 다르다.
            //
            // MUST NOT: 이 둘을 조건으로 올리지 않는다. 올리면 통과율·순위상관을 다시 재야
            // 하고(ADR-0061), 지금 넷이 여섯 쌍 전부 ρ < 0.8인 배치가 무너진다.
            return List.of(MacdVHistChange5Calculator.CODE, MacdVWeeklyCalculator.CODE);
        }
        return List.of();
    }

    // ── 1. 저평가 가치주 ──────────────────────────────────────────────────

    public static final String VALUE = "VALUE";

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
                VALUE,
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
                        weightedIn(DebtRatioCalculator.CODE, ComparisonOperator.LTE,
                                "150", "276", "1"),
                        // 추세가 완전히 무너지지는 않았을 것 — 200일선 10% 아래까지 허용
                        IndicatorCondition.of(
                                AdjCloseCalculator.CODE, ComparisonOperator.GTE,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE, "0.90"))
                                .withWeight(new BigDecimal("1")))));
    }

    // ── 2. 린치 고속성장주 ────────────────────────────────────────────────

    public static final String LYNCH_FAST_GROWER = "LYNCH_FAST_GROWER";

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
     * 자르는 일의 <b>대부분이 임계값이 아니라 결측 배제</b>였다(2026-08-20 대형 86 → PEG값
     * 58 → {@code PEG <= 1.5} 통과 55 — 자른 31 중 <b>28이 결측</b>이다).
     * 적자·역성장 배제는 이제 분류 게이트가 직접 한다.
     * <br><b>국내 분포로 재보정한 것</b>: {@code DEBT_RATIO <= 80}(§8 한국 조정값 표). 원문 D/E
     * 0.33은 국내에서 23%만 통과해 제조업 중심 구조에 과하고, 0.8이 절반을 가른다(174/346).
     * 유동성 하한은 <b>50억</b>이고, 이는 추세 프리셋들이 쓰는 100억의 절반이다 — 아래 참조.
     * <br><b>원문에 없는데 더한 것</b>: {@code PER_SECTOR_PCTL}·{@code DEBT_RATIO_SECTOR_REL}
     * (3.2·3.9의 업종 상대 요구를 실제 업종 모집단으로 옮긴 것), {@code ACCRUAL_RATIO},
     * {@code OPERATING_MARGIN}(3.18 세전이익률의 대용), {@code INVENTORY_GROWTH_GAP}(3.14 —
     * ADR-0069가 원자료만 적재해 둔 자리이고 이 프리셋이 그 소비자다),
     * {@code SHARES_GROWTH_3Y <= -0.760}(<b>4.5가 배점 둘을 갖게 된다.</b> 원문이
     * 「스크리너 구현 불가」로 적고 발행주식수 추이를 우회로 드는 조항이고, {@code <= 0}이 그
     * 우회이며 이것은 <b>의미 있게 줄였는가</b>라는 둘째 계단이다. 이 자리가
     * {@code PROFIT_RETIREMENT_3Y > 0}이었고 왜 바꿨는지는 {@link #buffettQualityKr()} javadoc에 있다).
     * <br>MUST NOT: {@code DIVIDEND_YIELD}를 여기 더하지 않는다 — §3.10에 명문 조건
     * {@code > 2}가 있지만 <b>그것이 어느 유형에 걸리는지를 원문이 정하지 않는다</b>(ADR-0083의
     * 「확인 못 한다」, 2026-08-24 사용자 결정). <b>출처가 없어서가 아니라 배치가 안 정해져서다.</b>
     * <br><b>원문보다 약하게 옮긴 것</b>: {@code FCF_MARGIN > 0}. §3.13의 1차 조건은
     * {@code Free Cash Flow Yield > 5}이고 마진은 보조인데, 밸류에이션 계열을 쓰면 성패 기준과
     * 축이 겹쳐 부호 조건으로 옮겼다.
     *
     * <p><b>{@code EPS_CAGR_5Y}를 문에 세운 대가가 있다.</b> 값이 있는 종목이 174/347이라
     * 절반이 <b>사업 성적이 아니라 데이터 사유로</b> 빠진다. 배제 사유는 셋이고 뭉뚱그리면 안
     * 된다 — ① 상장 5년 미만 ② 5년 전이 적자 ③ <b>미보정 기업행위</b>(1,261봉 창, ADR-0055,
     * 현재 6종목). ADR-0066이 바로 이 이유로 이 지표를 필수에서 점수로 내렸고 ADR-0070이 그
     * 결정을 뒤집었다 — <b>분류가 문에 없으면 통과 집합의 66%가 급반등주가 되기 때문</b>이다.
     *
     * <p><b>유동성 하한을 50억으로 내린 이유</b> — 시총 상한 이하 부분집합(173종목)에서
     * 거래대금 p25가 <b>51억</b>이라 {@code >= 100억}은 <b>거의 절반만 남긴다.</b> 그것은 유동성
     * 하한이 아니라 <b>두 번째 규모 필터</b>이고, 시총 상한과 같은 방향으로 겹쳐 게이트
     * 교집합을 <b>9 → 6종목</b>으로 떨어뜨린다(2026-08-20). 대형주 프리셋의 값을 그대로 승계하면 안 되는 자리다.
     *
     * <p><b>{@code PEG}는 이제 필수가 아니라 배점이다</b>(ADR-0070 — 위 「자리를 옮긴 것」).
     * 값이 있는 종목이 <b>184/347(53%)</b>이고, 값이 없다는 것은 적자이거나 이익이 역성장한다는
     * 뜻이라 <b>그 종목은 이 배점을 못 얻을 뿐 결과에서 빠지지는 않는다.</b> 배제는 분류 게이트
     * ({@code EPS_CAGR_5Y})가 한다.
     * <br>MUST NOT: 이것을 {@code OCF_SUM_5Y}를 점수로 내린 이유(<i>"현금흐름이 나빠서가 아니라
     * 이력이 짧아서"</i>)와 <b>같이 읽지 않는다</b> — PEG 결측은 이력이 아니라 <b>이익의 부호</b>가
     * 만드는 것이고, 린치가 실제로 배제하려는 종목이다.
     *
     * <p>MUST NOT: §6의 「흑자 확인」을 게이트로 더하지 않는다. {@code PER}이 적자에 값을 내지
     * 않으므로 그 조건은 {@code PER} 계열이 이미 함의한다 — 실측으로 {@code EPS_TTM > 0}인 종목
     * 수와 {@code PER} 값이 있는 종목 수가 <b>271</b>로 정확히 같다.
     *
     * <p>MUST NOT: 이 프리셋을 「텐배거를 찾는다」로 설명하지 않는다. 린치의 고속성장주는
     * <b>소형주</b>에서 나오는데 우리 유니버스 하한이 <b>4,761억</b>이다(2026-08-20). "중소형"은 유니버스 내
     * 상대 규모로 치환한 것이지 린치가 말한 규모가 아니다.
     */
    private static ScreenPreset lynchFastGrowerKr() {
        return new ScreenPreset(
                LYNCH_FAST_GROWER,
                "린치 고속성장주",
                "이익이 빠르게 느는데 그 속도에 견줘 주가가 싼 종목을 점수로 줄 세운다. "
                        + "판별 도구는 PEG다 — 주가수익비율을 이익 성장률로 나눈 값이며, "
                        + "1보다 작으면 성장 속도만큼 값이 매겨지지 않았다는 뜻이다. "
                        + "이익이 줄고 있는 종목은 PEG 값이 아예 없어 결과에서 빠진다. "
                        + "편입 종목의 절반 남짓(184/347)에만 값이 있으며, 그것은 적자이거나 "
                        + "이익이 역성장하는 종목을 이 방식이 의도적으로 배제하기 때문이다. "
                        + "시가총액 상한을 두어 이미 큰 회사는 빼고, 1년에 두 배 넘게 오른 "
                        + "종목도 뺀다. "
                        + "5년 주당순이익 성장률은 필수 조건이다 — 이 프리셋을 고속성장주라 "
                        + "부를 수 있게 하는 유형 분류축이라 문에 세웠다. 다섯 해치 재무가 있어야 "
                        + "계산되므로 성장이 과해서가 아니라 이력이 짧아서 빠지는 종목이 생긴다. "
                        + "옮기지 못한 조건이 있다. 배당 관련 셋(배당수익률·배당성향·연속 배당 "
                        + "연수)은 원문에서 저성장주 유형의 판별 조건으로 묶여 있고, 그것이 이 "
                        + "화면에 걸리는지를 원문이 정하지 않는다 — 앞의 둘은 지표로 있으니 조건 "
                        + "빌더에서 직접 걸 수 있고, 연속 배당 연수는 아직 자료가 4년치뿐이라 "
                        + "원문이 요구하는 5년을 셀 수 없다. 순부채/EBITDA는 감가상각비와 "
                        + "총차입금을 소스가 주지 않아 빠졌다. 잉여현금흐름은 계산할 수 있게 "
                        + "됐고 이 프리셋도 쓴다 — 발생액 비율이 그 자리를 대신한다고 적어 "
                        + "두었으나 둘의 순위상관이 -0.18이라 서로를 대신하지 않는다. "
                        + "재고와 매출의 증가 속도 비교도 넣었다. 재고가 매출보다 빨리 늘면 "
                        + "팔리지 않은 물건이 쌓이는 중이고 손익계산서에는 아직 안 나타난다 — "
                        + "편입 종목 284개에 값이 있다. "
                        + "세전이익률은 영업이익률로 대신했다 — 다른 값이다. "
                        + "금융·부동산 업종을 제외하지 못한다. 다만 저평가와 부채를 같은 업종 "
                        + "안에서 견주므로 금융업은 금융업끼리 비교된다. "
                        + "주식수 조건은 자사주 취득을 보지 못한다 — 상장주식수가 줄어야 잡히는데 "
                        + "국내에서 자사주는 소각해야 줄어든다. 그래서 소각한 몫을 따로 재는 "
                        + "조건을 함께 건다(3년 이익소각 비율) — 둘은 같은 조항의 다른 반쪽이다. "
                        + "다만 소각 조건은 편입 종목 47개에서 값이 없어 그 종목은 사업 성적이 "
                        + "아니라 자료 사유로 이 배점을 얻지 못한다. "
                        + "지배구조 지표는 사업보고서가 나오는 날 한꺼번에 바뀐다 — "
                        + "3월 공시철에 값이 무더기로 움직일 수 있다. "
                        + "업종 표본이 5종목에 못 미치면 업종 상대 조건을 판정할 수 없고, "
                        + "적자라 PER이 없는 종목도 그 조건에서 점수를 얻지 못한다 — "
                        + "둘을 합쳐 130종목이 그렇다. "
                        + "린치의 고속성장주는 본래 소형주에서 찾는 것인데 편입 종목의 하한이 "
                        + "4,761억원이라 '중소형'은 이 목록 안에서의 상대 규모다.",
                EvaluationMode.SCORING,
                null,
                ConditionGroup.and(List.of(
                        // ── 필수 게이트 ──
                        // ★ 유형 분류. 이 프리셋을 「고속성장주」라 부를 수 있게 하는 조건이다.
                        // 하한 20은 §2.3, 상한 50은 §3.17 — 둘 다 원문이나 §2.3은 TTM YoY 축이라
                        // 밴드를 5년 CAGR로 옮긴 것이다(ADR-0070). 89 → 9종목(2026-08-20).
                        requiredBetween(EpsCagr5yCalculator.CODE, "20", "50"),
                        // 2.3 "중소형"의 유니버스 내 치환. 시총 중앙값이다.
                        requiredIn(MarketCapCalculator.CODE, ComparisonOperator.LTE,
                                "2616000000000", "15800000000"),
                        // §6 유동성. 버핏과 같은 임계를 쓴다 — 회전율은 규모에 중립이라
                        // 「대형 지향이냐」로 가를 이유가 없다. 크기 축은 위 MARKET_CAP이 든다.
                        required(TurnoverRatio60Calculator.CODE,
                                ComparisonOperator.GTE, "0.15"),
                        // 5.1 가장 인기 있는 종목은 사지 않는다.
                        required(Return52wCalculator.CODE, ComparisonOperator.LT, "100"),

                        // ── 점수 ──
                        // 3.3 린치의 판별 도구. 가격은 문이 아니라 저울이다(ADR-0070) — 게이트로
                        // 두면 자르는 일의 대부분이 임계값이 아니라 결측 배제였다. PEG_PCTL은
                        // PEG와 순위상관 0.996이라 뺐다. 새 게이트축과는 ρ −0.010으로 직교한다.
                        weighted(PegCalculator.CODE, ComparisonOperator.LTE, "1.0", "3"),
                        // 3.2 업종 전체의 PER과 비교해서 봐야 한다 — 원문이 요구한 비교다.
                        weighted(sectorPctl(PerCalculator.CODE),
                                ComparisonOperator.LTE, "0.50", "2"),
                        weighted(PerCalculator.CODE, ComparisonOperator.LTE, "20", "2"),
                        weighted(RevenueGrowthCalculator.CODE, ComparisonOperator.GT, "15", "2"),
                        weightedBetween(EpsGrowthTtmYoyCalculator.CODE, "10", "30", "2"),
                        weightedIn(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "80", "152", "2"),
                        // 3.9 AAII 원문이 요구한 업종 상대 비교다. 1.0이 업종 중앙값이다.
                        weighted(sectorRel(DebtRatioCalculator.CODE),
                                ComparisonOperator.LTE, "1.0", "1"),
                        // 3.5 자기 5년 PER 중앙값보다 낮은가. 원본 명세가 구현 불가로 둔 조건이다.
                        IndicatorCondition.of(
                                PerCalculator.CODE, ComparisonOperator.LT,
                                ConditionOperand.IndicatorRef.of(PerMedian5yCalculator.CODE))
                                .withWeight(new BigDecimal("1")),
                        weightedIn(OperatingMarginCalculator.CODE, ComparisonOperator.GT,
                                "10", "16.3", "1"),
                        // 3.13 린치는 이익보다 현금흐름을 신뢰했다. 발생액이 이 자리를 지킨다고
                        // 적어 두었으나 ρ(ACCRUAL_RATIO, FCF_MARGIN) = −0.184로 지키지 않는다 —
                        // 둘 다 둔다. 원문 1차 조건은 FCF 수익률이라 약하게 옮긴 것이다.
                        weighted(FcfMarginCalculator.CODE, ComparisonOperator.GT, "0", "2"),
                        weighted(AccrualRatioCalculator.CODE, ComparisonOperator.LTE, "0", "1"),
                        // 3.14 재고가 매출보다 빨리 늘면 팔리지 않은 것이 쌓이는 중이다.
                        // 커버 287/347.
                        weighted(InventoryGrowthGapCalculator.CODE,
                                ComparisonOperator.LTE, "0", "1"),
                        weighted(SharesGrowth3yCalculator.CODE, ComparisonOperator.LTE, "0", "1"),
                        // 4.5의 다른 반쪽 — 희석하지 않았는가가 아니라 **의미 있게 줄였는가**다.
                        // 같은 변수의 둘째 계단이라 점수는 0/1/2다(DEBT_RATIO가 필수 100 +
                        // 배점 50으로 같은 모양이다).
                        //
                        // 이 자리는 PROFIT_RETIREMENT_3Y > 0이었다(ADR-0083). 그 지표가 미국
                        // 커버리지 0이라 만점 분모에만 남아 체계적으로 불리했고, 분모에서
                        // 빼는 것은 데이터 없는 종목의 달성률을 올린다(ADR-0112 · P27 축③).
                        // 계기를 시장마다 가르는 대신 하나로 두고 임계를 시장별로 옮겼다 —
                        // IndicatorContext에 시장이 없어 계기를 가르면 파급이 크고, 그것이
                        // 「같은 이름이 시장마다 다른 것을 잰다」의 형태이기도 하다.
                        //
                        // 임계는 그 배점의 국내 수여율을 승계한다: 보존 창 전체에서
                        // PROFIT_RETIREMENT_3Y > 0이 21.87%였고 SHARES_GROWTH_3Y <= -0.760이
                        // 21.87% · USD -7.86이 21.85%다(2026-09-01).
                        weightedIn(SharesGrowth3yCalculator.CODE, ComparisonOperator.LTE,
                                "-0.760", "-7.86", "1"))));
    }

    // ── 3. 린치 대형우량주 ────────────────────────────────────────────────

    public static final String LYNCH_STALWART = "LYNCH_STALWART";

    /**
     * 린치 대형우량주 — 같은 문서 2.2의 이식. 여섯 유형 중 「대형이면서 여전히 성장하는 회사」다.
     *
     * <p>{@link #lynchFastGrowerKr()}와 <b>시가총액 방향이 반대</b>라 실제로 다른 종목을 낸다
     * (2026-08-20 유니버스 시총 0.48조~1,584.3조, p25 1.39조 · p50 2.62조 · p75 <b>6.63조</b>).
     *
     * <p><b>원문 그대로</b>: {@code MARKET_CAP} 대형, {@code EPS_GROWTH_TTM_YOY 10~20},
     * {@code ROE > 12}, {@code PEG <= 1.5}, {@code DEBT_RATIO <= 60}(D/E 0.6).
     * <br><b>원문의 밴드를 다른 축으로 옮긴 것</b>: {@code EPS_CAGR_5Y 10~20}이 <b>유형 분류</b>
     * 이자 필수 게이트다. 2.2의 밴드를 그대로 썼지만 <b>원문의 축은 TTM YoY다.</b>
     * <b>2026-08-20에 되돌릴지 다시 봤고 <u>현행 유지로 닫혔다</b>. <b>잠정이 아니다</b> —
     * 근거는 두 성장축이 발생액과 거의 직교한다는 것이고(순위상관 {@code EPS_CAGR_5Y}
     * <b>+0.282</b> · {@code EPS_GROWTH_TTM_YOY} <b>+0.160</b>, 발생액은 낮을수록 좋으므로 양수는
     * 약한 역방향), <b>성장률 밴드가 이익의 현금 뒷받침을 개선할 이유가 원리적으로 없다.</b>
     * <br>MUST NOT: 「백테스트 전까지 잠정」이라 적지 않는다 — <b>그 전제가 거짓임이 같은 날
     * 실측으로 드러났다.</b> {@code EPS_CAGR_5Y}는 아웃오브샘플 관측이 <b>0</b>이라(1,261봉이
     * 2025-02-18에야 열린다) ADR-0071의 조건 단위 백테스트가 이 축을 <b>못 뒤집는다.</b>
     * 사용자가 고른 문장은 <b>「확인 못 한다로 적고 닫는다」</b>(2026-08-20)이고, 그것은
     * 「이 축이 옳다고 판정됐다」가 아니다. 다시 열려면 <b>새 데이터 소스</b>가 필요하다.
     * 경위는 ADR-0070의 분류축 재검토 절에 있다.
     * <br>MUST NOT: ADR-0070이 적은 「대형 87 중 6 / 질 게이트 하나에 0」을 근거로 쓰지 않는다 —
     * <b>그 수는 재현되지 않는다</b>(같은 문서에 무효 표시가 있다).
     * <br><b>국내 분포로 재보정한 것</b>: "대형"의 경계를 <b>유니버스 상위 25%였던 6.773조</b>로
     * 둔다 — 절대 금액으로는 시장마다 뜻이 달라진다. <b>임계는 고정이고 분위는 움직인다</b> —
     * 2026-08-20 p75는 <b>6.63조</b>라 지금 이 게이트는 상위 25%보다 약간 조인다.
     * <br><b>자리를 옮긴 것</b>: {@code PEG <= 1.5}가 게이트에서 배점으로 내려왔다(ADR-0070).
     * <br><b>원문에 있는데 뺀 것</b>: {@code TURNOVER_60 >= 100억}. 대형 <b>86</b>종목 중
     * <b>0종목</b>을 자른다 — 게이트가 아니라 상수이고, 대형 게이트가 유동성을 이미 함의한다.
     * <br><b>원문에 없는데 더한 것</b>: {@code OPM_MIN_5Y > 0}(<b>유일한 질 게이트</b>),
     * {@code PROFITABLE_QUARTERS_4}(4.1 이익 성장의 일관성), {@code ACCRUAL_RATIO},
     * {@code SHARES_GROWTH_3Y}, {@code PER_SECTOR_PCTL}, {@code FCF_MARGIN}(3.13),
     * {@code INVENTORY_GROWTH_GAP}(3.14), {@code SHARES_GROWTH_3Y <= -0.760}(4.5가
     * 배점 둘을 갖게 된다. 근거는 {@link #buffettQualityKr()} javadoc에 있다).
     * <br>MUST NOT: {@code DIVIDEND_YIELD}를 여기 더하지 않는다 — §3.10에 명문 조건
     * {@code > 2}가 있지만 <b>그것이 어느 유형에 걸리는지를 원문이 정하지 않는다</b>(ADR-0083의
     * 「확인 못 한다」, 2026-08-24 사용자 결정). <b>출처가 없어서가 아니라 배치가 안 정해져서다.</b>
     *
     * <p><b>질 게이트를 원문에서 가져오지 못했다.</b> §2.2의 질 조항은 {@code ROE > 12}와
     * {@code D/E 0~0.6} 둘인데 대형+CAGR 11종목에 걸면 <b>각각 1종목</b>만 남아 게이트로 쓸 수
     * 없다. 그래서 「나쁜 해에도 영업흑자인가」로 대신하고 원문 둘은 저울에 남겼다.
     * MUST NOT: 이것을 원문 조항이라 읽지 않는다 — <b>부호 조건이라 변별력도 낮다</b>(2026-08-20
     * 통과 10종목의 값이 0.69~19.91이고, 이 게이트가 자르는 것은 <b>1종목</b>뿐이다).
     *
     * <p><b>부채는 이 배치로 고쳐지지 않는다 — 다만 격차는 처음 적은 것보다 훨씬 작다.</b>
     * 2026-08-20 실측으로 통과 집합의 부채비율 중앙값이 <b>87.5</b>로 유니버스 <b>79.5</b>의
     * <b>1.10배</b>다(질 게이트까지만 건 53종목은 90.3이다). 처음 적은 <i>"149.5 → 117.6 /
     * 유니버스 77.9의 1.5배"</i>는 <b>재현되지 않는다.</b> 10종목 중 원문 {@code D/E <= 0.6}을
     * 충족하는 것은 <b>셋</b>이다. <b>지주회사도 못 가른다</b> — §8이 금융·부동산과 함께 지주회사
     * 제외를 명시하는데 통과 집합에 <b>LS</b>(KSIC 64992)가 있다.
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
                LYNCH_STALWART,
                "린치 대형우량주",
                "크면서도 아직 성장이 남아 있는 회사를 점수로 줄 세운다. "
                        + "고속성장주와 시가총액 조건이 반대라 서로 다른 종목이 나온다 — "
                        + "이쪽은 편입 종목 중 큰 편에 드는 회사만 본다. "
                        + "성장 구간을 10~20%로 좁게 잡는 것은 의도다. 그보다 빠르면 대형우량주가 "
                        + "아니고, 그런 종목은 고속성장주 쪽에서 다룬다. "
                        + "다만 이 성장 조건은 이익이 실제 현금으로 들어오는지를 보지 않는다 — "
                        + "성장률과 발생액 비율은 서로 거의 무관하다. 통과 종목 중에도 장부 이익만큼 "
                        + "현금이 안 들어오는 회사가 섞이며, 그것은 이 프리셋이 거르지 못한다. "
                        + "발생액 비율 컬럼을 함께 보라. "
                        + "자기자본이익률 조건은 원문에 있어 그대로 옮겼다. 다만 한국 시장을 "
                        + "2000년부터 2019년까지 본 연구에서 수익성 지표로 초과수익을 설명하려는 "
                        + "시도의 재현율이 5%에 그쳤다 — 이 조건을 근거로 수익률을 기대하지 않는다. "
                        + "PEG 값이 없는 종목은 결과에서 빠진다. 적자이거나 이익이 줄고 있다는 뜻이다. "
                        + "옮기지 못한 조건이 있다. 배당 관련 셋(배당수익률·배당성향·연속 배당 "
                        + "연수)은 원문에서 저성장주 유형의 판별 조건으로 묶여 있고, 그것이 이 "
                        + "화면에 걸리는지를 원문이 정하지 않는다 — 앞의 둘은 지표로 있으니 조건 "
                        + "빌더에서 직접 걸 수 있고, 연속 배당 연수는 아직 자료가 4년치뿐이라 "
                        + "원문이 요구하는 5년을 셀 수 없다. "
                        + "순부채/EBITDA는 감가상각비와 총차입금을 소스가 주지 않아 빠졌다. "
                        + "잉여현금흐름과 재고-매출 속도 차를 조건에 넣었다. "
                        + "세전이익률은 이 프리셋에 넣지 않았다. "
                        + "금융·부동산 업종을 제외하지 못한다 — 저평가를 같은 업종 안에서 견주는 "
                        + "것으로 일부만 대신한다. "
                        + "주식수 조건은 자사주 취득이 아니라 상장주식수 감소를 본다. "
                        + "그래서 소각한 몫을 따로 재는 조건을 함께 건다(3년 이익소각 비율) — "
                        + "둘은 같은 조항의 다른 반쪽이다. 다만 소각 조건은 편입 종목 47개에서 "
                        + "값이 없어 그 종목은 자료 사유로 이 배점을 얻지 못한다. "
                        + "지배구조 지표는 사업보고서가 나오는 날 한꺼번에 바뀐다 — "
                        + "3월 공시철에 값이 무더기로 움직일 수 있다.",
                EvaluationMode.SCORING,
                null,
                ConditionGroup.and(List.of(
                        // ── 필수 게이트 ──
                        // "대형"의 경계. 유니버스 상위 25%다.
                        requiredIn(MarketCapCalculator.CODE, ComparisonOperator.GTE,
                                "6773000000000", "41800000000"),
                        // ★ 유형 분류. 2.2의 밴드를 5년 CAGR로 옮겼다(ADR-0070). 53 → 10종목(2026-08-20).
                        requiredBetween(EpsCagr5yCalculator.CODE, "10", "20"),
                        // ★ 질. 「나쁜 해에도 영업흑자인가」 — 대형우량주가 약속하는 성질이다.
                        // 원문에 없는데 더한 것이다: §2.2의 질 조항 둘(ROE > 12 · D/E 0~0.6)은
                        // 대형+CAGR 11종목에 걸면 각각 1종목만 남아 게이트로 쓸 수 없다.
                        // 부호 조건이라 변별력은 낮다(통과 10종목이 0.69~19.91). 11 → 10종목.
                        required(OperatingMarginMin5yCalculator.CODE, ComparisonOperator.GT, "0"),
                        // TURNOVER_60을 뺀다 — 대형 86종목 중 0종목을 자른다. 게이트가 아니라
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
                        weighted(SharesGrowth3yCalculator.CODE, ComparisonOperator.LTE, "0", "1"),
                        // 4.5의 다른 반쪽. 위 고속성장주와 같은 자리다.
                        weightedIn(SharesGrowth3yCalculator.CODE, ComparisonOperator.LTE,
                                "-0.760", "-7.86", "1"))));
    }
    // ── 4. 추세 상승 ──────────────────────────────────────────────────────

    public static final String MOMENTUM = "MOMENTUM";

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
                MOMENTUM,
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

    // ── 5. 2국면 돌파 ─────────────────────────────────────────────────────

    public static final String STAGE2_BREAKOUT = "STAGE2_BREAKOUT";

    /**
     * 2국면 돌파 — Weinstein 국면 분석의 1국면→2국면 전환 정의.
     *
     * <p>원문은 셋이 <b>동시에</b> 성립해야 전환으로 본다 — 30주선 상향 돌파, 그 이동평균 자체의
     * 상승 전환, 그리고 거래량 확장이다. 거래량 없는 돌파는 흔히 실패한다는 것이 원문의 요지다.
     *
     * <p><b>국내 분포로 재보정한 것</b>: 거래량 확장 임계. 이식 시점(2026-08-13)에 원문의 2.0배는
     * 그날 {@code p90 1.88}을 넘어 상위 10% 미만이었고, <b>1.5배</b>는 그날 <b>상위 18.4%</b>였다.
     *
     * <p><b>MUST NOT: 그 분위를 이 임계의 고정된 성질로 읽지 않는다.</b> 단면 분포를 <b>그날 시장
     * 전체 거래량</b>이 정하므로 같은 1.5가 보존 창 180일 중앙에서는 상위 11.1%, 2026-08-21에는
     * <b>상위 4.9%</b>다({@code p90 1.19}) — 지금은 원문 2.0배가 「과도하다」던 그 자리에 1.5가 앉아
     * 있다. <b>그래도 결과 수를 근거로 내리지 않는다</b>(ADR-0061 §규칙): 1.23으로 풀어도 이 프리셋의
     * 통과는 0 → 1이고, 병목은 거래량이 아니라 52주 고점 근접(349 중 13)이다.
     * <br><b>원문에 없는데 더한 것</b>: {@code ADX_14 >= 20}(추세가 실재하는가),
     * {@code CMF_21 > 0}(거래량이 매수 쪽에 실렸는가), 52주 고점 10% 이내(돌파 확인).
     *
     * <p>돌파는 <b>그날 일어난 것</b>을 잡는 조건이라 결과 수가 날마다 크게 흔들린다.
     */
    private static ScreenPreset stage2BreakoutKr() {
        return new ScreenPreset(
                STAGE2_BREAKOUT,
                "2국면 돌파",
                "바닥 다지기를 끝내고 상승 국면으로 넘어가는 신호가 한꺼번에 나온 종목을 남긴다. "
                        + "주가가 30주선(150일선) 위로 올라섰고, 그 이동평균 자체가 상승으로 돌아섰으며, "
                        + "거래량이 평소의 1.5배 이상 실렸고, 지수보다 1년 수익률이 좋은 상위 30%에 들며, "
                        + "52주 최고가에서 10% 이내에 있는 상태다. "
                        + "원문은 거래량을 평소의 2~3배로 보지만 국내에서 그 기준이 너무 빡빡해 "
                        + "1.5배로 낮췄다. 다만 이 배수가 상위 몇 %를 자르는지는 그날 시장 전체 "
                        + "거래량이 정한다 — 옮겨 올 때는 상위 18%였고 최근에는 상위 5%까지 좁아졌다. "
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
                        literalIn(VolumeRatio20Calculator.CODE, ComparisonOperator.GTE,
                                "1.5", "1.38"),
                        // 거래량이 매수 쪽에 실렸는가
                        literal(Cmf21Calculator.CODE, ComparisonOperator.GT, "0"),
                        // 지수 대비 상대강도
                        literal(pctl(ExcessReturn52wCalculator.CODE),
                                ComparisonOperator.GTE, "0.70"),
                        // 신고가 근접. 값은 항상 0 이하다.
                        literalIn(PctFrom52wHighCalculator.CODE, ComparisonOperator.GTE,
                                "-10", "-4.58"))));
    }

    // ── 6. 상승추세 눌림목 ────────────────────────────────────────────────

    public static final String PULLBACK_UPTREND = "PULLBACK_UPTREND";

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
                PULLBACK_UPTREND,
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
                        literalIn(Cmf21Calculator.CODE, ComparisonOperator.GTE,
                                "-0.10", "-0.0503"))));
    }

    // ── 7. 저변동 우량 ────────────────────────────────────────────────────

    public static final String LOWVOL_QUALITY = "LOWVOL_QUALITY";

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
                LOWVOL_QUALITY,
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
                        weightedIn(AtrPct14Calculator.CODE, ComparisonOperator.LTE, "5.9", "3.09", "3"),
                        // 시장 동조 하위 약 30%. 국내 중앙값이 0.75라 0.9로는 축이 되지 않는다.
                        weightedIn(Beta104wCalculator.CODE, ComparisonOperator.LTE, "0.40", "0.436", "3"),
                        weighted(ProfitableQuarters4Calculator.CODE,
                                ComparisonOperator.GTE, "4", "2"),
                        weightedIn(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "100", "183", "2"),
                        weighted(AccrualRatioCalculator.CODE, ComparisonOperator.LTE, "0", "1"),
                        weighted(RoaCalculator.CODE, ComparisonOperator.GTE, "0", "1"),
                        weighted(OperatingCashFlow5ySumCalculator.CODE,
                                ComparisonOperator.GT, "0", "1"),
                        // 배점도 같은 축으로 옮긴다. 원화 금액으로 두면 미국 종목이
                        // 이 1점을 원리적으로 못 얻어 만점이 시장마다 달라진다(P27 축③).
                        weighted(TurnoverRatio60Calculator.CODE,
                                ComparisonOperator.GTE, "0.15", "1"))));
    }

    // ── 9. 3중창 반등 ─────────────────────────────────────────────────────

    public static final String TRIPLE_SCREEN_REBOUND = "TRIPLE_SCREEN_REBOUND";

    /**
     * 3중창 반등 — Elder의 다중 시간축에 Spiroglou의 모멘텀 국면 정의를 얹는다.
     *
     * <p><b>출처가 둘이다.</b> 시간축 구조는 Alexander Elder의 3중창 매매 시스템
     * (<i>Trading for a Living</i>, 1993)이고, 진입 국면의 정의는 Alex Spiroglou의
     * <i>MACD-V: Volatility Normalised Momentum</i>(2022 · CMT Charles H. Dow Award와
     * NAAIM Founders Award 수상)이다. 매집 판정은 Joseph Granville의 OBV(1963)를 쓴다.
     *
     * <p><b>원문 그대로</b>: Spiroglou가 정의한 <i>Rebounding</i> 국면 —
     * {@code 50 > MACD-V > -150 이면서 시그널선 위}. 논문이 이것을 강세 진입 국면으로 지목한다.
     * 두 조건이 그 정의를 그대로 옮긴 것이다.
     *
     * <p><b>원문의 축을 옮긴 것</b>: Elder의 첫째 화면은 <b>주봉 MACD 히스토그램의 상승</b>이고
     * 하락이면 매수를 금한다. 여기서는 고전 MACD가 아니라 <b>MACD-V</b>의 주봉 히스토그램을 쓴다 —
     * 고전 MACD는 주가 수준에 비례해 종목 간 비교가 안 되고({@code MacdCalculator}의 해석문이
     * 스스로 그렇게 적는다) 스크리너는 종목을 나란히 세우는 도구이기 때문이다. Spiroglou의 논문이
     * 존재하는 이유가 정확히 그 결함이다.
     * <b>다만 치환이 「상승」의 뜻을 바꾼다</b> — 모멘텀이 그대로여도 <b>변동성이 줄면</b>
     * {@code MACD/ATR} 히스토그램은 오른다. 나쁜 신호는 아니지만 Elder가 그것까지 뜻했다고 읽으면
     * 안 된다. <b>둘이 얼마나 자주 갈리는지는 재보지 않았다</b> — 주봉 고전 MACD를 저장하지 않는다.
     *
     * <p><b>원문에 없는데 더한 것</b>: {@code OBV_T_13 > 1.96}. 개념은 Granville의 OBV이고
     * 임계는 이 지표가 이미 구간으로 들고 있는 95% 유의수준이다(그 위가 「매집 뚜렷」).
     *
     * <p><b>옮기지 못한 것이 둘이다.</b> 하나는 Elder의 <b>둘째 화면</b>이 요구하는
     * 「되돌림을 기다린다」로, 조건으로 두지 않고 위 국면 판정으로 갈음했다 — 그래서 크게 밀리지
     * 않은 종목도 걸린다. 다른 하나는 <b>셋째 화면</b>의 장중 진입 시점이다.
     * <b>MUST NOT: 셋째를 「원리적으로 불가」로 적지 않는다</b> — {@code price_daily.high}가 있어
     * 「종가 &gt; 전일 고가」는 계산된다. 못 하는 것은 장중 체결 시점이고, 이 스크리너가 종가
     * 기준이라 진입 타이밍 축을 아예 만들지 않은 것이다(spec §3·§10이 진입가 제시를 금한다).
     *
     * <p>MUST NOT: ±150·±50을 국내 분포로 재보정하지 않는다(ADR-0109) — 원저가 그 시장을 보고
     * 쓴 수다. <b>구간이 닫혀 있는 것은 원문과 다르다</b>: 원문은 {@code 50 > X > -150}(열림)인데
     * {@code BETWEEN}은 양끝을 포함한다. 저장 자릿수 8에서 차이는 사실상 없고, 열린 구간으로
     * 쪼개면 {@code MACD_V > -150} 하나가 <b>99.2% 통과</b>(과매도가 1,348 중 11)가 되어
     * ADR-0061의 90% 규칙에 걸린다 — 그래서 한 조건으로 둔다.
     *
     * <p>시장 축(ADR-0106)을 안 쓴다. 넷 다 <b>부호 판정</b>이거나 <b>무차원 문헌 상수</b>라
     * 통화가 개입하지 않는다.
     *
     * <p>착수 시점 실측(2026-08-31 · 편입 1,350): 조건별 통과율이 시장마다
     * 72.5/74.7/37.2 · 80.5/81.3/68.0 · 69.5/60.0/30.4 · 40.0/28.0/23.2%이고
     * <b>열두 칸 어디도 90%를 넘지 않는다.</b> 넷을 모두 통과한 것은 코스피 43 · 코스닥 21 ·
     * 미국 35다. 네 지표의 순위상관은 최대 0.772로 여섯 쌍 다 0.8 미만이다.
     * 형제 추세 프리셋 셋과 겹치는 것은 99 중 9뿐이다.
     */
    private static ScreenPreset tripleScreenRebound() {
        return new ScreenPreset(
                TRIPLE_SCREEN_REBOUND,
                "3중창 반등",
                "주봉 흐름이 위를 향하기 시작한 종목 중, 일봉 모멘텀이 바닥에서 막 돌아선 것을 "
                        + "남긴다. 주봉 MACD-V 히스토그램이 오르고 있고(아직 0 아래여도 된다), "
                        + "일봉 MACD-V가 -150~50 구간에 있으면서 시그널선 위로 올라섰으며, "
                        + "거래량이 매집 쪽으로 통계적으로 뚜렷한 종목이다. "
                        + "다중 시간축 판단은 Elder의 3중창을, 모멘텀 국면 구분은 Spiroglou의 "
                        + "MACD-V를, 매집 판정은 Granville의 OBV를 따랐다. "
                        + "원문에서 옮기지 못한 것이 둘이다 — 3중창의 둘째 화면이 요구하는 "
                        + "「되돌림을 기다린다」는 조건으로 두지 않고 모멘텀 국면으로 갈음했으므로 "
                        + "크게 밀리지 않은 종목도 걸리며, 셋째 화면의 장중 진입 시점은 이 "
                        + "스크리너가 종가 기준이라 다루지 않는다. "
                        + "진입가·손절가는 이 화면이 답하지 않는다. "
                        + "주봉 조건은 4년치 일봉이 있어야 계산되며, 그만큼을 갖추지 못한 종목은 "
                        + "판정불가로 빠진다(코스피 9% · 코스닥 12% · 미국 5.5%). 그 대부분은 봉이 "
                        + "모자란 것이지만, 판정되지 않은 기업행위 때문에 장기 지표가 억제된 종목도 "
                        + "소수 섞인다 — 상장이 짧아서인지 수집이 덜 된 것인지를 이 화면은 "
                        + "구분하지 않는다. "
                        + "주봉 값은 진행 중인 주를 포함하므로 주중에 조건이 켜졌다 꺼질 수 있다. "
                        + "결과 수는 시장 국면에 민감하다 — 최근 13거래일에 97건에서 328건 "
                        + "사이를 오갔다. 0건인 날도 조건이 잘못된 것이 아니다. "
                        + "점수가 아니라 충족/불충족으로 걸러낸다.",
                EvaluationMode.FILTER,
                null,
                ConditionGroup.and(List.of(
                        // 첫째 화면 — 주봉 조류가 위를 향한다. 0 아래여도 오르고 있으면 통과다.
                        literal(MacdVHistChangeWeeklyCalculator.CODE, ComparisonOperator.GT, "0"),
                        // 둘째 화면 — Spiroglou의 Rebounding 밴드
                        IndicatorCondition.of(MacdVCalculator.CODE, ComparisonOperator.BETWEEN,
                                ConditionOperand.Literal.between(
                                        new BigDecimal("-150"), new BigDecimal("50"))),
                        // 같은 국면 정의의 나머지 절반 — 시그널선 위
                        literal(MacdVHistCalculator.CODE, ComparisonOperator.GT, "0"),
                        // 매집이 통계적으로 뚜렷한가
                        literal(ObvT13Calculator.CODE, ComparisonOperator.GT, "1.96"))));
    }

    // ── 조건 생성 헬퍼 ────────────────────────────────────────────────────

    /**
     * <b>시장마다 다른 임계.</b> 같은 프리셋이 두 시장에서 <b>같은 선택도</b>를 갖게 한다 —
     * 그것이 「같은 뜻」의 조작적 정의다.
     *
     * <p>옮기는 방법은 <b>분위 보존</b>이다. ADR-0073 §규칙이 <i>"미국 프리셋을 만들 때 이
     * 임계를 복사하지 않고 그 시장 분포로 다시 잰다"</i>로 처방했다.
     *
     * <p><b>기준은 보존 창 전체다</b>(ADR-0109) — 국내 2025-12-03~2026-08-28 · 미국
     * 2025-12-10~2026-08-28, 각 180거래일. 국내 활성 350 ↔ {@code US_TOP1000} 1,000의
     * <b>(종목,일자) 관측을 한 표본으로 모아</b> 분위를 잡고 <b>유효숫자 3자리</b>로
     * 반올림했다. <b>단면 하나로 재지 않는 이유</b>: 가격 유래 임계가 그날의 국면을 타
     * {@code CMF_21}은 방향까지 뒤집혔고 {@code BETA_104W}는 21% 갈렸다. 재무 유래는
     * 분기마다 계단으로 바뀌어 단면과 창이 사실상 같다.
     *
     * <p>MUST NOT: 이 헬퍼를 안 쓴 리터럴을 「시장 중립」으로 읽지 않는다. 중립의 근거가 될 수
     * 있는 것은 <b>부호·개수·연수·백분위·상대비교</b>뿐이다(P27 축①). 여기 없는 무차원
     * 임계는 <b>문헌 원문값이라 그대로 둔 것</b>이다 — 출처가 코드에 없던 다섯은
     * 2026-09-01에 이 헬퍼로 옮겨 그 문장이 참이 됐다(ADR-0109).
     *
     * <p>IMPORTANT — {@code DEBT_RATIO}는 옮기면서 <b>뜻이 한 겹 바뀐다.</b> 국내의 100은
     * D/E = 1.0이라는 회계적 경계이기도 한데, 미국의 183은 그 경계가 아니라 <b>같은 순위</b>다.
     * 미국 대형주의 레버리지 분포가 국내와 달라 둘을 동시에 만족시킬 수 없다 — 선택도를
     * 지키는 쪽을 골랐고, 그 대가를 여기 적어 둔다.
     */
    private static ConditionOperand marketLiteral(String krwValue, String usdValue) {
        Map<String, ConditionOperand.Literal> byCurrency = new LinkedHashMap<>();
        byCurrency.put(KRW, ConditionOperand.Literal.of(krwValue));
        byCurrency.put(USD, ConditionOperand.Literal.of(usdValue));
        return new ConditionOperand.MarketLiteral(byCurrency);
    }

    private static IndicatorCondition requiredIn(String code, ComparisonOperator operator,
                                                 String krwValue, String usdValue) {
        return IndicatorCondition.of(code, operator, marketLiteral(krwValue, usdValue)).asRequired();
    }

    private static IndicatorCondition weightedIn(String code, ComparisonOperator operator,
                                                 String krwValue, String usdValue, String weight) {
        return IndicatorCondition.of(code, operator, marketLiteral(krwValue, usdValue))
                .withWeight(new BigDecimal(weight));
    }

    private static IndicatorCondition literalIn(String code, ComparisonOperator operator,
                                                String krwValue, String usdValue) {
        return IndicatorCondition.of(code, operator, marketLiteral(krwValue, usdValue));
    }

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
