package com.stockscreener.pipeline.collector.web;

import com.stockscreener.pipeline.collector.CorporateActionReader;

import java.util.List;

/**
 * 판정에 필요한 것 전부 — 원장 행과 <b>증거</b>.
 *
 * <p>증거를 함께 주는 이유는 이 API의 목적이 "상태를 바꾸는 것"이 아니라 <b>"사람이 값을
 * 정하는 것"</b>이기 때문이다. 종류를 모르는 행이 46건인데(착수 시점 실측) 증거가 없으면
 * 사람은 그 46번을 psql로 풀어야 하고, 그러면 이 API는 있으나 마나다.
 */
public record CorporateActionDetailResponse(CorporateActionResponse action,
                                            List<ShareJumpResponse> shareJumps) {

    public static CorporateActionDetailResponse of(CorporateActionReader.Detail detail) {
        return new CorporateActionDetailResponse(
                CorporateActionResponse.of(detail.row()),
                detail.shareJumps().stream().map(ShareJumpResponse::of).toList());
    }
}
