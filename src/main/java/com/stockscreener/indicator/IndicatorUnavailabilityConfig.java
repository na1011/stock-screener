package com.stockscreener.indicator;

import com.stockscreener.indicator.calculator.AltmanZCalculator;
import com.stockscreener.indicator.calculator.AltmanZDoublePrimeCalculator;
import com.stockscreener.indicator.calculator.AuditEmphasisCalculator;
import com.stockscreener.indicator.calculator.AuditOpinionAdverseCalculator;
import com.stockscreener.indicator.calculator.AuditorChange3yCalculator;
import com.stockscreener.indicator.calculator.ControlTransfer3yCalculator;
import com.stockscreener.indicator.calculator.DirectorTurnover3yCalculator;
import com.stockscreener.indicator.calculator.DividendPayoutCalculator;
import com.stockscreener.indicator.calculator.DividendYieldCalculator;
import com.stockscreener.indicator.calculator.ExecPayToNetIncomeCalculator;
import com.stockscreener.indicator.calculator.LargestHolderStakeCalculator;
import com.stockscreener.indicator.calculator.MinorityHolderStakeCalculator;
import com.stockscreener.indicator.calculator.OutsideDirectorRatioCalculator;
import com.stockscreener.indicator.calculator.ProfitRetirement3yCalculator;
import com.stockscreener.indicator.calculator.TreasuryShareRatioCalculator;
import com.stockscreener.indicator.calculator.EpsTtmCalculator;
import com.stockscreener.indicator.calculator.NcavToMarketCalculator;
import com.stockscreener.indicator.calculator.PerCalculator;
import com.stockscreener.indicator.calculator.PsrCalculator;
import com.stockscreener.indicator.calculator.RoicMin3yCalculator;
import com.stockscreener.indicator.calculator.RoicPretaxCalculator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * "이 지표에 값이 없는 <b>이유</b>를 말할 수 있는가"의 선언.
 *
 * <p>{@link PercentileConfig}·{@link SectorMetricConfig}와 같은 형태다 — 지표 코드로 분기하는
 * 하드코딩이 아니라 <b>데이터</b>이고, 늘어나는 자리가 여기 한 곳이다.
 *
 * <p>왜 계산 시점이 아니라 응답 시점에 유도하는가: 사유를 아는 것은 계산기인데
 * {@link IndicatorCalculator#calculate}가 {@code List<IndicatorPoint>}를 돌려주고
 * {@link IndicatorPoint}는 생성자가 null 값을 거부한다. 반환형을 바꾸면 71개 계산기가 걸리고
 * 그 인터페이스의 <b>"새 지표는 클래스 하나"</b> MUST를 깬다. 그래서 {@code UnadjustedActions}
 * (ADR-0055)·{@code SectorContext}(ADR-0044)와 같은 길을 쓴다 — <b>원인 데이터를 응답 시점에
 * 다시 보고</b> 문구를 만든다.
 *
 * <p>MUST: 사유를 <b>말할 수 있는 것만</b> 여기 넣는다. 값 없는 지표를 전부 카드로 남기면
 * 빈 카드가 스무 장 깔리고 "값이 없어 표시하지 못한 지표 N개" 고지가 뜻을 잃는다.
 *
 * <p>MUST NOT: 추측을 문구로 쓰지 않는다. 그럴듯한데 틀린 사유는 <b>아무 말도 하지 않는 것보다
 * 나쁘다</b> — 사용자가 없는 결손을 믿는다. 실제로 {@code ALTMAN_Z}가 값을 못 내는 종목의
 * 다수는 유동자산 결손이라는 <b>부작용</b> 쪽인데, 종목별로 어느 계정이 비었는지는 이 계층이
 * 알 수 없으므로 그쪽은 규칙을 두지 않았다.
 */
@Configuration
public class IndicatorUnavailabilityConfig {

    @Bean
    public List<UnavailabilityRule> unavailabilityRules() {
        List<UnavailabilityRule> rules = new java.util.ArrayList<>(List.of(
                new UnavailabilityRule(
                        PerCalculator.CODE,
                        // 실측(2026-08-14) — PER이 없는 81종목 중 68이 EPS 음수이고 나머지 13은
                        // EPS도 없다(재무 부족). EPS가 양수인데 PER이 없는 종목은 0이라,
                        // 이 규칙이 엉뚱한 종목에 사유를 붙일 여지가 없다.
                        context -> isNegative(context.value(EpsTtmCalculator.CODE)),
                        "최근 4개 분기 순이익이 적자라 PER을 내지 않습니다. "
                                + "음수 PER은 '주가가 싸다'로 읽혀 반대의 뜻이 되기 때문입니다."),
                new UnavailabilityRule(
                        NcavToMarketCalculator.CODE,
                        // 재료가 원리적으로 없다 — K-IFRS 금융업은 재무상태표를 유동/비유동으로
                        // 가르지 않아 유동자산이 오지 않는다(ROIC_PRETAX가 유동부채에서 겪는
                        // 것과 같은 자리다).
                        //
                        // 실측(2026-08-21, 지표일 2026-08-20): 값이 없는 30종목 중 28이
                        // KSIC 64·65·66이다. 남는 둘에는 사유가 안 붙는데 그것이 옳다 —
                        // 그 둘이 왜 없는지 우리는 모른다.
                        context -> isFinancialDivision(context.industryDivisionKey()),
                        "금융업은 재무상태표를 유동·비유동으로 나누지 않아 "
                                + "순유동자산을 계산할 재료가 없습니다."),
                new UnavailabilityRule(
                        PsrCalculator.CODE,
                        // 매출 개념이 없거나 다르게 잡히는 업종이다. 실측(2026-08-21): 값이 없는
                        // 36종목 중 23이 KSIC 64·65·66이다.
                        //
                        // MUST NOT: 나머지 13에 이 사유를 넓혀 붙이지 않는다. 그들이 왜 없는지는
                        // 재본 적이 없고(분기 재무 부족일 수도, 매출 계정 결손일 수도 있다),
                        // 업종 조건이 그것을 자동으로 막아 준다. 비율이 64%뿐이라 이 규칙은
                        // PER·ROIC의 규칙들보다 설명력이 낮다는 것을 함께 적어 둔다.
                        context -> isFinancialDivision(context.industryDivisionKey()),
                        "금융업은 영업수익을 매출로 잡는 방식이 달라 "
                                + "매출 대비 배수를 다른 업종과 같은 뜻으로 낼 수 없습니다."),
                new UnavailabilityRule(
                        AltmanZCalculator.CODE,
                        // 계산기가 명시적으로 제외하는 업종이다(ADR-0044). 값이 안 나오는 것이
                        // 결손이 아니라 설계이며, 그 사실을 화면이 말해야 한다고
                        // AltmanZCalculator의 javadoc이 요구해 두었다.
                        context -> AltmanZCalculator.isInapplicable(context.industryDivisionKey()),
                        "보험·금융서비스에는 적용하지 않는 지표입니다. "
                                + "계산 자체는 되지만 그 업종의 재무 구조에서는 뜻을 갖지 않습니다."),
                new UnavailabilityRule(
                        AltmanZDoublePrimeCalculator.CODE,
                        // 수정모델도 정의역이 같다. 술어를 복사하지 않고 원본의 것을 그대로
                        // 부르므로 집합을 넓히면 두 카드가 함께 따라온다.
                        context -> AltmanZCalculator.isInapplicable(context.industryDivisionKey()),
                        "보험·금융서비스에는 적용하지 않는 지표입니다. "
                                + "계산 자체는 되지만 그 업종의 재무 구조에서는 뜻을 갖지 않습니다."),
                new UnavailabilityRule(
                        RoicPretaxCalculator.CODE,
                        // 계산기가 업종으로 거르는 것이 아니라 재료가 원리적으로 없다 —
                        // K-IFRS 금융업은 재무상태표를 유동/비유동으로 가르지 않는다.
                        // 실측(2026-08-18): 최근 분기 유동부채가 비어 있는 33종목이 전부
                        // KSIC 64·65·66이거나 업종 미상이다. 그 업종에서 값이 나오는 종목도
                        // 있으므로(64의 35종목 중 22) 이 규칙은 값이 없을 때만 붙고,
                        // 엉뚱한 종목에 사유를 붙일 여지가 없다.
                        context -> isFinancialDivision(context.industryDivisionKey()),
                        "금융업은 재무상태표를 유동·비유동으로 나누지 않아 "
                                + "투하자본(자산총계 − 유동부채)을 계산할 재료가 없습니다."),
                new UnavailabilityRule(
                        RoicMin3yCalculator.CODE,
                        // 분모가 ROIC_PRETAX와 같으므로 같은 업종에서 같은 이유로 값이 없다.
                        // 규칙이 코드 단위라 새 코드에 자동으로 붙지 않는다 — 안 적으면 실제로
                        // 종목을 막는 게이트 쪽이 아무 말 없이 비고, 안 막는 쪽에만 사유가 남는다.
                        //
                        // MUST NOT: "3년 연속 재무가 없다"를 여기 사유로 적지 않는다. 그것은
                        // 이 규칙이 확인할 수 없는 추측이고, 금융업이 아닌데 값이 없는 종목에
                        // 틀린 이유를 붙이게 된다(ALTMAN_Z가 업종만 말하는 것과 같은 이유다).
                        context -> isFinancialDivision(context.industryDivisionKey()),
                        "금융업은 재무상태표를 유동·비유동으로 나누지 않아 "
                                + "투하자본(자산총계 − 유동부채)을 계산할 재료가 없습니다.")));
        // 거버넌스 사유는 코드마다 **둘**이다 — 술어가 서로 배타적이라 먼저 맞는 하나만 붙는다.
        rules.addAll(GOVERNANCE_CODES.stream().flatMap(
                code -> java.util.stream.Stream.of(
                        governanceNotCollected(code), governanceOutOfMarket(code))).toList());
        return List.copyOf(rules);
    }

    /**
     * 거버넌스 원자료를 주는 시장. <b>지표마다 다르다.</b>
     *
     * <p>정본은 어댑터의 {@code supportedMarketCodes}·{@code supportedEndpoints}이고 여기 사본을
     * 두는 이유는 이 설정이 파이프라인을 끌어오지 않기 때문이다. <b>갈리면 문구가 거짓이 된다.</b>
     *
     * <p>집합 하나로 열셋을 판정하던 자리다. 미국에 배당 소스가 붙으면서(P28) 그 형태가 깨졌다 —
     * <b>둘만 오고 열하나는 안 온다.</b> 집합에 US를 더하면 나머지 열하나가 「수집되지 않았다」로
     * 바뀌어 <b>기다리면 온다는 거짓말</b>이 되고, 안 더하면 배당 둘이 「이 시장에는 원래 없다」는
     * 거짓말을 계속한다. 그래서 축을 (지표, 시장)으로 내린다.
     */
    private static final Set<String> KR_MARKETS = Set.of("KOSPI", "KOSDAQ");

    /** 미국에도 소스가 있는 거버넌스 지표. Alpaca {@code cash_dividends}가 그 원자료다. */
    private static final Set<String> US_BACKED_CODES = Set.of(
            DividendYieldCalculator.CODE, DividendPayoutCalculator.CODE);

    private static Set<String> marketsFor(String code) {
        Set<String> markets = new java.util.LinkedHashSet<>(KR_MARKETS);
        if (US_BACKED_CODES.contains(code)) {
            markets.add("US");
        }
        return Set.copyOf(markets);
    }

    /**
     * 거버넌스 지표가 없을 때, <b>그 종목의 정기보고서 주요정보가 통째로 없는 경우에만</b>
     * 사유를 붙인다(ADR-0081).
     *
     * <p>MUST: 문구가 <b>우리 데이터에 대한 사실</b>이지 그 회사에 대한 주장이 아니다.
     * 그리고 <b>그 시장에 대해서도 참이어야 한다</b>(P27 축④) — 미국을 켠 뒤 이 자리는
     * 1,000종목에서 <i>"DART 정기보고서 주요정보가 한 행도 수집되지 않아"</i>라고 말하고
     * 있었다. 미국에 DART는 애초에 없다.
     *
     * <p><b>두 문장을 가르는 것이 사용자가 할 일을 가른다.</b> 국내에서 이 상태인 종목은
     * 둘이고 둘 다 구조적이지만(인프라 투자회사·외국 발행 증권) <b>수집으로 메울 여지</b>가
     * 개념적으로 남는다. 소스가 안 덮는 시장은 <b>영영 안 온다</b> — 그것을 「수집되지
     * 않았다」로 적으면 사용자가 기다린다.
     *
     * <p>지표 하나만 없는 경우(무배당이라 배당수익률이 없는 것 등)에는 <b>아무 말도 하지
     * 않는다.</b> 원인이 여럿인데 {@link UnavailabilityRule.Context}가 그것을 가르지 못한다.
     */
    private static UnavailabilityRule governanceNotCollected(String code) {
        return new UnavailabilityRule(code,
                context -> marketsFor(code).contains(context.marketCode())
                        && governanceEmpty(context),
                "이 종목은 거버넌스 원자료가 한 행도 수집되지 않아 지표를 계산할 수 없습니다.");
    }

    /**
     * 소스가 <b>그 지표를</b> 그 시장에서 안 준다. <b>기다려도 안 온다</b>는 것이 위 문장과
     * 다른 점이고, 그 차이가 사용자가 할 일을 가른다.
     *
     * <p>MUST NOT: 여기에 {@code governanceEmpty}를 걸지 않는다. 소스가 안 덮는다는 것은
     * <b>그 종목에 다른 거버넌스 값이 있든 없든 참</b>이다 — 걸면 미국 종목이 배당 값을 가진
     * 순간 나머지 열하나가 <b>아무 사유도 없이</b> 비게 된다.
     */
    private static UnavailabilityRule governanceOutOfMarket(String code) {
        return new UnavailabilityRule(code,
                context -> !marketsFor(code).contains(context.marketCode()),
                "이 지표의 원자료는 국내 공시에서만 옵니다. 이 시장의 종목에는 "
                        + "값이 나오지 않습니다.");
    }

    /**
     * 그 종목의 거버넌스 원자료가 통째로 없는가.
     *
     * <p>MUST: <b>그 시장에서 원리적으로 값이 날 수 있는 지표</b>로만 판정한다. 국내 전용 지표로
     * 물으면 미국에서는 언제나 참이 되어, 배당을 실제로 받아 온 종목까지 「한 행도 수집되지
     * 않았다」는 말을 듣는다 — 술어가 시장을 안 보면 문구를 시장별로 갈라도 소용이 없다.
     */
    private static boolean governanceEmpty(UnavailabilityRule.Context context) {
        if (!KR_MARKETS.contains(context.marketCode())) {
            return US_BACKED_CODES.stream().allMatch(code -> context.value(code) == null);
        }
        return context.value(OutsideDirectorRatioCalculator.CODE) == null
                && context.value(MinorityHolderStakeCalculator.CODE) == null
                && context.value(TreasuryShareRatioCalculator.CODE) == null;
    }

    /** 거버넌스 사유가 붙는 지표. 새 거버넌스 지표를 더하면 여기에 함께 적는다. */
    private static final List<String> GOVERNANCE_CODES = List.of(
            DividendYieldCalculator.CODE, DividendPayoutCalculator.CODE,
            TreasuryShareRatioCalculator.CODE, ProfitRetirement3yCalculator.CODE,
            ExecPayToNetIncomeCalculator.CODE, OutsideDirectorRatioCalculator.CODE,
            DirectorTurnover3yCalculator.CODE, LargestHolderStakeCalculator.CODE,
            MinorityHolderStakeCalculator.CODE, ControlTransfer3yCalculator.CODE,
            AuditOpinionAdverseCalculator.CODE, AuditEmphasisCalculator.CODE,
            AuditorChange3yCalculator.CODE);

    /**
     * 유동/비유동 구분이 없는 업종. 금융업·보험업·금융서비스업이다.
     *
     * <p>{@code AltmanZCalculator}의 {@code {KSIC:65, KSIC:66}}과 <b>일부러 다르다.</b> 그쪽은
     * "지표가 뜻을 잃는 업종"이라 64를 뺐고(지주회사가 섞여 있다), 이쪽은 "재료가 오지 않는
     * 업종"이라 64가 들어간다 — 지주회사든 은행이든 유동부채가 안 오는 것은 같기 때문이다.
     *
     * <p><b>원소는 체계 접두가 붙은 키다</b>(ADR-0092). SIC 쪽은 Division H 전체이며
     * <b>{@code 66}은 SIC에 존재하지 않는다</b>(실측 6,266종목). 미국 은행·REIT가 유동/비유동을
     * 안 가른다는 것은 실측이 답했다({@code AssetsCurrent} 커버리지 15/19).
     * <b>{@code AltmanZCalculator}와 이번에는 SIC 쪽이 같다</b> — KR에서 둘을 가른 근거였던
     * "값이 실제로 나온 종목" 데이터가 미국에는 아직 없기 때문이고, 생기면 그때 갈린다.
     */
    private static final Set<String> FINANCIAL_DIVISIONS = Set.of(
            "KSIC:64", "KSIC:65", "KSIC:66",
            "SIC:60", "SIC:61", "SIC:62", "SIC:63", "SIC:64", "SIC:65", "SIC:67");

    /**
     * MUST: null을 먼저 거른다. {@code industryDivisionKey}은 <b>업종 미상이면 null</b>이고
     * {@code Set.of(...)}는 불변 집합이라 {@code contains(null)}에서 NPE를 던진다 — 그 예외가
     * 지표 하나가 아니라 <b>종목 상세 응답 전체</b>를 500으로 만든다.
     */
    private static boolean isFinancialDivision(String industryDivisionKey) {
        return industryDivisionKey != null && FINANCIAL_DIVISIONS.contains(industryDivisionKey);
    }

    private static boolean isNegative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }
}
