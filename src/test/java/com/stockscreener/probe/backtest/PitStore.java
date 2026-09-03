package com.stockscreener.probe.backtest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 시점 재현 값의 파일 저장소. <b>3.6시간짜리 적재가 중간에 죽어도 처음부터가 되지 않게 하는 것</b>이
 * 이 클래스의 존재 이유다.
 *
 * <p>규약 셋.
 * <ol>
 *   <li><b>데이터는 `.tmp`에 쓰고 원자적으로 옮긴다.</b> 반쪽 파일이 최종 이름을 갖는 순간이 없다.</li>
 *   <li><b>매니페스트를 데이터보다 <em>뒤에</em> 쓴다.</b> 매니페스트가 있다 = 그 날짜는 끝났다.
 *       순서를 뒤집으면 죽은 실행이 「끝났다」고 주장하는 파일을 남긴다.</li>
 *   <li><b>지문이 안 맞으면 없는 것으로 친다.</b> 낡은 값을 쓰느니 다시 계산하는 편이 낫다.</li>
 * </ol>
 *
 * <p>MUST NOT: 저장소를 {@code build/} 아래 두지 않는다 — {@code ./gradlew clean}이 3.6시간을
 * 조용히 지운다. 저장소 안 다른 곳도 안 된다({@code .gitignore}가 안 덮어 커밋에 섞인다).
 * 기본은 {@code ~/.stock-screener/pit-store/}이고 {@code PIT_STORE_DIR}로 바꾼다.
 */
final class PitStore {

    private final Path root;

    PitStore(Path root) {
        this.root = root;
    }

    static PitStore fromEnvironment() {
        String dir = System.getenv("PIT_STORE_DIR");
        Path root = (dir == null || dir.isBlank())
                ? Path.of(System.getProperty("user.home"), ".stock-screener", "pit-store")
                : Path.of(dir);
        return new PitStore(root);
    }

    Path root() {
        return root;
    }

    private Path data(String currency, LocalDate asOf) {
        return root.resolve(currency + "-" + asOf + ".csv.gz");
    }

    private Path manifest(String currency, LocalDate asOf) {
        return root.resolve(currency + "-" + asOf + ".manifest");
    }

    /**
     * 저장본이 지금도 쓸 수 있는가. <b>못 쓰는 사유를 값으로 들고 다닌다</b> — 「없음」과
     * 「지수가 바뀜」과 「미완료」를 {@code null} 하나로 접으면 재개가 왜 다시 도는지 알 수 없다.
     */
    record Freshness(String unusableReason, java.util.Set<Long> staleStocks) {

        boolean usable() {
            return unusableReason == null;
        }

        /** 그대로 읽어도 되는가. */
        boolean complete() {
            return usable() && staleStocks.isEmpty();
        }
    }

    /**
     * 그 날짜가 <b>끝났고 아직 유효한가.</b>
     *
     * <p><b>종목 지문</b>이 다르면 그 종목만 다시 만든다.
     * <p><b>지수 지문</b>이 다르면 <b>날짜 전체</b>가 못 쓰게 된다 — 지수는 모집단이 함께 쓰므로
     * 어느 한 종목의 낡음으로 돌릴 수 없다.
     *
     * <p><b>전역 지문을 쓰지 않는 이유</b>: 값이 실제로 바뀌는 사건이 평일 대낮에도 떨어진다
     * (실측 2026-09-03 14:00 사슬이 APH 한 종목의 시세 1,760행을 재작성했다). 전역이면 그 한
     * 종목 때문에 270MB가 통째로 죽는다. 종목 단위면 그 종목이 든 날짜 파일만 다시 쓴다 —
     * <b>gzip은 부분 수정이 안 되므로 파일 단위가 최소 단위다.</b>
     *
     * <p>같은 24시간에 {@code updated_at}은 <b>1,350종목 전부</b>에서 움직였다. 그래서 지문은
     * 내용만 본다({@link PitFingerprint}).
     */
    Freshness freshnessOf(String currency, LocalDate asOf,
                          Map<Long, String> fingerprints, String indexFingerprint)
            throws Exception {
        Path m = manifest(currency, asOf);
        if (!Files.exists(m)) {
            return new Freshness("매니페스트 없음", java.util.Set.of());
        }
        if (!Files.exists(data(currency, asOf))) {
            return new Freshness("데이터 파일 없음", java.util.Set.of());
        }
        Map<String, String> fields = readManifest(m);
        if (!"true".equals(fields.get("complete"))) {
            return new Freshness("미완료(실패 " + fields.getOrDefault("failed", "?") + ")",
                    java.util.Set.of());
        }
        String storedIndex = fields.get("indexFp");
        if (storedIndex == null || !storedIndex.equals(indexFingerprint)) {
            return new Freshness("지수 지문 불일치", java.util.Set.of());
        }
        java.util.Set<Long> stale = new java.util.TreeSet<>();
        for (Map.Entry<Long, String> e : fingerprints.entrySet()) {
            String stored = fields.get("fp:" + e.getKey());
            if (stored == null || !stored.equals(e.getValue())) {
                stale.add(e.getKey());
            }
        }
        // 매니페스트에는 있는데 지금 모집단에 없는 종목은 낡음이 아니다(편출이다).
        return new Freshness(null, stale);
    }

