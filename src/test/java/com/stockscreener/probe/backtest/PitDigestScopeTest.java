package com.stockscreener.probe.backtest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종목별 제자리-수정 해시가 <b>모집단에 의존하지 않는가</b>를 지킨다.
 *
 * <p><b>왜 있는가.</b> 이 해시가 전역 문자열 하나였을 때는 네 성분이 전부 {@code JOIN pop}을
 * 지났고, 그래서 <b>편입·편출 한 번이 전 종목을 의심으로 올렸다.</b> 값은 하나도 안 틀리는데
 * 512날짜가 비싼 길로 갔고 그 확인이 <b>약 535초</b>였다(적재와 스윕이 각각 문다).
 *
 * <p>지금은 {@code pop}이 <b>어느 종목을 낼지</b>만 정하고 각 종목의 해시는 자기 행만 본다.
 * 실측으로 2026-09-04 리밸런싱을 사이에 두고 <b>양쪽 모집단에 다 있는 1,310종목 중 해시가
 * 움직인 것이 0</b>이었다. 성분 안에 {@code pop}이 다시 들어오면 그 성질이 조용히 사라진다 —
 * 값은 여전히 맞고 <b>느려질 뿐</b>이라 어떤 시험도 붉어지지 않는다.
 *
 * <p><b>DB도 스프링도 안 쓴다.</b> 정적 SQL 하나를 보는 검사라 {@code ./gradlew test}에서
 * 매번 돌고, 그래서 이 성질이 깨진 커밋이 백테스트 태스크를 돌리기 전에 잡힌다.
 * <b>MUST NOT: {@code @Tag("backtest")}를 붙이지 않는다</b> — 붙이면 `test`에서 빠져,
 * 이 결함이 가장 조용한 시점에 검사도 함께 사라진다.
 */
class PitDigestScopeTest {

    /** 성분마다 <b>그 종목 하나</b>로 묶는 술어. 하나라도 빠지면 그 성분이 모집단을 본다. */
    private static final String[] PER_STOCK_BINDINGS = {
            "f.stock_id = pop.id",
            "g.stock_id = pop.id",
            "s.id = pop.id",
            "m.stock_id = pop.id",
            "p.stock_id = pop.id",
            "ca.stock_id = pop.id",
    };

    /**
     * 성분이 <b>통째로 빠지지 않았는가.</b>
     *
     * <p>묶임만 보면 성분 하나를 지운 것을 못 본다 — 그러면 그 표의 제자리 수정이
     * <b>영영 안 잡히고</b>, 시각 훑기도 못 보는 축이라(`financial_statement`·
     * {@code governance_fact}에는 {@code updated_at}이 없다) <b>조용히 낡은 값이 표가 된다.</b>
     * 묶임 술어가 그 표를 이름으로 들고 있으므로 아래 검사가 그것을 함께 지킨다.
     */
    @Test
    @DisplayName("종목별 해시의 네 성분이 전부 그 종목으로 묶여 있다")
    void everyComponentIsBoundToOneStock() {
        String sql = PitChangeDetector.inPlaceDigestSql();
        for (String table : new String[]{
                "financial_statement", "governance_fact", "stock", "universe_membership",
                "price_daily", "corporate_action"}) {
            assertThat(normalized(sql))
                    .as("제자리 수정을 하는 표가 해시에서 빠졌다 — 시각 훑기도 그 축을 못 보므로 "
                            + "조용히 낡은 값이 표가 된다: " + table)
                    .contains(table);
        }
        for (String binding : PER_STOCK_BINDINGS) {
            assertThat(normalized(sql))
                    .as("성분이 그 종목으로 안 묶였다 — 모집단이 바뀌기만 해도 전 종목이 "
                            + "의심이 되어 512날짜가 비싼 길로 간다: " + binding)
                    .contains(binding);
        }
    }

    /**
     * {@code pop}을 <b>집합으로</b> 쓰는 자리가 남아 있지 않은가.
     *
     * <p>{@code JOIN pop}·{@code IN (SELECT … pop)}이 성분 안에 들어오면 그 성분의 값이
     * <b>다른 종목의 편입 여부</b>에 달리게 된다. 바깥의 {@code FROM pop}과 CTE 정의는
     * 그래도 되는 유일한 자리이고, 그 둘만 남기고 센다.
     */
    @Test
    @DisplayName("성분 안에서 모집단을 집합으로 쓰지 않는다")
    void noComponentTreatsPopulationAsASet() {
        String sql = normalized(PitChangeDetector.inPlaceDigestSql());

        assertThat(sql)
                .as("성분이 모집단을 조인한다 — 편입·편출이 다시 전 종목의 해시를 흔든다")
                .doesNotContain("join pop");

        // 허용된 형태를 지우고 **남는 것이 있는지** 본다. 개수를 박아 두면 SQL을 정당하게
        // 손댈 때마다 그 수를 고치게 되고, 그러면 펜스가 「고치는 절차」로 전락한다.
        String rest = sql
                .replace("with pop as", "")
                .replace("from pop", "")
                .replaceAll("pop\\.id", "");
        Matcher leftover = Pattern.compile("\\bpop\\b").matcher(rest);
        assertThat(leftover.find())
                .as("모집단을 허용되지 않은 형태로 쓴다 — 성분이 다른 종목의 편입 여부에 "
                        + "달리면 편입·편출이 다시 전 종목의 해시를 흔든다. 남은 자리: " + rest)
                .isFalse();
    }

    /** 줄바꿈·연속 공백을 하나로 접는다. SQL을 다시 정렬해도 검사가 안 깨지게 한다. */
    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }
}
