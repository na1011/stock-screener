package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.kr.DartCorpCodeCache;
import com.stockscreener.pipeline.datasource.kr.DartReportKey;
import com.stockscreener.pipeline.datasource.kr.DartXbrlClient;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrXbrlStore.Filing;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrXbrlStore.RowVintage;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrXbrlStore.Target;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 국내 보통주 몫 재료 받기(ADR-0192) — 종목 하나의 재무 행 판마다 그 판의 XBRL 재료와, 행이 있는 사업연도 · 앞 해의 주식총수 명부를 채운다.
 * 값을 바꾸는 표를 안 쓴다(재료 표 둘과 디스크 캐시뿐).
 *
 * <ol>
 *   <li>판 짝: 재무 행(Q4 빼고)마다 같은 (사업연도, 보고서 코드)에 {@code published_at}이 접수번호 앞 8자리 또는 접수일인 판. 짝 없는 행이
 *       있으면 그 종목의 정기 공시 목록을 다시 받는다(종목당 3콜).</li>
 *   <li>재료: 짝지은 판 중 재료가 없거나(받기 실패 포함) 판독기 판이 다른 것 — 디스크 캐시에 원본이 있으면 0콜로 다시 판독한다.</li>
 *   <li>명부: 행이 있는 사업연도와 그 앞 해 중 아직 안 받은 해(해마다 1콜).</li>
 * </ol>
 *
 * <p>판 하나의 받기 실패는 그 판 행에 FAILED로 남기고 다음 판으로 간다 — 그래도 종목은 끝에서 실패로 올린다(조용한 부분 성공을 안 만든다).
 * 한도 소진({@code RATE_LIMITED})과 인증 실패는 그 자리에서 던진다.
 */
@Component
public class KrXbrlCollector {

    /** 국내 재무 행의 첫 사업연도(재무 적재 창 — 2015~). 목록은 그 해 1월 1일부터 묻는다. */
    static final LocalDate LIST_FROM = LocalDate.of(2015, 1, 1);

    private final ObjectProvider<DartXbrlClient> client;
    private final ObjectProvider<DartCorpCodeCache> corpCodes;
    private final KrXbrlStore store;
    private final Path cacheDir;

    public KrXbrlCollector(ObjectProvider<DartXbrlClient> client, ObjectProvider<DartCorpCodeCache> corpCodes, KrXbrlStore store,
                           @Value("${stockscreener.kr-xbrl.cache-dir}") String cacheDir) {
        this.client = client;
        this.corpCodes = corpCodes;
        this.store = store;
        this.cacheDir = Path.of(cacheDir);
    }

    /** 키가 없는 환경(시험 · 키 없는 사본)에서는 소스가 없다 — 잡이 시장 미완료로 센다. */
    public boolean hasSource() {
        return client.getIfAvailable() != null && corpCodes.getIfAvailable() != null;
    }

    /** 한 종목의 결과 — 받은 판 · 캐시에서 다시 읽은 판 · 원본 없음 · 받은 명부 · 이번에 실패한 판. */
    public record Collected(int fetched, int reread, int noFile, int registers, int failed, int unbound) {
    }

