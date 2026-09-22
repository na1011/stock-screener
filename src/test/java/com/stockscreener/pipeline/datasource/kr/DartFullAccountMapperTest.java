package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영업활동현금흐름을 계정명으로 찾을 때의 판별을 고정한다.
 *
 * <p>여기서 재는 것은 <b>총계와 소계를 가르는가</b>다. {@code 영업활동에서 창출된 현금}은
 * 이자·법인세 차감 전이라 총계보다 20% 안팎 크고, 둘을 나란히 내는 회사가 실제로 있다
 * (삼성전기 2022). 잘못 고르면 값이 그럴듯하게 틀리는데 H-05는 5년 합이라 오차가 누적된다.
 *
 * <p>아래 이름은 전부 <b>실측</b>이다 — 350종목 적재에서 표준계정 ID로 못 찾은 행들의 실제
 * {@code account_nm}이다.
 */
class DartFullAccountMapperTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "영업활동순현금흐름 합계",          // 삼성전기 2022 (표준코드 미사용)
            "영업활동으로 인한 순현금흐름",      // 현대글로비스 2021 (표준코드 미사용)
            "I. 영업활동으로인한현금흐름",       // 코오롱인더 2023 (ID가 …InOperations)
            "영업활동으로부터의 순현금유입",     // 이마트 2021·2022 — '현금흐름'이라 말하지 않는다
            "영업활동현금흐름",
            "영업활동으로부터의 현금흐름",
            "Ⅰ.영업활동 현금흐름",
    })
    @DisplayName("총계로 읽어야 하는 이름을 알아본다")
    void recognizesOperatingTotals(String accountName) {
        assertThat(DartFullAccountMapper.looksLikeOperatingCashFlowTotal(accountName)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "영업활동에서 창출된 현금",          // 삼성전기 2022가 총계와 나란히 갖고 있다
            "영업에서 창출된 현금흐름",
            "투자활동순현금흐름 합계",
            "재무활동순현금흐름 합계",
            "이자의 수취",
            "기말 현금및현금성자산",
            "기초 현금및현금성자산",
    })
    @DisplayName("소계와 다른 구간을 총계로 읽지 않는다")
    void rejectsSubtotalsAndOtherSections(String accountName) {
        assertThat(DartFullAccountMapper.looksLikeOperatingCashFlowTotal(accountName)).isFalse();
    }

    @Test
    @DisplayName("null 계정명에 값을 만들지 않는다")
    void rejectsNull() {
        assertThat(DartFullAccountMapper.looksLikeOperatingCashFlowTotal(null)).isFalse();
    }

    // ---------- 지배주주 자본 — 저장한 응답으로 0콜 드라이런 ----------

    /**
     * 픽스처는 <b>실제 {@code fnlttSinglAcntAll} 응답의 재무상태표 섹션</b>이다
     * (2026-09-21 · {@code DartControllingEquityProbeLiveTest}가 남겼다).
     *
     * <p><b>왜 저장하는가</b>: 국내에는 전체 재무제표의 드라이런 수단이 없었다 — 미국은
     * {@code .factcache} 1,214종목을 스윕에 먹이는데 국내는 이 형태의 픽스처가 <b>0개</b>였고,
     * 그래서 매퍼를 고칠 때마다 소스를 다시 부르거나 재적재로 확인하게 됐다. 재적재는
     * {@code invalidate}로 지표를 지우므로 검증 수단이 될 수 없다.
     *
     * <p><b>셋이 세 갈래를 다 태운다</b> — 직접 태그 · 비지배가 0인 회사 · 지분 구분이 없는 회사.
     */
    private static List<JsonNode> balanceSheet(String ticker) throws Exception {
        Path path = Path.of("src/test/resources/fixtures/dart/full-statement",
                ticker + "-bs-2025.json");
        JsonNode body = new tools.jackson.databind.ObjectMapper()
                .readTree(Files.readString(path, StandardCharsets.UTF_8));
        return DartFullAccountMapper.sections(body.get("list"), "BS");
    }

    @Test
    @DisplayName("지배주주 자본 — 직접 태그를 계정명이 아니라 account_id로 읽는다")
    void readsControllingEquityByAccountId() throws Exception {
        // 두산: 계정명이 「지배기업 소유주지분」이다. 실측 계정명이 28가지라 이름으로는 못 찾는다.
        assertThat(DartFullAccountMapper.controllingEquity(balanceSheet("000150")))
                .isEqualByComparingTo(new BigDecimal("1559193000000"));
    }

    @Test
    @DisplayName("항등식이 선다 — 자본총계 = 지배주주 + 비지배")
    void controllingEquityPlusNoncontrollingEqualsTotal() throws Exception {
        List<JsonNode> bs = balanceSheet("000150");
        BigDecimal total = DartFullAccountMapper.find(
                bs, DartFullAccountMapper.ID_TOTAL_EQUITY, DartAccountMapper.TOTAL_EQUITY);
        BigDecimal controlling = DartFullAccountMapper.controllingEquity(bs);
        BigDecimal noncontrolling = DartFullAccountMapper.find(
                bs, DartFullAccountMapper.ID_NONCONTROLLING_INTERESTS, null);
        assertThat(controlling.add(noncontrolling)).isEqualByComparingTo(total);
        // 두산은 비지배가 87.3%다 — PBR 분모가 그만큼 작아진다.
        assertThat(total.divide(controlling, 2, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo(new BigDecimal("7.85"));
    }

    @Test
    @DisplayName("비지배지분이 없는 회사에서는 자본총계와 같은 값이 온다")
    void controllingEqualsTotalWhenNoNoncontrolling() throws Exception {
        List<JsonNode> bs = balanceSheet("207940");
        assertThat(DartFullAccountMapper.controllingEquity(bs))
                .isEqualByComparingTo(DartFullAccountMapper.find(
                        bs, DartFullAccountMapper.ID_TOTAL_EQUITY, DartAccountMapper.TOTAL_EQUITY));
    }

    @Test
    @DisplayName("지분 구분 행이 없으면 값을 지어내지 않는다")
    void doesNotInventWhenNoEquitySplit() throws Exception {
        // MUST: 여기서 자본총계로 폴백하면 「비지배가 없다」를 행 하나로 판정하는 것이다.
        // 그 판정은 종목 단위 증거를 요구한다(ADR-0159 §결정). 실측으로 이렇게 비는 종목은 4뿐이다.
        assertThat(DartFullAccountMapper.controllingEquity(balanceSheet("100790"))).isNull();
    }

    // ── 지배주주 순이익의 네 갈래 (2026-09-22) ───────────────────────────────

    @Test
    @DisplayName("직접 태그가 있으면 그것을 쓴다 — 게이트와 무관하다")
    void usesDirectTagRegardlessOfGate() {
        List<JsonNode> income = incomeRows("""
                {"account_id":"ifrs-full_ProfitLoss","sj_div":"CIS","thstrm_amount":"1000"}
                {"account_id":"ifrs-full_ProfitLossAttributableToOwnersOfParent","sj_div":"CIS","thstrm_amount":"900"}
                """);
        assertThat(DartFullAccountMapper.controllingIncome(income, false))
                .isEqualByComparingTo("900");
        assertThat(DartFullAccountMapper.controllingIncome(income, true))
                .isEqualByComparingTo("900");
    }

    @Test
    @DisplayName("비지배 손익 행이 있으면 연결총액에서 뺀다 — 산수라 게이트가 필요 없다")
    void derivesBySubtractingNoncontrolling() {
        List<JsonNode> income = incomeRows("""
                {"account_id":"ifrs-full_ProfitLoss","sj_div":"CIS","thstrm_amount":"1000"}
                {"account_id":"ifrs-full_ProfitLossAttributableToNoncontrollingInterests","sj_div":"CIS","thstrm_amount":"150"}
                """);
        assertThat(DartFullAccountMapper.controllingIncome(income, false))
                .isEqualByComparingTo("850");
    }

    /**
     * <b>이 시험이 치명 1의 잠금이다.</b> 게이트가 열리고 닫히는 두 상태에서 같은 응답이
     * 다른 답을 내야 한다 — 닫힌 쪽이 {@code null}이 아니면 「비지배 전무」가
     * <b>보고서 하나로</b> 판정되고 있다는 뜻이고, 그러면 별도재무제표(OFS)를 받은 기간에
     * <b>별도 순이익이 지배주주 값으로 박힌다.</b> 실측으로 {@code 450080}은 FY의 CFS가
     * {@code 013}이라 OFS만 오는데 <b>비지배 손익이 41.5%</b>인 회사다.
     */
    @Test
    @DisplayName("지분 구분이 없을 때 게이트가 답을 가른다 — 닫히면 비운다")
    void gateDecidesWhenNoEquitySplit() {
        List<JsonNode> income = incomeRows("""
                {"account_id":"ifrs-full_ProfitLoss","sj_div":"CIS","thstrm_amount":"1000"}
                """);
        assertThat(DartFullAccountMapper.controllingIncome(income, true))
                .as("게이트가 열리면 연결총액이 곧 지배주주 몫이다")
                .isEqualByComparingTo("1000");
        assertThat(DartFullAccountMapper.controllingIncome(income, false))
                .as("게이트가 닫히면 지어내지 않고 비운다")
                .isNull();
    }

    @Test
    @DisplayName("연결총액도 없으면 게이트가 열려도 비운다")
    void staysNullWithoutTotal() {
        List<JsonNode> income = incomeRows("""
                {"account_id":"ifrs-full_Revenue","sj_div":"IS","thstrm_amount":"5000"}
                """);
        assertThat(DartFullAccountMapper.controllingIncome(income, true)).isNull();
    }

    @Test
    @DisplayName("비지배 행의 존재를 값이 아니라 행으로 센다 — 0도 증거다")
    void countsNoncontrollingRowNotItsValue() {
        assertThat(DartFullAccountMapper.hasNoncontrollingIncome(incomeRows("""
                {"account_id":"ifrs-full_ProfitLossAttributableToNoncontrollingInterests","sj_div":"CIS","thstrm_amount":"0"}
                """))).as("0으로 낸 것도 「낸 적 있다」다").isTrue();
        assertThat(DartFullAccountMapper.hasNoncontrollingIncome(incomeRows("""
                {"account_id":"ifrs-full_ProfitLoss","sj_div":"CIS","thstrm_amount":"1000"}
                """))).isFalse();
    }

    /** 한 줄에 행 하나씩 적은 JSON을 손익 행 목록으로 만든다. */
    private static List<JsonNode> incomeRows(String lines) {
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        List<JsonNode> rows = new java.util.ArrayList<>();
        for (String line : lines.strip().split("\n")) {
            if (!line.isBlank()) {
                rows.add(mapper.readTree(line.strip()));
            }
        }
        return rows;
    }
}
