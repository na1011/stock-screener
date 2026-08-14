package com.stockscreener.indicator;

import com.stockscreener.indicator.calculator.AltmanZCalculator;
import com.stockscreener.indicator.calculator.EpsTtmCalculator;
import com.stockscreener.indicator.calculator.PerCalculator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;

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
                        AltmanZCalculator.CODE,
                        // 계산기가 명시적으로 제외하는 업종이다(ADR-0044). 값이 안 나오는 것이
                        // 결손이 아니라 설계이며, 그 사실을 화면이 말해야 한다고
                        // AltmanZCalculator의 javadoc이 요구해 두었다.
                        context -> AltmanZCalculator.isInapplicable(context.industryDivision()),
                        "보험·금융서비스에는 적용하지 않는 지표입니다. "
                                + "계산 자체는 되지만 그 업종의 재무 구조에서는 뜻을 갖지 않습니다."));
    }

    private static boolean isNegative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }
}
