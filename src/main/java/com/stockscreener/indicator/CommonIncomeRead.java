package com.stockscreener.indicator;

import java.math.BigDecimal;

/**
 * 재무 행 하나에 붙은 보통주 귀속 이익(미국 관문 켬 · ADR-0191 · 국내 관문 켬 · ADR-0192 · 표 {@code financial_common_income}).
 * 이 값이 있는 행(= 미국 관문 켬)은 주당 지표 분자를 이 규칙으로만 낸다 — 못 정한 행이면 그 창은 값이 없다(지배 NI로 안 내려간다).
 *
 * @param value  보통주 몫({@code ni}면 null — 배분 0이라 행 지배 NI가 곧 보통주 몫이다)
 * @param basis  {@code T} 중단 포함 합 · {@code C} 계속영업 몫(계속영업 EPS에서 지었다) · 유도가 안 돈 행이면 null
 * @param branch 갈래({@code decl} · {@code adj} · {@code ni} · {@code lp} · {@code eps} · {@code cls} · {@code q4}) · 못 정함이면 그 밖 ·
 *               유도가 안 돈 행이면 null. 국내({@code kr-ni} · {@code exw} · {@code cum} · {@code q4} · ADR-0192)는 {@code kr-ni}도 값을 싣는다
 * @param payoutBase     배당성향 분모 — 국내만(사용자 결정 「지배 NI − 신종자본증권 몫」 · null이면 그 해 미표시)
 * @param payoutDeclared 분모를 판정이 정했는가(국내) — 거짓이면 분모는 보통주 몫(중단 포함 · 미국)
 * @param nonCommon      보통주가 아닌 청구권(우선주 장부가 · GP 자본 · 우선 단위 — 미국 · 탐색 {@code PERSHARE-DEN-PLAN-1026.md} v1.1) — PBR 분모는
 *                       지배 자본 − 이 값. {@link #equityBranch}가 있는데 null이면 못 정함
 * @param ncavNonCommon  NCAV가 더 빼는 영구자본 청구권(우선주 · 우선 단위 — GP 자본 제외)
 * @param equityBranch   자본 판정 갈래 — {@link #equityRead}인데 null이면 유도가 그 판에 안 돈 행(못 정함)
 * @param equityRead     질의가 자본 열을 실었는가(미국 · 국내 관문 켬). 거짓이면 보통주 자본은 이 규칙으로 안 읽는다
 * @param commonFraction 보통주와 나란한 잔여 청구권 중 보통주 몫(국내 — 보통주 유통 ÷ 전 종류 유통 · ADR-0199). null이면 1(미국)
 */
public record CommonIncomeRead(BigDecimal value, String basis, String branch, BigDecimal payoutBase, boolean payoutDeclared,
                               BigDecimal nonCommon, BigDecimal ncavNonCommon, String equityBranch, boolean equityRead,
                               BigDecimal commonFraction) {

    /** 국내 자본 갈래 — 별도 기저로 정함(별도재무제표만 내는 회사 · 총자본이 곧 지배 자본이다). */
    public static final String KR_SEPARATE = "kr-s";

    public CommonIncomeRead(BigDecimal value, String basis, String branch, BigDecimal payoutBase, boolean payoutDeclared,
                            BigDecimal nonCommon, BigDecimal ncavNonCommon, String equityBranch, boolean equityRead) {
        this(value, basis, branch, payoutBase, payoutDeclared, nonCommon, ncavNonCommon, equityBranch, equityRead, null);
    }

    public CommonIncomeRead(BigDecimal value, String basis, String branch, BigDecimal payoutBase, boolean payoutDeclared) {
        this(value, basis, branch, payoutBase, payoutDeclared, null, null, null, false);
    }

    /** 미국 — 배당성향 분모는 보통주 몫(배당 총액이 보통주 DPS × 보통주 수다 · ADR-0191). */
    public CommonIncomeRead(BigDecimal value, String basis, String branch) {
        this(value, basis, branch, null, false);
    }

    /** 미국 — 보통주 자본 재료를 함께 싣는다. */
    public CommonIncomeRead(BigDecimal value, String basis, String branch, BigDecimal nonCommon, BigDecimal ncavNonCommon,
                            String equityBranch) {
        this(value, basis, branch, null, false, nonCommon, ncavNonCommon, equityBranch, true);
    }

    /** 자본 판정이 비보통 청구권을 정했는가 — {@link #equityRead}인데 거짓이면 그 행의 보통주 자본은 못 정함이다(미표시). */
    public boolean equityDecided() {
        return equityBranch != null && nonCommon != null;
    }

    /**
     * 손익 몫을 비운 사본 — 그 기간 손익 칸의 기저가 대체된 봉({@code FinancialFigures.asSeenOn} · 재고 A6). 갈래는 「못 정함」으로 바꿔 지배 NI
     * 갈래(배분 0)로 내려가지 않게 하고, 자본 재료는 잔액이라 그대로 둔다.
     */
    public CommonIncomeRead withoutIncome() {
        return new CommonIncomeRead(null, basis, "none:superseded", null, payoutDeclared, nonCommon, ncavNonCommon, equityBranch,
                equityRead, commonFraction);
    }

    /** 보통주 몫 — 미국은 1(우선주는 {@link #nonCommon}이 장부가로 뺀다). */
    public BigDecimal fraction() {
        return commonFraction == null ? BigDecimal.ONE : commonFraction;
    }

    /** 국내 별도 기저로 정한 행인가 — 지배 자본 칸이 비어도 총자본이 곧 지배 자본이다(비지배가 없다). */
    public boolean separateEntity() {
        return KR_SEPARATE.equals(equityBranch);
    }

    public boolean sameAsControlling() {
        return "ni".equals(branch);
    }

    public boolean continuing() {
        return "C".equals(basis);
    }
}
