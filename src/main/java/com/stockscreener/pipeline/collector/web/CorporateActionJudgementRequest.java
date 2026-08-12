package com.stockscreener.pipeline.collector.web;

import com.stockscreener.pipeline.collector.CorporateActionLedger;
import com.stockscreener.pipeline.collector.CorporateActionStatus;
import com.stockscreener.pipeline.datasource.CorporateAction;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 사람이 고치겠다는 것. <b>비운 항목은 그대로 둔다.</b>
 *
 * <p>{@code status}에 {@code @NotNull}을 걸지 않는 것이 의도다 — 종류를 모르는 행에
 * {@code listingDate}만 채우고 판정은 미루는 것이 실제로 필요한 경로다. 전부 비면 원장이
 * 400으로 거절한다.
 *
 * <p>Jakarta 제약을 안 쓰는 이유는 여기서 검증할 수 있는 것이 거의 없기 때문이다. 지켜야 할
 * 것은 <b>항목들 사이의 관계</b>({@code type}과 {@code ratio}가 함께 오는가, 승인인데 종류를
 * 아는가, 옮길 날짜가 비었는가)이고 그 판정의 정본은 {@link CorporateActionLedger}다 —
 * 컨트롤러에 두면 다른 호출부가 그 검사를 건너뛴다.
 */
public record CorporateActionJudgementRequest(CorporateActionStatus status,
                                              CorporateAction.Type type, BigDecimal ratio,
                                              LocalDate effectiveDate, LocalDate listingDate,
                                              String note) {

    public CorporateActionLedger.Judgement toJudgement() {
        return new CorporateActionLedger.Judgement(status, type, ratio, effectiveDate,
                listingDate, note);
    }
}
