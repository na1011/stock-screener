package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장해 둔 {@code fnlttSinglAcntAll} 응답 <b>전량</b>을 매퍼에 먹여 중단영업 판정(F2b · ADR-0184)을 TSV로 찍는다. DB도 외부
 * 호출도 없다 — 국내 관문 켬의 영향 범위를 재적재 전에 낸다.
 *
 * <p>입력 파일 이름은 {@code <종목>_<연도>_<보고서코드>.json}(탐색 {@code f2b/kr_probe.py}가 남긴 꼴)이고 본문에 {@code list}가
 * 있다. 출력 열: 종목 · 연도 · 보고서 · 연결 · 지배 · 지배주주 순이익(직접) · 계속+중단 지배 합.
 *
 * <pre>REPLAY_KR_DIR=&lt;원응답 디렉터리&gt; REPLAY_KR_OUT=&lt;tsv&gt; ./gradlew test --tests "*DartDiscontinuedSweepTest*"</pre>
 */
@EnabledIfEnvironmentVariable(named = "REPLAY_KR_DIR", matches = ".+")
class DartDiscontinuedSweepTest {

    @Test
    @DisplayName("[sweep] 저장한 응답 전량에서 중단영업 판정과 지배주주 순이익 합을 찍는다")
    void sweepDiscontinued() throws Exception {
        Path dir = Path.of(System.getenv("REPLAY_KR_DIR"));
        Path out = Path.of(Optional.ofNullable(System.getenv("REPLAY_KR_OUT")).orElse("build/kr-discontinued.tsv"));
        List<Path> files;
        try (Stream<Path> paths = Files.list(dir)) {
            files = paths.filter(p -> p.getFileName().toString().matches(".+_\\d{4}_\\d{5}\\.json")).sorted().toList();
        }
        assertThat(files).as("원응답이 없으면 이 시험은 아무것도 안 잰다").isNotEmpty();

        ObjectMapper mapper = new ObjectMapper();
        int answered = 0;
        int nonZero = 0;
        int controllingUnknown = 0;
        int fromParts = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (Path file : files) {
                String[] key = file.getFileName().toString().replace(".json", "").split("_");
                JsonNode list = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8)).get("list");
                if (list == null || list.isEmpty()) {
                    continue;
                }
                List<JsonNode> income = DartFullAccountMapper.sections(list,
                        DartFullAccountMapper.SJ_INCOME, DartFullAccountMapper.SJ_COMPREHENSIVE_INCOME);
                if (income.isEmpty()) {
                    continue;
                }
                answered++;
                BigDecimal[] d = DartFullAccountMapper.discontinued(income, false);
                BigDecimal direct = DartFullAccountMapper.find(income, DartFullAccountMapper.ID_NET_INCOME_CONTROLLING, null);
                BigDecimal parts = direct == null ? DartFullAccountMapper.controllingIncomeFromParts(income) : null;
                if (d[0] == null || d[0].signum() != 0) {
                    nonZero++;
                    if (d[1] == null) {
                        controllingUnknown++;
                    }
                }
                if (parts != null) {
                    fromParts++;
                }
                w.write(String.join("\t", key[0], key[1], key[2], text(d[0]), text(d[1]), text(direct), text(parts)));
                w.newLine();
            }
        }
        System.out.printf("KR 중단 스윕 보고서=%d 중단≠0 또는 연결모름=%d 지배모름=%d 지배순이익_계속+중단합=%d out=%s%n",
                answered, nonZero, controllingUnknown, fromParts, out.toAbsolutePath());
    }

    private static String text(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
