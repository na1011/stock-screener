package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.support.ReadOnlyDb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전체 재무제표가 <b>지배기업 소유주 귀속 자본</b>을 주는지 전수로 묻는다.
 *
 * <p><b>왜 다시 묻는가</b>: {@code total_equity_controlling}이 국내에서 <b>0 / 20,154행</b>이라
 * {@code FinancialMath.equityAttributableToShareholders}가 전 종목에서 연결자본으로 폴백하고,
 * {@code PBR}이 그 분모를 쓴다. 분자인 시가총액은 <b>상장주식에만</b> 값을 매기므로 비지배지분이
 * 분모에만 들어간다(ADR-0159 §규칙).
 *
 * <p><b>이 축의 실측은 있었는데 재현 수단이 없었다.</b> {@code docs/progress.md} [A15]가
 * <i>"전수 350종목 · 태그 직접 310/350(88.6%) · 실효 339/344 · 항등식 275/275 = 100.00%"</i>를
 * 적었으나 <b>그 탐침이 저장소에 없다</b>(2026-09-21 확인). 기록은 사실이 아니라 「누군가 그때
 * 그렇게 믿었다」이므로, 매퍼를 고치기 전에 같은 물음을 코드로 남기고 다시 잰다.
 *
 * <p><b>MUST: {@code account_id} 완전일치로만 문다.</b> {@code ifrs-full_Equity}는
 * {@code ifrs-full_EquityAttributableToOwnersOfParent}의 <b>접두사</b>라, 부분일치를 쓰면
 * 지배주주지분을 자본총계로 읽는다({@code DartFullAccountMapper} javadoc이 같은 함정을 경고한다).
 *
 * <p><b>MUST: {@code sj_div='BS'}로 좁힌다.</b> 같은 {@code account_id}가 자본변동표(SCE)에도
 * 나오고 거기서는 {@code account_detail}이 구성요소를 가리킨다 — 기말 잔액이 아니다.
 *
 * <pre>
 * ./gradlew liveTest --tests "*DartControllingEquityProbe*"                    # 편입 국내 전수
 * ./gradlew liveTest --tests "*DartControllingEquityProbe*" -Dprobe.tickers=000150,207940
 * </pre>
 *
 * <p>콜 수는 <b>종목당 1콜</b>이다(전수 350이면 350콜 · DART 리미터 100ms라 약 35초).
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartControllingEquityProbeLiveTest {

    private static final String ANNUAL_REPORT = "11011";
    private static final String SETTLED_YEAR = "2025";

    /** 상대오차 허용치. A15가 1e-9로 쟀다고 적었으므로 그보다 느슨한 자리에서 먼저 신고한다. */
    private static final BigDecimal IDENTITY_TOLERANCE = new BigDecimal("0.0001");

    private record Target(String label, List<String> accountIds) {
    }

    private static final Target CONTROLLING = new Target("지배주주자본",
            List.of("ifrs-full_EquityAttributableToOwnersOfParent",
                    "ifrs_EquityAttributableToOwnersOfParent"));
    private static final Target NONCONTROLLING = new Target("비지배지분",
            List.of("ifrs-full_NoncontrollingInterests",
                    "ifrs_NoncontrollingInterests"));
    private static final Target TOTAL = new Target("자본총계",
            List.of("ifrs-full_Equity", "ifrs_Equity"));

    private static String prop(String key, String fallback) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 응답을 픽스처로 남긴다 — <b>국내에는 전체 재무제표의 드라이런 수단이 없다.</b>
     *
     * <p>미국은 {@code .factcache} 1,214종목을 {@code SecEdgarFactsSweepTest}에 먹여 재적재 없이
     * 전/후를 대조하는데, 국내는 {@code fnlttSinglAcntAll} 응답 픽스처가 <b>0개</b>다
     * (테스트 픽스처 여섯은 전부 요약본이라 {@code account_id}가 없다 · 2026-09-21 확인).
     * 그래서 매퍼를 고칠 때마다 <b>소스를 다시 부르거나 재적재로 확인</b>하게 되는데,
     * 재적재는 {@code invalidate}로 지표를 지우므로 검증 수단이 될 수 없다.
     *
     * <p><b>MUST: 대조군을 함께 남긴다.</b> 비지배가 큰 회사만 남기면 「부재」 갈래를 못 만든다.
     *
     * <pre>-Dprobe.saveFixture=src/test/resources/fixtures/dart/full-statement</pre>
     */
    private static void saveFixture(String dir, String ticker, JsonNode body) {
        if (dir == null || body == null) {
            return;
        }
        try {
            Path target = Path.of(dir);
            Files.createDirectories(target);
            Files.writeString(target.resolve(ticker + "-equity.json"), body.toString(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 탐침의 본 일은 묻는 것이다. 저장 실패로 실측을 잃지 않는다 — 다만 삼키지 않고 찍는다.
            System.out.printf("    [픽스처 저장 실패] %s: %s%n", ticker, e);
        }
    }

    /**
     * 모집단. <b>기본은 편입 국내 전수다</b> — 표본으로는 커버리지를 못 답하고,
     * 이 축의 수용 기준이 「88% 이상」이라 분모가 필요하다(ADR-0159 §검증 6).
     *
     * <p><b>대조군은 모집단에 자연히 들어 있다</b> — 비지배지분이 큰 지주회사({@code 000150}
     * 두산 87.55% · {@code 034730} SK 68.72%)와 비지배가 정확히 0인 회사
     * ({@code 207940} 삼성바이오로직스)가 둘 다 편입이다. 전수가 아니면 그 둘을 이름으로 넣는다.
     */
    private static List<String> population() throws Exception {
        String override = prop("probe.tickers", null);
        if (override != null) {
            List<String> given = new ArrayList<>();
            for (String ticker : override.split(",")) {
                given.add(ticker.strip());
            }
            for (String control : List.of("000150", "207940")) {
                if (!given.contains(control)) {
                    given.add(control);   // 대조군이 없으면 부재를 「안 닿았다」와 못 가른다
                }
            }
            return given;
        }
        List<String> tickers = new ArrayList<>();
        String sql = """
                SELECT s.ticker
                FROM stock s
                JOIN universe_membership m ON m.stock_id = s.id AND m.effective_to IS NULL
                JOIN universe u ON u.code = m.universe_code AND u.is_active
                WHERE s.market_code IN ('KOSPI', 'KOSDAQ')
                ORDER BY s.ticker
                """;
        try (Connection connection = ReadOnlyDb.open();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                tickers.add(rs.getString("ticker"));
            }
        }
        return tickers;
    }

    @Test
    @DisplayName("[live][probe] 전체 재무제표가 지배주주 귀속 자본을 주는지 전수로 묻는다")
    void probeWhetherFullStatementsGiveControllingEquity() throws Exception {
        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();
        String apiKey = DartProbeSupport.apiKey();

        List<String> tickers = population();
        String year = prop("probe.year", SETTLED_YEAR);
        String report = prop("probe.report", ANNUAL_REPORT);
        String fixtureDir = prop("probe.saveFixture", null);

        System.out.println("\n===== 지배주주 귀속 자본 실측 =====");
        System.out.printf("모집단 %d종목 · bsns_year=%s · reprt_code=%s · fs_div=CFS%n",
                tickers.size(), year, report);
        System.out.println("MUST: 부재를 「소스가 안 준다」로 읽기 전에 아래 갈래를 본다 —");
        System.out.println("  ① 셋 다 없음(응답은 왔다)  ② 총계만 있음(비지배가 없는 회사면 정상)");
        System.out.println("  ③ 지배주주만 없음(계정 후보군이 좁다는 신호)  ④ 응답 자체가 없음");

        int answered = 0;
        int noResponse = 0;
        int both = 0;          // 지배주주 + 비지배 둘 다
        int controllingOnly = 0;
        int totalOnly = 0;     // 지분 구분이 없다
        int noncontrollingOnly = 0;
        int identityOk = 0;
        int identityChecked = 0;
        Set<String> accountNames = new LinkedHashSet<>();
        Set<String> sjDivs = new LinkedHashSet<>();
        Map<String, String> missing = new TreeMap<>();
        List<String> identityBroken = new ArrayList<>();

        for (String ticker : tickers) {
            String corpCode = cache.findCorpCode(ticker).orElse(null);
            if (corpCode == null) {
                missing.put(ticker, "corp_code 없음");
                continue;
            }
            JsonNode body = DartProbeSupport.get(client, uri -> uri
                    .path("/fnlttSinglAcntAll.json")
                    .queryParam("crtfc_key", apiKey)
                    .queryParam("corp_code", corpCode)
                    .queryParam("bsns_year", year)
                    .queryParam("reprt_code", report)
                    .queryParam("fs_div", "CFS")
                    .build());

            saveFixture(fixtureDir, ticker, body);

            JsonNode list = body == null ? null : body.get("list");
            if (list == null || list.isEmpty()) {
                noResponse++;
                missing.put(ticker, "응답 없음 status=" + DartProbeSupport.describeStatus(body));
                continue;
            }
            answered++;

            JsonNode controlling = findOnBalanceSheet(list, CONTROLLING);
            JsonNode noncontrolling = findOnBalanceSheet(list, NONCONTROLLING);
            JsonNode total = findOnBalanceSheet(list, TOTAL);

            if (controlling != null) {
                accountNames.add(controlling.path("account_nm").asString("?"));
                sjDivs.add(controlling.path("sj_div").asString("?"));
            }

            if (controlling != null && noncontrolling != null) {
                both++;
            } else if (controlling != null) {
                controllingOnly++;
            } else if (noncontrolling != null) {
                noncontrollingOnly++;
            } else {
                totalOnly++;
                missing.put(ticker, total != null ? "지분 구분 없음(총계만)" : "셋 다 없음");
            }

            BigDecimal c = amount(controlling);
            BigDecimal n = amount(noncontrolling);
            BigDecimal t = amount(total);
            if (c != null && n != null && t != null && t.signum() != 0) {
                identityChecked++;
                BigDecimal diff = t.subtract(c.add(n)).abs()
                        .divide(t.abs(), 12, RoundingMode.HALF_UP);
                if (diff.compareTo(IDENTITY_TOLERANCE) <= 0) {
                    identityOk++;
                } else {
                    identityBroken.add("%s 상대오차 %s".formatted(ticker, diff.toPlainString()));
                }
            }
        }

        System.out.println("\n===== 판정 =====");
        System.out.printf("응답 %d / 모집단 %d (응답 없음 %d)%n", answered, tickers.size(), noResponse);
        System.out.printf("지배주주+비지배 둘 다 %d · 지배주주만 %d · 비지배만 %d · 지분 구분 없음 %d%n",
                both, controllingOnly, noncontrollingOnly, totalOnly);
        if (answered > 0) {
            System.out.printf("지배주주 자본 직접 태그: %d / %d = %.1f%%%n",
                    both + controllingOnly, answered, 100.0 * (both + controllingOnly) / answered);
        }
        System.out.printf("항등식 「자본총계 = 지배주주 + 비지배」: %d / %d%n",
                identityOk, identityChecked);
        if (!identityBroken.isEmpty()) {
            System.out.println("  깨진 것: " + identityBroken.subList(0, Math.min(10, identityBroken.size())));
        }
        System.out.println("계정명 분포(" + accountNames.size() + "가지): " + accountNames);
        System.out.println("sj_div: " + sjDivs);
        System.out.println("\n부재·이상 " + missing.size() + "종목:");
        missing.entrySet().stream().limit(40)
                .forEach(e -> System.out.printf("  %s  %s%n", e.getKey(), e.getValue()));

        System.out.println("\nMUST: 「지분 구분 없음」을 결손으로 세지 않는다 — 비지배지분이 없는");
        System.out.println("      회사에서는 자본총계가 곧 지배주주 자본이고 폴백이 정답이다.");
        System.out.println("MUST: 이 출력을 docs/api-spec/dart-financial.md 「계정별 가용성」에 옮긴다");
        System.out.println("      — 그 표에 자본 행이 없다(2026-09-21 확인).");
        System.out.printf("콜 수: 종목당 1콜 = %d콜.%n", answered + noResponse);
        if (fixtureDir != null) {
            System.out.println("픽스처를 남겼다: " + fixtureDir
                    + "  — 이것이 국내 전체 재무제표의 첫 드라이런 재료다.");
        } else {
            System.out.println("MUST: -Dprobe.saveFixture=<dir>로 몇 종목을 남긴다 —"
                    + " 국내에는 전체 재무제표 픽스처가 0개라 매퍼 수정의 드라이런이 불가능하다.");
        }

        assertThat(answered)
                .as("한 종목도 응답하지 않았다 — 키·엔드포인트·연도를 먼저 의심한다")
                .isPositive();
        assertThat(both + controllingOnly)
                .as("지배주주 자본이 한 종목도 안 왔다 — 계정 후보군이나 sj_div 필터를 의심한다")
                .isPositive();
    }

    /**
     * 재무상태표에서만 고른다. <b>완전일치</b>이고 {@code sj_div='BS'}다.
     *
     * <p>같은 {@code account_id}가 자본변동표(SCE)에도 나오는데 거기서는
     * {@code account_detail}이 구성요소를 가리켜 기말 잔액이 아니다.
     */
    private static JsonNode findOnBalanceSheet(JsonNode list, Target target) {
        for (JsonNode row : list) {
            if (!"BS".equals(row.path("sj_div").asString(""))) {
                continue;
            }
            String accountId = row.path("account_id").asString("");
            if (target.accountIds().contains(accountId)) {
                return row;
            }
        }
        return null;
    }

    private static BigDecimal amount(JsonNode row) {
        if (row == null) {
            return null;
        }
        String raw = row.path("thstrm_amount").asString("").replace(",", "").strip();
        if (raw.isEmpty() || "-".equals(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;   // 「-」 말고도 비수치가 온다. 삼키지 않고 부재로 센다.
        }
    }
}