    /**
     * @param fetchBudget 이번 실행에 남은 「새로 받기」 수(캐시 판독은 안 센다) — 0이 되면 남은 판은 다음 실행이 잇는다.
     */
    public Collected collect(Target target, LocalDate today, AtomicInteger fetchBudget) throws DataSourceException {
        DartXbrlClient dart = client.getObject();
        String corpCode = corpCodes.getObject().findCorpCode(target.ticker()).orElseThrow(() -> new DataSourceException(
                DataSourceException.Kind.NOT_FOUND, "dart", "corp_code를 모른다(corpCode.xml에 없음): " + target.ticker()));
        List<RowVintage> rows = store.rowVintages(target.stockId());
        List<Filing> filings = store.filings(target.stockId());
        if (rows.stream().anyMatch(r -> bound(r, filings) == null)) {
            for (DartXbrlClient.Filing f : dart.periodicFilings(corpCode, LIST_FROM, today)) {
                DartReportKey key = DartReportKey.of(f.detailType(), f.reportName(), target.accountingMonth());
                store.upsertListed(target.stockId(), corpCode, f.detailType(), f.reportName(), f.rceptNo(), f.rceptDate(),
                        key.bsnsYear(), key.reprtCode(), key.reason());
            }
            filings.clear();
            filings.addAll(store.filings(target.stockId()));
        }
        int fetched = 0;
        int reread = 0;
        int noFile = 0;
        int failed = 0;
        int unbound = 0;
        Set<String> seen = new java.util.HashSet<>();
        String firstFailure = null;
        for (RowVintage row : rows) {
            Filing filing = bound(row, filings);
            if (filing == null) {
                unbound++;
                continue;
            }
            if (!seen.add(filing.rceptNo()) || !needs(filing)) {
                continue;
            }
            Path cached = cachePath(corpCode, filing.rceptNo());
            byte[] instance;
            try {
                if (Files.exists(cached)) {
                    instance = readCache(cached);
                    reread++;
                } else {
                    if (fetchBudget.get() <= 0) {
                        continue;
                    }
                    fetchBudget.decrementAndGet();
                    Optional<byte[]> got = dart.instance(filing.rceptNo());
                    if (got.isEmpty()) {
                        store.recordNoFile(target.stockId(), filing.rceptNo());
                        noFile++;
                        continue;
                    }
                    instance = got.get();
                    writeCache(cached, instance);
                    fetched++;
                }
            } catch (DataSourceException e) {
                if (e.kind() == DataSourceException.Kind.RATE_LIMITED || e.fatal()) {
                    throw e;
                }
                store.recordFailure(target.stockId(), filing.rceptNo(), "FAILED", e.kind().name(), e.getMessage());
                failed++;
                firstFailure = firstFailure == null ? filing.rceptNo() + " " + e.getMessage() : firstFailure;
                continue;
            } catch (IOException e) {
                store.recordFailure(target.stockId(), filing.rceptNo(), "FAILED", "CACHE_IO", e.getMessage());
                failed++;
                firstFailure = firstFailure == null ? filing.rceptNo() + " 캐시 " + e.getMessage() : firstFailure;
                continue;
            }
            try (InputStream in = new ByteArrayInputStream(instance)) {
                DartXbrlFactReader.Result read = DartXbrlFactReader.read(in, target.ticker() + " " + filing.rceptNo());
                store.recordOk(target.stockId(), filing.rceptNo(), read.facts(), read.unreadableValues());
            } catch (DataSourceException e) {
                // 판독 실패는 평생이다(판독기 판이 바뀌면 다시) — 받기 실패와 갈라 센다.
                store.recordFailure(target.stockId(), filing.rceptNo(), "UNREADABLE", e.kind().name(), e.getMessage());
                failed++;
                firstFailure = firstFailure == null ? filing.rceptNo() + " " + e.getMessage() : firstFailure;
            } catch (IOException e) {
                throw new IllegalStateException("바이트 배열 스트림이 닫히다 실패했다", e);
            }
        }
        int registers = 0;
        Set<Integer> wanted = new TreeSet<>();
        for (RowVintage row : rows) {
            wanted.add(row.fiscalYear());
            wanted.add(row.fiscalYear() - 1);
        }
        wanted.removeAll(store.registerYears(target.stockId()));
        for (int year : wanted) {
            if (year < LIST_FROM.getYear() - 1 || year > today.getYear() - 1) {
                continue;   // 그 해 사업보고서가 아직 없다(올해) · 재무 창 밖
            }
            store.recordRegister(target.stockId(), year, dart.shareTotals(corpCode, year));
            registers++;
        }
        if (failed > 0) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, "dart",
                    "판 %d개의 재료를 못 받았다(첫 실패: %s) — 표에 남겼고 다음 실행이 다시 받는다".formatted(failed, firstFailure));
        }
        return new Collected(fetched, reread, noFile, registers, failed, unbound);
    }

    /** 재무 행 판 하나의 짝 — 같은 (사업연도, 보고서 코드) · 같은 날 판 중 가장 늦은 접수번호. 없으면 {@code null}. */
    static Filing bound(RowVintage row, List<Filing> filings) {
        String code = switch (row.fiscalPeriod()) {
            case "Q1" -> "11013";
            case "Q2" -> "11012";
            case "Q3" -> "11014";
            case "FY" -> "11011";
            default -> null;
        };
        Filing found = null;
        for (Filing f : filings) {
            if (code != null && code.equals(f.reprtCode()) && f.bsnsYear() != null && f.bsnsYear() == row.fiscalYear()
                    && f.publishedOn(row.published()) && (found == null || f.rceptNo().compareTo(found.rceptNo()) > 0)) {
                found = f;
            }
        }
        return found;
    }

    private static boolean needs(Filing f) {
        if (f.status() == null || "FAILED".equals(f.status())) {
            return true;
        }
        return !"NO_FILE".equals(f.status()) && (f.readerVersion() == null || f.readerVersion() != DartXbrlFactReader.READER_VERSION);
    }

    Path cachePath(String corpCode, String rceptNo) {
        return cacheDir.resolve(corpCode).resolve(rceptNo + ".xbrl.gz");
    }

    private static byte[] readCache(Path path) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            return in.readAllBytes();
        }
    }

    /** 원자적으로 쓴다 — 반쯤 쓴 캐시가 다음 판독에서 「판독 실패」로 평생 남지 않게. */
    private static void writeCache(Path path, byte[] instance) throws IOException {
        Files.createDirectories(path.getParent());
        Path part = path.resolveSibling(path.getFileName() + ".part");
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(part))) {
            out.write(instance);
        }
        Files.move(part, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 받기 대상. */
    public List<Target> targets(List<String> tickers) {
        return new ArrayList<>(store.targets(tickers));
    }
}
