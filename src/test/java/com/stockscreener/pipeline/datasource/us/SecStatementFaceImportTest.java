package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * <b>재료 가져오기</b>(0콜 · ADR-0202) — 받아 둔 SEC Financial Statement Data Sets 분기 ZIP({@code FSDS_DIR})에서, {@code FACE_CIKS}(한 줄에 CIK
 * 하나)의 공시마다 재무제표 본문 개념과 손익계산서 본문 값을 {@link SecStatementFaceCollector}의 캐시 모양으로 {@code FACE_DIR}에 쓴다
 * ({@code source=fsds}). 데이터셋이 정본이다 — 공시 폴더에서 지은 파일도 덮는다. 데이터셋이 그 공시의 손익계산서 마지막 줄을 못 실었으면 파일을 안
 * 남긴다(그 공시는 운영 수집기가 공시 폴더에서 받는다). 원자적으로 쓴다(잘린 파일이 본문을 조용히 바꾸지 않게).
 *
 * <pre>
 * FSDS_DIR=~/.stock-screener/sec-fsds FACE_DIR=~/.stock-screener/sec-face FACE_CIKS=&lt;파일&gt; ./gradlew test --tests "*SecStatementFaceImportTest*"
 * </pre>
 */
class SecStatementFaceImportTest {

    @Test
    @DisplayName("데이터셋 분기 ZIP에서 공시 본문(개념 · 손익계산서 본문 값) 캐시를 짓는다")
    void importDataSets() throws Exception {
        String fsds = System.getenv("FSDS_DIR");
        String faceDir = System.getenv("FACE_DIR");
        String ciks = System.getenv("FACE_CIKS");
        Assumptions.assumeTrue(fsds != null && faceDir != null && ciks != null, "FSDS_DIR · FACE_DIR · FACE_CIKS가 없어 건너뛴다");
        Set<String> wanted = new HashSet<>(Files.readAllLines(Path.of(ciks)).stream().map(String::strip).filter(s -> !s.isEmpty()).toList());
        int written = 0;
        int withdrawn = 0;
        List<Path> zips;
        try (Stream<Path> files = Files.list(Path.of(fsds))) {
            zips = files.filter(p -> p.getFileName().toString().endsWith(".zip")).sorted().toList();
        }
        for (Path zip : zips) {
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                Map<String, String> cikOf = new HashMap<>();
                try (BufferedReader r = reader(zf, "sub.txt")) {
                    List<String> head = List.of(r.readLine().split("\t", -1));
                    int adsh = head.indexOf("adsh");
                    int cik = head.indexOf("cik");
                    for (String line; (line = r.readLine()) != null; ) {
                        String[] v = line.split("\t", -1);
                        if (wanted.contains(v[cik])) {
                            cikOf.put(v[adsh], v[cik]);
                        }
                    }
                }
                Map<String, Set<String>> face = new HashMap<>();
                Set<String> hasIncome = new HashSet<>();
                Set<String> inPre = new HashSet<>();
                Set<String> incomeLines = new HashSet<>();   // adsh|tag|version — 손익 표의 줄
                try (BufferedReader r = reader(zf, "pre.txt")) {
                    List<String> head = List.of(r.readLine().split("\t", -1));
                    int adsh = head.indexOf("adsh");
                    int stmt = head.indexOf("stmt");
                    int tag = head.indexOf("tag");
                    int version = head.indexOf("version");
                    for (String line; (line = r.readLine()) != null; ) {
                        String[] v = line.split("\t", -1);
                        if (!cikOf.containsKey(v[adsh])) {
                            continue;
                        }
                        inPre.add(v[adsh]);
                        if (SecStatementFace.finalIncomeLine(v[stmt], v[tag])) {
                            hasIncome.add(v[adsh]);
                        }
                        if (SecStatementFace.INCOME_STATEMENTS.contains(v[stmt])) {
                            incomeLines.add(v[adsh] + "|" + v[tag] + "|" + v[version]);
                        }
                        String concept = SecStatementFace.dataSetConcept(v[version], v[tag], v[stmt]);
                        if (concept != null) {
                            face.computeIfAbsent(v[adsh], k -> new TreeSet<>()).add(concept);
                        }
                    }
                }
                Map<String, Set<String>> values = new HashMap<>();
                try (BufferedReader r = reader(zf, "num.txt")) {
                    List<String> head = List.of(r.readLine().split("\t", -1));
                    int adsh = head.indexOf("adsh");
                    int tag = head.indexOf("tag");
                    int version = head.indexOf("version");
                    int ddate = head.indexOf("ddate");
                    int qtrs = head.indexOf("qtrs");
                    int segments = head.indexOf("segments");
                    int coreg = head.indexOf("coreg");
                    int value = head.indexOf("value");
                    for (String line; (line = r.readLine()) != null; ) {
                        String[] v = line.split("\t", -1);
                        if (!hasIncome.contains(v[adsh]) || !v[segments].isEmpty() || !v[coreg].isEmpty() || v[value].isEmpty()
                                || !incomeLines.contains(v[adsh] + "|" + v[tag] + "|" + v[version])) {
                            continue;
                        }
                        values.computeIfAbsent(v[adsh], k -> new TreeSet<>()).add(v[ddate] + " " + v[qtrs] + " "
                                + new BigDecimal(v[value]).stripTrailingZeros().toPlainString());
                    }
                }
                for (String adshInPre : inPre) {
                    Path file = Path.of(faceDir, cikOf.get(adshInPre), adshInPre.replace("-", "") + ".txt");
                    if (!hasIncome.contains(adshInPre)) {
                        // 데이터셋이 손익계산서의 마지막 줄을 못 실었다 — 이 재료로 판정하지 않는다(그 공시는 운영 수집기가 공시 폴더에서 받는다).
                        if (Files.exists(file) && Files.readAllLines(file).get(0).matches("source=(fsds|none)")) {
                            Files.delete(file);
                            withdrawn++;
                        }
                        continue;
                    }
                    StringBuilder body = new StringBuilder("source=fsds\n");
                    face.getOrDefault(adshInPre, Set.of()).forEach(c -> body.append(c).append('\n'));
                    values.getOrDefault(adshInPre, Set.of()).forEach(v -> body.append("@value ").append(v).append('\n'));
                    Files.createDirectories(file.getParent());
                    Path part = file.resolveSibling(file.getFileName() + ".part");
                    Files.writeString(part, body, StandardCharsets.UTF_8);
                    Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    written++;
                }
                System.out.printf("%s 공시=%d 손익계산서있음=%d%n", zip.getFileName(), inPre.size(), hasIncome.size());
            }
        }
        System.out.printf("written=%d withdrawn=%d%n", written, withdrawn);
    }

    private static BufferedReader reader(ZipFile zf, String name) throws Exception {
        ZipEntry entry = zf.getEntry(name);
        return new BufferedReader(new InputStreamReader(zf.getInputStream(entry), StandardCharsets.UTF_8));
    }
}
