package com.stockscreener.pipeline.datasource.us.shareclass;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 클래스 축 재료를 폴더에 두는 저장소 — 후보 순위 효과 스윕(관문 2단계 · {@code UsCandidateRankSweepLiveTest})이 운영 DB에 한 줄도
 * 안 쓰고 받기({@link com.stockscreener.pipeline.datasource.us.ShareClassCollector}) · 유도({@code ShareClassDeriver}) · 순위
 * 판독기({@code JdbcStoredShareCounts})를 <b>실제 코드 그대로</b> 돌리는 자리다.
 *
 * <ul>
 *   <li>받은 재료 둘은 폴더에 — {@code input/<stockId>_<cik>.json}(가지친 companyfacts — 운영 {@code sec_share_class_input}과 같은 것) ·
 *       {@code fact/<cik>_<접수번호>.json}(운영 {@code sec_filing_class_fact}의 한 행). 폴더가 남아 있으면 다시 돌릴 때 받은 것을 안
 *       받는다(받기의 「저장된 OK 행은 다시 안 묻는다」가 그대로 선다).</li>
 *   <li>판정은 메모리에 둔다 — 유도가 쓰고 순위 판독기가 같은 실행에서 읽는다.</li>
 *   <li>공시 보충 목록과 분할 원장은 부모 클래스가 운영 DB를 읽는다 — 읽기 전용 연결을 준다.</li>
 * </ul>
 */
