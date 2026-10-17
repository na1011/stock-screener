package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 받은 원자료를 디스크에 담아 다음 재실행을 0콜로 만든다 — 색인 줄 · 양식 본문 · 표지. */
public final class CachingListingFormClient extends SecListingFormClient {

    private final Path dir;

    public CachingListingFormClient(SecDailyIndexClient daily, SecFilingClient filings, ExternalApiClientFactory factory,
                                    DataSourceProperties properties, TokenBucketRateLimiter limiter, Path dir) throws java.io.IOException {
        super(daily, filings, factory, properties, limiter);
        this.dir = Files.createDirectories(dir);
    }

    @Override
    public List<SecDailyIndexClient.IndexLine> lines(LocalDate from, LocalDate to, java.util.Set<String> forms)
            throws com.stockscreener.pipeline.datasource.DataSourceException {
        Path f = dir.resolve("lines-%s-%s-%s.tsv".formatted(from, to, String.join("_", new java.util.TreeSet<>(forms)).replace('/', '-')));
        try {
            if (Files.exists(f)) {
                return Files.readAllLines(f).stream().map(l -> l.split("\t", -1)).map(c -> new SecDailyIndexClient.IndexLine(
                        c[0], Long.parseLong(c[1]), LocalDate.parse(c[2]), c[3], c[4])).toList();
            }
            List<SecDailyIndexClient.IndexLine> got = super.lines(from, to, forms);
            Files.write(f, got.stream().map(l -> String.join("\t", l.form(), String.valueOf(l.cik()), l.filed().toString(),
                    l.accession(), l.company())).toList());
            return got;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public List<SecDailyIndexClient.IndexLine> candidateLines(LocalDate from, LocalDate to, java.util.Set<String> forms)
            throws com.stockscreener.pipeline.datasource.DataSourceException {
        Path f = dir.resolve("cand-%s-%s-%s.tsv".formatted(from, to, String.join("_", new java.util.TreeSet<>(forms)).replace('/', '-')));
        try {
            if (Files.exists(f)) {
                return Files.readAllLines(f).stream().map(l -> l.split("\t", -1)).map(c -> new SecDailyIndexClient.IndexLine(
                        c[0], Long.parseLong(c[1]), LocalDate.parse(c[2]), c[3], c[4])).toList();
            }
            List<SecDailyIndexClient.IndexLine> got = super.candidateLines(from, to, forms);
            Files.write(f, got.stream().map(l -> String.join("\t", l.form(), String.valueOf(l.cik()), l.filed().toString(),
                    l.accession(), l.company())).toList());
            return got;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public Optional<byte[]> document(long cik, String accession, String form)
            throws com.stockscreener.pipeline.datasource.DataSourceException {
        Path f = dir.resolve("doc-%d-%s.bin".formatted(cik, accession));
        try {
            if (Files.exists(f)) {
                byte[] b = Files.readAllBytes(f);
                return b.length == 0 ? Optional.empty() : Optional.of(b);
            }
            Optional<byte[]> got = super.document(cik, accession, form);
            Files.write(f, got.orElse(new byte[0]));
            return got;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Cover> lastCoverBefore(long cik, LocalDate before, LocalDate since)
            throws com.stockscreener.pipeline.datasource.DataSourceException {
        Path f = dir.resolve("cover-%d-%s.json".formatted(cik, before));
        try {
            if (Files.exists(f)) {
                String[] parts = Files.readString(f).split("\n", 4);
                return parts[0].isEmpty() ? Optional.empty() : Optional.of(new Cover(parts[0], parts[1], LocalDate.parse(parts[2]),
                        com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts.fromJson(parts[3])));
            }
            Optional<Cover> got = super.lastCoverBefore(cik, before, since);
            Files.writeString(f, got.map(c -> c.accession() + "\n" + c.form() + "\n" + c.filed() + "\n" + c.facts().toJson()).orElse(""));
            return got;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
