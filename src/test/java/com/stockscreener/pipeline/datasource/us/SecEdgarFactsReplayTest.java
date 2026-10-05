package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.FinancialColumnAbsence;
import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장해 둔 {@code companyfacts} JSON을 <b>어댑터에 그대로 먹여 보고서를 찍는다.</b>
 * DB도 배치도 외부 호출도 없다.
 *
 * <p><b>이 도구가 없어서 2026-09-14~15에 재적재를 19번 · 2시간 13분 돌렸다.</b> 기간 재구성
 * 규칙을 다섯 번 갈아엎으면서 매번 「전 종목 재적재 → DB 질의」로만 확인했는데, 어댑터는
 * {@code companyfacts → 보고서 목록}에 가까운 <b>순수 함수</b>라 그럴 이유가 없었다. 실제로
 * 한 번 직접 찍어 보자 <b>즉시</b> 원인이 보였다 — 회계연도 창은 그 해를 자기 해로 내는
 * 공시에서만 생기고, 10-K의 비교연도는 창을 안 만든다.
 *
 * <p><b>MUST: 기간 재구성·태그 선택·유도 규칙을 고치면 전 종목 재적재 전에 이것부터 돌린다.</b>
 * 순서는 ① 이 리플레이 ② 영향 범위를 SQL로 세기 ③ {@code --tickers}로 대표 종목 드라이런
 * ④ 전체 재적재 + 백필이다. 재적재는 {@code invalidate}를 불러 지표를 지우므로 <b>검증 없이
 * 돌리는 것 자체가 정합성을 해친다.</b>
 *
 * <p>쓰는 법 — 캐시는 {@code build/factcache/}에 둔다(git이 안 따라간다).
 * <pre>
 * UA=$(grep '^SEC_EDGAR_USER_AGENT=' .env | cut -d= -f2-)
 * curl -s -H "User-Agent: $UA" --compressed \
 *   https://data.sec.gov/api/xbrl/companyfacts/CIK0000935703.json -o build/factcache/DLTR.json
 * REPLAY_FACTS=build/factcache/DLTR.json REPLAY_PERIOD=Q4 REPLAY_SUPPLEMENT_DIR=off \
 *   ./gradlew test --tests "*SecEdgarFactsReplayTest*" -i | grep REPLAY
 * </pre>
 *
 * <p><b>{@code REPLAY_SUPPLEMENT_DIR}는 필수다</b>(경로 또는 {@code off} · ADR-0173). 어댑터 출력은 companyfacts + 공시 보충이라,
 * {@code off}는 「companyfacts만으로는 무엇이 나오는가」이고 운영 어댑터의 출력이 아니다 — 예: AOS 2024/FY는 {@code off}에서
 * 없고 보충에서 있다. 경로는 {@code SecFilingSupplementCacheLiveTest}가 같은 날 모은 디렉터리이고, 그 때 {@code REPLAY_FACTS}도
 * 그 디렉터리의 {@code companyfacts/<티커>.json}을 준다(따로 받은 companyfacts와 섞으면 거짓 보충 대상이 생긴다).
 *
 * <p><b>환경변수로 받는다 — Gradle의 {@code -D}는 데몬 JVM에만 붙어 포크된 테스트에 안 닿는다.</b>
 * {@code build.gradle}이 전달하는 접두는 {@code probe.}·{@code sweep.} 둘뿐이고, 그 목록에
 * 하나를 더하면 펜스도 함께 고쳐야 한다.
 */
@EnabledIfEnvironmentVariable(named = "REPLAY_FACTS", matches = ".+")
class SecEdgarFactsReplayTest {

    @Test
    void replay() throws Exception {
        Path path = Path.of(System.getenv("REPLAY_FACTS"));
        int from = Integer.parseInt(
                Optional.ofNullable(System.getenv("REPLAY_FROM")).orElse("2015"));
        String period = System.getenv("REPLAY_PERIOD");

        tools.jackson.databind.JsonNode root = new ObjectMapper().readTree(Files.readString(path));
        tools.jackson.databind.JsonNode facts = root.path("facts");
        Optional<Path> supplementDir = SupplementReplay.requireSetting("REPLAY_SUPPLEMENT_DIR");
        if (supplementDir.isPresent()) {
            try (SupplementReplay replay = SupplementReplay.start(supplementDir.get())) {
                SecFilingSupplement.Outcome outcome = replay.supplement().supplement(root.path("cik").asLong(),
                        (tools.jackson.databind.node.ObjectNode) facts, from, path.getFileName().toString());
                System.out.println("REPLAY 보충 받음=" + outcome.fetched() + " 더한 팩트=" + outcome.merged()
                        + " 실패=" + outcome.failed() + " 원자료 없음=" + replay.missing());
                // 원자료가 빠지면 목록이 404로 떨어져 저장분만 쓰는 갈래를 탄다 — 보충 전 출력이 조용히 나온다.
                assertThat(replay.missing()).as("수집이 빠뜨린 원자료 — 같은 날 모은 디렉터리인가").isZero();
            }
        }
        SecEdgarFacts.Result result = SecEdgarFacts.build(facts, from);

        System.out.println("REPLAY " + path.getFileName()
                + " 보고서=" + result.reports().size() + " 버린팩트=" + result.dropped()
                + " 부재=" + result.absences().size());
        result.reports().stream()
                .filter(r -> period == null || period.equals(r.fiscalPeriod()))
                .sorted(Comparator.comparing(RawFinancialReport::fiscalYear)
                        .thenComparing(RawFinancialReport::fiscalPeriod)
                        .thenComparing(RawFinancialReport::publishedAt))
                .forEach(r -> System.out.println("REPLAY   " + r.fiscalYear() + "/" + r.fiscalPeriod()
                        + " pub=" + r.publishedAt() + " rc=" + r.reportCode()
                        + " rev=" + r.revenue() + " cos=" + r.costOfSales()
                        + " teq=" + r.totalEquity() + " teqc=" + r.totalEquityControlling()
                        + " ni=" + r.netIncome() + " derived=" + r.derived()));
        result.absences().stream()
                .filter(a -> a.state() != FinancialColumnAbsence.State.ABSENT)
                .forEach(a -> System.out.println("REPLAY   부재 " + a.columnName() + "=" + a.state()));
    }
}
