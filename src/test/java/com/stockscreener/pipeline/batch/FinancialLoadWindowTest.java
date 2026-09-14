package com.stockscreener.pipeline.batch;

import com.stockscreener.indicator.IndicatorRegistry;
import com.stockscreener.support.TestDatabaseGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 적재 기본 창이 지표가 읽는 창을 덮는지 단언한다.
 *
 * <p><b>이 시험이 ADR-0145의 실제 방어선이다.</b> 잡의 경고는 로그 한 줄이고 premise는
 * 세션 시작의 사후 검출이라, 둘 다 <b>사람이 읽어야</b> 발화한다. 실측된 누수도 로그가
 * <i>"2020년 이후"</i>를 정확히 찍었는데 놓쳐서 났다.
 *
 * <p>여기서 붉으면 고칠 것은 <b>적재 기본값</b>이다. 지표가 더 깊은 재무를 요구하게 된
 * 것이고, 그때 세 상수를 함께 올려야 한다. <b>MUST NOT: 이 시험의 기대값을 낮춰서 통과시키지
 * 않는다</b> — 그러면 그 창 밖 회계연도가 옛 규칙의 값으로 고정되는데 <b>에러도 결손 신고도
 * 없고 최신 일자 화면은 멀쩡하다.</b>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class FinancialLoadWindowTest {

    @Autowired
    private IndicatorRegistry registry;

    @Autowired
    private FinancialLoadWindow loadWindow;

    @Test
    void 적재_기본_창이_지표가_읽는_창보다_넓다() {
        int needed = registry.maxFinancialYears();

        // 「연수」와 「행 수」는 단위가 다르다 — 적재는 asOf.year − N부터 돌고 지표는 회계연도
        // 행을 센다. 비12월 결산은 회계연도가 역년 둘에 걸치므로 같은 N이 한 해 적게 걸린다.
        // 그래서 같으면 안 되고 커야 한다.
        assertThat(FinancialRefillJob.DEFAULT_YEARS_BACK)
                .as("financial-refill 기본 창이 지표 요구(%d년)를 못 덮는다", needed)
                .isGreaterThan(needed);
        assertThat(FullStatementRefillJob.DEFAULT_YEARS_BACK)
                .as("full-statement-refill 기본 창이 지표 요구(%d년)를 못 덮는다", needed)
                .isGreaterThan(needed);
        assertThat(InitialLoadJob.LoadRange.defaults().financialYears())
                .as("initial-load 기본 창이 지표 요구(%d년)를 못 덮는다 — MembershipGapFiller가 "
                        + "이 값으로 신규 편입 종목을 채운다", needed)
                .isGreaterThan(needed);
    }

    @Test
    void 창이_좁으면_경고하고_넓으면_조용하다() {
        int needed = loadWindow.yearsNeededByIndicators();

        assertThat(needed).isPositive();
        // 경고는 로그로만 나가므로 여기서 재는 것은 「부르고 안 터진다」까지다.
        // 무엇을 찍는지는 javadoc이 정본이고, 실제 방어는 위 시험이다.
        loadWindow.warnIfShorterThanIndicators("test", needed - 1);
        loadWindow.warnIfShorterThanIndicators("test", needed + 1);
    }
}
