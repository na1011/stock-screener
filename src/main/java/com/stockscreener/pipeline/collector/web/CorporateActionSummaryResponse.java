package com.stockscreener.pipeline.collector.web;

import com.stockscreener.pipeline.collector.CorporateActionReader;
import com.stockscreener.pipeline.collector.CorporateActionStatus;

/**
 * 상태별 행 수와 그중 종류를 모르는 수.
 *
 * <p>ADR-0049가 <b>"이 수가 이 phase의 성공을 가른다"</b>고 적은 값이다 — 원장 행 수가 아니라
 * <b>공시가 붙어 적용 가능해진 비율</b>이 성패를 가른다. {@code unknownType}이 사람이 값을
 * 지어내야 하는 행 수이고, 그 수가 안 줄면 소스 경로가 일을 못 하고 있다는 뜻이다.
 */
public record CorporateActionSummaryResponse(CorporateActionStatus status, long total,
                                             long unknownType) {

    public static CorporateActionSummaryResponse of(CorporateActionReader.StatusCount count) {
        return new CorporateActionSummaryResponse(count.status(), count.total(),
                count.unknownType());
    }
}
