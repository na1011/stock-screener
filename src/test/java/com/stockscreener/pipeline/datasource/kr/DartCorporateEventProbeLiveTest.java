package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 합병·주식교환({@code cmpMgDecsn} · {@code stkExtrDecsn})이 <b>U-06을 실제로 열어 주는지</b>와
 * 감사의견({@code accnutAdtorNmNdAdtOpinion})이 무엇을 주는지 실측한다.
 *
 * <p>ADR-0039가 U-06(피인수 진행 제외)을 <i>"버림. 코퍼레이트 액션 소스가 없다"</i>로 적었고,
 * api-spec §9가 카탈로그를 받은 뒤 <i>"있다. 넉넉하다"</i>로 뒤집었다. 여기서 <b>부를 수 있는가</b>까지
 * 확인한다.
 *
 * <p><b>다만 U-06의 실익 자체를 함께 재판정해야 한다.</b> 이 API는 인수하는 쪽과 당하는 쪽이
 * 둘 다 제출하고, 우리 유니버스는 시총 상위 350이라 대개 인수하는 쪽이다. 흡수합병의
 * 소멸법인은 상장폐지되어 유니버스에서 자동으로 빠지므로, U-06이 실제로 겨냥하는 것은
 * <b>{@code bddd}(이사회결의일)부터 {@code mgsc_mgdt}(합병기일) 사이 주가가 인수가에 고정되어
 * 추세 분석이 무의미해지는 구간</b>이다. 그 두 필드가 채워져 오는지가 성립 조건이다.
 *
 * <p>감사의견은 <b>계획에 없던 부수 발견</b>이다. 비적정(한정·부적정·의견거절)은 상장폐지
 * 사유이고, 원본 명세 §1-1 ③의 <i>"부실 종목이 통과하는 것이 훨씬 비싼 실수"</i>에 정확히 맞는
 * 새 유니버스 제외 조건 후보다. <b>후보로만 기록하고 채택은 별도 phase에서 정한다</b> —
 * ADR-0039의 대조표에 없던 항목을 탐침이 조용히 늘리면 그 표가 정본 노릇을 못 하게 된다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartCorporateEventProbeLiveTest {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 합병 이력이 있는 쪽과 없는 쪽. 정상 응답과 {@code 013}이 구분되는지 보려면 둘 다 필요하다. */
    private record Sample(String ticker, String name, String note) {
    }

    private static final List<Sample> MERGER_SAMPLES = List.of(
            new Sample("068270", "셀트리온", "2023년 셀트리온헬스케어 흡수합병 — 있을 것으로 기대"),
            new Sample("005930", "삼성전자", "대조군 — 없을 것으로 기대"));

    private static final List<Sample> OPINION_SAMPLES = List.of(
            new Sample("005930", "삼성전자", "대형 제조"),
            new Sample("271560", "오리온", "api-spec 이익잉여금 결손사"));

    @Test
    @DisplayName("[live][probe] 합병·주식교환 결정이 U-06을 열어 주는지 기록한다")
    void probeMergerAndExchange() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();

        // 2015년 이후부터 정보제공이다. 넉넉히 잡아 "없다"가 기간 탓이 아니게 만든다.
        String from = LocalDate.of(2022, 1, 1).format(YMD);
        String to = LocalDate.now().format(YMD);

        System.out.println("\n===== cmpMgDecsn / stkExtrDecsn 실측 (" + from + "~" + to + ") =====");

        boolean sawMergerRow = false;
        for (Sample sample : MERGER_SAMPLES) {
            String corpCode = cache.findCorpCode(sample.ticker()).orElseThrow();
            System.out.printf("%n--- %s(%s) — %s%n", sample.name(), sample.ticker(), sample.note());

            JsonNode merger = call(client, "/cmpMgDecsn.json", corpCode, from, to);
            System.out.println("    [회사합병] status=" + DartProbeSupport.describeStatus(merger));
            for (JsonNode row : rowsOf(merger)) {
                sawMergerRow = true;
                System.out.printf("      상대회사=%s | 합병형태=%s | 이사회결의일=%s | 합병기일=%s%n",
                        row.path("mgptncmp_cmpnm").asString(""),
                        row.path("mg_stn").asString(""),
                        row.path("bddd").asString(""),
                        row.path("mgsc_mgdt").asString(""));
                System.out.printf("      거래정지 %s~%s | 합병방법=%s%n",
                        row.path("mgsc_trspprpd_bgd").asString(""),
                        row.path("mgsc_trspprpd_edd").asString(""),
                        row.path("mg_mth").asString(""));
            }

            JsonNode exchange = call(client, "/stkExtrDecsn.json", corpCode, from, to);
            System.out.println("    [주식교환·이전] status=" + DartProbeSupport.describeStatus(exchange));
            for (JsonNode row : rowsOf(exchange)) {
                sawMergerRow = true;
                System.out.printf("      대상법인=%s | 교환형태=%s | 이사회결의일=%s | 교환일자=%s%n",
                        row.path("extr_tgcmp_cmpnm").asString(""),
                        row.path("extr_stn").asString(""),
                        row.path("bddd").asString(""),
                        row.path("extrsc_extrdt").asString(""));
            }
        }

        System.out.println("\n===== 판정 =====");
        System.out.println("합병·교환 행 관측: " + sawMergerRow);
        System.out.println("U-06 성립 조건: bddd(이사회결의일)와 mgsc_mgdt(합병기일)가 둘 다 채워져야"
                + " '진행 중' 구간을 정의할 수 있다. 위 출력에서 빈 칸인지 확인한다.");
        System.out.println("IMPORTANT — 이 API는 인수하는 쪽도 제출한다."
                + " mg_stn(합병형태)·mgptncmp_cmpnm(합병상대회사)으로 방향을 가를 수 없으면,"
                + " U-06이 '피인수'가 아니라 '합병에 관여한 전부'를 자르게 된다.");
        System.out.println("호출 수: corp_code와 bgn_de/end_de가 전부 필수라 종목별로 돌아야 한다."
                + " 유니버스 350종목이면 API당 350콜, 둘이면 700콜이다.");

        assertThat(MERGER_SAMPLES).isNotEmpty();
    }

    @Test
    @DisplayName("[live][probe] 감사의견이 무엇을 주는지 기록한다")
    void probeAuditOpinion() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        int settledYear = LocalDate.now().getYear() - 1;

        System.out.println("\n===== accnutAdtorNmNdAdtOpinion.json 실측 ("
                + settledYear + " 사업보고서) =====");

        TreeSet<String> opinions = new TreeSet<>();
        TreeSet<String> years = new TreeSet<>();

        for (Sample sample : OPINION_SAMPLES) {
            String corpCode = cache.findCorpCode(sample.ticker()).orElseThrow();
            JsonNode body = DartProbeSupport.get(client, uri -> uri
                    .path("/accnutAdtorNmNdAdtOpinion.json")
                    .queryParam("crtfc_key", DartProbeSupport.apiKey())
                    .queryParam("corp_code", corpCode)
                    .queryParam("bsns_year", settledYear)
                    .queryParam("reprt_code", "11011")
                    .build());

            System.out.printf("%n--- %s(%s) status=%s%n", sample.name(), sample.ticker(),
                    DartProbeSupport.describeStatus(body));
            for (JsonNode row : rowsOf(body)) {
                String year = row.path("bsns_year").asString("");
                String opinion = row.path("adt_opinion").asString("");
                years.add(year);
                opinions.add(opinion);
                System.out.printf("      %s | 감사인=%s | 의견='%s'%n",
                        year, row.path("adtor").asString(""), opinion);
            }
        }

        System.out.println("\n===== 판정 =====");
        System.out.println("관측된 adt_opinion 값: " + opinions);
        System.out.println("관측된 bsns_year 값: " + years
                + (years.size() > 1 ? "  ← 1콜에 여러 해가 온다" : "  ← 1콜에 한 해뿐이다"));
        System.out.println("→ '적정' 외의 값을 실제로 볼 때까지는 비적정 판정 로직을 만들지 않는다."
                + " 표본 둘 다 적정이면 문자열 형태만 확인된 것이고, 비적정의 표기는 미확정이다.");
        System.out.println("→ 새 유니버스 제외 조건 '후보'로만 기록한다. 채택은 별도 phase다.");

        assertThat(OPINION_SAMPLES).isNotEmpty();
    }

    private static JsonNode call(RestClient client, String path, String corpCode,
                                 String from, String to) {
        return DartProbeSupport.get(client, uri -> uri
                .path(path)
                .queryParam("crtfc_key", DartProbeSupport.apiKey())
                .queryParam("corp_code", corpCode)
                .queryParam("bgn_de", from)
                .queryParam("end_de", to)
                .build());
    }

    private static List<JsonNode> rowsOf(JsonNode body) {
        List<JsonNode> rows = new java.util.ArrayList<>();
        if (body == null) {
            return rows;
        }
        JsonNode list = body.get("list");
        if (list != null) {
            list.forEach(rows::add);
        }
        return rows;
    }
}
