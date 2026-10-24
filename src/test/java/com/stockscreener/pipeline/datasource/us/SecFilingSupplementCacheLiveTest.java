package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 공시 보충의 <b>같은 날 원자료</b>를 파일로 모은다 — companyfacts · 공시 목록(+페이지) · 보충 대상의 색인·인스턴스
 * (ADR-0173 · RS0). 모은 것은 {@link SecEdgarFactsSweepTest}가 {@code REPLAY_SUPPLEMENT_DIR}로 읽어, 운영 코드
 * ({@link SecFilingSupplement})를 한 줄도 안 바꾸고 보충 전/후 출력을 0콜로 대조한다.
 *
 * <p><b>왜 같은 날인가</b>: companyfacts를 며칠 전에 받아 두고 공시 목록만 오늘 받으면, 그 사이 제출된 공시가 전부
 * 「companyfacts에 us-gaap 0」으로 보여 거짓 보충 대상이 된다.
 *
 * <p>배치: {@code <dir>/companyfacts/<티커>.json} · {@code <dir>/sec/<URL 경로>}. 2xx가 아니면 본문 대신
 * {@code <경로>.status}에 상태코드를 적는다 — 스윕이 그 실패를 같은 코드로 재현한다.
 *
 * <pre>
 * SUPP_CACHE_DIR=… SUPP_TICKERS=…(한 줄에 하나) SUPP_FROM=2018 SUPP_CONTROLS=AAPL:0000320193-26-000020,…
 *   ./gradlew liveTest --tests "*SecFilingSupplementCacheLiveTest*"
 * </pre>
 *
 * <p>{@code SUPP_CONTROLS}는 제거 대조용이다 — companyfacts에 <b>있는</b> 공시의 인스턴스도 받아 둔다.
 *
 * <p><b>받기는 운영과 같은 자리({@link SecFilingClient#fetch})다</b> — 대기 · 호스트별 차단 · 덜 온 2xx(JSON으로 안 읽힘 ·
 * 인스턴스 길이 ≠ 색인 크기)를 다시 받는 규칙까지. 따로 짠 재시도는 대기도 길이 대조도 없이 끊긴 본문을 그대로 저장해, 재생이
 * 보충 실패로 읽고 손 FR 2-5가 거짓으로 멈췄다(2026-10-06 v12 반증). 끝까지 못 받은 것은 {@code .status}에 503을 적는다 —
 * 재생은 그것을 받기 실패로 재현하지만 <b>종류까지는 아니다</b>(운영이 MALFORMED인 잘린 인스턴스도 재생에선 TRANSIENT · v14
 * 반증). 그래서 출력의 {@code failures=}가 0이 아니면 손 FR 2-2가 멈추고, 그 디렉터리를 재생에 쓰지 않는다.
 *
 * <p><b>같은 디렉터리에 다시 모아도 된다</b> — 2xx를 저장할 때 그 경로의 낡은 {@code .status}를 지운다. 재생은 {@code .status}를
 * 본문보다 먼저 보므로, 안 지우면 다시 모아 {@code failures=0}인데도 재생이 그 공시를 받기 실패로 읽었다(v14 반증 재현).
 *
 * <p>{@code SUPP_DATA_BASE}·{@code SUPP_FILES_BASE}를 주면 두 호스트를 바꿔 끼운다 — 가짜 서버로 0콜 연습할 때만.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SUPP_CACHE_DIR", matches = ".+")
class SecFilingSupplementCacheLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient data;
    private RestClient files;
    private TokenBucketRateLimiter limiter;
    private final SecFilingClient.Backoff dataBackoff = SecFilingClient.Backoff.standard();
    private final SecFilingClient.Backoff filesBackoff = SecFilingClient.Backoff.standard();
    private Path dir;
    private int calls;
    private int failures;

    @Test
    void collect() throws Exception {
        dir = Path.of(System.getenv("SUPP_CACHE_DIR"));
        List<String> tickers = Files.readAllLines(Path.of(System.getenv("SUPP_TICKERS"))).stream()
                .map(String::trim).filter(t -> !t.isEmpty()).toList();
        int from = Integer.parseInt(Optional.ofNullable(System.getenv("SUPP_FROM")).orElse("2018"));
        List<String> controls = Optional.ofNullable(System.getenv("SUPP_CONTROLS")).map(v -> List.of(v.split(",")))
                .orElse(List.of());

        DataSourceProperties properties = new DataSourceProperties();
        properties.getSecEdgar().setApiKey(System.getenv("SEC_EDGAR_USER_AGENT"));
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        // 운영과 같은 간격(초당 5회).
        limiter = new TokenBucketRateLimiter("sec-edgar", 300, 0);
        if (System.getenv("SUPP_DATA_BASE") != null) {
            properties.getSecEdgar().setBaseUrl(System.getenv("SUPP_DATA_BASE"));
        }
        if (System.getenv("SUPP_FILES_BASE") != null) {
            properties.setSecEdgarFilesBaseUrl(System.getenv("SUPP_FILES_BASE"));
        }
        DataSourceProperties.Source source = properties.getSecEdgar();
        data = factory.create(source, source.getBaseUrl(), SecEdgarHeaders.of(source));
        files = factory.create(source, properties.getSecEdgarFilesBaseUrl(), SecEdgarHeaders.of(source));
        SecTickerCikCache cikCache = new SecTickerCikCache(factory, properties, limiter);

        LocalDate since = SecFilingSupplement.windowStart(from);
        Files.createDirectories(dir.resolve("companyfacts"));
        StringBuilder report = new StringBuilder();
        int noCik = 0;
        int targetsTotal = 0;
        for (String ticker : tickers) {
            Optional<Long> cik = cikCache.findCik(ticker);
            if (cik.isEmpty()) {
                noCik++;
                report.append(ticker).append("\tno-cik\n");
                continue;
            }
            String padded = SecTickerCikCache.padded(cik.get());
            byte[] facts = get(data, dataBackoff, "/api/xbrl/companyfacts/" + padded + ".json", null, JSON);
            if (facts == null && "on".equals(System.getenv("SUPP_EMPTY_ON_404"))) {
                // 관문 켬 어댑터가 companyfacts 404를 빈 트리로 받아 보충으로 잇는 것을 재현한다(ADR-0190 드라이런).
                facts = ("{\"cik\":" + cik.get() + ",\"facts\":{}}").getBytes(StandardCharsets.UTF_8);
                report.append(ticker).append("\tcompanyfacts-empty-substituted\n");
            }
            if (facts == null) {
                report.append(ticker).append("\tcompanyfacts-failed\n");
                continue;
            }
            Files.write(dir.resolve("companyfacts").resolve(ticker + ".json"), facts);
            JsonNode factsTree = MAPPER.readTree(facts).path("facts");

            String subsPath = "/submissions/" + padded + ".json";
            byte[] subs = get(data, dataBackoff, subsPath, dir.resolve("sec").resolve(subsPath.substring(1)), JSON);
            if (subs == null) {
                report.append(ticker).append("\tsubmissions-failed\n");
                continue;
            }
            JsonNode filingsNode = MAPPER.readTree(subs).path("filings");
            List<SecFilingClient.Filing> filings = new ArrayList<>(
                    SecFilingClient.parseFilings(filingsNode.path("recent"), since, ticker));
            for (JsonNode page : filingsNode.path("files")) {
                String name = page.path("name").asString("");
                String to = page.path("filingTo").asString("");
                if (name.isBlank() || (!to.isBlank() && LocalDate.parse(to).isBefore(since))) {
                    continue;
                }
                String pagePath = "/submissions/" + name;
                byte[] body = get(data, dataBackoff, pagePath, dir.resolve("sec").resolve(pagePath.substring(1)), JSON);
                if (body != null) {
                    filings.addAll(SecFilingClient.parseFilings(MAPPER.readTree(body), since, ticker + " " + name));
                }
            }

            Set<String> wanted = new HashSet<>();
            for (SecFilingClient.Filing f : SecFilingSupplement.targets(filings,
                    SecFilingSupplement.usGaapAccessions(factsTree))) {
                wanted.add(f.accession());
            }
            targetsTotal += wanted.size();
            for (String control : controls) {
                String[] parts = control.split(":");
                if (parts[0].equals(ticker)) {
                    wanted.add(parts[1]);
                }
            }
            for (String accession : wanted) {
                String folder = accession.replace("-", "");
                String indexPath = "/Archives/edgar/data/%d/%s/index.json".formatted(cik.get(), folder);
                byte[] index = get(files, filesBackoff, indexPath, dir.resolve("sec").resolve(indexPath.substring(1)), JSON);
                if (index == null) {
                    continue;
                }
                List<String> candidates = new ArrayList<>();
                List<String> sizes = new ArrayList<>();
                for (JsonNode item : MAPPER.readTree(index).path("directory").path("item")) {
                    String name = item.path("name").asString("");
                    if (SecFilingClient.isInstanceCandidate(name)) {
                        candidates.add(name);
                        sizes.add(item.path("size").asString(""));
                    }
                }
                if (candidates.size() != 1) {
                    // 운영 코드도 여기서 MALFORMED로 멈춘다 — 받을 파일이 없다.
                    report.append(ticker).append('\t').append(accession).append("\tambiguous\t")
                            .append(candidates).append('\n');
                    continue;
                }
                String instancePath = "/Archives/edgar/data/%d/%s/%s".formatted(cik.get(), folder, candidates.get(0));
                long expected;
                try {
                    expected = new SecFilingClient.FolderItem(candidates.get(0), sizes.get(0)).expectedSize(instancePath);
                } catch (DataSourceException e) {
                    // 운영도 여기서 받기 실패다(크기 없는 색인 항목) — 그 공시만 실패로 세고 나머지는 계속 모은다.
                    failures++;
                    report.append(ticker).append('\t').append(accession).append("\tno-size\n");
                    continue;
                }
                get(files, filesBackoff, instancePath, dir.resolve("sec").resolve(instancePath.substring(1)),
                        received -> {
                            byte[] got = received.getBody();
                            return got != null && got.length == expected ? null
                                    : "받은 길이가 색인 크기 %d와 다르다.".formatted(expected);
                        });
            }
            report.append(ticker).append("\tok\ttargets=").append(wanted.size()).append('\n');
        }
        Files.writeString(dir.resolve("collect-report.tsv"), report.toString(), StandardCharsets.UTF_8);
        System.out.printf("supplement.cache tickers=%d noCik=%d targets=%d calls=%d failures=%d dir=%s%n",
                tickers.size(), noCik, targetsTotal, calls, failures, dir);
    }

    /** 본문을 쓰고 그 경로의 낡은 {@code .status}를 지운다 — 재생이 {@code .status}를 본문보다 먼저 본다. */
    static void saveBody(Path save, byte[] body) throws java.io.IOException {
        Files.createDirectories(save.getParent());
        Files.write(save, body);
        Files.deleteIfExists(statusOf(save));
    }

    static Path statusOf(Path save) {
        return save.resolveSibling(save.getFileName() + ".status");
    }

    /** JSON 본문(companyfacts · 공시 목록 · 색인)의 덜 온 2xx 판정 — 운영과 같다. */
    private static final Function<ResponseEntity<byte[]>, String> JSON = received -> SecEdgarResponse.unreadableJson(
            received.getBody() == null ? null : new String(received.getBody(), StandardCharsets.UTF_8));

    /**
     * 한 콜 — 운영 받기 자리({@link SecFilingClient#fetch})로. 2xx면 본문을 {@code save}에 쓰고 돌려준다. 아니면
     * {@code <save>.status}에 상태코드(끝까지 못 받은 일시 실패 · 덜 온 본문은 503)를 적고 {@code null}.
     */
    private byte[] get(RestClient client, SecFilingClient.Backoff backoff, String path, Path save,
                       Function<ResponseEntity<byte[]>, String> incomplete) throws Exception {
        int status;
        String reason = "";
        try {
            ResponseEntity<byte[]> response = SecFilingClient.fetch(limiter, backoff, () -> {
                calls++;
                return client.get().uri(path).retrieve().toEntity(byte[].class);
            }, incomplete, path);
            status = response.getStatusCode().value();
            if (response.getStatusCode().is2xxSuccessful()) {
                if (save != null) {
                    saveBody(save, response.getBody());
                }
                return response.getBody();
            }
        } catch (DataSourceException e) {
            status = 503;
            reason = e.kind() + ": " + e.getMessage();
        }
        failures++;
        if (save != null) {
            Files.createDirectories(save.getParent());
            Files.writeString(statusOf(save), Integer.toString(status));
        }
        System.out.printf("supplement.cache failed status=%d path=%s %s%n", status, path, reason);
        return null;
    }
}
