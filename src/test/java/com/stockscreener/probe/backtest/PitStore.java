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
import java.time.Instant;
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

    /**
     * 그 날짜의 <b>데이터 파일</b>이 있는가. {@code freshnessOf}가 이미 보지만 그것은
     * <b>비싼 길</b>에서만 돌고, 싼 건너뛰기 판정은 매니페스트만 본다 — 매니페스트가
     * 「완료」인데 gzip이 없으면 적재가 <b>영원히 건너뛰고</b> 스윕의 {@code readDate}가 던진다.
     */
    boolean hasData(String currency, LocalDate asOf) {
        return Files.exists(data(currency, asOf));
    }

    private Path data(String currency, LocalDate asOf) {
        return root.resolve(currency + "-" + asOf + ".csv.gz");
    }

    private Path manifest(String currency, LocalDate asOf) {
        return root.resolve(currency + "-" + asOf + ".manifest");
    }

    /**
     * 한 날짜의 저장본에 붙는 사실들. 값이 아니라 <b>그 값을 믿어도 되는 근거</b>다.
     *
     * @param attempted        시도한 모집단 수. 「유효하지만 짧은 파일」을 가려내는 근거다
     * @param codes            이 저장본이 담기로 <b>선언한</b> 코드 집합. 파일에서 관찰한 것이
     *                         아니다 — 한 종목에만 있는 코드가 통과해 버리기 때문이다
     * @param fingerprints     종목별 입력 지문. 바뀐 종목만 다시 만들기 위한 것
     * @param indexFingerprint 종목에 안 걸리는 입력(시장 지수). 종목 지문에 섞으면 지수가 한 번
     *                         바뀔 때 전 종목이 낡은 것으로 읽혀 종목 단위 무효화가 무의미해진다
     * @param inPlaceDigests   적재 시점에 이미 있던 행들의 <b>종목별</b> 내용 해시. 읽는 쪽이
     *                         <b>제자리 수정</b>을 싸게 알아채는 근거다({@link PitChangeDetector}).
     *                         종목별인 이유는 저쪽 javadoc에 있다 — 전역 하나였을 때는 모집단이
     *                         바뀌기만 해도 전 종목이 의심이 됐다
     * @param fpFormula        <b>종목 지문 식의 판</b>. 값이 아니라 <b>재는 방법</b>이다 —
     *                         이것이 없으면 「마이그레이션이 끝난 쉬는 상태」와 「기준선이
     *                         소진된 상태」를 구별할 수 없다. 둘 다 「전량 일치 아님」으로
     *                         보이기 때문이다(실측: 소진 275/350, 기업행위 승인 하나 349/350)
     * @param indexFpFormula   공유 지문 식의 판. 같은 이유다
     * @param calculatorFp     값을 만든 계산기의 판. DB가 하나도 안 움직여도 공식이 바뀌면
     *                         저장본은 낡는데, 이것이 없으면 그것을 알 방법이 아예 없다
     * @param writtenAt        <b>적재 실행이 시작한</b> 시각. 날짜마다 찍지 않는다 — 읽는 쪽은
     *                         서로 다른 시각마다 변경 훑기를 한 번씩 돌아야 하고(회당 2초대),
     *                         실측에서 그것이 스윕 시간을 통째로 지배했다. 실행 단위로 묶으면
     *                         한 번이면 된다. 이르게 잡히므로 과검출이지 미검출이 아니다
     * @param failed           값을 못 만든 종목. <b>비어 있지 않으면 완료로 적지 않는다</b>
     */
    record Stamp(int attempted, java.util.Set<String> codes, Map<Long, String> fingerprints,
                 String indexFingerprint, Map<Long, String> inPlaceDigests, String calculatorFp,
                 String fpFormula, String indexFpFormula,
                 Instant writtenAt, java.util.Set<Long> failed) {
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
     * <b>넘겨받은 지문 집합</b>이 그 날짜의 저장본과 맞는가.
     *
     * <p><b>MUST: 「그 날짜가 끝났고 유효한가」로 읽지 않는다.</b> 이 판정은 인자로 준 종목만
     * 본다 — {@code PitLoadTest}가 <b>의심 종목만</b> 넘기므로(1단계가 「그 밖은 안 움직였다」를
     * 보증한다) 안 넘긴 종목은 <b>신선으로 가정</b>된다. 전량을 넘기던 때의 문장을 그대로 두면
     * 다음 세션이 이것을 전수 감사로 읽는다.
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

    /** 한 날짜를 쓴다. <b>데이터 → 원자적 이동 → 매니페스트</b> 순서를 지킨다. */
    void writeDate(String currency, LocalDate asOf,
                   Map<Long, Map<String, BigDecimal>> values, Stamp stamp) throws Exception {
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
        writeManifest(currency, asOf, values.size(), stamp);
    }

    /**
     * 데이터는 그대로 두고 매니페스트만 다시 찍는다.
     *
     * <p>읽는 쪽이 「낡지 않았음」을 내용으로 확정한 뒤 쓴다. 안 되쓰면 다음 스윕이 같은
     * 확정을 처음부터 되풀이하고, 전략을 여러 번 고치는 것이 이 저장소의 용도라 그 되풀이가
     * 곧 비용이 된다.
     */
    void rewriteManifest(String currency, LocalDate asOf, int stocks, Stamp stamp)
            throws Exception {
        if (!Files.exists(data(currency, asOf))) {
            throw new IllegalStateException(currency + " " + asOf
                    + " 데이터 파일이 없는데 매니페스트만 쓰려 한다 — 그러면 없는 것이 완료로 읽힌다");
        }
        writeManifest(currency, asOf, stocks, stamp);
    }

    private void writeManifest(String currency, LocalDate asOf, int stocks, Stamp stamp)
            throws Exception {
        Path mtmp = root.resolve(currency + "-" + asOf + ".manifest.tmp");
        StringBuilder sb = new StringBuilder();
        sb.append("currency=").append(currency).append('\n');
        sb.append("asOf=").append(asOf).append('\n');
        sb.append("attempted=").append(stamp.attempted()).append('\n');
        sb.append("stocks=").append(stocks).append('\n');
        sb.append("codes=").append(String.join(" ", new java.util.TreeSet<>(stamp.codes())))
                .append('\n');
        // MUST: 실패가 있으면 「완료」로 적지 않는다. 적으면 생존분만 든 날짜가 유효로 읽힌다.
        // MUST: 정렬해서 적는다. Set의 순회 순서는 보장되지 않으므로 같은 입력이 다른
        // 매니페스트를 만들고, 그러면 두 실행이 같은지를 파일로 비교할 수 없다.
        sb.append("failed=").append(new java.util.TreeSet<>(stamp.failed()).stream()
                .map(String::valueOf).collect(java.util.stream.Collectors.joining(" ")))
                .append('\n');
        sb.append("indexFp=").append(stamp.indexFingerprint()).append('\n');
        sb.append("calculatorFp=").append(stamp.calculatorFp()).append('\n');
        sb.append("fpFormula=").append(stamp.fpFormula()).append('\n');
        sb.append("indexFpFormula=").append(stamp.indexFpFormula()).append('\n');
        sb.append("writtenAt=").append(stamp.writtenAt()).append('\n');
        sb.append("complete=").append(stamp.failed().isEmpty()).append('\n');
        for (Map.Entry<Long, String> e : new java.util.TreeMap<>(stamp.fingerprints()).entrySet()) {
            sb.append("fp:").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        // MUST: `fp:`와 같은 이유로 정렬해서 적는다. Set·Map의 순회 순서는 보장되지 않으므로
        // 같은 입력이 다른 매니페스트를 만들고, 그러면 두 실행이 같은지를 파일로 비교할 수 없다.
        for (Map.Entry<Long, String> e : new java.util.TreeMap<>(stamp.inPlaceDigests()).entrySet()) {
            sb.append("ip:").append(e.getKey()).append('=').append(e.getValue()).append('\n');
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

    /**
     * 매니페스트의 <b>접두어 붙은 종목별 항목</b>을 뽑는다({@code fp:}·{@code ip:}).
     *
     * <p><b>MUST: 사본을 만들지 않는다.</b> 같은 파싱이 지금 {@code PitSweep.restamp}와
     * {@code PitLoadTest.stampFrom} 둘에서 필요하고, 갈리면 한쪽만 항목을 잃는다 — 이 파일
     * 계열에서 <b>이어받기를 빠뜨린 사고가 이미 두 번</b> 났다(ADR-0123 §규칙).
     *
     * @return 정렬된 가변 맵. 부르는 쪽이 모집단으로 자르고 새 값을 덮는다
     */
    static Map<Long, String> prefixed(Map<String, String> manifest, String prefix) {
        Map<Long, String> out = new java.util.TreeMap<>();
        for (Map.Entry<String, String> e : manifest.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                out.put(Long.parseLong(e.getKey().substring(prefix.length())), e.getValue());
            }
        }
        return out;
    }

    /**
     * 매니페스트의 종목별 항목을 <b>이어받되 모집단으로 자르고</b> 새로 잰 것을 덮는다.
     *
     * <p><b>양 끝이 다 결함이라 한 함수로 둔다.</b>
     * <ul>
     *   <li><b>안 이어받으면</b> — 부분만 재는 경로에서 안 잰 종목의 항목이 통째로 사라지고,
     *       그 종목은 다음 실행에서 「지문 없음」으로 <b>영원히 낡음</b>이 된다.</li>
     *   <li><b>안 자르면</b> — 편출 종목의 항목이 영원히 남는데 <b>값은
     *       {@code values.keySet().retainAll(population)}으로 데이터 파일에서 빠진다.</b> 그
     *       종목이 아무것도 안 바뀐 채 복귀하면 지문이 그대로 맞아 낡음이 아니게 되고, 값은
     *       영영 안 만들어지며, {@code PitSweep.repair}의 완전성 검사가 그 날짜에서
     *       <b>영구히 던진다.</b> 실측으로 편출입 이력이 있는 종목이 9개이고 2026-09-04에
     *       둘이 실제로 복귀했다.</li>
     * </ul>
     *
     * <p><b>지금까지 그 정리를 하던 것은 적재의 전량 교체 하나였다</b>(실측: 512 매니페스트가
     * 전부 「지금 모집단과 정확히 같은 {@code fp:} 집합」을 든다 — 여분 0 · 결손 0 · 345,600줄).
     * 적재가 부분만 재게 되면서 그 정리가 사라지므로 여기가 그 자리를 잇는다.
     */
    static Map<Long, String> carryOver(Map<String, String> manifest, String prefix,
                                       java.util.Collection<Long> population,
                                       Map<Long, String> fresh) {
        Map<Long, String> out = prefixed(manifest, prefix);
        out.putAll(fresh);
        // MUST: 자르기를 **합친 뒤에** 한다. 이어받은 쪽만 자르면 새로 잰 쪽에 섞인 모집단 밖
        // 종목이 그대로 실린다 — 종목별 해시는 **모집단 전체(두 통화)**를 한 번에 재므로 그것이
        // 실제로 일어난다(실측: USD 매니페스트에 1,350개가 실렸다. 모집단은 1,000이다).
        out.keySet().retainAll(new java.util.LinkedHashSet<>(population));
        return out;
    }

    Map<String, String> readManifest(String currency, LocalDate asOf) throws Exception {
        return readManifest(manifest(currency, asOf));
    }

    /** 저장소가 그 통화에 대해 담은 날짜. <b>파일이 정본이고 달력이 아니다.</b> */
    java.util.List<LocalDate> storedDates(String currency) throws Exception {
        if (!Files.exists(root)) {
            return java.util.List.of();
        }
        String prefix = currency + "-";
        try (var s = Files.list(root)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(prefix) && n.endsWith(".manifest"))
                    .map(n -> n.substring(prefix.length(), n.length() - ".manifest".length()))
                    .map(LocalDate::parse)
                    // 매니페스트만 보고 「있다」고 하지 않는다 — 데이터 파일이 사라진 날짜가
                    // 목록에 들면 읽기가 그때서야 터진다.
                    .filter(d -> Files.exists(data(currency, d)))
                    .sorted()
                    .toList();
        }
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
