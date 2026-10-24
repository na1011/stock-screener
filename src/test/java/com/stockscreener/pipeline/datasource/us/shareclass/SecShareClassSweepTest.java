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
        for (String name : Optional.ofNullable(System.getenv("SHARE_CLASS_TODO")).orElse("pop_todo.json").split(",")) {
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
        int unreadable = 0;
        List<String> lines = new ArrayList<>();
        lines.add("ticker\tfiled\taccession\tkind\tstatus\tbranch\tcategory\tvalue\tdenominator");
        for (Map.Entry<String, List<String[]>> e : todo.entrySet()) {
            String ticker = e.getKey();
            String cik = e.getValue().get(0)[0];
            Set<String> anchors = new HashSet<>();
            e.getValue().forEach(x -> anchors.add(x[1]));
            anchors.addAll(supplements.getOrDefault(ticker, Map.of()).keySet());
            JsonNode raw = mapper.readTree(dir.resolve("tcf").resolve(ticker + ".json").toFile());
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
            for (IssuerTimeline.Verdict v : timeline.judge(ticker, cf, anchors, instances,
                    splits.getOrDefault(ticker, List.of()), LocalDate.of(2015, 1, 1), LocalDate.of(2020, 9, 1))) {
                lines.add(String.join("\t", ticker, v.filed().toString(), v.accession(), v.kind(), String.valueOf(v.status()),
                        v.branch(), v.category() == null ? "" : v.category(), fmt(v.value()), fmt(v.denominator())));
            }
            unreadable += skipped[0];
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, lines);
        System.out.printf(Locale.ROOT, "share-class sweep: issuers=%d verdicts=%d unreadableValues=%d -> %s%n",
                todo.size(), lines.size() - 1, unreadable, out);
    }

    private static String fmt(Double v) {
        return v == null ? "" : String.format(Locale.ROOT, "%.0f", v);
    }
}
