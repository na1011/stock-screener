package com.stockscreener.pipeline.collector.web;

import com.stockscreener.pipeline.collector.CorporateActionReader;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 효력일 이후 상장주식수가 바뀐 날 하나.
 *
 * <p>MUST NOT: 이 목록에서 하나를 골라 내려보내지 않는다. 루닛 2026-05-04는 창 안에 셋이 있고
 * (05-15 전환권행사 · 05-26 신주상장 · 06-19 잡음) 고르는 규칙이 틀린 날 사람은 그것을 알
 * 방법이 없다. {@code matchesPrice}가 참인 것이 <b>둘 이상이면 그 자체가 신호다.</b>
 *
 * @param relative 관측 가격 배수 × 이 비율. 이 비율의 권리락이 그날을 설명하면 1 부근이다.
 */
public record ShareJumpResponse(LocalDate date, BigDecimal sharesBefore, BigDecimal sharesAfter,
                                BigDecimal ratio, BigDecimal relative, boolean matchesPrice) {

    public static ShareJumpResponse of(CorporateActionReader.ShareJump jump) {
        return new ShareJumpResponse(jump.date(), jump.sharesBefore(), jump.sharesAfter(),
                jump.ratio(), jump.relative(), jump.matchesPrice());
    }
}
