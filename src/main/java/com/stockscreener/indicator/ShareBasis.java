package com.stockscreener.indicator;

/**
 * 한 재무 판이 실은 주식수가 <b>시세와 같은 기저인가</b>.
 *
 * <p><b>왜 필요한가</b>: 시가총액 = 주식수 × 종가인데 그 둘의 기저가 다른 종목이 실재한다.
 * {@code AKTX}는 SEC 표지가 <b>보통주</b> 155,758,529,533주를 주고 시세는 <b>ADS당</b> 가격이라
 * 저장 시총이 1,565B로 미국 8위에 앉았다(2026-09-22 실측 · 회사가 밝힌 공개유동가치 $15.0M).
 * {@code AMRN}은 같은 모양의 20배다.
 *
 * <p><b>비율은 공시 표지가 판마다 준다</b>(2026-10-22 · ADR-0162) — 증권 이름({@code dei:Security12bTitle})의
 * 「each representing N Ordinary Shares」다. 관문 켬은 그것으로 예탁증권 줄 행에 {@link #DEPOSITARY}를 짓는다. 관문 밖에서는
 * 기업행위 원장이 「기저가 어긋났다」를 말해 주는 자리만 고친다({@link #SHIFTED}).
 *
 * <p><b>기업행위 원장은 「기저가 어긋났다」를 말해 준다.</b> 선언된 주식 교환이 있었는데
 * 주식수 계열이 그 비율을 안 따라갔으면, 그 뒤 판의 수는 <b>사건 전 기저</b>다. 그 판정을 담는
 * 것이 이 enum이고, {@code FinancialMath.sharesOf}와 {@code JdbcStoredShareCounts}가
 * <b>둘 다</b> 읽는다 — 한쪽만 고치면 지표는 맞는데 유니버스 순위는 그대로 오염된다.
 *
 * <p><b>MUST: NULL을 {@link #OK}와 같게 읽는다.</b> 아직 유도가 안 돈 판이 그것이고, 그때의
 * 동작은 이 축이 생기기 전과 정확히 같아야 한다. 그래야 컬럼만 만든 중간 상태가 무해하다.
 *
 * <p>분류 규칙과 실측은 ADR-0162 §결정에 있다.
 */
public enum ShareBasis {

    /**
     * 기저가 맞다. 저장된 주식수를 그대로 쓴다.
     *
     * <p>선언된 주식 교환이 없었거나, 있었고 <b>계열이 그 비율대로 따라갔다.</b>
     * 2026-09-23 실측으로 미국 재료 사건 {@code 117/143}이 여기다.
     */
    OK,

    /**
     * 사건 전 기저다. {@code share_basis_multiple}을 곱해야 시세와 짝이 맞는다.
     *
     * <p>선언된 주식 교환이 있었는데 <b>계열이 안 따라갔고, 그 뒤로도 영영 안 따라잡으며,
     * 판정불가 사건이 겹치지 않은</b> 종목이다. 지금 {@code AMRN} 하나다 —
     * 420,044,022 × 0.05 = 21,002,201이고 공개유동가치가 함의하는 20,634,000과 1.8% 안에서 만난다.
     *
     * <p><b>MUST: 배수는 판마다 다르다 — 종목 상수로 두지 않는다.</b> 사건 전에 공시된 판은
     * 배수가 1이어야 하는데 상수로 두면 {@code SharesGrowth3yCalculator}처럼 분자·분모에
     * 같은 수를 곱하는 지표에서 <b>약분돼 한 톨도 안 고쳐진다</b>(실측: {@code AMRN}
     * {@code SHARES_GROWTH_3Y}가 {@code +1,958.36%}이고 참값은 약 {@code +2.9%}다).
     */
    SHIFTED,

    /**
     * 기저가 어긋난 것은 확실한데 <b>크기를 모른다.</b> 주식수를 내지 않는다.
     *
     * <p>「계열 불변」 사건과 「다른 배수로 움직인」 사건이 <b>한 종목에 공존</b>하는 자리다 —
     * 앞이 기저가 어긋났다는 양성 증거이고, 뒤가 사슬을 끊어 총 배수를 못 세게 만든다.
     * 지금 {@code AKTX} 하나다(2026-03-31 사건은 계열 불변, 2023-08-17 사건은 증자가 겹쳐
     * 계열이 2.13배 움직였다).
     *
     * <p><b>MUST NOT: 「판정불가 사건이 있으면 여기」로 읽지 않는다.</b> 그것만으로는
     * 아무 말도 안 한다 — 판정불가만 있는 9종목({@code CVNA} $48B · {@code IR} $28B ·
     * {@code QXO} $13B · {@code BMNR} $17B 등)이 전부 정상이고, 그렇게 읽으면 그들이 통째로 사라진다.
     *
     * <p><b>MUST NOT: 부분 보정으로 갈음하지 않는다.</b> {@code AKTX}에 정당화되는 사건
     * 하나만 걸면 1,565B가 <b>39.1B · 순위 279위</b>가 된다 — 여전히 약 1,000배 틀린데
     * 8위일 때와 달리 <b>아무도 못 본다.</b> 틀린 값을 그럴듯한 값으로 바꾸는 것이 더 나쁘다.
     */
    UNRESOLVED,

    /**
     * 예탁증권 줄의 행이다(관문 켬 · ADR-0183). 저장 주식수는 <b>보통주</b>이고 {@code share_basis_multiple} = 1/k(그 공시 표지의
     * 「1 ADS = k주」)를 곱해야 ADS 시세와 짝이 맞는다. 저장 단위를 안 바꾸는 이유: EPS 함의(보통주당 EPS)와 맞대는 소비자(배당
     * 20% 게이트 · 순위 가드 훅 · 분모 판정)가 배수를 안 곱하는 자리에 있어 그대로 맞는다.
     */
    DEPOSITARY;

    /** 이 상태의 행은 {@code share_basis_multiple}을 곱해야 시세와 같은 기저가 된다. */
    public boolean multiplies() {
        return this == SHIFTED || this == DEPOSITARY;
    }

    /**
     * 저장 문자열을 읽는다. NULL은 {@link #OK}다 — 이 축이 생기기 전과 같은 동작이다.
     *
     * <p><b>MUST: 모르는 값에 던진다.</b> 「모르는 값 = OK」였을 때 새 상태가 지표 쪽에서 조용히 무시되어 순위(상태 무관하게 곱함)와
     * 지표가 k배로 갈릴 뻔했다(2026-10-22 B 계획 반증).
     */
    public static ShareBasis of(String stored) {
        if (stored == null) {
            return OK;
        }
        for (ShareBasis value : values()) {
            if (value.name().equals(stored)) {
                return value;
            }
        }
        throw new IllegalStateException("모르는 주식수 기저 상태: " + stored);
    }
}
