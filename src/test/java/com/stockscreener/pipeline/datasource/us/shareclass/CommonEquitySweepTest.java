package com.stockscreener.pipeline.datasource.us.shareclass;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 보통주 자본 판정(탐색 {@code PERSHARE-DEN-PLAN-1026.md} v1.1)의 0콜 스윕 — 저장한 공시 인스턴스를 운영 판독기({@link ClassFactReader})와
 * 엔진({@link CommonEquity})에 먹여 (공시, 날짜)마다 N · N′ · 갈래를 TSV로 찍는다. 같은 재료를 먹인 시안({@code rule6.py})의 출력과 줄 단위로
 * 맞대 어긋남을 어느 쪽 결함인지 가른다. DB · 외부 호출 없음.
 *
 * <p>스위치: {@code EQUITY_INST_DIRS}(필수 · 쉼표 — {@code <티커>_<접수번호>.xml}) · {@code EQUITY_FACTCACHE}(CIK를 읽을 companyfacts 폴더 ·
 * 기본 {@code .factcache}) · {@code EQUITY_OUT}(기본 {@code build/common-equity-sweep.tsv}) · {@code EQUITY_FACTS_OUT}(주면 공시마다 저장 팩트
 * JSON을 판 8 그대로 한 줄씩 — 계획 5 ⑥의 판 7 대 8 맞대기 재료).
 */
@EnabledIfEnvironmentVariable(named = "EQUITY_INST_DIRS", matches = ".+")
class CommonEquitySweepTest {

    @Test
    void sweep() throws Exception {
        Path cache = Path.of(Optional.ofNullable(System.getenv("EQUITY_FACTCACHE")).orElse(".factcache"));
        Path out = Path.of(Optional.ofNullable(System.getenv("EQUITY_OUT")).orElse("build/common-equity-sweep.tsv"));
        String factsOut = System.getenv("EQUITY_FACTS_OUT");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Map<String, ClassFacts>> byTicker = new TreeMap<>();
        List<String> factLines = new ArrayList<>();
        Map<String, Long> ciks = new LinkedHashMap<>();
        for (String d : System.getenv("EQUITY_INST_DIRS").split(",")) {
            List<Path> files;
            try (var s = Files.list(Path.of(d))) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".xml")).sorted().toList();
            }
            for (Path p : files) {
                String name = p.getFileName().toString();
                String ticker = name.substring(0, name.indexOf('_'));
                String accession = name.substring(name.indexOf('_') + 1, name.length() - 4);
                long cik = ciks.computeIfAbsent(ticker, t -> mapper.readTree(cache.resolve(t + ".json").toFile()).get("cik").asLong());
                ClassFacts facts;
                try (InputStream in = Files.newInputStream(p)) {
                    facts = ClassFactReader.read(in, cik, name).facts();
                }
                byTicker.computeIfAbsent(ticker, t -> new LinkedHashMap<>()).putIfAbsent(accession, facts);
                if (factsOut != null) {
                    factLines.add(ticker + "\t" + accession + "\t" + facts.toJson());
                }
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("ticker\taccession\tdate\tbranch\tnon_common\tncav_non_common");
        byTicker.forEach((ticker, facts) -> new TreeMap<>(asSorted(CommonEquity.judge(facts))).forEach((k, v) ->
                lines.add(String.join("\t", ticker, k.split("\\|")[0], k.split("\\|")[1], v.branch(), num(v.nonCommon()),
                        num(v.ncavNonCommon())))));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, lines);
        if (factsOut != null) {
            Files.write(Path.of(factsOut), factLines);
        }
    }

    private static Map<String, CommonEquity.Value> asSorted(Map<CommonEquity.Key, CommonEquity.Value> judged) {
        Map<String, CommonEquity.Value> out = new TreeMap<>();
        judged.forEach((k, v) -> out.put(k.accession() + "|" + k.date(), v));
        return out;
    }

    private static String num(Double v) {
        return v == null ? "" : String.valueOf(Math.round(v));
    }
}
