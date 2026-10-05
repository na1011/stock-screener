package com.stockscreener.pipeline.datasource.us;

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
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SUPP_CACHE_DIR", matches = ".+")
class SecFilingSupplementCacheLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient data;
    private RestClient files;
    private TokenBucketRateLimiter limiter;
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
            byte[] facts = get(data, "/api/xbrl/companyfacts/" + padded + ".json", null);
            if (facts == null) {
                report.append(ticker).append("\tcompanyfacts-failed\n");
                continue;
            }
            Files.write(dir.resolve("companyfacts").resolve(ticker + ".json"), facts);
            JsonNode factsTree = MAPPER.readTree(facts).path("facts");

            String subsPath = "/submissions/" + padded + ".json";
            byte[] subs = get(data, subsPath, dir.resolve("sec").resolve(subsPath.substring(1)));
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
                byte[] body = get(data, pagePath, dir.resolve("sec").resolve(pagePath.substring(1)));
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
                byte[] index = get(files, indexPath, dir.resolve("sec").resolve(indexPath.substring(1)));
                if (index == null) {
                    continue;
                }
                List<String> candidates = new ArrayList<>();
                for (JsonNode item : MAPPER.readTree(index).path("directory").path("item")) {
                    String name = item.path("name").asString("");
                    if (SecFilingClient.isInstanceCandidate(name)) {
                        candidates.add(name);
                    }
                }
                if (candidates.size() != 1) {
                    // 운영 코드도 여기서 MALFORMED로 멈춘다 — 받을 파일이 없다.
                    report.append(ticker).append('\t').append(accession).append("\tambiguous\t")
                            .append(candidates).append('\n');
                    continue;
                }
                String instancePath = "/Archives/edgar/data/%d/%s/%s".formatted(cik.get(), folder, candidates.get(0));
                get(files, instancePath, dir.resolve("sec").resolve(instancePath.substring(1)));
            }
            report.append(ticker).append("\tok\ttargets=").append(wanted.size()).append('\n');
        }
        Files.writeString(dir.resolve("collect-report.tsv"), report.toString(), StandardCharsets.UTF_8);
        System.out.printf("supplement.cache tickers=%d noCik=%d targets=%d calls=%d failures=%d dir=%s%n",
                tickers.size(), noCik, targetsTotal, calls, failures, dir);
    }

    /**
     * 한 콜. 2xx면 본문을 {@code save}에 쓰고 돌려준다. 아니면 {@code <save>.status}에 상태코드를 적고 {@code null}.
     * 5xx·전송 실패는 운영 코드({@link SecFilingClient})처럼 두 번 다시 묻는다.
     */
    private byte[] get(RestClient client, String path, Path save) throws Exception {
        int status = -1;
        String lastError = "";
        for (int attempt = 0; attempt <= SecFilingClient.TRANSIENT_RETRIES; attempt++) {
            limiter.acquire();
            calls++;
            ResponseEntity<byte[]> response;
            try {
                response = client.get().uri(path).retrieve().toEntity(byte[].class);
            } catch (RuntimeException e) {
                status = -1;
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                continue;
            }
            status = response.getStatusCode().value();
            if (response.getStatusCode().is5xxServerError()) {
                continue;
            }
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                if (save != null) {
                    Files.createDirectories(save.getParent());
                    Files.write(save, response.getBody());
                }
                return response.getBody();
            }
            break;
        }
        failures++;
        if (save != null) {
            Files.createDirectories(save.getParent());
            Files.writeString(save.resolveSibling(save.getFileName() + ".status"), Integer.toString(status));
        }
        System.out.printf("supplement.cache failed status=%d path=%s %s%n", status, path, lastError);
        return null;
    }
}
