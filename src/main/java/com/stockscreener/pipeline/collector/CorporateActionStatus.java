package com.stockscreener.pipeline.collector;

/**
 * 원장 한 행이 계수까지 가는 길의 어디에 있는가. V21의 {@code ck_ca_status}와 같은 집합이다.
 *
 * <p><b>MUST NOT: {@code wire-contract.json}의 {@code enums}에 넣지 않는다.</b> 그 픽스처는
 * <b>프론트가 거울로 들고 있는</b> enum만 담는다(ADR-0020). 원장 판정에는 화면이 없고
 * (ADR-0049가 그렇게 정했다) 프론트에 대응하는 상수가 없으므로, 넣으면
 * {@code contract.spec.ts}의 "픽스처의 enum을 하나도 빠뜨리지 않았다"가 깨진다.
 * 화면을 만들게 되면 그때 양쪽을 함께 더한다.
 */
public enum CorporateActionStatus {

    /** 후보. 아직 사람이 보지 않았다. */
    OBSERVED,

    /** 적용 대상. <b>계수에 들어간 것은 아니다</b> — 그것은 {@link #APPLIED}다. */
    APPROVED,

    /** 계수에 반영됐다. */
    APPLIED,

    /** 오탐이었다. 하한가 연속·거래재개가 여기 들어온다. */
    REJECTED,

    /**
     * 사건은 실재하지만 우리가 계수를 낼 수 없다.
     *
     * <p>{@link #REJECTED}와 뜻이 다르다 — 저쪽은 "그런 일이 없었다"이고 이쪽은 "있었는데 못
     * 고친다"다. 둘을 한 값으로 접으면 phase 기준의 <i>"적용하지 못한 사건이 무엇인지 이름으로
     * 말할 수 있다"</i>가 성립하지 않는다. 인적분할이 대표적이다(SK텔레콤 2021-11-29 등).
     */
    UNSUPPORTED;

    /**
     * 사람이 판정으로 찍을 수 있는 상태인가.
     *
     * <p><b>{@link #APPLIED}가 빠지는 이유</b> — 그것을 참으로 말할 수 있는 것은 재구축 안의
     * {@code markApplied()}뿐이다. 사람이 찍으면 계수는 그대로인데 원장이 반영됐다고 말하고,
     * 그 어긋남은 ADR-0049의 불변식({@code adj_close = close × adj_factor})이 보지 않는다.
     *
     * <p><b>{@link #OBSERVED}가 빠지는 이유</b> — {@code CorporateActionLedger}가
     * {@code OBSERVED} + {@code HEURISTIC}인 행을 <b>덮어도 되는 후보</b>로 읽는다. 사람이 판정한
     * 행을 그리로 되돌리면 다음 수집이 그 판정을 조용히 지운다. 판정을 미루고 싶으면 상태를 비운
     * 채로 값만 고치면 된다 — 판정 자체가 선택이다.
     */
    public boolean humanAssignable() {
        return this == APPROVED || this == REJECTED || this == UNSUPPORTED;
    }
}
