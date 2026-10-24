package com.stockscreener.indicator;

import java.math.BigDecimal;

/**
 * 재무 행 하나에 붙은 보통주 귀속 이익(미국 관문 켬 · 탐색 {@code PERSHARE-NUM-PLAN-1024.md} v2.1 · 표 {@code financial_common_income}).
 * 이 값이 있는 행(= 미국 관문 켬)은 주당 지표 분자를 이 규칙으로만 낸다 — 못 정한 행이면 그 창은 값이 없다(지배 NI로 안 내려간다).
 *
 * @param value  보통주 몫({@code ni}면 null — 배분 0이라 행 지배 NI가 곧 보통주 몫이다)
 * @param basis  {@code T} 중단 포함 합 · {@code C} 계속영업 몫(계속영업 EPS에서 지었다) · 유도가 안 돈 행이면 null
 * @param branch 갈래({@code decl} · {@code adj} · {@code ni} · {@code lp} · {@code eps} · {@code cls} · {@code q4}) · 못 정함이면 그 밖 ·
 *               유도가 안 돈 행이면 null
 */
public record CommonIncomeRead(BigDecimal value, String basis, String branch) {

    public boolean sameAsControlling() {
        return "ni".equals(branch);
    }

    public boolean continuing() {
        return "C".equals(basis);
    }
}