public class ShareClassFileStore extends ShareClassStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path input;
    private final Path fact;
    private final JdbcTemplate jdbc;
    /** CIK → 접수번호 → 행의 상태(팩트 본문은 빼고 — 18,000행이면 힙을 채운다). 처음 부를 때 폴더를 한 번 읽는다. */
    private Map<Long, Map<String, FactRow>> facts;
    private Map<Long, Path> inputFiles;
    private final Map<Long, List<VerdictRow>> verdicts = new LinkedHashMap<>();

    public ShareClassFileStore(JdbcTemplate readOnly, Path dir) {
        super(readOnly);
        this.jdbc = readOnly;
        this.input = dir.resolve("input");
        this.fact = dir.resolve("fact");
        try {
            Files.createDirectories(input);
            Files.createDirectories(fact);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 그 줄의 재료가 이미 있으면 그 경로(받기를 건너뛸 수 있다). */
    public Path inputOf(long stockId) {
        return inputs().get(stockId);
    }

    @Override
    public void saveInput(long stockId, long cik, String companyFacts) {
        Path target = input.resolve(stockId + "_" + cik + ".json");
        Path old = inputs().get(stockId);
        write(target, companyFacts);
        if (old != null && !old.equals(target)) {
            try {
                Files.delete(old);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        inputs().put(stockId, target);
    }

    @Override
    public Map<String, FactRow> factRows(long cik) {
        return new HashMap<>(facts().getOrDefault(cik, Map.of()));
    }

    @Override
    public void saveFact(long cik, String accession, String form, LocalDate filed, String status, String facts,
                         String failureKind, String failureMessage) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("form", form);
        row.put("filed", filed.toString());
        row.put("readerVersion", ClassFactReader.READER_VERSION);
        row.put("status", status);
        row.put("facts", facts);
        row.put("failureKind", failureKind);
        row.put("failureMessage", failureMessage);
        write(fact.resolve(cik + "_" + accession + ".json"), row.toString());
        facts().computeIfAbsent(cik, k -> new HashMap<>()).put(accession, stateOf(accession, row));
    }

    /** 폴더의 재료 중 그 시장의 활성 줄 — 티커는 운영 {@code stock}에서 읽는다(재료는 종목 ID로 묶여 있다). */
    @Override
    public List<Input> inputs(String marketCode) {
        Map<Long, String> active = new HashMap<>();
        jdbc.query("SELECT id, ticker FROM stock WHERE market_code = ? AND is_active",
                rs -> {
                    active.put(rs.getLong("id"), rs.getString("ticker"));
                }, marketCode);
        List<Input> out = new ArrayList<>();
        try {
            for (Map.Entry<Long, Path> e : inputs().entrySet()) {
                String ticker = active.get(e.getKey());
                if (ticker != null) {
                    String[] key = e.getValue().getFileName().toString().replace(".json", "").split("_");
                    out.add(new Input(e.getKey(), ticker, Long.parseLong(key[1]), Files.readString(e.getValue())));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(java.util.Comparator.comparing(Input::ticker));
        return out;
    }

    @Override
    public Map<String, ClassFacts> okFacts(long cik) {
        Map<String, ClassFacts> out = new HashMap<>();
        facts().getOrDefault(cik, Map.of()).forEach((accession, row) -> {
            if ("OK".equals(row.status()) && row.readerVersion() == ClassFactReader.READER_VERSION) {
                JsonNode stored = MAPPER.readTree(fact.resolve(cik + "_" + accession + ".json").toFile());
                out.put(accession, ClassFacts.fromJson(stored.get("facts").asString()));
            }
        });
        return out;
    }

    @Override
    public int[] replaceVerdicts(long stockId, long cik, List<VerdictRow> rows) {
        List<VerdictRow> previous = verdicts.put(stockId, List.copyOf(rows));
        int removed = previous == null ? 0 : (int) previous.stream()
                .filter(p -> rows.stream().noneMatch(r -> r.accession().equals(p.accession()))).count();
        return new int[]{rows.size(), removed};
    }

    /** 유도가 메모리에 둔 판정 — 활성 줄만은 {@link #inputs}가 이미 걸렀다. */
    @Override
    protected Map<Long, List<ShareClassLine.Verdict>> verdictsByStock(String marketCode) {
        Map<Long, List<ShareClassLine.Verdict>> out = new LinkedHashMap<>();
        verdicts.forEach((stockId, rows) -> out.put(stockId, rows.stream().map(ShareClassStore::lineVerdict).toList()));
        return out;
    }

    /** 유도가 메모리에 둔 판정 행(스윕 출력용). */
    public Map<Long, List<VerdictRow>> verdictRows() {
        return verdicts;
    }

    /** 받은 인스턴스 행의 상태별 수(스윕 요약용 · 지금 읽기 판만). */
    public Map<String, Integer> factStatusCounts() {
        Map<String, Integer> out = new java.util.TreeMap<>();
        facts().values().forEach(byAccession -> byAccession.values().forEach(row -> {
            if (row.readerVersion() == ClassFactReader.READER_VERSION) {
                out.merge(row.failureKind() == null ? row.status() : row.status() + ":" + row.failureKind(), 1, Integer::sum);
            }
        }));
        return out;
    }

    /** 지금 읽기 판이 아닌 인스턴스 행의 수 — 유도는 이 행을 없는 것으로 친다({@link #okFacts}). */
    public long staleFactRows() {
        return facts().values().stream().flatMap(byAccession -> byAccession.values().stream())
                .filter(row -> row.readerVersion() != ClassFactReader.READER_VERSION).count();
    }

    /** 종목 ID → 재료 파일. 처음 부를 때 폴더를 한 번 읽는다(반쪽 파일 {@code .tmp}는 안 읽는다). */
    private Map<Long, Path> inputs() {
        if (inputFiles == null) {
            inputFiles = new HashMap<>();
            try (Stream<Path> files = Files.list(input)) {
                files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> inputFiles.put(
                        Long.parseLong(p.getFileName().toString().split("_")[0]), p));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return inputFiles;
    }

    private Map<Long, Map<String, FactRow>> facts() {
        if (facts == null) {
            facts = new HashMap<>();
            try (Stream<Path> files = Files.list(fact)) {
                for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                    String name = p.getFileName().toString().replace(".json", "");
                    int cut = name.indexOf('_');
                    String accession = name.substring(cut + 1);
                    facts.computeIfAbsent(Long.parseLong(name.substring(0, cut)), k -> new HashMap<>())
                            .put(accession, stateOf(accession, MAPPER.readTree(p.toFile())));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return facts;
    }

    private static FactRow stateOf(String accession, JsonNode row) {
        return new FactRow(accession, row.get("readerVersion").asInt(), row.get("status").asString(), text(row, "failureKind"));
    }

    private static String text(JsonNode row, String field) {
        JsonNode v = row.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    /** 끊긴 실행이 반쪽 파일을 남기지 않게 옆에 쓰고 옮긴다. */
    private static void write(Path target, String content) {
        try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, content);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
