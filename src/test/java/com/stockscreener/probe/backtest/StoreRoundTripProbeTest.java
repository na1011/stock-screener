package com.stockscreener.probe.backtest;

import com.stockscreener.indicator.IndicatorDataReader.FinancialVisibility;
import com.stockscreener.indicator.IndicatorRunner;
import com.stockscreener.screening.ScreeningResult;
import com.stockscreener.screening.store.ScreenDefinition;
import com.stockscreener.screening.preset.ScreenPreset;
import com.stockscreener.screening.store.ScreenStore;
import com.stockscreener.support.ReadOnlyDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계획의 읽기 경로를 <b>작게 실제로 만들어</b> 결론 일치와 스트리밍 메모리를 함께 잰다.
 *
 * <p>계획이 세운 검증 1-b가 이것이다 — <b>값 일치만으로는 부족하고 결론까지 봐야 한다.</b>
 * 잘못된 코드 집합·잘못된 모집단·잘못된 읽기 경로를 한 단언이 함께 잡는다.
 *
 * <p>앞선 {@code LoadAndEvaluateProbeTest}는 {@code addSectorRelatives}를 안 불러
 * {@code PER_SECTOR_PCTL}이 빠진 채 쟀다 — 그 「판정 1초」는 속도지 정합성이 아니었다.
 * 여기서는 {@link PointInTimeScreener#screenAllFromValues}가 라이브와 <b>같은 코드</b>로
 * 업종 상대값을 재구성하고 판정한다.
 *
 * <p><b>스트리밍</b>: 날짜 하나를 읽고 판정하고 <b>버린다.</b> 전 구간을 한꺼번에 들면
 * 약 3,770만 값 ≈ 5GB이고, 이 세션에서 이미 같은 모양으로 OOM이 났다.
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class StoreRoundTripProbeTest {

    /** 1년에 흩어진 날짜. 가장 이른 것을 반드시 넣는다 — 워밍업·데이터 희소가 거기서 드러난다. */
    private static final List<LocalDate> DATES = List.of(
            LocalDate.of(2025, 9, 2),
            LocalDate.of(2025, 12, 12),
            LocalDate.of(2026, 3, 3),
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 7, 31));

    @Autowired
    private IndicatorRunner runner;

    @Autowired
    private PointInTimeScreener screener;

    @Autowired
    private ScreeningTargets targets;

    @Autowired
    private ScreenStore screenStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private java.util.List<ScreenPreset> presets;

    @Test
    @DisplayName("저장본으로 낸 결론이 직접 계산과 같고, 스트리밍 읽기가 메모리를 안 쌓는다")
    void storeRoundTripMatchesDirectComputation() throws Exception {
        ScreenDefinition v12 = loadByName("v12");
        java.util.List<PointInTimeScreener.Request> requests = new java.util.ArrayList<>();
        requests.add(new PointInTimeScreener.Request("v12", v12.conditions(), v12.mode()));

        // MUST: 유니버스 _PCTL을 쓰는 전략을 함께 넣는다. 저장소는 **통화 단위**인데 그 백분위는
        // **유니버스별**이라, 둘이 만나는 지점은 _PCTL을 쓰는 전략으로만 검증된다.
        // v12는 그 축을 안 써서 이 탐침이 오래 그 자리를 못 봤다.
        for (ScreenPreset p : presets) {
            boolean usesUniversePctl = p.conditions().referencedIndicatorCodes().stream()
                    .anyMatch(c -> c.endsWith("_PCTL") && !c.endsWith("_SECTOR_PCTL"));
            if (usesUniversePctl) {
                requests.add(new PointInTimeScreener.Request(
                        p.code(), p.conditions(), p.mode()));
            }
        }
        assertThat(requests).as("_PCTL을 쓰는 프리셋이 없어 그 축이 검증되지 않는다").hasSizeGreaterThan(1);
        System.out.println("대조 전략: " + requests.stream()
                .map(PointInTimeScreener.Request::label).toList());

        Path dir = Files.createTempDirectory("pit-roundtrip");
        Map<String, Long> loadMs = new LinkedHashMap<>();

        // --- 적재: 통화 단위, 날짜별 파일, 원자적 이동 ---
        for (String ccy : List.of("KRW", "USD")) {
            List<Long> population = screener.populationFor(ccy);
            long t0 = System.nanoTime();
            for (LocalDate asOf : DATES) {
                Map<String, NavigableMap<LocalDate, BigDecimal>> indexes =
                        runner.marketIndexesFor(asOf);
                Map<Long, Map<String, BigDecimal>> values = new LinkedHashMap<>();
                for (Long stockId : population) {
                    values.put(stockId,
                            runner.computeAt(stockId, asOf, indexes, FinancialVisibility.KNOWN_ON));
                }
                writeAtomically(values, dir, ccy, asOf);
            }
            loadMs.put(ccy, (System.nanoTime() - t0) / 1_000_000);
        }

        // --- 읽기: 날짜 하나씩 스트리밍하며 판정 ---
        System.out.printf("%n===== 저장본 왕복 (날짜 %d개) =====%n", DATES.size());
        int comparedCells = 0;
        int setMismatchDates = 0;
        int scoreMismatchCells = 0;
        Set<String> diffs = new TreeSet<>();
        Map<String, Integer> cellsByTarget = new LinkedHashMap<>();
        long readMs = 0;
        long evalMs = 0;
        long peakHeap = 0;

        for (ScreeningTargets.Target target : targets.all()) {
            for (LocalDate asOf : DATES) {
                Path f = dir.resolve(fileName(target.currency(), asOf));
                long t0 = System.nanoTime();
                Map<Long, Map<String, BigDecimal>> stored = readGz(f);
                readMs += (System.nanoTime() - t0) / 1_000_000;

                long t1 = System.nanoTime();
                Map<String, PointInTimeScreener.Outcome> storeAll =
                        screener.screenAllFromValues(asOf, requests, target, stored);
                evalMs += (System.nanoTime() - t1) / 1_000_000;

                for (PointInTimeScreener.Request req : requests) {
                PointInTimeScreener.Outcome fromStore = storeAll.get(req.label());
                PointInTimeScreener.Outcome direct =
                        screener.screen(asOf, req.group(), req.mode(), target);

                Map<Long, BigDecimal> a = ratiosOf(direct);
                Map<Long, BigDecimal> b = ratiosOf(fromStore);
                if (!a.keySet().equals(b.keySet())) {
                    setMismatchDates++;
                    Set<Long> onlyA = new LinkedHashSet<>(a.keySet());
                    onlyA.removeAll(b.keySet());
                    Set<Long> onlyB = new LinkedHashSet<>(b.keySet());
                    onlyB.removeAll(a.keySet());
                    onlyA.forEach(id -> diffs.add(asOf + " " + target.code() + " " + req.label() + " 직접만 " + id));
                    onlyB.forEach(id -> diffs.add(asOf + " " + target.code() + " " + req.label() + " 저장본만 " + id));
                }
                for (Map.Entry<Long, BigDecimal> e : a.entrySet()) {
                    comparedCells++;
                    cellsByTarget.merge(target.code(), 1, Integer::sum);
                    BigDecimal other = b.get(e.getKey());
                    if (other == null || e.getValue().compareTo(other) != 0) {
                        scoreMismatchCells++;
                        diffs.add(asOf + " " + target.code() + " " + req.label() + " 점수 " + e.getKey()
                                + " 직접=" + e.getValue() + " 저장본=" + other);
                    }
                }
                }
                // 스트리밍이므로 여기서 stored는 참조가 끊긴다
                peakHeap = Math.max(peakHeap, usedHeap());
            }
        }

        System.out.printf("적재 KRW %,dms · USD %,dms (날짜 %d개)%n",
                loadMs.get("KRW"), loadMs.get("USD"), DATES.size());
        System.out.printf("읽기 %,dms · 판정 %,dms (시장 3 × 날짜 %d)%n",
                readMs, evalMs, DATES.size());
        System.out.printf("스트리밍 중 최대 사용 힙 %,d MB (한계 %,d MB)%n",
                peakHeap / 1_048_576, Runtime.getRuntime().maxMemory() / 1_048_576);
        System.out.printf("통과집합 다른 날짜 %d · 달성률 다른 칸 %d / %d%n",
                setMismatchDates, scoreMismatchCells, comparedCells);
        if (diffs.isEmpty()) {
            System.out.println("차이 없음 — 저장본 결론이 직접 계산과 같다");
        } else {
            diffs.stream().limit(15).forEach(d -> System.out.println("   " + d));
        }

        System.out.println("-- 대상별 대조 칸 --");
        for (ScreeningTargets.Target t : targets.all()) {
            System.out.printf("   %-16s %d%n", t.code(), cellsByTarget.getOrDefault(t.code(), 0));
        }

        // MUST: 불일치를 단언한다. 세지 않고 출력만 하면 **어긋나도 초록**이고,
        // 이 테스트를 회귀로 쓰는 순간 그 초록이 「저장본이 맞다」로 읽힌다.
        assertThat(comparedCells).as("대조가 0이면 잰 것이 없다").isPositive();
        assertThat(diffs).as("저장본 결론이 직접 계산과 다르다").isEmpty();
        assertThat(setMismatchDates).as("통과집합이 다른 날짜가 있다").isZero();
        assertThat(scoreMismatchCells).as("달성률이 다른 칸이 있다").isZero();
        // MUST: 대상마다 실제로 뭔가를 대조했는지 본다. 한 시장이 통과 0이면
        // 그 시장은 **빈 집합 == 빈 집합**으로 조용히 통과하고, 커버리지가 없는데 초록이 된다.
        for (ScreeningTargets.Target t : targets.all()) {
            assertThat(cellsByTarget.getOrDefault(t.code(), 0))
                    .as("%s에서 대조한 칸이 0이다 — 이 시장은 검증되지 않았다", t.code())
                    .isPositive();
        }
        for (Path f : Files.list(dir).toList()) {
            Files.deleteIfExists(f);
        }
        Files.deleteIfExists(dir);
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static String fileName(String ccy, LocalDate asOf) {
        return ccy + "-" + asOf + ".csv.gz";
    }

    /** `.tmp`에 쓰고 원자적으로 옮긴다 — 중단이 반쪽 파일을 남기지 않게. */
    private static void writeAtomically(Map<Long, Map<String, BigDecimal>> values,
                                        Path dir, String ccy, LocalDate asOf) throws Exception {
        Path tmp = dir.resolve(fileName(ccy, asOf) + ".tmp");
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                new java.util.zip.GZIPOutputStream(Files.newOutputStream(tmp)),
                StandardCharsets.UTF_8))) {
            for (Map.Entry<Long, Map<String, BigDecimal>> e : values.entrySet()) {
                if (e.getValue().isEmpty()) {
                    // 「계산했으나 값이 없음」 표지. 없으면 적재 실패와 구분되지 않는다.
                    w.write(e.getKey() + ",,\n");
                    continue;
                }
                for (Map.Entry<String, BigDecimal> v : e.getValue().entrySet()) {
                    w.write(e.getKey() + "," + v.getKey() + "," + v.getValue().toPlainString());
                    w.write('\n');
                }
            }
        }
        Files.move(tmp, dir.resolve(fileName(ccy, asOf)),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 지표 코드는 종류가 적어 인터닝한다 — 행마다 새 String을 만들면 그것이 메모리를 지배한다. */
    private static Map<Long, Map<String, BigDecimal>> readGz(Path path) throws Exception {
        Map<String, String> codePool = new HashMap<>();
        Map<Long, Map<String, BigDecimal>> back = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.util.zip.GZIPInputStream(Files.newInputStream(path)),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int c1 = line.indexOf(',');
                int c2 = line.indexOf(',', c1 + 1);
                long stockId = Long.parseLong(line.substring(0, c1));
                Map<String, BigDecimal> m =
                        back.computeIfAbsent(stockId, k -> new LinkedHashMap<>());
                if (c2 == c1 + 1) {
                    continue; // 표지 행: 계산했으나 값이 없다
                }
                String raw = line.substring(c1 + 1, c2);
                String code = codePool.computeIfAbsent(raw, x -> x);
                m.put(code, new BigDecimal(line.substring(c2 + 1)));
            }
        }
        return back;
    }

    private static Map<Long, BigDecimal> ratiosOf(PointInTimeScreener.Outcome outcome) {
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        for (ScreeningResult r : outcome.included()) {
            out.put(r.stockId(), r.scoreRatio());
        }
        return out;
    }

    private ScreenDefinition loadByName(String namePart) {
        List<Long> ids = new ArrayList<>(jdbcTemplate.queryForList(
                "SELECT id FROM screen WHERE screen_type = 'CUSTOM' AND is_active"
                        + " AND lower(name) LIKE lower(?) ORDER BY id",
                Long.class, "%" + namePart + "%"));
        if (ids.size() != 1) {
            throw new IllegalStateException("「" + namePart + "」가 " + ids.size() + "개 걸린다");
        }
        return screenStore.findById(ids.get(0)).orElseThrow();
    }
}
