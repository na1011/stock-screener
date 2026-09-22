package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.StatementPeriod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>비지배 전무 게이트의 드라이런.</b> 적재 전에 「게이트가 어디서 열리고 어디서 닫히는가」를
 * <b>쓰기 없이</b> 답한다.
 *
 * <p><b>왜 필요한가</b>(2026-09-22 사용자 지시): KOSDAQ 150종목은 실제로 적재해 봤지만
 * <b>KOSPI 200과 편출 129에는 한 번도 안 물었다.</b> 그 시장에서 게이트가 어떻게 서는지는
 * <b>추측</b>이고, 그 추측 위에서 15,800콜을 태우면 틀렸을 때 되돌리는 값이 그만큼이다.
 *
 * <p><b>어댑터의 판정을 그대로 태운다 — 손으로 다시 짜지 않는다.</b>
 * {@code fetchOne}의 CFS→OFS 폴백을 재현해 {@link DartFullStatementAdapter.Pending}을 만들고,
 * {@link DartFullStatementAdapter#lastNoncontrollingIncome}과
 * {@link DartFullStatementAdapter#gateOpen}과 {@link DartFullAccountMapper#controllingIncome}을
 * <b>운영과 같은 코드로</b> 부른다. 여기서 나온 답과 적재 결과가 갈리면 그것은 재현의 결함이다.
 *
 * <pre>./gradlew liveTest --tests "*DartGateDryRun*" -Dprobe.tickers=003690,450080,323410</pre>
 *
 * <p>콜은 <b>종목당 (연도 수 × 4)</b>이고 CFS가 비는 (연도,기간)마다 1콜이 더 붙는다.
 * 기본 창은 적재와 같게 {@code -Dprobe.fromYear}/{@code -Dprobe.toYear}로 준다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartGateDryRunLiveTest {

    /**
     * <b>MUST: 리미터를 탄다.</b> {@code DartProbeSupport.get}은 {@code client.get()}을 바로
     * 부르므로 <b>속도 제한이 없다</b> — 2026-09-22에 18종목(약 900콜)을 42초에 던졌다가
     * {@code SocketException}으로 끊겼다. 쿼터 초과({@code status 020})가 아니라 <b>서버가
     * 연결을 끊은 것</b>이고, 적재 경로는 어댑터가 리미터를 타므로 같은 일이 안 생긴다.
     * 탐침만 맨손이라 여기서 감는다.
     */
    private static final TokenBucketRateLimiter LIMITER =
            new TokenBucketRateLimiter("dart-dryrun", 300, 0);

    private static String prop(String key, String fallback) {
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    @Test
    @DisplayName("[live][dryrun] 게이트가 어디서 열리고 닫히는지 쓰기 없이 찍는다")
    void dryRunGate() throws Exception {
        String tickersRaw = prop("probe.tickers", null);
        if (tickersRaw == null) {
            System.out.println("MUST: -Dprobe.tickers=<종목들>을 준다. 표본 없이는 아무것도 안 잰다.");
            return;
        }
        int fromYear = Integer.parseInt(prop("probe.fromYear", "2018"));
        int toYear = Integer.parseInt(prop("probe.toYear", "2026"));

        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        System.out.println("\n===== 게이트 드라이런(쓰기 0) =====");
        System.out.printf("연도=%d~%d · 기간=[FY,Q1,Q2,Q3]%n", fromYear, toYear);

        int calls = 0;
        Map<String, String> verdicts = new LinkedHashMap<>();
        for (String raw : tickersRaw.split(",")) {
            String ticker = raw.strip();
            String corpCode = cache.findCorpCode(ticker).orElse(null);
            if (corpCode == null) {
                verdicts.put(ticker, "corp_code 없음");
                continue;
            }

            List<DartFullStatementAdapter.Pending> pending = new ArrayList<>();
            int separateCount = 0;
            int noResponse = 0;
            for (int year = fromYear; year <= toYear; year++) {
                for (StatementPeriod period : List.of(StatementPeriod.FY, StatementPeriod.Q1,
                        StatementPeriod.Q2, StatementPeriod.Q3)) {
                    JsonNode list = call(client, apiKey, corpCode, year, period, "CFS");
                    calls++;
                    boolean consolidated = true;
                    if (list == null || list.isEmpty()) {
                        list = call(client, apiKey, corpCode, year, period, "OFS");
                        calls++;
                        consolidated = false;
                    }
                    if (list == null || list.isEmpty()) {
                        noResponse++;
                        continue;
                    }
                    if (!consolidated) {
                        separateCount++;
                    }
                    pending.add(new DartFullStatementAdapter.Pending(year, period, list, consolidated));
                }
            }
            if (pending.isEmpty()) {
                verdicts.put(ticker, "응답 0 — 판정에서 뺀다");
                continue;
            }

            // ── 운영과 같은 코드로 판정한다.
            LocalDate last = DartFullStatementAdapter.lastNoncontrollingIncome(pending);
            int opened = 0, direct = 0, derived = 0, stillNull = 0;
            for (DartFullStatementAdapter.Pending p : pending) {
                boolean gate = DartFullStatementAdapter.gateOpen(p, last);
                if (gate) {
                    opened++;
                }
                List<JsonNode> income = DartFullAccountMapper.sections(p.list(),
                        DartFullAccountMapper.SJ_INCOME,
                        DartFullAccountMapper.SJ_COMPREHENSIVE_INCOME);
                BigDecimal value = DartFullAccountMapper.controllingIncome(income, gate);
                if (value == null) {
                    stillNull++;
                } else if (DartFullAccountMapper.find(
                        income, DartFullAccountMapper.ID_NET_INCOME_CONTROLLING, null) != null) {
                    direct++;
                } else {
                    derived++;
                }
            }
            String verdict = "판=%d 별도=%d 무응답=%d | 게이트열림=%d | 직접=%d 유도·게이트=%d 여전히NULL=%d | 마지막비지배=%s"
                    .formatted(pending.size(), separateCount, noResponse, opened,
                            direct, derived, stillNull, last);
            verdicts.put(ticker, verdict);
            System.out.printf("  %-8s %s%n", ticker, verdict);
        }

        System.out.println("\n===== 요약 =====");
        System.out.printf("콜 %d · 종목 %d%n", calls, verdicts.size());
        System.out.println("MUST: 「별도=판」인 종목에서 게이트열림이 0이 아니면 CFS 한정이 깨진 것이다.");
        System.out.println("MUST: 「마지막비지배」가 최근인데 게이트열림이 크면 기간 술어가 뒤집힌 것이다.");
    }

    private static JsonNode call(RestClient client, String apiKey, String corpCode,
                                 int year, StatementPeriod period, String fsDiv) {
        String reportCode = switch (period) {
            case FY -> "11011";
            case Q1 -> "11013";
            case Q2 -> "11012";
            case Q3 -> "11014";
            default -> throw new IllegalArgumentException("Q4는 유도다: " + period);
        };
        try {
            LIMITER.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("드라이런이 중단됐다", e);
        }
        JsonNode body = DartProbeSupport.get(client, uri -> uri
                .path("/fnlttSinglAcntAll.json")
                .queryParam("crtfc_key", apiKey)
                .queryParam("corp_code", corpCode)
                .queryParam("bsns_year", String.valueOf(year))
                .queryParam("reprt_code", reportCode)
                .queryParam("fs_div", fsDiv)
                .build());
        return body == null ? null : body.get("list");
    }
}
