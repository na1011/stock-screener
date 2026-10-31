package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.us.shareclass.CoverUnitJudge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 연차 보고서를 아직 안 낸 등록인 — 가장 늦은 10-Q 누계의 시작이 회계연도 시작이고, 그 창의 끝이 선언 결산월과 맞을 때만 받는다. 픽스처는
 * XOM 지주회사 재편 승계 등록인(CIK 2115436)의 실제 companyfacts를 다섯 태그로 자른 것이다(2026-10-31 · 그 등록인의 정기 공시는 2026
 * 2분기 10-Q 하나).
 *
 * <p>무너지는 방식이 조용하다 — 연차 창이 없으면 라벨을 하나도 못 만들어 보고서가 0건이고, 그 종목은 주식수가 없어 순위에 못 든 채
 * 아무 실패도 남기지 않는다(시총 10위권이 편입 밖이었다). 반대로 설립일부터의 기간(재편 껍데기 · 스팩)을 누계로 받으면 라벨이 한 분기씩
 * 밀린다 — 선언 결산월이 그것을 거른다.
 */
class SecEdgarInterimOnlyTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/sec/companyfacts-xom-successor-interim-only.json");

    private static List<RawFinancialReport> build(String json, boolean usGate, Integer fiscalYearEndMonth) throws Exception {
        JsonNode facts = new ObjectMapper().readTree(json).path("facts");
        return SecEdgarFacts.build(facts, 2018, usGate, CoverUnitJudge.Result.NONE, fiscalYearEndMonth).reports();
    }

    private static String fixture() throws Exception {
        return Files.readString(FIXTURE, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("10-Q 하나뿐인 등록인도 그 분기 보고서를 낸다 — 반기 누계 2026-01-01이 회계연도 시작이고 끝 12-31이 선언 결산월 12와 맞는다")
    void interimOnlyRegistrantYieldsItsQuarter() throws Exception {
        for (boolean usGate : new boolean[]{false, true}) {
            List<RawFinancialReport> reports = build(fixture(), usGate, 12);

            assertThat(reports).as("관문 %s — 비교 열(2025)은 자기 보고서가 이 원장에 없어 라벨을 안 받는다", usGate).hasSize(1);
            RawFinancialReport q2 = reports.get(0);
            assertThat(q2.fiscalYear()).isEqualTo(2026);
            assertThat(q2.fiscalPeriod()).isEqualTo("Q2");
            assertThat(q2.periodStart()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(q2.periodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
            assertThat(q2.revenue()).isEqualByComparingTo("116017000000");
            assertThat(q2.sharesOutstanding()).as("표지 그대로").isEqualByComparingTo("4111911960");
        }
    }

    @Test
    @DisplayName("선언 결산월이 없거나 누계로 세운 끝과 안 맞으면 창을 안 세운다 — 0건(미표시)이지 틀린 라벨이 아니다")
    void noSeedWithoutAMatchingDeclaredFiscalYearEnd() throws Exception {
        assertThat(build(fixture(), true, null)).as("결산월 없음").isEmpty();
        assertThat(build(fixture(), true, 6)).as("결산월 6 — 누계 끝 12-31과 안 맞음").isEmpty();
    }

    @Test
    @DisplayName("설립일부터의 기간(누계가 회계연도 첫날이 아님)은 선언 결산월이 거른다 — 재편 껍데기 · 스팩 꼴")
    void stubSinceIncorporationIsRejectedByTheDeclaredMonth() throws Exception {
        // 누계 시작을 설립일(05-10)로 옮긴다 — 05-10~06-30은 분기보다 짧아 누계로 안 받히고, 남는 3개월(04-01)이 시작이 되면 끝이 03-31이다.
        String stub = fixture().replace("\"start\": \"2026-01-01\"", "\"start\": \"2026-05-10\"");

        assertThat(stub).as("픽스처를 실제로 바꿨다").isNotEqualTo(fixture());
        assertThat(build(stub, true, 12)).isEmpty();
    }
}
