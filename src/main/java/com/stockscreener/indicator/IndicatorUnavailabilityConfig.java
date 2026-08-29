package com.stockscreener.indicator;

import com.stockscreener.indicator.calculator.AltmanZCalculator;
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
        return List.of(
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
                                + "투하자본(자산총계 − 유동부채)을 계산할 재료가 없습니다."),
                governanceMissing(DividendYieldCalculator.CODE),
                governanceMissing(DividendPayoutCalculator.CODE),
                governanceMissing(TreasuryShareRatioCalculator.CODE),
                governanceMissing(ProfitRetirement3yCalculator.CODE),
                governanceMissing(ExecPayToNetIncomeCalculator.CODE),
                governanceMissing(OutsideDirectorRatioCalculator.CODE),
                governanceMissing(DirectorTurnover3yCalculator.CODE),
                governanceMissing(LargestHolderStakeCalculator.CODE),
                governanceMissing(MinorityHolderStakeCalculator.CODE),
                governanceMissing(ControlTransfer3yCalculator.CODE),
                governanceMissing(AuditOpinionAdverseCalculator.CODE),
                governanceMissing(AuditEmphasisCalculator.CODE),
                governanceMissing(AuditorChange3yCalculator.CODE));
    }

    /**
     * 거버넌스 지표가 없을 때, <b>그 종목의 정기보고서 주요정보가 통째로 없는 경우에만</b>
     * 사유를 붙인다(ADR-0081).
     *
     * <p>MUST: 문구가 <b>우리 데이터에 대한 사실</b>이지 그 회사에 대한 주장이 아니다.
     * 실측으로 이 상태인 종목은 둘이고 둘 다 구조적이지만(인프라 투자회사·외국 발행 증권),
     * 셋째가 다른 이유로 생기면 그 문장이 거짓이 된다 — 그리고 「그럴듯한데 틀린 사유는 아무
     * 말도 하지 않는 것보다 나쁘다」가 이 클래스의 MUST NOT이다.
     *
     * <p>지표 하나만 없는 경우(무배당이라 배당수익률이 없는 것 등)에는 <b>아무 말도 하지
     * 않는다.</b> 원인이 여럿인데 {@link UnavailabilityRule.Context}가 그것을 가르지 못한다.
     */
    private static UnavailabilityRule governanceMissing(String code) {
        return new UnavailabilityRule(code,
                context -> context.value(OutsideDirectorRatioCalculator.CODE) == null
                        && context.value(MinorityHolderStakeCalculator.CODE) == null
                        && context.value(TreasuryShareRatioCalculator.CODE) == null,
                "이 종목은 DART 정기보고서 주요정보가 한 행도 수집되지 않아 거버넌스 지표를 "
                        + "계산할 수 없습니다.");
    }

    /**
     * 유동/비유동 구분이 없는 업종. 금융업·보험업·금융서비스업이다.
     *
     * <p>{@code AltmanZCalculator}의 {@code {KSIC:65, KSIC:66}}과 <b>일부러 다르다.</b> 그쪽은
     * "지표가 뜻을 잃는 업종"이라 64를 뺐고(지주회사가 섞여 있다), 이쪽은 "재료가 오지 않는
     * 업종"이라 64가 들어간다 — 지주회사든 은행이든 유동부채가 안 오는 것은 같기 때문이다.
     *
     * <p><b>원소는 체계 접두가 붙은 키다</b>(ADR-0092). SIC 쪽 집합은 전량 적재 뒤 실측으로
     * 정한다 — 미국 은행·REIT도 유동/비유동을 안 가르지만(실측 커버리지 15/19)
     * <b>어느 2자리가 그 집합인지는 값을 받아야 안다.</b>
     */
    private static final Set<String> FINANCIAL_DIVISIONS =
            Set.of("KSIC:64", "KSIC:65", "KSIC:66");

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
