package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.Register;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.ShareRow;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncomeRows.Resolved;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncomeRows.Row;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 국내 보통주 몫 판정(ADR-0192)의 0콜 스윕 — 저장한 XBRL 인스턴스 · 재무 행 · 명부 · 기업행위를 판독기와 판정에 먹여 행마다 TSV로 찍는다.
 * DB · 외부 호출 없음. 같은 재료를 먹인 탐색의 파이썬 기준 구현({@code kr5/plan9h.py})과 갈래를 맞대 어긋남을 어느 쪽 결함인지 가른다.
 *
 * <p>스위치(전부 필수 · {@code KR_OUT}만 기본 {@code build/kr-common-income-sweep.tsv}):
 * <ul>
 *   <li>{@code KR_XBRL_DIR} — {@code <열쇠>.xbrl} 또는 {@code <열쇠>.xbrl.gz}(운영 캐시 꼴 — 열쇠가 접수번호)</li>
 *   <li>{@code KR_ROWS} — TSV {@code key ticker id fy fp start end pub nic ni}(빈 칸은 NULL · {@code key}가 비면 그 판 재료 없음)</li>
 *   <li>{@code KR_REGISTER} — TSV {@code ticker year asof label issued treasury}(명부 11011의 줄)</li>
 *   <li>{@code KR_CA} — TSV {@code ticker effective ratio}(APPLIED 기업행위)</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "KR_XBRL_DIR", matches = ".+")
class DartCommonIncomeSweepTest {

    @Test
    void sweep() throws Exception {
        Path dir = Path.of(System.getenv("KR_XBRL_DIR"));
        Path out = Path.of(Optional.ofNullable(System.getenv("KR_OUT")).orElse("build/kr-common-income-sweep.tsv"));
        Map<String, List<String[]>> rowsByTicker = new TreeMap<>();
        for (String[] p : tsv(System.getenv("KR_ROWS"))) {
            rowsByTicker.computeIfAbsent(p[1], k -> new ArrayList<>()).add(p);
        }
        Map<String, Map<Integer, List<ShareRow>>> registerRows = new LinkedHashMap<>();
        Map<String, Map<Integer, LocalDate>> registerDays = new LinkedHashMap<>();
        for (String[] p : tsv(System.getenv("KR_REGISTER"))) {
            if ("비고".equals(p[3].strip())) {
                continue;   // 비고 줄은 주석 문구다 — 수 칸이 아니다(종류 · 합계 줄은 운영 2025 명부에서 전부 수)
            }
            int year = Integer.parseInt(p[1]);
            registerRows.computeIfAbsent(p[0], k -> new TreeMap<>()).computeIfAbsent(year, k -> new ArrayList<>())
                    .add(new ShareRow(p[3], number(p[4]), number(p[5])));
            registerDays.computeIfAbsent(p[0], k -> new TreeMap<>()).put(year, p[2].isBlank() ? null : LocalDate.parse(p[2]));
        }
        Map<String, List<String[]>> actions = new LinkedHashMap<>();
        for (String[] p : tsv(System.getenv("KR_CA"))) {
            actions.computeIfAbsent(p[0], k -> new ArrayList<>()).add(p);
        }

        List<String> lines = new ArrayList<>();
        lines.add("key\tticker\tfy\tfp\tpub\tbranch\tcommon\tbasis\tstatement\tpayout\tunreadable");
        int unreadableTotal = 0;
        for (Map.Entry<String, List<String[]>> e : rowsByTicker.entrySet()) {
            String ticker = e.getKey();
            Map<String, List<DartXbrlFactReader.Fact>> facts = new LinkedHashMap<>();
            Map<String, Integer> unreadable = new LinkedHashMap<>();
            List<Row> rows = new ArrayList<>();
            Map<Long, String> keyOf = new LinkedHashMap<>();
            for (String[] p : e.getValue()) {
                String key = p[0];
                List<DartXbrlFactReader.Fact> f = null;
                if (!key.isBlank()) {
                    if (!facts.containsKey(key)) {
                        Path plain = dir.resolve(key + ".xbrl");
                        Path gz = dir.resolve(key + ".xbrl.gz");
                        if (Files.exists(plain) || Files.exists(gz)) {
                            try (InputStream in = Files.exists(plain) ? Files.newInputStream(plain)
                                    : new GZIPInputStream(Files.newInputStream(gz))) {
                                DartXbrlFactReader.Result r = DartXbrlFactReader.read(in, ticker + " " + key);
                                facts.put(key, r.facts());
                                unreadable.put(key, r.unreadableValues());
                            }
                        } else {
                            facts.put(key, null);
                        }
                    }
                    f = facts.get(key);
                }
                long id = Long.parseLong(p[2]);
                keyOf.put(id, key);
                rows.add(new Row(id, Integer.parseInt(p[3]), p[4], LocalDate.parse(p[5]), LocalDate.parse(p[6]),
                        LocalDate.parse(p[7]), number(p[8]), number(p[9]), f));
            }
            Map<Integer, Register> registers = new LinkedHashMap<>();
            registerRows.getOrDefault(ticker, Map.of()).forEach((year, rs) -> {
                Register r = Register.of(registerDays.get(ticker).get(year), rs);
                if (r != null) {
                    registers.put(year, r);
                }
            });
            List<String[]> ca = actions.getOrDefault(ticker, List.of());
            KrCommonIncome.Multiple multiple = (from, to) -> {
                double m = 1.0;
                for (String[] a : ca) {
                    LocalDate day = LocalDate.parse(a[1]);
                    if (day.isAfter(from) && !day.isAfter(to)) {
                        m *= Double.parseDouble(a[2]);
                    }
                }
                return m;
            };
            for (Resolved r : KrCommonIncomeRows.resolve(rows, registers, multiple)) {
                String key = keyOf.get(r.row().id());
                int bad = unreadable.getOrDefault(key, 0);
                unreadableTotal += bad;
                lines.add(String.join("\t", key, ticker, String.valueOf(r.row().fiscalYear()), r.row().fiscalPeriod(),
                        r.row().published().toString(), r.branch(), plain(r.common()), String.valueOf(r.basis()),
                        String.valueOf(r.statement()), plain(r.payoutBase()), String.valueOf(bad)));
            }
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, lines);
        System.out.println("rows=" + (lines.size() - 1) + " unreadable=" + unreadableTotal + " out=" + out);
        assertThat(lines).hasSizeGreaterThan(1);
    }

    private static List<String[]> tsv(String path) throws java.io.IOException {
        List<String> all = Files.readAllLines(Path.of(path));
        List<String[]> out = new ArrayList<>();
        for (String line : all.subList(1, all.size())) {
            if (!line.isBlank()) {
                out.add(line.split("\t", -1));
            }
        }
        return out;
    }

    private static BigDecimal number(String s) {
        if (s == null || s.isBlank() || "-".equals(s.strip())) {
            return null;
        }
        return new BigDecimal(s.strip().replace(",", ""));
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }
}
