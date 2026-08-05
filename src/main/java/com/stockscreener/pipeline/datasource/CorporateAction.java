package com.stockscreener.pipeline.datasource;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 수정계수에 영향을 주는 기업 행위. v1은 액면분할·병합만 다룬다.
 *
 * <p>{@code ratio}는 <b>1주가 몇 주가 되었는가</b>다. 50:1 액면분할이면 50, 10주를 1주로
 * 합치는 병합이면 0.1이다. 이 방향을 뒤집으면 과거 시세가 50배로 부풀고, 그 오류는
 * 분할 종목의 차트를 직접 보기 전까지 드러나지 않는다.
 *
 * @param effectiveDate 변경된 가격으로 거래가 시작되는 날. 이 날 <b>이전</b> 봉들이 조정 대상이다.
 * @param source        이 사건을 무엇이 알려줬는가. 소스 공시와 휴리스틱 탐지를 구분해야
 *                      자동 적용 여부를 판단할 수 있다.
 */
public record CorporateAction(
        LocalDate effectiveDate,
        Type type,
        BigDecimal ratio,
        DetectionSource source
) {

    public CorporateAction {
        if (effectiveDate == null) {
            throw new IllegalArgumentException("effectiveDate가 null이다.");
        }
        if (ratio == null || ratio.signum() <= 0) {
            throw new IllegalArgumentException("ratio는 양수여야 한다: " + ratio);
        }
    }

    public enum Type {
        /** 액면분할. ratio > 1. */
        SPLIT,
        /** 액면병합. ratio < 1. */
        REVERSE_SPLIT
    }

    /**
     * IMPORTANT — 이 구분이 자동 적용 여부를 가른다.
     *
     * <p>{@link #SOURCE_DISCLOSURE}는 신뢰할 수 있으므로 자동 재계산해도 된다.
     * {@link #HEURISTIC}은 하한가 연속·유상증자를 분할로 오인할 수 있고, 오탐으로 2년치를
     * 잘못 재계산하면 되돌리기가 매우 비싸다. 그래서 후보로만 기록하고 사람이 승인한다.
     */
    public enum DetectionSource {
        SOURCE_DISCLOSURE,
        HEURISTIC
    }
}