    /**
     * 한 날짜를 쓴다. <b>데이터 → 원자적 이동 → 매니페스트</b> 순서를 지킨다.
     *
     * @param attempted 시도한 모집단 수. 「유효하지만 짧은 파일」을 읽는 쪽에서 가려내는 근거다
     * @param codes     이 저장본이 담기로 <b>선언한</b> 코드 집합. 파일에서 관찰한 것이 아니다 —
     *                  한 종목에만 있는 코드가 통과해 버리기 때문이다
     * @param indexFingerprint 종목에 안 걸리는 입력(시장 지수). 종목 지문에 섞으면 지수가 한 번
     *                  바뀔 때 전 종목이 낡은 것으로 읽혀 종목 단위 무효화가 무의미해진다
     */
    void writeDate(String currency, LocalDate asOf,
                   Map<Long, Map<String, BigDecimal>> values,
                   int attempted, java.util.Set<String> codes,
                   Map<Long, String> fingerprints, String indexFingerprint,
                   java.util.Set<Long> failed)
            throws Exception {
        Files.createDirectories(root);
        Path tmp = root.resolve(currency + "-" + asOf + ".csv.gz.tmp");
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(tmp)), StandardCharsets.UTF_8))) {
            for (Map.Entry<Long, Map<String, BigDecimal>> e : values.entrySet()) {
                if (e.getValue().isEmpty()) {
                    // 「계산했으나 값이 없음」. 없으면 적재 실패와 구분되지 않는다.
                    w.write(e.getKey() + ",,\n");
                    continue;
                }
                for (Map.Entry<String, BigDecimal> v : e.getValue().entrySet()) {
                    w.write(e.getKey() + "," + v.getKey() + "," + v.getValue().toPlainString());
                    w.write('\n');
                }
            }
        }
        Files.move(tmp, data(currency, asOf),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        // MUST: 데이터가 최종 이름을 가진 뒤에 쓴다.
        Path mtmp = root.resolve(currency + "-" + asOf + ".manifest.tmp");
        StringBuilder sb = new StringBuilder();
        sb.append("currency=").append(currency).append('\n');
        sb.append("asOf=").append(asOf).append('\n');
        sb.append("attempted=").append(attempted).append('\n');
        sb.append("stocks=").append(values.size()).append('\n');
        sb.append("codes=").append(String.join(" ", new java.util.TreeSet<>(codes))).append('\n');
        // MUST: 실패가 있으면 「완료」로 적지 않는다. 적으면 생존분만 든 날짜가 유효로 읽힌다.
        // MUST: 정렬해서 적는다. Set의 순회 순서는 보장되지 않으므로 같은 입력이 다른
        // 매니페스트를 만들고, 그러면 두 실행이 같은지를 파일로 비교할 수 없다.
        sb.append("failed=").append(new java.util.TreeSet<>(failed).stream()
                .map(String::valueOf).collect(java.util.stream.Collectors.joining(" ")))
                .append('\n');
        sb.append("indexFp=").append(indexFingerprint).append('\n');
        sb.append("complete=").append(failed.isEmpty()).append('\n');
        for (Map.Entry<Long, String> e : new java.util.TreeMap<>(fingerprints).entrySet()) {
            sb.append("fp:").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        Files.writeString(mtmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(mtmp, manifest(currency, asOf),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 지표 코드는 종류가 적어 인터닝한다 — 행마다 새 String을 만들면 그것이 메모리를 지배한다. */
    Map<Long, Map<String, BigDecimal>> readDate(String currency, LocalDate asOf) throws Exception {
        Map<String, String> pool = new HashMap<>();
        Map<Long, Map<String, BigDecimal>> back = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(data(currency, asOf))),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int c1 = line.indexOf(',');
                int c2 = line.indexOf(',', c1 + 1);
                long stockId = Long.parseLong(line.substring(0, c1));
                Map<String, BigDecimal> m = back.computeIfAbsent(stockId, k -> new LinkedHashMap<>());
                if (c2 == c1 + 1) {
                    continue; // 표지 행
                }
                String code = pool.computeIfAbsent(line.substring(c1 + 1, c2), x -> x);
                m.put(code, new BigDecimal(line.substring(c2 + 1)));
            }
        }
        return back;
    }

    Map<String, String> readManifest(String currency, LocalDate asOf) throws Exception {
        return readManifest(manifest(currency, asOf));
    }

    private static Map<String, String> readManifest(Path p) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return out;
    }

    /** 중단된 실행이 남긴 `.tmp`. 재개 전에 지운다 — 남아 있어도 무해하지만 쌓인다. */
    int sweepTemporaries() throws Exception {
        if (!Files.exists(root)) {
            return 0;
        }
        int removed = 0;
        try (var s = Files.list(root)) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".tmp")).toList()) {
                Files.deleteIfExists(p);
                removed++;
            }
        }
        return removed;
    }
}
