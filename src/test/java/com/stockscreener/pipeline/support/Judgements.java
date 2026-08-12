package com.stockscreener.pipeline.support;

import com.stockscreener.pipeline.collector.CorporateActionLedger.Judgement;
import com.stockscreener.pipeline.collector.CorporateActionStatus;

/**
 * 값은 그대로 두고 <b>상태만</b> 찍는 판정. 관리자 API가 하는 일 중 가장 흔한 형태다.
 *
 * <p>세 테스트가 같은 것을 만들고 있어 여기 모았다. 값까지 고치는 판정은 그 자체가 검증 대상이라
 * 각 테스트가 {@link Judgement}를 직접 만든다 — 여기에 편의 메서드를 더하면 무엇을 재는지가
 * 호출부에서 안 보이게 된다.
 */
public final class Judgements {

    private Judgements() {
    }

    public static Judgement approved(String note) {
        return statusOnly(CorporateActionStatus.APPROVED, note);
    }

    public static Judgement rejected(String note) {
        return statusOnly(CorporateActionStatus.REJECTED, note);
    }

    public static Judgement unsupported(String note) {
        return statusOnly(CorporateActionStatus.UNSUPPORTED, note);
    }

    private static Judgement statusOnly(CorporateActionStatus status, String note) {
        return new Judgement(status, null, null, null, null, note);
    }
}
