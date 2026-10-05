package com.stockscreener.probe;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** 임시 탐침 — 국내 인적분할 재상장일의 존속·신설 시가총액(공공데이터포털). 커밋하지 않는다. */
@Tag("live")
class KrNewcoProbeLiveTest {

    @Test
    void sweep() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String goKey = System.getenv("DATA_GO_KR_SERVICE_KEY");
        DefaultUriBuilderFactory fac = new DefaultUriBuilderFactory("https://apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService");
        fac.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY);
        RestClient go = RestClient.builder().uriBuilderFactory(fac).build();
        DateTimeFormatter f = DateTimeFormatter.BASIC_ISO_DATE;
        String[][] qs = {{"2020-08-06", "솔브레인"}, {"2021-11-29", "SK"}, {"2023-04-10", "현대"},
                {"2023-05-30", "OCI"}, {"2024-09-27", "한화"}, {"2025-11-24", "삼성"}, {"2026-08-25", "한화"}};
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(System.getProperty("probe.out"))))) {
            for (String[] q : qs) {
                LocalDate d = LocalDate.parse(q[0]);
                for (LocalDate day : new LocalDate[]{d.minusDays(1), d}) {
                    Thread.sleep(300);
                    String body = go.get().uri(u -> u.path("/getStockPriceInfo").queryParam("serviceKey", goKey)
                            .queryParam("resultType", "json").queryParam("numOfRows", 200).queryParam("pageNo", 1)
                            .queryParam("likeItmsNm", q[1]).queryParam("basDt", day.format(f)).build())
                            .retrieve().body(String.class);
                    JsonNode b = mapper.readTree(body).path("response").path("body");
                    w.printf("== %s %s basDt=%s total=%s%n", q[0], q[1], day, b.path("totalCount").asString(""));
                    for (JsonNode it : b.path("items").path("item")) {
                        w.printf("%s\t%s\t%s\tclpr=%s\tvs=%s\tlstg=%s\tmcap=%s%n", it.path("srtnCd").asString(""), it.path("itmsNm").asString(""),
                                it.path("mrktCtg").asString(""), it.path("clpr").asString(""), it.path("vs").asString(""),
                                it.path("lstgStCnt").asString(""), it.path("mrktTotAmt").asString(""));
                    }
                }
            }
        }
    }
}
