package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>비12월 결산 종목의 회계기간을 원장에 직접 묻는다.</b>
 *
 * <p>두 가지가 코드 판단을 가르는데 저장소에 실측이 없었다.
 * <ol>
 *   <li><b>전체 재무제표({@code fnlttSinglAcntAll})가 {@code thstrm_dt}를 주는가.</b>
 *       {@code docs/api-spec/dart-financial.md}의 전체 재무제표 응답 목록에는 <b>없고</b>
 *       ADR-0056은 <i>"준다"</i>고 적었는데 <b>인용이 없다.</b> 그 둘이 갈리면
 *       {@code DartReportPeriods}를 고칠 때 두 어댑터가 <b>서로 다른 기간</b>을 쓰게 된다.
 *   <li><b>3월 결산 회사의 {@code bsns_year=N} 보고서 넷이 각각 어느 기간인가.</b>
 *       유도 4분기가 {@code FY − Q3누적}인데, 둘이 서로 다른 회계연도면
 *       <b>12개월 떨어진 두 기간을 뺀다.</b> 실측으로 신영증권 2024 Q4가 그 값이고
 *       비츠로셀 2017 Q4는 <b>매출이 음수(−60,993,170,139)</b>다.
 * </ol>
 *
 * <p>콜 수: 2종목 × 2엔드포인트 × 2보고서 = <b>8콜</b>.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartFiscalPeriodProbeLiveTest {

    /** 3월 결산(신영증권)과 결산월을 바꾼 회사(비츠로셀, 6월 → 12월). 둘 다 현재 편입이다. */
    private static final Map<String, String> SAMPLES = new LinkedHashMap<>(Map.of(
            "001720", "신영증권(3월 결산)",
            "082920", "비츠로셀(6월→12월 결산 변경)"));

    private static final List<String> REPORTS = List.of("11011", "11014");
    private static final List<Integer> YEARS = List.of(2024, 2017);

    @Test
    @DisplayName("[live][probe] 비12월 결산의 thstrm_dt를 두 엔드포인트에서 기록한다")
    void probeFiscalPeriods() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = new ExternalApiClientFactory().create(properties().getDart());
        String apiKey = System.getenv("DART_API_KEY");

        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElseThrow();
            int year = sample.getKey().equals("001720") ? YEARS.get(0) : YEARS.get(1);
            for (String path : List.of("/fnlttSinglAcnt.json", "/fnlttSinglAcntAll.json")) {
                for (String reprt : REPORTS) {
                    ResponseEntity<JsonNode> response = client.get().uri(uri -> {
                        var b = uri.path(path)
                                .queryParam("crtfc_key", apiKey)
                                .queryParam("corp_code", corpCode)
                                .queryParam("bsns_year", year)
                                .queryParam("reprt_code", reprt);
                        if (path.endsWith("All.json")) {
                            b = b.queryParam("fs_div", "CFS");
                        }
                        return b.build();
                    }).retrieve().toEntity(JsonNode.class);

                    JsonNode body = response.getBody();
                    String status = body == null ? "(본문 없음)" : body.path("status").asString("");
                    System.out.printf("%n--- %s %s bsns_year=%d reprt=%s status=%s%n",
                            sample.getValue(), path, year, reprt, status);
                    JsonNode list = body == null ? null : body.get("list");
                    if (list == null || list.isEmpty()) {
                        System.out.println("    행 없음");
                        continue;
                    }
                    boolean hasDt = false;
                    int shown = 0;
                    for (JsonNode row : list) {
                        if (row.has("thstrm_dt")) {
                            hasDt = true;
                        }
                        String sj = row.path("sj_div").asString("");
                        if (shown < 4 && ("IS".equals(sj) || "BS".equals(sj) || "CIS".equals(sj))) {
                            System.out.printf("    %s %-22s thstrm_nm=%-18s thstrm_dt=%s%n",
                                    sj, row.path("account_nm").asString(""),
                                    row.path("thstrm_nm").asString("(없음)"),
                                    row.path("thstrm_dt").asString("(없음)"));
                            shown++;
                        }
                    }
                    System.out.println("    thstrm_dt 필드 존재=" + hasDt + " · 행 수=" + list.size());
                }
            }
        }
    }

    private static DataSourceProperties properties() {
        return DartProbeSupport.properties();
    }
}
