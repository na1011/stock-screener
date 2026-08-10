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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주요계정({@code fnlttSinglAcnt.json})의 <b>계정명이 회사마다 얼마나 갈리는지</b> 실측한다.
 *
 * <p>{@code docs/api-spec/dart-open-api.md}의 미검증 항목이 이것이다 — "계정명 일관성은
 * 4개사에서만 확인했고, 업종이 다양해지면 후보군 보강이 필요할 수 있다."
 *
 * <p>실제로 새어 나갔다. 2026-08-10 결손 보정 뒤 <b>이익잉여금만 세 종목에서 비었다</b>
 * (오리온·오리온홀딩스·에스티큐브). 유동자산·유동부채는 왔으므로 금융업 사유가 아니라
 * {@link DartAccountMapper#RETAINED_EARNINGS} 후보군에 없는 이름을 쓴다는 뜻이고, 그 결과
 * 그 종목의 {@code ALTMAN_Z}가 계산되지 않아 스크리닝 결과에서 조용히 빠졌다.
 *
 * <p>테스트라기보다 <b>탐침</b>이다. 단언은 최소한만 두고 관측한 것을 표준출력으로 낸다 —
 * <b>매핑되지 않은 재무상태표 계정명</b>이 그 출력이며 후보군 보강의 근거가 된다.
 * 단언을 늘리면 벤더가 조금만 바뀌어도 탐침이 죽어 아무것도 못 보게 된다.
 *
 * <p>호출 수는 회사당 1콜이다. 표본을 늘려도 쿼터에 영향이 없다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartAccountNameProbeLiveTest {

    /**
     * 매핑이 실제로 실패한 셋 + 대조군 하나.
     *
     * <p>대조군이 있어야 "이 회사만 다르다"와 "우리 파싱이 통째로 틀렸다"를 가른다.
     */
    private static final Map<String, String> SAMPLES = new LinkedHashMap<>(Map.of(
            "271560", "오리온",
            "001800", "오리온홀딩스",
            "052020", "에스티큐브",
            "005930", "삼성전자(대조군)"));

    /** 직전 회계연도의 사업보고서. 확실히 공시된 시점을 고른다. */
    private static final int SETTLED_YEAR = LocalDate.now().getYear() - 1;
    private static final String ANNUAL_REPORT = "11011";

    @Test
    @DisplayName("[live][probe] 재무상태표 계정명 중 우리가 매핑하지 못하는 것을 기록한다")
    void probeBalanceSheetAccountNames() throws Exception {
        DartCorpCodeCache cache = corpCodeCache();
        RestClient client = new ExternalApiClientFactory().create(properties().getDart());
        String apiKey = System.getenv("DART_API_KEY");

        System.out.println("\n===== 주요계정 재무상태표 계정명 실측 (" + SETTLED_YEAR + " 사업보고서) =====");

        int probed = 0;
        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            String corpCode = cache.findCorpCode(sample.getKey()).orElse(null);
            if (corpCode == null) {
                System.out.println("\n[" + sample.getValue() + "] DART에 종목코드가 없다");
                continue;
            }

            ResponseEntity<JsonNode> response = client.get().uri(uri -> uri
                            .path("/fnlttSinglAcnt.json")
                            .queryParam("crtfc_key", apiKey)
                            .queryParam("corp_code", corpCode)
                            .queryParam("bsns_year", SETTLED_YEAR)
                            .queryParam("reprt_code", ANNUAL_REPORT)
                            .build())
                    .retrieve()
                    .toEntity(JsonNode.class);

            JsonNode body = response.getBody();
            String status = body == null ? "(응답 없음)" : body.path("status").asString("");
            System.out.println("\n[" + sample.getValue() + "] status=" + status);
            if (!"000".equals(status)) {
                continue;
            }

            for (JsonNode row : body.path("list")) {
                if (!"BS".equals(row.path("sj_div").asString(""))) {
                    continue;
                }
                String name = row.path("account_nm").asString("");
                System.out.printf("  %-4s %-24s %s%n",
                        row.path("fs_div").asString(""), name, mappedTo(name));
            }
            probed++;
        }

        // MUST: 아무것도 못 재고 초록이 되지 않게 한다. 탐침의 실패 모드가 그것이다.
        assertThat(probed).as("표본 중 한 곳도 조회하지 못했다 — 키나 엔드포인트를 확인하라")
                .isGreaterThan(0);
    }

    /** 그 계정명이 우리 필드로 들어가는가. 빈 문자열이면 <b>버려지는 계정</b>이다. */
    private static String mappedTo(String name) {
        if (DartAccountMapper.CURRENT_ASSETS.contains(name)) {
            return "→ currentAssets";
        }
        if (DartAccountMapper.CURRENT_LIABILITIES.contains(name)) {
            return "→ currentLiabilities";
        }
        if (DartAccountMapper.RETAINED_EARNINGS.contains(name)) {
            return "→ retainedEarnings";
        }
        if (DartAccountMapper.TOTAL_ASSETS.contains(name)) {
            return "→ totalAssets";
        }
        if (DartAccountMapper.TOTAL_LIABILITIES.contains(name)) {
            return "→ totalLiabilities";
        }
        if (DartAccountMapper.TOTAL_EQUITY.contains(name)) {
            return "→ totalEquity";
        }
        return "(버려짐)";
    }

    private DartCorpCodeCache corpCodeCache() {
        return DartProbeSupport.corpCodeCache();
    }

    private static DataSourceProperties properties() {
        return DartProbeSupport.properties();
    }
}
