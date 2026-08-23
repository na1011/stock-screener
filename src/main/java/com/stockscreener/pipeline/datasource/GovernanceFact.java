package com.stockscreener.pipeline.datasource;

import com.stockscreener.indicator.GovernanceMetric;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 원자료에서 유도한 「종목 × 회계연도 × 스칼라」 하나. {@code governance_fact}의 한 행이다.
 *
 * @param fiscalYear  <b>회계연도</b>다. 요청한 {@code bsns_year}가 아니다 — 배당은 한 응답이
 *                    3개년을 열로 주고 최대주주 변동은 누적이라 더 넓다
 * @param metric      {@link GovernanceMetric}. 쓰기도 읽기도 이 enum 하나를 지나는 것이
 *                    {@code metric_key}에 CHECK를 걸지 않는 근거다
 * @param value       숫자. 문자열에서 나오는 판정은 여기 오기 전에 0/1로 바뀐다
 * @param publishedAt 이 값을 실은 보고서의 공시일. 지표가 <b>봉마다 「그날 공시돼 있던 회계연도」</b>를
 *                    고르는 근거이며, 없으면 이듬해 3월에 나온 값을 회계연도 말일에 알았던 것처럼 쓴다
 * @param receiptNo   유도의 근거가 된 접수번호. 보고서 둘을 견줘 만드는 값은 <b>둘 중 큰 쪽</b>이다
 */
public record GovernanceFact(
        int fiscalYear,
        GovernanceMetric metric,
        BigDecimal value,
        LocalDate publishedAt,
        String receiptNo) {

    public GovernanceFact {
        if (metric == null) {
            throw new IllegalArgumentException("metric이 null이다. fiscalYear=" + fiscalYear);
        }
        if (value == null) {
            throw new IllegalArgumentException(
                    "value가 null이다 — 값이 없으면 fact를 만들지 않는다. metric=" + metric);
        }
        if (publishedAt == null) {
            throw new IllegalArgumentException(
                    "publishedAt이 null이다 — 공시일 없는 값은 시점을 판정할 수 없다. metric=" + metric);
        }
    }
}
