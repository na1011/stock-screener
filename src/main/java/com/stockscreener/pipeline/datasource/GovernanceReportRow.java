package com.stockscreener.pipeline.datasource;

import java.time.LocalDate;
import java.util.Map;

/**
 * 정기보고서 주요정보 응답의 <b>한 행을 원문 그대로</b> 나르는 것.
 *
 * <p>여기서 값을 해석하지 않는 것이 요점이다. 여덟의 필드명이 전부 다르고 값 집합이 개발가이드와
 * 어긋난 자리가 넷이라(§16-C), 어댑터가 타입을 확정하면 <b>매핑을 한 번 틀릴 때마다 수천 콜을
 * 다시 태워야 한다.</b> 벤더 경계는 어댑터가 아니라 {@link GovernanceFactMapper}에 둔다.
 *
 * @param endpoint    어느 표에서 왔는가. 도메인 이름이다
 * @param bsnsYear    요청한 사업연도. <b>응답 행의 회계연도가 아니다</b> — {@code DIVIDEND}는
 *                    한 응답에 3개년이 열로 오고 {@code CONTROL_CHANGE}는 누적이라 더 넓다
 * @param reportCode  요청한 보고서 코드({@code 11011} 사업보고서)
 * @param receiptNo   소스 {@code rcept_no}. <b>데이터 없음 행은 빈 문자열</b>이다
 * @param publishedAt {@code rcept_no}에서 유도한 공시일. <b>데이터 없음 행은 null</b>이다 —
 *                    폴백으로 회계연도 말일을 넣으면 없는 공시일을 지어내게 된다
 * @param ordinal     응답 배열의 0부터의 순번. <b>데이터 없음 행은 -1</b>이다
 * @param fields      행의 필드 전부. 키는 소스 필드명 그대로이고 값은 문자열 그대로다
 */
public record GovernanceReportRow(
        GovernanceEndpoint endpoint,
        int bsnsYear,
        String reportCode,
        String receiptNo,
        LocalDate publishedAt,
        int ordinal,
        Map<String, String> fields) {

    /** 데이터 없음 행의 {@code ordinal}. 「미수집」과 「그 회사에 그 표가 없음」을 가른다. */
    public static final int NO_DATA_ORDINAL = -1;

    public GovernanceReportRow {
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint가 null이다.");
        }
        receiptNo = receiptNo == null ? "" : receiptNo;
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    /**
     * 소스가 «그 회사에 그 표가 없다»고 답한 것을 남기는 행.
     *
     * <p>안 남기면 <b>「아직 안 받았다」와 구별되지 않는다.</b> 커버리지 결손의 원인을
     * 업종·구조·수집으로 가르는 판정이 그 구분 위에 선다(phase 기준 §P18 축①).
     */
    public static GovernanceReportRow noData(GovernanceEndpoint endpoint, int bsnsYear,
                                             String reportCode) {
        return new GovernanceReportRow(endpoint, bsnsYear, reportCode, "", null,
                NO_DATA_ORDINAL, Map.of());
    }

    public boolean isNoData() {
        return ordinal == NO_DATA_ORDINAL;
    }

    /**
     * 데이터 없음 행에 <b>그 보고서의</b> 접수번호와 공시일을 채운다.
     *
     * <p>「그 회사의 그 표가 비어 있다」는 것도 시점이 있는 사실이다 — 최대주주 변동이 없었다는
     * 것을 <b>언제 알았는가</b>가 지표의 계단 함수에 필요하다. 그런데 {@code 013} 응답에는
     * {@code rcept_no}가 없다.
     *
     * <p>같은 종목·같은 사업연도의 <b>다른 표</b>가 같은 정기보고서에서 오므로 그 접수번호가
     * 이 행의 것이기도 하다. <b>지어내는 값이 아니라 같은 보고서의 값</b>이라는 것이 요점이고,
     * 채우는 것은 잡의 몫이다(여덟을 한 순회에서 받는 곳이 거기뿐이다).
     */
    public GovernanceReportRow stampedWith(String reportReceiptNo, LocalDate reportPublishedAt) {
        if (!isNoData() || reportReceiptNo == null || reportReceiptNo.isBlank()) {
            return this;
        }
        return new GovernanceReportRow(endpoint, bsnsYear, reportCode, reportReceiptNo,
                reportPublishedAt, NO_DATA_ORDINAL, fields);
    }

    /** 필드 하나. 없으면 빈 문자열이다 — 소스의 센티널 {@code -}는 <b>그대로 돌려준다.</b> */
    public String field(String name) {
        return fields.getOrDefault(name, "");
    }
}
