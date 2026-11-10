package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장해 둔 실적 발표 공시 본문 <b>전량</b>을 운영 해석기({@link DartProvisionalDocument})에 먹여 TSV로 찍는다 — DB도 배치도 외부 호출도 없다
 * (국내 잠정 관문 전 0콜 스윕 · ADR-0206). 해석 규칙을 바꾸면 전/후 TSV를 diff해 영향 범위를 잰다. 자체 검사 통과율 · 정식 대비 정확도는 이
 * TSV와 정식 분기 행으로 잰다(해석기 밖의 판정은 {@code ReportingBasis.provisionalQuarter}가 봉마다 한다).
 *
 * <pre>
 * SWEEP_PROV_DIR=~/.stock-screener/dart-prov SWEEP_PROV_LIST=&lt;접수번호 TAB 제목 한 줄에 하나&gt; SWEEP_PROV_OUT=&lt;출력 TSV&gt; \
 *   ./gradlew test --tests "*KrProvisionalSweepTest*"
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "SWEEP_PROV_DIR", matches = ".+")
class KrProvisionalSweepTest {

    @Test
    @DisplayName("[sweep] 캐시 본문 전량 → 서식 값 TSV · 상태별 수")
    void sweep() throws Exception {
        Path dir = Path.of(System.getenv("SWEEP_PROV_DIR"));
        List<String> list = Files.readAllLines(Path.of(System.getenv("SWEEP_PROV_LIST")), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        out.add(String.join("\t", "rcept_no", "status", "form", "consolidated", "period_start", "period_end", "cumulative_start",
                "cumulative_end", "release_period", "unit_won", "revenue", "operating_income", "profit_before_tax", "net_income",
                "net_income_controlling", "cum_revenue", "cum_operating_income", "cum_profit_before_tax", "cum_net_income",
                "cum_net_income_controlling"));
        Map<String, Integer> counts = new TreeMap<>();
        int missing = 0;
        for (String line : list) {
            if (line.isBlank()) {
                continue;
            }
            String[] c = line.split("\t", -1);
            Path file = dir.resolve(c[0] + ".html");
            if (!Files.exists(file)) {
                missing++;
                continue;
            }
            String row;
            try {
                DartProvisionalDocument.Parsed p = DartProvisionalDocument.parse(Files.readAllBytes(file), c[1]);
                row = String.join("\t", c[0], "OK", p.form().name(), String.valueOf(p.consolidated()), String.valueOf(p.periodStart()),
                        String.valueOf(p.periodEnd()), String.valueOf(p.cumulativeStart()), String.valueOf(p.cumulativeEnd()),
                        p.releasePeriod().name(), String.valueOf(p.unitWon()), s(p.current().revenue()), s(p.current().operatingIncome()),
                        s(p.current().profitBeforeTax()), s(p.current().netIncome()), s(p.current().netIncomeControlling()),
                        s(p.cumulative().revenue()), s(p.cumulative().operatingIncome()), s(p.cumulative().profitBeforeTax()),
                        s(p.cumulative().netIncome()), s(p.cumulative().netIncomeControlling()));
                counts.merge("OK " + p.releasePeriod(), 1, Integer::sum);
            } catch (DartProvisionalDocument.Unreadable e) {
                row = String.join("\t", c[0], e.reason(), DartProvisionalDocument.formOf(c[1]).name(), "", String.valueOf(e.periodStart()),
                        String.valueOf(e.periodEnd()), "", "", "", "", "", "", "", "", "", "", "", "", "", "");
                counts.merge(e.reason(), 1, Integer::sum);
            }
            out.add(row);
        }
        Files.write(Path.of(System.getenv("SWEEP_PROV_OUT")), out, StandardCharsets.UTF_8);
        System.out.println("[sweep] 본문 " + (out.size() - 1) + " · 캐시 없음 " + missing + " · " + counts);
        assertThat(out).as("캐시가 비었으면 이 시험은 아무것도 안 잰다").hasSizeGreaterThan(1);
    }

    private static String s(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }
}
