package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * <b>재료 받기</b> — 목록(CIK · 접수번호)의 공시마다 재무제표 본문에 실린 개념({@link SecStatementFace})을
 * {@code <FACE_DIR>/<CIK>/<접수번호 숫자>.txt}에 둔다(첫 줄 {@code source=metalinks|linkbase|none} · 다음 줄부터 개념). 이미 있으면 안 부른다.
 * 공시당 1콜(MetaLinks) · 없으면 3콜(폴더 · FilingSummary · 표시 링크베이스) · 초당 약 7회.
 *
 * <p>MUST NOT: {@code SEC_EDGAR_USER_AGENT}를 출력에 찍지 않는다 — 이메일이 들어 있다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FACE_TARGETS", matches = ".+", disabledReason = "FACE_TARGETS가 없어 건너뛴다")
class SecStatementFaceCacheLiveTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    /** 전 갈래 합쳐 초당 8회 아래 — 다음 호출이 허락되는 시각(나노초). */
    private long nextSlot = System.nanoTime();

    private synchronized void pace() throws InterruptedException {
        long now = System.nanoTime();
        long wait = nextSlot - now;
        nextSlot = Math.max(now, nextSlot) + 125_000_000L;
        if (wait > 0) {
            Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
        }
    }

    private HttpResponse<String> get(String url) throws Exception {
        for (int attempt = 0; ; attempt++) {
            pace();
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT")).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if ((res.statusCode() == 429 || res.statusCode() >= 500) && attempt < 4) {
                Thread.sleep(10_000L * (attempt + 1));
                continue;
            }
            return res;
        }
    }

    @Test
    @DisplayName("[live] 목록의 공시마다 재무제표 본문 개념을 캐시한다")
    void cacheFaces() throws Exception {
        List<String> lines = Files.readAllLines(Path.of(System.getenv("FACE_TARGETS"))).stream().filter(l -> !l.isBlank()).toList();
        Path dir = Path.of(System.getenv("FACE_DIR"));
        java.util.concurrent.atomic.AtomicInteger meta = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger linkbase = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger none = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Integer.parseInt(System.getenv().getOrDefault("FACE_THREADS", "16")));
        List<java.util.concurrent.Future<?>> jobs = new java.util.ArrayList<>();
        for (String line : lines) {
            jobs.add(pool.submit(() -> {
                one(line, dir, meta, linkbase, none, done, lines.size());
                return null;
            }));
        }
        for (java.util.concurrent.Future<?> job : jobs) {
            job.get();
        }
        pool.shutdown();
        System.out.printf("targets=%d metalinks=%d linkbase=%d none=%d%n", lines.size(), meta.get(), linkbase.get(), none.get());
    }

    private void one(String line, Path dir, java.util.concurrent.atomic.AtomicInteger meta, java.util.concurrent.atomic.AtomicInteger linkbase,
                     java.util.concurrent.atomic.AtomicInteger none, java.util.concurrent.atomic.AtomicInteger done, int total) throws Exception {
        {
            String[] v = line.split("\t");
            String folder = v[1].replace("-", "");
            Path target = dir.resolve(v[0]).resolve(folder + ".txt");
            if (Files.exists(target)) {
                return;
            }
            String base = "https://www.sec.gov/Archives/edgar/data/%s/%s/".formatted(v[0], folder);
            String source = "none";
            Set<String> face = Set.of();
            HttpResponse<String> m = get(base + "MetaLinks.json");
            if (m.statusCode() == 200) {
                face = SecStatementFace.fromMetaLinks(json.readTree(m.body()));
                source = face.isEmpty() ? "none" : "metalinks";
            } else if (m.statusCode() == 404) {
                HttpResponse<String> index = get(base + "index.json");
                if (index.statusCode() == 200) {
                    String pre = null;
                    String xsd = null;
                    for (JsonNode it : json.readTree(index.body()).path("directory").path("item")) {
                        String name = it.path("name").asString("");
                        pre = name.endsWith("_pre.xml") ? name : pre;
                        xsd = name.endsWith(".xsd") ? name : xsd;
                    }
                    HttpResponse<String> summary = get(base + "FilingSummary.xml");
                    String presentationName = pre != null ? pre : xsd;
                    if (summary.statusCode() == 200 && presentationName != null) {
                        HttpResponse<String> presentation = get(base + presentationName);
                        String schema = xsd == null ? null : xsd.equals(presentationName) ? presentation.body() : get(base + xsd).body();
                        if (System.getenv("FACE_DEBUG") != null) {
                            java.util.regex.Matcher r = java.util.regex.Pattern.compile("<MenuCategory>([^<]*)</MenuCategory>|<Role>([^<]*)</Role>")
                                    .matcher(summary.body());
                            StringBuilder b = new StringBuilder();
                            while (r.find() && b.length() < 1500) {
                                b.append(r.group()).append(' ');
                            }
                            System.out.println("SUMMARY " + b);
                            java.util.regex.Matcher l = java.util.regex.Pattern.compile("presentationLink[^>]*>").matcher(presentation.body());
                            StringBuilder c = new StringBuilder();
                            while (l.find() && c.length() < 1500) {
                                c.append(l.group()).append(' ');
                            }
                            System.out.println("PRE(" + presentationName + ") " + c);
                        }
                        if (presentation.statusCode() == 200) {
                            face = SecStatementFace.fromLinkbase(summary.body(), presentation.body(), schema);
                            source = face.isEmpty() ? "none" : "linkbase";
                        }
                    }
                }
            } else {
                System.out.println("건너뜀 " + v[1] + " MetaLinks HTTP " + m.statusCode());
                return;
            }
            if (!SecStatementFace.carriesFinalIncome(face)) {
                // 운영 수집기와 같은 문 — 손익계산서의 마지막 줄이 재료에 없으면 모른다(표지만 있는 8-K).
                source = "none";
                face = Set.of();
            }
            Files.createDirectories(target.getParent());
            Path part = target.resolveSibling(target.getFileName() + ".part");
            Files.writeString(part, "source=" + source + "\n" + String.join("\n", face) + "\n");
            Files.move(part, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            switch (source) {
                case "metalinks" -> meta.incrementAndGet();
                case "linkbase" -> linkbase.incrementAndGet();
                default -> none.incrementAndGet();
            }
            if (done.incrementAndGet() % 1000 == 0) {
                System.out.printf("진행 %d/%d metalinks=%d linkbase=%d none=%d%n", done.get(), total, meta.get(), linkbase.get(), none.get());
            }
        }
    }
}
