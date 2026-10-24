package com.stockscreener.pipeline.datasource.us.shareclass;

import com.stockscreener.pipeline.datasource.DataSourceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 클래스 축 판정 엔진의 0콜 스윕(탐색 `SHARES-CLASS-PLAN-V5-1015.md` §4-1 드라이런 1).
 *
 * <p>저장한 재료 폴더({@code SHARE_CLASS_DIR} — {@code tcf/<티커>.json} companyfacts · {@code tinst/<CIK>_<접수번호>.xml}
 * 인스턴스 · {@code splits.tsv} · 모집단 JSON)를 엔진에 먹여 공시마다 판정을 TSV로 찍는다. 같은 재료를 먹인 시뮬레이터
 * ({@code v5bsim.py})의 출력과 줄 단위로 맞대 어긋남을 어느 쪽 결함인지 가른다. DB·외부 호출 없음.
 *
 * <p>스위치: {@code SHARE_CLASS_DIR}(필수) · {@code SHARE_CLASS_TODO}(모집단 JSON 이름들, 쉼표 · 기본 {@code pop_todo.json}) ·
 * {@code SHARE_CLASS_OUT}(기본 {@code build/share-class-sweep.tsv}) · 문턱 {@code TAU} · {@code TAUC} · {@code TAUC_PT} ·
 * {@code SHARE_CLASS_FRAME=on}(운영 입력의 틀 · IFRS 별칭 · {@code plain}은 별칭 없는 틀) · {@code SHARE_CLASS_SUPP}(보충 공시 목록 TSV — 운영 {@code withFilings}와 같다).
 * {@code SHARE_CLASS_TODO=auto}는 {@code tcf/}의 모든 티커를 앵커 후보({@code anchorCandidates})와 함께 돈다(모집단 JSON 없이).
 *
 * <p>보통주 귀속 이익(탐색 {@code PERSHARE-NUM-PLAN-1024.md}): {@code SHARE_CLASS_COMMON_OUT}이면 키(공시 × 기간)마다 값을 찍고,
 * {@code SHARE_CLASS_ROWS}(재무 스윕 {@code SecEdgarFactsSweepTest} TSV)를 주면 행마다 붙인 몫을 {@code SHARE_CLASS_ROWS_OUT}에 찍는다.
 */
@EnabledIfEnvironmentVariable(named = "SHARE_CLASS_DIR", matches = ".+")
class SecShareClassSweepTest {

