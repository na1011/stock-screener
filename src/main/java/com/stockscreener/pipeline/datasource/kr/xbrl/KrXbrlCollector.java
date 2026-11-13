package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.kr.DartCorpCodeCache;
import com.stockscreener.pipeline.datasource.kr.DartEarlierInstances;
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
import java.util.Map;
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
public class KrXbrlCollector implements DartEarlierInstances {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KrXbrlCollector.class);

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

    /** 국내 관문({@code stockscreener.gate.kr-provisional}) — 켜면 짝 고르기가 XBRL 없는 첨부정정 판을 건너뛴다(K5). 시험은 생성자만 부른다. */
    private boolean krGate;

    @org.springframework.beans.factory.annotation.Autowired
    void krGate(@Value("${stockscreener.gate.kr-provisional}") boolean on) {
        this.krGate = on;
    }

    /** 키가 없는 환경(시험 · 키 없는 사본)에서는 소스가 없다 — 잡이 시장 미완료로 센다. */
    public boolean hasSource() {
        return client.getIfAvailable() != null && corpCodes.getIfAvailable() != null;
    }

    /** 한 종목의 결과 — 받은 판 · 캐시에서 다시 읽은 판 · 원본 없음 · 받은 명부 · 이번에 실패한 판 · 이름표만 다시 받은 판 · 받은 주식수 표. */
    public record Collected(int fetched, int reread, int noFile, int registers, int failed, int unbound, int labels, int materials) {

        public Collected(int fetched, int reread, int noFile, int registers, int failed, int unbound) {
            this(fetched, reread, noFile, registers, failed, unbound, 0, 0);
        }

        public Collected(int fetched, int reread, int noFile, int registers, int failed, int unbound, int labels) {
            this(fetched, reread, noFile, registers, failed, unbound, labels, 0);
        }
    }

    /** 정기보고서마다 받는 주식수 표(계획 K3 · 국내 관문 켬) — 사업보고서 명부는 {@code kr_share_register}가 든다. */
    static final List<String> MATERIAL_ENDPOINTS = List.of("stockTotqySttus", "irdsSttus", "tesstkAcqsDspsSttus");

    /**
     * @param fetchBudget 이번 실행에 남은 「새로 받기」 수(캐시 판독은 안 센다) — 0이 되면 남은 판은 다음 실행이 잇는다.
     */
    public Collected collect(Target target, LocalDate today, AtomicInteger fetchBudget) throws DataSourceException {
        DartXbrlClient dart = client.getObject();
        String corpCode = corpCodes.getObject().findCorpCode(target.ticker()).orElseThrow(() -> new DataSourceException(
                DataSourceException.Kind.NOT_FOUND, "dart", "corp_code를 모른다(corpCode.xml에 없음): " + target.ticker()));
        List<RowVintage> rows = store.rowVintages(target.stockId());
        List<Filing> filings = store.filings(target.stockId());
        if (rows.stream().anyMatch(r -> bound(r, filings, krGate) == null)) {
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
        List<Filing> queue = new java.util.ArrayList<>();
        for (RowVintage row : rows) {
            Filing filing = bound(row, filings, krGate);
            if (filing == null) {
                unbound++;
                continue;
            }
            queue.add(filing);
            // 그 보고서의 더 이른 판(원본 · 앞선 정정)도 받는다 — 같은 숫자를 처음 공개한 판의 접수일을 가리는 재료다(ADR-0195 ·
            // KrEarlierVintages). DART 재무 API는 보고서마다 가장 늦은 판만 주어 저장 공시일이 정정일로 밀린다.
            queue.addAll(earlier(filing, filings));
        }
        for (Filing filing : queue) {
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
                    Optional<DartXbrlClient.Original> got = dart.original(filing.rceptNo());
                    if (got.isEmpty()) {
                        store.recordNoFile(target.stockId(), filing.rceptNo());
                        noFile++;
                        continue;
                    }
                    instance = got.get().instance();
                    writeCache(cached, instance);
                    if (got.get().labelsKo() != null) {
                        writeCache(labelPath(corpCode, filing.rceptNo()), got.get().labelsKo());
                    }
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
            Map<String, String> labels;
            try {
                labels = labelsFor(dart, corpCode, filing.rceptNo(), instance, fetchBudget);
            } catch (DataSourceException e) {
                if (e.kind() == DataSourceException.Kind.RATE_LIMITED || e.fatal()) {
                    throw e;
                }
                // 재료 표에 FAILED로 안 남긴다 — 그 판의 지금 재료(옛 판독기 판)를 지우지 않고 다음 실행이 다시 본다(판독기 판이 그대로라 needs).
                failed++;
                firstFailure = firstFailure == null ? filing.rceptNo() + " 이름표 " + e.getMessage() : firstFailure;
                continue;
            }
            if (labels == null) {
                continue;   // 이름표가 필요한데 새로 받기 상한이 다했다 — 판 3으로 안 읽고 다음 실행이 다시 본다
            }
            String unreadable = readAndRecord(target.stockId(), target.ticker(), filing.rceptNo(), instance, labels);
            if (unreadable != null) {
                failed++;
                firstFailure = firstFailure == null ? filing.rceptNo() + " " + unreadable : firstFailure;
            }
        }
        // 비최신 판의 한국어 이름표 — 이른 판을 그 판의 인스턴스로 지을 때 표준계정코드 없는 줄의 계정명이다(ADR-0201). 인스턴스만 캐시하던 때
        // 받은 판은 이름표가 없어 한 번 더 받는다(같은 1콜 · 새로 받기 상한 안). 값을 안 바꾸는 재료다.
        int labels = 0;
        for (RowVintage row : rows) {
            Filing filing = row.latest() ? null : bound(row, filings, krGate);
            if (filing == null || !"OK".equals(filing.status()) || Files.exists(labelPath(corpCode, filing.rceptNo()))
                    || !Files.exists(cachePath(corpCode, filing.rceptNo())) || fetchBudget.get() <= 0) {
                continue;
            }
            fetchBudget.decrementAndGet();
            Optional<DartXbrlClient.Original> got = dart.original(filing.rceptNo());
            if (got.isPresent() && got.get().labelsKo() != null) {
                try {
                    writeCache(labelPath(corpCode, filing.rceptNo()), got.get().labelsKo());
                    labels++;
                } catch (IOException e) {
                    throw new DataSourceException(DataSourceException.Kind.TRANSIENT, "dart", "이름표 캐시를 못 썼다: " + filing.rceptNo(), e);
                }
            } else {
                log.warn("원본 ZIP에 한국어 이름표가 없다. stockId={} 접수번호={}", target.stockId(), filing.rceptNo());
            }
        }
        // 회사 확장 수 사실이 있는 판의 이름표(ADR-0210) — 국내 관문 켬의 판독기 판 3이 회사 확장 주당이익의 뜻을 읽는 재료다. 값을 안 바꾸므로
        // 꺼짐에서도 미리 받는다(관문 날 재판독을 0콜로 둔다 · 새로 받기 상한 안 · 받은 판은 다시 안 본다).
        for (Filing filing : new java.util.LinkedHashSet<>(queue)) {
            Path cached = cachePath(corpCode, filing.rceptNo());
            if (Files.exists(labelPath(corpCode, filing.rceptNo())) || !Files.exists(cached) || fetchBudget.get() <= 0) {
                continue;
            }
            try {
                if (!DartXbrlFactReader.hasEntityFacts(new ByteArrayInputStream(readCache(cached)), filing.rceptNo())) {
                    continue;
                }
            } catch (IOException e) {
                throw new DataSourceException(DataSourceException.Kind.TRANSIENT, "dart", "인스턴스 캐시를 못 읽었다: " + cached, e);
            }
            fetchBudget.decrementAndGet();
            Optional<DartXbrlClient.Original> got = dart.original(filing.rceptNo());
            if (got.isPresent() && got.get().labelsKo() != null) {
                try {
                    writeCache(labelPath(corpCode, filing.rceptNo()), got.get().labelsKo());
                    labels++;
                } catch (IOException e) {
                    throw new DataSourceException(DataSourceException.Kind.TRANSIENT, "dart", "이름표 캐시를 못 썼다: " + filing.rceptNo(), e);
                }
            } else {
                log.warn("원본 ZIP에 한국어 이름표가 없다 — 회사 확장 주당이익을 못 읽는다. stockId={} 접수번호={}", target.stockId(), filing.rceptNo());
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
        int materials = krGate ? collectMaterial(dart, corpCode, target.stockId(), rows, fetchBudget) : 0;
        if (failed > 0) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, "dart",
                    "판 %d개의 재료를 못 받았다(첫 실패: %s) — 표에 남겼고 다음 실행이 다시 받는다".formatted(failed, firstFailure));
        }
        return new Collected(fetched, reread, noFile, registers, failed, unbound, labels, materials);
    }

    /**
     * 국내 관문 켬(계획 K3-b · K3-d) — 행이 있는 (사업연도, 보고서) 중 아직 안 받은 주식수 표를 받는다(표마다 1콜 · 새로 받기 상한 안). 재무 창
     * (2015~) 밖과 사업보고서 주식총수 표(명부가 든다)는 안 받는다.
     */
    private int collectMaterial(DartXbrlClient dart, String corpCode, long stockId, List<RowVintage> rows, AtomicInteger fetchBudget)
            throws DataSourceException {
        Set<String> have = store.materialKeys(stockId);
        Set<String> wanted = new java.util.LinkedHashSet<>();
        for (RowVintage row : rows) {
            String code = reportCode(row.fiscalPeriod());
            if (code == null || row.fiscalYear() < LIST_FROM.getYear()) {
                continue;
            }
            for (String endpoint : MATERIAL_ENDPOINTS) {
                if (!("stockTotqySttus".equals(endpoint) && "11011".equals(code))) {
                    wanted.add(row.fiscalYear() + "/" + code + "/" + endpoint);
                }
            }
        }
        wanted.removeAll(have);
        int got = 0;
        for (String key : wanted) {
            if (fetchBudget.get() <= 0) {
                break;
            }
            String[] k = key.split("/");
            fetchBudget.decrementAndGet();
            store.recordMaterial(stockId, Integer.parseInt(k[0]), k[1], k[2], dart.reportTable(k[2], corpCode, Integer.parseInt(k[0]), k[1]));
            got++;
        }
        return got;
    }

    private static String reportCode(String fiscalPeriod) {
        return switch (fiscalPeriod) {
            case "Q1" -> "11013";
            case "Q2" -> "11012";
            case "Q3" -> "11014";
            case "FY" -> "11011";
            default -> null;
        };
    }

    /** 재무 행 판 하나의 짝 — 같은 (사업연도, 보고서 코드) · 같은 날 판 중 가장 늦은 접수번호. 없으면 {@code null}. */
    static Filing bound(RowVintage row, List<Filing> filings) {
        return bound(row, filings, false);
    }

    /**
     * @param skipNoFile 국내 관문 켬(계획 K5) — 같은 날 판 중 XBRL이 없다고 확인된 판({@code NO_FILE} · 주로 {@code [첨부정정]})은 같은 날 다른 판이 있으면
     *                   건너뛴다. 가장 늦은 접수번호만 고르면 첨부정정 판이 짝이 되어 원 판은 받지도 않고 그 판의 보통주 몫이 영영 「키 없음」이다
     *                   (2026-11-10 운영: 36판/33종목 · 배당성향 분모 포함)
     */
    static Filing bound(RowVintage row, List<Filing> filings, boolean skipNoFile) {
        Filing found = latestSameDay(row, filings, skipNoFile);
        return found != null || !skipNoFile ? found : latestSameDay(row, filings, false);
    }

    private static Filing latestSameDay(RowVintage row, List<Filing> filings, boolean skipNoFile) {
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
                    && f.publishedOn(row.published()) && !(skipNoFile && "NO_FILE".equals(f.status()))
                    && (found == null || f.rceptNo().compareTo(found.rceptNo()) > 0)) {
                found = f;
            }
        }
        return found;
    }

    /**
     * 국내 관문 켬 — 그 판의 회사 확장 개념 이름표(판독기 판 3이 회사 확장 주당이익 사실의 뜻을 읽는 재료). 인스턴스에 회사 확장 주당 수 사실이
     * 없으면 빈 맵 · 캐시에 있으면 그것 · 없으면 원본 ZIP을 한 번 더 받아 캐시한다(새로 받기 상한 안 · 다했으면 {@code null} — 그 판을 이번에 안
     * 읽는다). ZIP에 이름표가 없으면 경고하고 빈 맵이다. 꺼짐은 언제나 빈 맵이다(외부 0콜).
     */
    private Map<String, String> labelsFor(DartXbrlClient dart, String corpCode, String rceptNo, byte[] instance,
                                          AtomicInteger fetchBudget) throws DataSourceException {
        if (!store.krGate() || !DartXbrlFactReader.hasEntityFacts(new ByteArrayInputStream(instance), rceptNo)) {
            return Map.of();
        }
        Path labelCache = labelPath(corpCode, rceptNo);
        try {
            if (Files.exists(labelCache)) {
                return DartXbrlLabels.entityLabels(readCache(labelCache), rceptNo);
            }
            if (dart == null || fetchBudget.get() <= 0) {
                return null;
            }
            fetchBudget.decrementAndGet();
            Optional<DartXbrlClient.Original> got = dart.original(rceptNo);
            if (got.isEmpty() || got.get().labelsKo() == null) {
                log.warn("원본 ZIP에 한국어 이름표가 없다 — 회사 확장 주당이익을 못 읽는다. 접수번호={}", rceptNo);
                return Map.of();
            }
            writeCache(labelCache, got.get().labelsKo());
            return DartXbrlLabels.entityLabels(got.get().labelsKo(), rceptNo);
        } catch (IOException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, "dart", "이름표 캐시를 못 읽거나 못 썼다: " + labelCache, e);
        }
    }

    /** 판 하나를 판독해 재료 표에 적는다. 판독 실패면 그 사유(표에는 UNREADABLE로 남긴다) · 성공이면 {@code null}. */
    private String readAndRecord(long stockId, String ticker, String rceptNo, byte[] instance, Map<String, String> labels) {
        try (InputStream in = new ByteArrayInputStream(instance)) {
            DartXbrlFactReader.Result read = DartXbrlFactReader.read(in, ticker + " " + rceptNo, store.krGate(), labels);
            store.recordOk(stockId, rceptNo, read.facts(), read.unreadableValues());
            return null;
        } catch (DataSourceException e) {
            // 판독 실패는 평생이다(판독기 판이 바뀌면 다시) — 받기 실패와 갈라 센다.
            store.recordFailure(stockId, rceptNo, "UNREADABLE", e.kind().name(), e.getMessage());
            return e.getMessage();
        } catch (IOException e) {
            throw new IllegalStateException("바이트 배열 스트림이 닫히다 실패했다", e);
        }
    }

    /** 캐시 재판독 결과 — 다시 읽은 판 · 캐시에 원본이 없어 못 읽은 판 · 판독 실패. */
    public record Reread(int reread, int missing, int unreadable) {
    }

    /**
     * 재료 표에 OK로 있는데 판독기 판이 지금과 다른 판을 <b>디스크 캐시에서만</b> 다시 읽는다 — 외부 0콜 · 목록 · 명부를 안 묻는다. 작은 DB 재생
     * 도구가 판독기 판이 바뀐 뒤 부른다({@link #collect}는 짝 없는 행이 있으면 목록을 다시 받는다).
     */
    public Reread rereadCached(long stockId, String ticker, String corpCode) {
        int reread = 0;
        int missing = 0;
        int unreadable = 0;
        for (Filing f : store.filings(stockId)) {
            if (!"OK".equals(f.status()) || !needs(f)) {
                continue;
            }
            Path cached = cachePath(corpCode, f.rceptNo());
            if (!Files.exists(cached)) {
                missing++;
                log.warn("재료 표에 OK인 판의 캐시 원본이 없다. stockId={} 접수번호={}", stockId, f.rceptNo());
                continue;
            }
            byte[] instance;
            try {
                instance = readCache(cached);
            } catch (IOException e) {
                throw new IllegalStateException("인스턴스 캐시를 못 읽었다: " + cached, e);
            }
            Map<String, String> labels;
            try {
                labels = labelsFor(null, corpCode, f.rceptNo(), instance, new AtomicInteger());
            } catch (DataSourceException e) {
                throw new IllegalStateException("이름표 캐시를 못 읽었다: " + f.rceptNo(), e);
            }
            if (labels == null) {
                // 0콜 재판독 — 이름표가 필요한데 캐시에 없다. 판 3으로 안 읽고 남긴다(재료 받기가 이름표를 받는다).
                missing++;
                log.warn("회사 확장 주당 사실의 이름표가 캐시에 없어 그 판을 다시 읽지 않는다. stockId={} 접수번호={}", stockId, f.rceptNo());
                continue;
            }
            if (readAndRecord(stockId, ticker, f.rceptNo(), instance, labels) == null) {
                reread++;
            } else {
                unreadable++;
            }
        }
        return new Reread(reread, missing, unreadable);
    }

    /** 같은 (사업연도, 보고서 코드)에서 {@code bound}보다 앞서 접수된 판들. */
    static List<Filing> earlier(Filing bound, List<Filing> filings) {
        return filings.stream()
                .filter(f -> f.bsnsYear() != null && f.bsnsYear().equals(bound.bsnsYear())
                        && bound.reprtCode() != null && bound.reprtCode().equals(f.reprtCode())
                        && f.rceptDate().isBefore(bound.rceptDate()))
                .toList();
    }

    private boolean needs(Filing f) {
        if (f.status() == null || "FAILED".equals(f.status())) {
            return true;
        }
        return !"NO_FILE".equals(f.status()) && (f.readerVersion() == null || f.readerVersion() != store.readerVersion());
    }

    /** {@link DartEarlierInstances} — 저장 이른 판의 인스턴스를 디스크 캐시에서(0콜). */
    @Override
    public List<DartEarlierInstances.Instance> of(long stockId, int year, String reprtCode, String latestRceptNo)
            throws DataSourceException {
        List<DartEarlierInstances.Instance> out = new ArrayList<>();
        for (KrXbrlStore.StoredVintage v : store.storedEarlierVintages(stockId, year, reprtCode, latestRceptNo)) {
            Path cached = cachePath(v.corpCode(), v.rceptNo());
            if (!"OK".equals(v.status()) || !Files.exists(cached)) {
                // 그 판의 원장을 아직 못 읽었다 — 그 판은 이번에 안 짓는다(받기 · 판독은 kr-xbrl-collect의 일). 조용히 넘기지 않는다.
                log.warn("저장 이른 판의 인스턴스가 없어 그 판을 인스턴스로 못 짓는다. stockId={} {}/{} 접수번호={} 상태={} 캐시={}",
                        stockId, year, reprtCode, v.rceptNo(), v.status(), Files.exists(cached));
                continue;
            }
            try {
                Path labelCache = labelPath(v.corpCode(), v.rceptNo());
                out.add(new DartEarlierInstances.Instance(v.rceptNo(), readCache(cached),
                        Files.exists(labelCache) ? readCache(labelCache) : null));
            } catch (IOException e) {
                throw new DataSourceException(DataSourceException.Kind.TRANSIENT, "dart", "인스턴스 캐시를 못 읽었다: " + cached, e);
            }
        }
        return out;
    }

    /** 한국어 이름표 링크베이스 캐시 — 인스턴스 옆. */
    Path labelPath(String corpCode, String rceptNo) {
        return cacheDir.resolve(corpCode).resolve(rceptNo + ".lab-ko.xml.gz");
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
