package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계정 매핑과 금액 파싱을 검증한다.
 *
 * <p>실측에서 확인한 두 가지가 핵심이다 — 금액이 콤마 포함 문자열이고,
 * 같은 계정명이 중복으로 나온다.
 */
class DartAccountMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("콤마 포함 문자열을 파싱한다 — 실측 형태가 그렇다")
    void parsesCommaSeparatedAmount() {
        assertThat(DartAccountMapper.parseAmount("333,605,938,000,000"))
                .isEqualByComparingTo("333605938000000");
    }

    @Test
    @DisplayName("음수를 파싱한다 — 적자 기업이 있다")
    void parsesNegativeAmount() {
        assertThat(DartAccountMapper.parseAmount("-1,234,000"))
                .isEqualByComparingTo("-1234000");
    }

    @Test
    @DisplayName("빈 값은 null이다 — 0으로 대체하면 '값이 0'이라는 다른 사실이 된다")
    void treatsBlankAsNull() {
        assertThat(DartAccountMapper.parseAmount("")).isNull();
        assertThat(DartAccountMapper.parseAmount("  ")).isNull();
        assertThat(DartAccountMapper.parseAmount("-")).isNull();
        assertThat(DartAccountMapper.parseAmount(null)).isNull();
    }

    @Test
    @DisplayName("해석할 수 없는 값은 null이다 — 예외로 종목 전체를 잃지 않는다")
    void returnsNullForGarbage() {
        assertThat(DartAccountMapper.parseAmount("N/A")).isNull();
    }

    @Test
    @DisplayName("후보군 순서대로 매칭한다 — 업종마다 계정명이 다를 수 있다")
    void matchesByCandidateOrder() {
        List<JsonNode> rows = List.of(
                row("영업수익", "5000", "1"),
                row("기타", "100", "2"));

        // 후보군에 매출액·수익(매출액)·영업수익이 있고 실제로는 영업수익만 존재한다.
        assertThat(DartAccountMapper.find(rows, DartAccountMapper.REVENUE, false))
                .isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("중복 계정은 ord가 작은 첫 행을 쓴다 — 당기순이익이 실제로 두 번 나온다")
    void picksFirstByOrd() {
        List<JsonNode> rows = List.of(
                row("당기순이익(손실)", "999", "12"),
                row("당기순이익(손실)", "45206805000000", "9"));

        assertThat(DartAccountMapper.find(rows, DartAccountMapper.NET_INCOME, false))
                .isEqualByComparingTo("45206805000000");
    }

    @Test
    @DisplayName("일치하는 계정이 없으면 null이다 — 호출부가 집계해 로그로 남긴다")
    void returnsNullWhenNoMatch() {
        List<JsonNode> rows = List.of(row("알수없는계정", "100", "1"));

        assertThat(DartAccountMapper.find(rows, DartAccountMapper.REVENUE, false)).isNull();
    }

    @Test
    @DisplayName("누적 금액을 요청하면 thstrm_add_amount를 쓴다 — Q4 유도의 근거다")
    void readsCumulativeAmount() {
        JsonNode row = MAPPER.readTree("""
                {"account_nm":"매출액","ord":"1",
                 "thstrm_amount":"86,061,747,000,000",
                 "thstrm_add_amount":"239,768,567,000,000"}""");

        assertThat(DartAccountMapper.find(List.of(row), DartAccountMapper.REVENUE, true))
                .isEqualByComparingTo("239768567000000");
        assertThat(DartAccountMapper.find(List.of(row), DartAccountMapper.REVENUE, false))
                .isEqualByComparingTo("86061747000000");
    }

    @Test
    @DisplayName("사업보고서는 누적 컬럼이 비어 있어 당기 금액으로 대체한다")
    void fallsBackToCurrentWhenCumulativeMissing() {
        JsonNode row = MAPPER.readTree("""
                {"account_nm":"매출액","ord":"1",
                 "thstrm_amount":"333,605,938,000,000","thstrm_add_amount":""}""");

        assertThat(DartAccountMapper.find(List.of(row), DartAccountMapper.REVENUE, true))
                .isEqualByComparingTo("333605938000000");
    }

    private static JsonNode row(String accountName, String amount, String ord) {
        return MAPPER.readTree("""
                {"account_nm":"%s","thstrm_amount":"%s","ord":"%s"}"""
                .formatted(accountName, amount, ord));
    }
}