    @Test
    void sweep() throws Exception {
        Path dir = Path.of(System.getenv("SHARE_CLASS_DIR"));
        Path out = Path.of(Optional.ofNullable(System.getenv("SHARE_CLASS_OUT")).orElse("build/share-class-sweep.tsv"));
        double tau = Double.parseDouble(Optional.ofNullable(System.getenv("TAU")).orElse("0.054"));
        double tauc = Double.parseDouble(Optional.ofNullable(System.getenv("TAUC_PT")).orElse("0.052"));
        double proxy = Double.parseDouble(Optional.ofNullable(System.getenv("TAUC")).orElse("0.054"));
        ObjectMapper mapper = new ObjectMapper();

        Map<String, List<String[]>> todo = new TreeMap<>();
        boolean auto = "auto".equals(System.getenv("SHARE_CLASS_TODO"));
        if (auto) {
            try (java.util.stream.Stream<Path> files = Files.list(dir.resolve("tcf"))) {
                files.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(".json")).sorted().forEach(f -> {
                    String t = f.substring(0, f.length() - 5);
                    todo.put(t, new ArrayList<>());
                });
            }
        }
        for (String name : auto ? new String[0] : Optional.ofNullable(System.getenv("SHARE_CLASS_TODO")).orElse("pop_todo.json").split(",")) {
            for (JsonNode x : mapper.readTree(dir.resolve(name).toFile())) {
                // [그룹, 티커, CIK, 접수번호, 제출일]
                todo.computeIfAbsent(x.get(1).asString(), k -> new ArrayList<>())
                        .add(new String[]{x.get(2).asString(), x.get(3).asString()});
            }
        }
        Map<String, List<IssuerTimeline.Split>> splits = new LinkedHashMap<>();
        for (String line : Files.readAllLines(dir.resolve("splits.tsv"))) {
            String[] p = line.split("\t");
            splits.computeIfAbsent(p[0], k -> new ArrayList<>())
                    .add(new IssuerTimeline.Split(LocalDate.parse(p[1]), Double.parseDouble(p[2])));
        }
        IssuerTimeline timeline = new IssuerTimeline(tau, tauc, proxy);
        String frameMode = Optional.ofNullable(System.getenv("SHARE_CLASS_FRAME")).orElse("off");
        boolean frame = !"off".equals(frameMode);
        // 보충 공시 목록(운영 sec_filing_supplement의 정기 공시 중 companyfacts에 없는 것 · 티커 접수번호 폼 제출일) — 판정기는 withFilings로 받는다
        Map<String, Map<String, IssuerFacts.Filing>> supplements = new LinkedHashMap<>();
        String supp = System.getenv("SHARE_CLASS_SUPP");
        if (supp != null && !supp.isBlank()) {
            for (String line : Files.readAllLines(dir.resolve(supp))) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split("\t");
                supplements.computeIfAbsent(p[0], k -> new LinkedHashMap<>()).put(p[1], new IssuerFacts.Filing(p[2], LocalDate.parse(p[3])));
            }
        }
        Path commonOut = Optional.ofNullable(System.getenv("SHARE_CLASS_COMMON_OUT")).map(Path::of).orElse(null);
        // 받기가 더 받을 분자 후보(운영 ShareClassCollector와 같은 함수) — 「CIK:접수번호」 줄.
        Path candidatesOut = Optional.ofNullable(System.getenv("SHARE_CLASS_NUMERATOR_CANDIDATES_OUT")).map(Path::of).orElse(null);
        List<String> candidateLines = new ArrayList<>();
        Map<String, List<CommonIncomeRows.Row>> finRows = rows(System.getenv("SHARE_CLASS_ROWS"));
        Path rowsOut = Optional.ofNullable(System.getenv("SHARE_CLASS_ROWS_OUT")).map(Path::of).orElse(null);
        List<String> commonLines = new ArrayList<>();
        commonLines.add("ticker\taccession\tstart\tend\tfiled\tbranch\tbasis\tcommon");
        List<String> rowLines = new ArrayList<>();
        rowLines.add("ticker\tfy\tfp\trev\tstart\tend\tpub\trc\tnetIncomeControlling\tbranch\tbasis\tcommon");
        int unreadable = 0;
        List<String> lines = new ArrayList<>();
        lines.add("ticker\tfiled\taccession\tkind\tstatus\tbranch\tcategory\tvalue\tdenominator");
        for (Map.Entry<String, List<String[]>> e : todo.entrySet()) {
            String ticker = e.getKey();
            JsonNode raw = mapper.readTree(dir.resolve("tcf").resolve(ticker + ".json").toFile());
            String cik = auto ? String.valueOf(raw.path("cik").asLong()) : e.getValue().get(0)[0];
            Set<String> anchors = new HashSet<>();
            e.getValue().forEach(x -> anchors.add(x[1]));
            anchors.addAll(supplements.getOrDefault(ticker, Map.of()).keySet());
            if (frame) {
                // 운영 입력과 같은 재료 — 관문 켬 받기는 틀(TaxonomyFrame)을 지난 companyfacts를 판정 재료로 저장한다.
                tools.jackson.databind.node.ObjectNode framed = mapper.createObjectNode();
                framed.set("facts", "plain".equals(frameMode)
                        ? com.stockscreener.pipeline.datasource.us.TaxonomyFrame.framedFacts(raw.path("facts"))
                        : com.stockscreener.pipeline.datasource.us.TaxonomyFrame.shareClassFacts(raw.path("facts")));
                raw = framed;
            }
            IssuerFacts cf = IssuerFacts.fromCompanyFacts(raw);
            Map<String, IssuerFacts.Filing> extra = supplements.getOrDefault(ticker, Map.of());
            if (!extra.isEmpty()) {
                cf = cf.withFilings(extra);
            }
            if (auto) {
                anchors.addAll(cf.anchorCandidates());
            }
            int[] skipped = {0};
            IssuerTimeline.Instances instances = accession -> {
                Path xml = dir.resolve("tinst").resolve(cik + "_" + accession + ".xml");
                if (!Files.exists(xml)) {
                    return null;
                }
                try (InputStream in = Files.newInputStream(xml)) {
                    ClassFactReader.Result r = ClassFactReader.read(in, Long.parseLong(cik), ticker + " " + accession);
                    skipped[0] += r.unreadableValues();
                    return r.facts();
                } catch (java.io.IOException ex) {
                    throw new DataSourceException(DataSourceException.Kind.MALFORMED, "sweep", ex.getMessage(), ex);
                }
            };
            List<IssuerTimeline.Verdict> judged = timeline.judge(ticker, cf, anchors, instances,
                    splits.getOrDefault(ticker, List.of()), LocalDate.of(2015, 1, 1), LocalDate.of(2020, 9, 1));
            for (IssuerTimeline.Verdict v : judged) {
                lines.add(String.join("\t", ticker, v.filed().toString(), v.accession(), v.kind(), String.valueOf(v.status()),
                        v.branch(), v.category() == null ? "" : v.category(), fmt(v.value()), fmt(v.denominator())));
            }
            if (candidatesOut != null) {
                for (String a : IssuerTimeline.numeratorCandidates(cf)) {
                    candidateLines.add(cik + ":" + a);
                }
            }
            if (commonOut != null) {
                Map<IssuerFacts.Key, CommonIncome.Value> keys = timeline.commonIncomes(cf, anchors, instances,
                        splits.getOrDefault(ticker, List.of()), judged);
                keys.forEach((k, v) -> commonLines.add(String.join("\t", ticker, k.accession(), k.period().start().toString(),
                        k.period().end().toString(), v.filed().toString(), v.branch(), v.basis().name(), fmt(v.common()))));
                for (CommonIncomeRows.Resolved r : CommonIncomeRows.resolve(finRows.getOrDefault(ticker, List.of()), keys)) {
                    CommonIncomeRows.Row x = r.row();
                    rowLines.add(String.join("\t", ticker, String.valueOf(x.fiscalYear()), x.fiscalPeriod(),
                            String.valueOf(x.revision()), String.valueOf(x.start()), String.valueOf(x.end()), String.valueOf(x.published()),
                            x.reportCode(), x.netIncomeControlling() == null ? "" : x.netIncomeControlling().toPlainString(),
                            r.branch(), r.basis().name(), r.common() == null ? "" : r.common().toPlainString()));
                }
            }
            unreadable += skipped[0];
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, lines);
        if (commonOut != null) {
            Files.write(commonOut, commonLines);
        }
        if (rowsOut != null) {
            Files.write(rowsOut, rowLines);
        }
        if (candidatesOut != null) {
            Files.write(candidatesOut, candidateLines);
        }
        System.out.printf(Locale.ROOT, "share-class sweep: issuers=%d verdicts=%d unreadableValues=%d -> %s%n",
                todo.size(), lines.size() - 1, unreadable, out);
    }

    /** 재무 스윕 TSV → 티커마다 행(판 번호는 같은 기간 안 공시일 순). */
    private static Map<String, List<CommonIncomeRows.Row>> rows(String path) throws java.io.IOException {
        Map<String, List<CommonIncomeRows.Row>> out = new LinkedHashMap<>();
        if (path == null || path.isBlank()) {
            return out;
        }
        List<String> all = Files.readAllLines(Path.of(path));
        List<String> header = List.of(all.get(0).split("\t", -1));
        int t = header.indexOf("ticker");
        int fy = header.indexOf("fy");
        int fp = header.indexOf("fp");
        int st = header.indexOf("start");
        int en = header.indexOf("end");
        int pub = header.indexOf("pub");
        int rc = header.indexOf("rc");
        int nic = header.indexOf("netIncomeControlling");
        Map<String, Integer> revisions = new java.util.HashMap<>();
        List<String[]> parsed = new ArrayList<>();
        for (String line : all.subList(1, all.size())) {
            parsed.add(line.split("\t", -1));
        }
        parsed.sort(java.util.Comparator.comparing((String[] p) -> p[t]).thenComparing(p -> p[fy]).thenComparing(p -> p[fp])
                .thenComparing(p -> p[pub]));
        for (String[] p : parsed) {
            String key = p[t] + "|" + p[fy] + "|" + p[fp];
            int rev = revisions.merge(key, 1, Integer::sum) - 1;
            out.computeIfAbsent(p[t], k -> new ArrayList<>()).add(new CommonIncomeRows.Row(Integer.parseInt(p[fy]), p[fp], rev,
                    p[st].isEmpty() ? null : LocalDate.parse(p[st]), p[en].isEmpty() ? null : LocalDate.parse(p[en]),
                    p[pub].isEmpty() ? null : LocalDate.parse(p[pub]), p[rc],
                    p[nic].isEmpty() ? null : new java.math.BigDecimal(p[nic])));
        }
        return out;
    }

    private static String fmt(Double v) {
        return v == null ? "" : String.format(Locale.ROOT, "%.0f", v);
    }
}
