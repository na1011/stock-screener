package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전체 재무제표({@code fnlttSinglAcntAll}) 한 판의 손익 줄 원문 — 저장된 칸이 원장의 어느 줄에서 왔는지 가를 때 쓴다(탐색 {@code KR-BLANKS-PLAN-1106.md}
 * K19 · 위메이드 2018~2023 지배 순이익이 전 분기 6,455원) · 같은 판 자본변동표의 당기순이익 열들. {@code PROBE_CALLS}는 한 줄에 {@code corp_code 사업연도 보고서코드 CFS|OFS}(한 줄 1콜).
 *
 * <pre>PROBE_CALLS=&lt;파일&gt; PROBE_OUT=&lt;scratchpad 아래 폴더&gt; ./gradlew liveTest --tests "*DartFullStatementRowsProbeLiveTest*"</pre>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "PROBE_CALLS", matches = ".+")
class DartFullStatementRowsProbeLiveTest {

    @Test
    @DisplayName("[live][probe] 전체 재무제표 한 판의 손익 줄 원문")
    void rows() throws Exception {
        Path out = Path.of(System.getenv("PROBE_OUT"));
        Files.createDirectories(out);
        RestClient client = DartProbeSupport.client();
        int answered = 0;
        for (String line : Files.readAllLines(Path.of(System.getenv("PROBE_CALLS")))) {
            String[] c = line.trim().split("\\s+");
            if (c.length < 4) {
                continue;
            }
            JsonNode body = DartProbeSupport.get(client, uri -> uri.path("/fnlttSinglAcntAll.json")
                    .queryParam("crtfc_key", DartProbeSupport.apiKey()).queryParam("corp_code", c[0])
                    .queryParam("bsns_year", c[1]).queryParam("reprt_code", c[2]).queryParam("fs_div", c[3]).build());
            Files.writeString(out.resolve(String.join("-", c) + ".json"), String.valueOf(body));
            System.out.printf("%s status=%s%n", line, DartProbeSupport.describeStatus(body));
            if (body != null && body.path("list").isArray()) {
                for (JsonNode row : body.path("list")) {
                    String sj = row.path("sj_div").asString("");
                    if ("IS".equals(sj) || "CIS".equals(sj)) {
                        answered++;
                        System.out.printf("  %s %s | %s | %s | %s%n", sj, row.path("account_id").asString(""), row.path("account_nm").asString(""),
                                row.path("thstrm_amount").asString(""), row.path("thstrm_add_amount").asString(""));
                    } else if ("SCE".equals(sj) && "ifrs-full_ProfitLoss".equals(row.path("account_id").asString(""))) {
                        // 자본변동표 당기순이익 — 열(지배 · 비지배 · 구성 열)마다 한 줄(K19의 확인 재료)
                        System.out.printf("  SCE %s | %s%n", row.path("account_detail").asString(""), row.path("thstrm_amount").asString(""));
                    }
                }
            }
            Thread.sleep(400);
        }
        assertThat(answered).as("손익 줄이 0이면 요청이 틀린 것이다 — status를 위에서 본다").isPositive();
    }
}
