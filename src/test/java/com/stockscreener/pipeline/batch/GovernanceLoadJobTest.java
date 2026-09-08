package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.datasource.GovernanceEndpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 표마다 <b>몇 해를 받는가</b>와 그 합이 몇 콜인가.
 *
 * <p>이 표에 펜스가 필요한 이유는 <b>바꿔도 아무것도 안 깨지기</b> 때문이다. 해를 줄이면
 * 저장 창 앞부분이 조용히 비고(계단 함수라 최신 일자는 멀쩡하다), 늘리면 쿼터를 태우는데
 * 잡은 성공으로 끝난다. 콜 수는 문서 넷에 적혀 있어 <b>실제와 갈리면 아무도 못 잡는다</b> —
 * 실제로 한 번 갈렸다.
 *
 * <p>해 수의 근거는 전부 실측이다({@code docs/api-spec/dart-governance.md}).
 */
class GovernanceLoadJobTest {

    /** 가장 최근 사업보고서가 나온 회계연도. 2026년에 도는 배치가 보는 값이다. */
    private static final int LATEST = 2025;

    /** 활성 편입 종목 수. 콜 수 환산에만 쓴다. */
    private static final int UNIVERSE = 350;

    @Test
    @DisplayName("표마다 받는 회계연도가 실측이 정한 대로인가")
    void yearsPerEndpointMatchTheMeasuredNeed() {
        Map<GovernanceEndpoint, List<Integer>> expected = new LinkedHashMap<>();
        // 이익소각이 상장 이래 누적이라 3년 차분에 끝점 둘이 필요하고, 창 앞에서도 그 차분이
        // 서려면 한 해가 더 필요하다.
        expected.put(GovernanceEndpoint.SHARE_TOTALS, List.of(2025, 2024, 2023, 2022, 2021));
        // 한 응답이 3개년을 열로 주지만 그 셋이 **같은 공시일**을 갖는다. 한 번만 부르면
        // FY2024 배당을 2026년 3월에야 알게 된 것이 되어 창 앞 137거래일이 빈다(2026-09-08 실측).
        expected.put(GovernanceEndpoint.DIVIDEND, List.of(2025, 2024));
        // 개발가이드는 3개년을 준다고 적었으나 실측은 1행이고 fscl_year가 '-'였다.
        expected.put(GovernanceEndpoint.EXEC_PAY, List.of(2025, 2024));
        // 3년 합이 창 앞부분에서도 서려면 넉 해가 필요하다.
        expected.put(GovernanceEndpoint.OUTSIDE_DIRECTOR, List.of(2025, 2024, 2023, 2022));
        expected.put(GovernanceEndpoint.LARGEST_HOLDER, List.of(2025, 2024));
        expected.put(GovernanceEndpoint.MINORITY_HOLDER, List.of(2025, 2024));
        // 요청 연도까지의 누적이라 창은 우리가 자르지만, 창 앞에서도 값이 서려면 그 시점에
        // 이미 나와 있던 보고서가 필요하다.
        expected.put(GovernanceEndpoint.CONTROL_CHANGE, List.of(2025, 2024));
        // 1콜이 3개년이라 세 번이면 아홉 해가 온다.
        expected.put(GovernanceEndpoint.AUDIT_OPINION, List.of(2025, 2022, 2019));

        assertThat(expected.keySet())
                .as("표를 더하거나 빼면 여기도 함께 고친다 — 안 고치면 그 표만 조용히 안 받힌다")
                .containsExactlyInAnyOrder(GovernanceEndpoint.values());

        expected.forEach((endpoint, years) ->
                assertThat(GovernanceLoadJob.yearsFor(endpoint, LATEST, null))
                        .as("%s가 받는 해", endpoint)
                        .containsExactlyElementsOf(years));
    }

    @Test
    @DisplayName("전량 적재가 350종목 7,700콜이다 — 문서 넷이 이 수를 인용한다")
    void fullLoadCostsTheDocumentedNumberOfCalls() {
        int pairs = 0;
        for (GovernanceEndpoint endpoint : GovernanceEndpoint.values()) {
            pairs += GovernanceLoadJob.yearsFor(endpoint, LATEST, null).size();
        }

        assertThat(pairs).as("(엔드포인트, 연도) 쌍").isEqualTo(22);
        assertThat(pairs * UNIVERSE)
                .as("DART 보수 예산 10,000의 77%다. 이 수가 바뀌면 batch-load·api-sources skill과 "
                        + "ADR-0081·api-spec dart-governance.md의 인용을 함께 고친다 — 한 번 갈린 적이 있다")
                .isEqualTo(7700);
    }

    @Test
    @DisplayName("--governance-span이 표의 기본 해 수를 덮는다 — 없으면 한 해에 다섯 해를 받는다")
    void spanOverrideNarrowsToASingleYear() {
        assertThat(GovernanceLoadJob.yearsFor(GovernanceEndpoint.SHARE_TOTALS, 2021, 1))
                .as("이미 받아 둔 해에 한 해만 얹을 때의 형태")
                .containsExactly(2021);
        assertThat(GovernanceLoadJob.yearsFor(GovernanceEndpoint.SHARE_TOTALS, 2021, null))
                .as("덮지 않으면 그 표의 기본 다섯 해가 그대로 걸린다")
                .hasSize(5);
    }

    @Test
    @DisplayName("한 응답이 3개년을 주는 표는 세 해씩 건너뛴다")
    void multiYearEndpointStridesByThree() {
        assertThat(GovernanceLoadJob.yearsFor(GovernanceEndpoint.AUDIT_OPINION, LATEST, null))
                .as("안 건너뛰면 같은 3개년을 세 번 받아 콜이 세 배가 되는데 결과가 같아 안 드러난다")
                .containsExactly(2025, 2022, 2019);
    }
}
