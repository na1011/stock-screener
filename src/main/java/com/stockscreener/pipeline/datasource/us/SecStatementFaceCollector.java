package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.TreeSet;

/**
 * 공시마다 재무제표 본문에 실린 개념({@link SecStatementFace})을 받아 디스크에 둔다 — 관문 켬에서만 {@link SecEdgarFinancialAdapter}가 부른다
 * (ADR-0202). 캐시는 {@code <cache-dir>/<CIK>/<접수번호 숫자>.txt} — 첫 줄 {@code source=…} · 개념 한 줄에 하나 · 손익계산서 본문 값은
 * {@code @value <기말 yyyyMMdd> <분기 수> <값>}(데이터셋 재료만 싣는다). 재료가 없거나 손익계산서의 마지막 줄이 없으면 {@code source=none}(모른다).
 * 받기 실패는 그 종목의 실패로 올린다 — 「모른다」로 접으면 그 실행이 부문 합계를 받아 저장하고 다음 실행이 고치며 실행마다 값이 갈린다.
 *
 * <p>{@link SecFilingClient}의 폴더 · 파일 받기를 부르기만 한다(그 파일은 판독기 판 범위다).
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class SecStatementFaceCollector {

    private static final Logger log = LoggerFactory.getLogger(SecStatementFaceCollector.class);

    private final SecFilingClient client;
    private final Path cacheDir;
    private final ObjectMapper json = new ObjectMapper();

    public SecStatementFaceCollector(SecFilingClient client, @Value("${stockscreener.sec-face.cache-dir}") String cacheDir) {
        this.client = client;
        this.cacheDir = Path.of(cacheDir);
    }

    /** 그 발행사 공시들의 본문 — 캐시에 없으면 받는다. 모르는 공시는 결과에 없다. 원장이 재료를 안 주는(NOT_FOUND — 폴더 404 · 빈 본문) 공시는 모른다(캐시 안 함) · 일시 실패는 던진다(종목 실패). */
    public SecStatementFaces faces(long cik, Set<String> accessions, String context) throws DataSourceException {
        Map<String, Face> known = new HashMap<>();
        int fetched = 0;
        int unknown = 0;
        for (String accession : accessions) {
            Optional<Face> cached = read(cacheDir, cik, accession);
            if (cached == null) {
                try {
                    cached = fetch(cik, accession);
                    fetched++;
                } catch (DataSourceException e) {
                    if (e.kind() != DataSourceException.Kind.NOT_FOUND) {
                        throw e;
                    }
                    // 원장이 그 공시의 재료를 안 준다(VOD 2018 폴더 404 · WF 2019 빈 xsd — 2026-11-02) — 본문을 모르는 공시다. 캐시하지 않는다: 다음 실행이 다시 묻는다.
                    // 일시 실패는 위로 던진다 — 「모름」으로 접으면 실행마다 그 판의 본문 판정이 갈린다.
                    log.warn("공시 본문 재료를 원장이 주지 않는다 — 이 공시는 본문 모름. {} {} — {}", context, accession, e.getMessage());
                    cached = Optional.empty();
                }
            }
            if (cached.isPresent()) {
                known.put(accession, cached.get());
            } else {
                unknown++;
            }
        }
        if (fetched > 0 || unknown > 0) {
            log.info("공시 본문 재료. {} 대상={} 받음={} 모름={}", context, accessions.size(), fetched, unknown);
        }
        return new SecStatementFaces(known);
    }

    /** 한 공시의 본문 — 개념과 손익계산서 본문 값(열쇠 {@code yyyyMMdd|분기 수|값} · 데이터셋 재료만). */
    public record Face(Set<String> concepts, Set<String> incomeValues) {

        /** 손익계산서 본문이 싣는 기간({@code yyyyMMdd|분기 수}) — 값 재료가 없으면 빈 집합. */
        public Set<String> incomePeriods() {
            Set<String> out = new HashSet<>();
            for (String key : incomeValues) {
                out.add(key.substring(0, key.lastIndexOf('|')));
            }
            return out;
        }

        /** 값 열쇠 — 값은 소수 끝 0을 뗀 평문. */
        public static String key(String ddate, int quarters, java.math.BigDecimal value) {
            return ddate + "|" + quarters + "|" + value.stripTrailingZeros().toPlainString();
        }
    }

    /** 캐시 — 파일이 없으면 {@code null} · {@code source=none}이면 빈 값(모른다) · 아니면 본문. */
    static Optional<Face> read(Path cacheDir, long cik, String accession) throws DataSourceException {
        Path file = cacheDir.resolve(String.valueOf(cik)).resolve(accession.replace("-", "") + ".txt");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).startsWith("source=")) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME, "본문 재료 캐시가 깨졌다: " + file);
            }
            if (lines.get(0).equals("source=none")) {
                return Optional.empty();
            }
            Set<String> concepts = new TreeSet<>();
            Set<String> values = new HashSet<>();
            for (String line : lines.subList(1, lines.size())) {
                if (line.startsWith("@value ")) {
                    String[] v = line.substring("@value ".length()).split(" ");
                    values.add(Face.key(v[0], Integer.parseInt(v[1]), new java.math.BigDecimal(v[2])));
                } else if (!line.isBlank()) {
                    concepts.add(line);
                }
            }
            return Optional.of(new Face(concepts, values));
        } catch (IOException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SecEdgarResponse.SOURCE_NAME, "본문 재료 캐시를 못 읽었다: " + file, e);
        }
    }

    private Optional<Face> fetch(long cik, String accession) throws DataSourceException {
        List<SecFilingClient.FolderItem> items = client.folder(cik, accession);
        SecFilingClient.FolderItem meta = item(items, n -> n.equals("MetaLinks.json"));
        Set<String> face = Set.of();
        String source = "none";
        if (meta != null) {
            try {
                face = SecStatementFace.fromMetaLinks(json.readTree(client.instance(cik, accession, meta)));
                source = "metalinks";
            } catch (JacksonException e) {
                // MetaLinks.json이 JSON 문법에 안 맞는다(문자열 안 날 줄바꿈 · 잘못된 escape — LOOP · BBAR 2026-11-02) — 같은 폴더의
                // 링크베이스가 같은 본문 재료다. 그것도 없으면 아래에서 「모름」이 된다.
                log.warn("MetaLinks.json을 읽지 못해 링크베이스로 읽는다. CIK {} {} — {}", cik, accession, e.getOriginalMessage());
                meta = null;
            } catch (DataSourceException e) {
                if (e.kind() != DataSourceException.Kind.NOT_FOUND) {
                    throw e;
                }
                // 원장이 MetaLinks.json을 비워서 준다(ATXS 2023 · 2026-11-02) — 위와 같은 까닭으로 링크베이스로 읽는다.
                log.warn("MetaLinks.json을 받지 못해 링크베이스로 읽는다. CIK {} {} — {}", cik, accession, e.getMessage());
                meta = null;
            }
        }
        if (meta == null) {
            SecFilingClient.FolderItem summary = item(items, n -> n.equals("FilingSummary.xml"));
            SecFilingClient.FolderItem pre = item(items, n -> n.endsWith("_pre.xml"));
            SecFilingClient.FolderItem xsd = item(items, n -> n.endsWith(".xsd"));
            SecFilingClient.FolderItem presentation = pre != null ? pre : xsd;
            if (summary != null && presentation != null) {
                String schema = xsd == null ? null : new String(client.instance(cik, accession, xsd), StandardCharsets.UTF_8);
                String links = presentation == xsd ? schema : new String(client.instance(cik, accession, presentation), StandardCharsets.UTF_8);
                face = SecStatementFace.fromLinkbase(new String(client.instance(cik, accession, summary), StandardCharsets.UTF_8), links, schema);
                source = "linkbase";
            }
        }
        if (!SecStatementFace.carriesFinalIncome(face)) {
            // 손익계산서의 마지막 줄이 재료에 없다(표지만 있는 8-K · 본문 역할을 못 찾음) — 그 공시로는 손익 열의 부재를 판정하지 않는다.
            source = "none";
            face = Set.of();
        }
        write(cik, accession, source, face);
        return face.isEmpty() ? Optional.empty() : Optional.of(new Face(face, Set.of()));
    }

    private static SecFilingClient.FolderItem item(List<SecFilingClient.FolderItem> items, java.util.function.Predicate<String> name) {
        return items.stream().filter(i -> name.test(i.name())).findFirst().orElse(null);
    }

    private void write(long cik, String accession, String source, Set<String> face) throws DataSourceException {
        Path file = cacheDir.resolve(String.valueOf(cik)).resolve(accession.replace("-", "") + ".txt");
        try {
            Files.createDirectories(file.getParent());
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.writeString(part, "source=" + source + "\n" + String.join("\n", face) + "\n", StandardCharsets.UTF_8);
            Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SecEdgarResponse.SOURCE_NAME, "본문 재료 캐시를 못 썼다: " + file, e);
        }
    }

    /** 공시 → 본문(아는 것만). */
    public record SecStatementFaces(Map<String, Face> known) {

        public static final SecStatementFaces NONE = new SecStatementFaces(Map.of());

        /** 그 공시의 본문이 그 개념들 중 하나를 싣는가 — 모르면 {@code null}. */
        public Boolean carries(String accession, Set<String> concepts) {
            Face face = known.get(accession);
            if (face == null) {
                return null;
            }
            return concepts.stream().anyMatch(face.concepts()::contains);
        }

        /**
         * 그 사실이 그 공시 손익계산서 본문의 줄인가 — 값 재료(데이터셋)가 있으면 값이 같은 공시 · 같은 기간 손익계산서 본문의 한 줄 값과 같은가
         * (다른 개념 · 회사 확장 개념으로 단 소계도 받는다 — SSD 「Income from operations」 · HRL · BR), 없으면(공시 폴더) 개념이 본문에 실렸는가.
         * 모르면 {@code null}.
         *
         * <p><b>본문이 그 기간을 싣는 사실만 판정한다</b> — 10-K 손익계산서는 연간만 싣고 4분기 3개월 값은 주석(분기 자료)에서 온다(RVTY FY2022
         * 4분기 매출). 데이터셋 재료는 본문 값의 기간으로, 값 재료가 없는 공시 폴더 재료는 양식의 본문 기간(연차 보고서 4분기 · 분기 보고서 1~3분기)
         * 으로 가른다 · 그 밖(수시보고서)은 판정하지 않는다.
         */
        public Boolean onIncomeStatement(String accession, String form, Set<String> concepts, String endYyyyMmDd, int quarters,
                                         java.math.BigDecimal value) {
            Face face = known.get(accession);
            if (face == null) {
                return null;
            }
            // 판정하는 기간은 양식의 본문 기간이다 — 10-K 손익계산서는 연간(4분기 3개월 값은 주석의 분기 자료 · GE FY2015 10-K의 데이터셋에 3개월
            // 줄이 섞여 주석의 4분기 `Revenues` 2,585가 본문 `SalesRevenueNet` 30,614를 밀어냈다).
            if (!formPresents(form, quarters)) {
                return null;
            }
            Set<String> periods = face.incomePeriods();
            if (periods.isEmpty()) {
                // 값 재료가 없다(공시 폴더) — 개념 이름으로.
                return concepts.stream().anyMatch(face.concepts()::contains);
            }
            String ddate = dataSetDate(endYyyyMmDd);
            if (!periods.contains(ddate + "|" + quarters)) {
                return null;
            }
            if (value.signum() == 0) {
                return null;   // 0은 아무 줄과나 같다(PSMT FY2015 `Revenues` 0) — 값으로 판정하지 않는다
            }
            // 값 재료가 있다(데이터셋) — 값으로만(개념 이름은 기간을 안 가린다).
            return face.incomeValues().contains(Face.key(ddate, quarters, value));
        }

        /** 그 공시의 손익계산서 본문이 그 기간에 그 값의 줄을 실었는가(데이터셋 재료만). */
        public boolean presents(String accession, String endYyyyMmDd, int quarters, java.math.BigDecimal value) {
            Face face = known.get(accession);
            return face != null && value.signum() != 0 && face.incomeValues().contains(Face.key(dataSetDate(endYyyyMmDd), quarters, value));
        }

        /**
         * 그 기간의 그 값을 손익계산서 본문에 실은 공시가 이 발행사에 하나라도 있는가(데이터셋 재료만 — 값 재료가 없는 공시는 안 센다). 본문 표시가 판마다
         * 바뀐다 — SNA는 2018년 3분기 전 본문이 「Net sales」 · 「Financial services revenue」 두 줄만 싣고 합계 3,711.8은 2019 10-K부터 본문에 있다.
         */
        public boolean presentedAnywhere(String endYyyyMmDd, int quarters, java.math.BigDecimal value) {
            if (value.signum() == 0) {
                return false;
            }
            String key = Face.key(dataSetDate(endYyyyMmDd), quarters, value);
            return known.values().stream().anyMatch(f -> f.incomeValues().contains(key));
        }

        /**
         * 데이터셋의 기말({@code ddate}) — 가장 가까운 월말로 반올림한 날이다(SEC 데이터셋 규약). 사실의 기말 그대로 찾으면 52/53주 회계연도의 기간이
         * 판정되지 않는다(SNA 2018-09-29 · HAS 2024-12-29 · NTAP 2022-01-28 — 데이터셋은 0930 · 1231 · 0131).
         */
        static String dataSetDate(String yyyyMmDd) {
            java.time.LocalDate d = java.time.LocalDate.parse(yyyyMmDd, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            java.time.LocalDate monthEnd = d.withDayOfMonth(d.lengthOfMonth());
            java.time.LocalDate previousEnd = d.withDayOfMonth(1).minusDays(1);
            java.time.LocalDate nearest = java.time.temporal.ChronoUnit.DAYS.between(d, monthEnd)
                    <= java.time.temporal.ChronoUnit.DAYS.between(previousEnd, d) ? monthEnd : previousEnd;
            return nearest.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        }

        private static boolean formPresents(String form, int quarters) {
            String base = form == null ? "" : form.replace("/A", "");
            return switch (base) {
                case "10-K", "20-F", "40-F" -> quarters == 4;
                case "10-Q" -> quarters >= 1 && quarters <= 3;
                default -> false;
            };
        }
    }
}
