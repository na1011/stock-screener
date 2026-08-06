package com.stockscreener.pipeline.datasource.kr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * DART 주요계정 응답의 {@code account_nm}을 우리 필드로 옮긴다.
 *
 * <p>실측(삼성전자·KB금융·카카오·NAVER)에서는 네 회사 모두 같은 계정명을 썼다. 금융지주까지
 * {@code 매출액}으로 나온 것으로 보아 주요계정 API가 계정명을 정규화하는 것으로 보인다.
 * 그래도 <b>후보군 집합</b>으로 매칭하는 이유는 확인한 표본이 4개사뿐이고 350종목에는
 * 업종이 훨씬 다양하기 때문이다.
 *
 * <p>MUST: 매칭 실패를 조용히 넘기지 않는다. null로 두되 호출부가 집계해 로그로 남긴다 —
 * 대량 발생은 후보군을 보강해야 한다는 신호이며, 그 신호가 없으면 지표가 사라진 이유를
 * 알 수 없다.
 */
final class DartAccountMapper {

    private static final Logger log = LoggerFactory.getLogger(DartAccountMapper.class);

    /** 손익계산서. 분기 보고서에서 누적금액을 갖는다. */
    static final String SJ_INCOME = "IS";
    /** 재무상태표. 시점 잔액이므로 Q4 유도 시 차감하면 안 된다. */
    static final String SJ_BALANCE = "BS";

    static final Set<String> REVENUE = ordered("매출액", "수익(매출액)", "영업수익");
    static final Set<String> OPERATING_INCOME = ordered("영업이익", "영업이익(손실)");
    static final Set<String> NET_INCOME = ordered("당기순이익(손실)", "당기순이익");
    static final Set<String> TOTAL_ASSETS = ordered("자산총계");
    static final Set<String> TOTAL_LIABILITIES = ordered("부채총계");
    static final Set<String> TOTAL_EQUITY = ordered("자본총계");

    private DartAccountMapper() {
    }

    private static Set<String> ordered(String... names) {
        return new LinkedHashSet<>(List.of(names));
    }

    /**
     * 후보군 중 처음 일치하는 계정의 당기 금액을 찾는다.
     *
     * <p>같은 계정명이 여러 번 나올 수 있다 — 실측에서 {@code 당기순이익(손실)}이 두 번
     * 나왔다(지배기업/비지배 지분으로 추정되나 계정명만으로는 구분되지 않는다).
     * {@code ord}가 작은 첫 행을 취하고, 3개 이상이면 로그를 남긴다.
     *
     * @param cumulative true면 {@code thstrm_add_amount}(누적), false면 {@code thstrm_amount}
     */
    static BigDecimal find(List<JsonNode> rows, Set<String> candidates, boolean cumulative) {
        for (String candidate : candidates) {
            List<JsonNode> matches = rows.stream()
                    .filter(row -> candidate.equals(row.path("account_nm").asString("")))
                    .sorted(java.util.Comparator.comparingInt(
                            row -> parseOrd(row.path("ord").asString(""))))
                    .toList();
            if (matches.isEmpty()) {
                continue;
            }
            if (matches.size() > 2) {
                log.warn("계정 '{}'이 {}번 중복된다 — 첫 행을 쓴다. 매핑 규칙 재확인이 필요하다.",
                        candidate, matches.size());
            }
            BigDecimal value = amount(matches.get(0), cumulative);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 금액을 파싱한다.
     *
     * <p>실측 형태가 {@code "333,605,938,000,000"}처럼 <b>콤마 포함 문자열</b>이다.
     * 빈 문자열과 {@code "-"}는 null로 둔다 — 0으로 대체하면 "값이 0"이라는 다른 사실이 된다.
     */
    static BigDecimal amount(JsonNode row, boolean cumulative) {
        String field = cumulative ? "thstrm_add_amount" : "thstrm_amount";
        String raw = row.path(field).asString("");
        if (raw.isBlank() && cumulative) {
            // 사업보고서에는 누적 컬럼이 비어 있다. 당기 금액이 곧 연간 누계다.
            raw = row.path("thstrm_amount").asString("");
        }
        return parseAmount(raw);
    }

    static BigDecimal parseAmount(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.strip().replace(",", "").replace(" ", "");
        if (cleaned.isEmpty() || "-".equals(cleaned)) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            log.warn("금액을 해석할 수 없다: '{}'", raw);
            return null;
        }
    }

    private static int parseOrd(String raw) {
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
