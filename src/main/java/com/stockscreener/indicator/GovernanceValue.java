package com.stockscreener.indicator;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 거버넌스 사실 하나와 <b>그것을 언제 알았는지</b>.
 *
 * <p>공시일을 값에 붙여 두는 것이 요점이다. 사업보고서는 회계연도가 끝난 이듬해 3월에 나오므로
 * (실측 중앙값 2026-03-18) 값만 나르면 계산기가 <b>그 값을 회계연도 말일에 알았던 것처럼</b> 쓴다.
 *
 * <p>값마다 갖는 이유는 지표가 <b>표를 가로지르기</b> 때문이다 — 최대주주 지분율은 최대주주 표와
 * 주식 총수 표를 나누는데, 한쪽만 정정되면 두 값의 공시일이 갈린다.
 */
public record GovernanceValue(BigDecimal value, LocalDate publishedAt, LocalDate visibleFrom) {

    public GovernanceValue {
        if (value == null || publishedAt == null) {
            throw new IllegalArgumentException("값과 공시일은 둘 다 있어야 한다.");
        }
    }

    /** 보이는 날을 따로 안 싣는 값 — 보이는 날은 공시일이다. */
    public GovernanceValue(BigDecimal value, LocalDate publishedAt) {
        this(value, publishedAt, null);
    }

    /**
     * 보이기 시작하는 날(관문 켬 · {@code ReportingBasis.visibleFrom} · 없으면 공시일) — 보이기 소비자는 이것을, 기업행위 배수의 앵커는
     * {@link #publishedAt}을 읽는다.
     */
    public LocalDate visibleOn() {
        return visibleFrom != null ? visibleFrom : publishedAt;
    }
}
