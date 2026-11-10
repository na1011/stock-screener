package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>분기 보통주 수가 원장에 있는가</b>(탐색 {@code BLANKS-PLAN-1106.md} K3 · K6 · 2026-11-10). 계획 v3는 {@code stockTotqySttus} 하나만 묻고
 * 「분기말 주식수가 없다(1Q · 3Q 수량 -)」를 적었다 — 엔드포인트 하나 판정이다. 같은 분기 보고서의 증자(감자) 현황({@code irdsSttus} · 일자 ·
 * 수량)과 자기주식 취득 · 처분({@code tesstkAcqsDspsSttus} · 기간 합계)이 기간 중 주식수 변동을 주는지, 분기마다 무엇이 오는지 status 코드와
 * 함께 찍는다(013 = 그 보고서에 행 없음 · 000 = 행 있음). 결산월이 비어 있는 두 종목(K6)의 기업개황 {@code acc_mt}도 묻는다.
 *
 * <p>표본: 087010 · 348340(K3 · W 없음 묶음) · 대조 005930. 분기 넷 × 엔드포인트 셋 × 셋 = 36콜 + 기업개황 2콜. 원문은 {@code PROBE_OUT}에 둔다.
 *
 * <pre>PROBE_OUT=&lt;scratchpad 아래 폴더&gt; ./gradlew liveTest --tests "*DartQuarterShareCountProbeLiveTest*"</pre>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+", disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartQuarterShareCountProbeLiveTest {

    private static final List<String> TICKERS = List.of("087010", "348340", "005930");
    private static final List<String> REPORTS = List.of("11013", "11012", "11014", "11011");
    private static final List<String> ENDPOINTS = List.of("stockTotqySttus", "irdsSttus", "tesstkAcqsDspsSttus");

    @Test
    @DisplayName("[live][probe] 분기 보고서의 주식총수 · 증자(감자) · 자기주식 표가 무엇을 주는가")
    void probe() throws Exception {
        Path out = Path.of(System.getenv().getOrDefault("PROBE_OUT", "build/probe-quarter-shares"));
        Files.createDirectories(out);
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        int answered = 0;
        for (String ticker : TICKERS) {
            String corp = cache.findCorpCode(ticker).orElseThrow();
            for (String report : REPORTS) {
                for (String endpoint : ENDPOINTS) {
                    JsonNode body = DartProbeSupport.get(client, uri -> uri.path("/" + endpoint + ".json")
                            .queryParam("crtfc_key", DartProbeSupport.apiKey()).queryParam("corp_code", corp)
                            .queryParam("bsns_year", 2025).queryParam("reprt_code", report).build());
                    Files.writeString(out.resolve(ticker + "-" + report + "-" + endpoint + ".json"), String.valueOf(body));
                    JsonNode list = body == null ? null : body.path("list");
                    int rows = list == null || !list.isArray() ? 0 : list.size();
                    StringBuilder sample = new StringBuilder();
                    if (rows > 0) {
                        for (JsonNode row : list) {
                            if ("stockTotqySttus".equals(endpoint)) {
                                sample.append(" [").append(row.path("se").asString("")).append(" 발행=")
                                        .append(row.path("istc_totqy").asString("")).append(" 자사주=")
                                        .append(row.path("tesstk_co").asString("")).append(" 유통=")
                                        .append(row.path("distb_stock_co").asString("")).append(']');
                            } else if ("irdsSttus".equals(endpoint)) {
                                sample.append(" [").append(row.path("isu_dcrs_de").asString("")).append(' ')
                                        .append(row.path("isu_dcrs_stle").asString("")).append(' ')
                                        .append(row.path("isu_dcrs_qy").asString("")).append(']');
                            } else {
                                sample.append(" [").append(row.path("stock_knd").asString("")).append(" 기초=")
                                        .append(row.path("bsis_qy").asString("")).append(" 취득=")
                                        .append(row.path("change_qy_acqs").asString("")).append(" 처분=")
                                        .append(row.path("change_qy_dsps").asString("")).append(" 기말=")
                                        .append(row.path("trmend_qy").asString("")).append(']');
                            }
                        }
                        answered++;
                    }
                    System.out.printf("%s %s %-20s status=%s 행=%d%s%n", ticker, report, endpoint, DartProbeSupport.describeStatus(body),
                            rows, sample.length() > 400 ? sample.substring(0, 400) + "…" : sample);
                    Thread.sleep(400);
                }
            }
        }
        for (String ticker : List.of("056080", "399720")) {
            String corp = cache.findCorpCode(ticker).orElseThrow();
            JsonNode body = DartProbeSupport.get(client, uri -> uri.path("/company.json")
                    .queryParam("crtfc_key", DartProbeSupport.apiKey()).queryParam("corp_code", corp).build());
            Files.writeString(out.resolve(ticker + "-company.json"), String.valueOf(body));
            System.out.printf("%s company status=%s acc_mt=%s%n", ticker, DartProbeSupport.describeStatus(body),
                    body == null ? "" : body.path("acc_mt").asString(""));
            Thread.sleep(400);
        }
        assertThat(answered).as("대조군(삼성전자)까지 행이 0이면 요청이 틀린 것이다 — status를 위에서 본다").isPositive();
    }

    /**
     * 모집단 표본 — {@code PROBE_SAMPLE}(티커 한 줄에 하나)의 2025 1분기 · 3분기 보고서 주식총수 표에 보통주 · 합계 발행주식수가 숫자로 오는가.
     * 한 종목 2콜. 대조군(삼성전자)처럼 「-」만 오는 회사가 몇인지 센다.
     */
    @Test
    @DisplayName("[live][probe] 분기 주식총수가 숫자로 오는 회사의 비율(표본)")
    @EnabledIfEnvironmentVariable(named = "PROBE_SAMPLE", matches = ".+")
    void sampleQuarterTotals() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        int companies = 0;
        int bothNumeric = 0;
        int noneNumeric = 0;
        List<String> dashOnly = new java.util.ArrayList<>();
        for (String ticker : Files.readAllLines(Path.of(System.getenv("PROBE_SAMPLE")))) {
            if (ticker.isBlank()) {
                continue;
            }
            java.util.Optional<String> corp = cache.findCorpCode(ticker.strip());
            if (corp.isEmpty()) {
                System.out.println(ticker + " corp_code 없음");
                continue;
            }
            companies++;
            int numeric = 0;
            StringBuilder line = new StringBuilder(ticker);
            for (String report : List.of("11013", "11014")) {
                JsonNode body = DartProbeSupport.get(client, uri -> uri.path("/stockTotqySttus.json")
                        .queryParam("crtfc_key", DartProbeSupport.apiKey()).queryParam("corp_code", corp.get())
                        .queryParam("bsns_year", 2025).queryParam("reprt_code", report).build());
                String total = "";
                if (body != null && body.path("list").isArray()) {
                    for (JsonNode row : body.path("list")) {
                        if ("합계".equals(row.path("se").asString("").replace(" ", ""))) {
                            total = row.path("istc_totqy").asString("");
                        }
                    }
                }
                boolean isNumeric = total.replace(",", "").matches("\\d+");
                numeric += isNumeric ? 1 : 0;
                line.append(' ').append(report).append('=').append(DartProbeSupport.describeStatus(body).substring(0, 3)).append(':')
                        .append(total.isEmpty() ? "(합계 행 없음)" : total);
                Thread.sleep(400);
            }
            if (numeric == 2) {
                bothNumeric++;
            } else if (numeric == 0) {
                noneNumeric++;
                dashOnly.add(ticker.strip());
            }
            System.out.println(line);
        }
        System.out.printf("[sample] 회사 %d · 1Q·3Q 둘 다 숫자 %d · 둘 다 아님 %d %s%n", companies, bothNumeric, noneNumeric, dashOnly);
        assertThat(companies).isPositive();
    }
}
