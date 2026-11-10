package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.kr.DartDayList;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 국내 공시 게시 시각(ADR-0204) — 날짜마다 파일 캐시({@code <dir>/<yyyyMMdd>.tsv} · 첫 줄 {@code total=<n>})와 관문 표 {@code kr_filing_time}.
 * 캐시는 관문 전에도 쌓고(운영 DB에 그 표가 없다), 표는 국내 관문 켬에서만 쓴다. 게시 시각은 분 단위라 그 분의 끝(59초)으로 둔다 — 마감 분에 걸린
 * 공시를 「마감 전」으로 읽지 않는다. 시각은 그 줄이 실린 날짜 쪽의 것이다(18시 뒤 접수도 그날 목록에 실린다).
 *
 * <p><b>재게시 줄</b>(국내 잠정 관문 켬): 지난 날짜의 목록을 나중에 받으면, 그 사이 다시 게시된 공시는 원래 날짜가 아니라 다시 게시한 날짜의
 * 목록에 접수일 칸만 원래 날짜로 실린다(2026-11-10 캐시 전수: 접수일 &lt; 목록 날짜인 줄 12,020 접수번호 — 지표가 읽는 접수번호 중 2026년
 * 48건 · 반기보고서 8-14 → 9-29 ~ 11-06 · 373220 잠정 10-08 08:34 → 11-03 09:27). 그 시각은 원래 게시 시각이 아니다. 그런 줄은 표에
 * 올리지 않고, 그 줄로 앞서 올라간 행은 걷는다 — 원래 날짜 목록에 그 공시가 있으면 그 시각이 남고, 없으면 「시각 모름」이다(보이기는
 * 다음 날 · 막기는 접수일 — 두 규칙의 보수 쪽). 접수일이 목록 날짜보다 <b>늦은</b> 줄(영업시간 밖 제출이 그날 목록에 실린 것)은 그날의
 * 진짜 게시 시각이라 그대로 올린다.
 */
@Component
public class KrFilingTimeStore {

    private static final ZoneId KR_ZONE = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final Path dir;
    /** 국내 잠정 관문({@code stockscreener.gate.kr-provisional}) — 켜면 재게시 줄을 거른다. */
    private final boolean provisionalGate;

    public KrFilingTimeStore(JdbcTemplate jdbc, @Value("${stockscreener.dart-day.cache-dir}") String dir,
                             @Value("${stockscreener.gate.kr-provisional}") boolean provisionalGate) {
        this.jdbc = jdbc;
        this.dir = Path.of(dir);
        this.provisionalGate = provisionalGate;
    }

    public boolean cached(LocalDate day) {
        return Files.exists(file(day));
    }

    /** 완결된 날짜 목록을 캐시에 쓴다(원자적). */
    public void writeCache(LocalDate day, List<DartDayList.Row> rows) {
        StringBuilder body = new StringBuilder("total=" + rows.size() + "\n");
        for (DartDayList.Row r : rows) {
            body.append(String.join("\t", r.rceptNo(), r.corpCode() == null ? "" : r.corpCode(), r.corpName().replace('\t', ' '),
                    r.reportName().replace('\t', ' '), r.receiptDate().toString(), r.time().toString())).append('\n');
        }
        try {
            Files.createDirectories(dir);
            Path part = file(day).resolveSibling(file(day).getFileName() + ".part");
            Files.writeString(part, body, StandardCharsets.UTF_8);
            Files.move(part, file(day), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("게시 시각 캐시를 못 썼다: " + file(day), e);
        }
    }

    /**
     * 캐시 전부를 표에 올린다(관문 켬 · 0콜 · 같은 접수번호는 덮는다). 캐시 줄 수가 첫 줄 총 건수와 다르면 그 파일은 깨진 것이라 던진다.
     *
     * @return 올린 줄 수
     */
    public long loadAll() {
        long loaded = 0;
        List<Path> files;
        try (Stream<Path> list = Files.exists(dir) ? Files.list(dir) : Stream.empty()) {
            files = list.filter(p -> p.getFileName().toString().matches("\\d{8}\\.tsv")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("게시 시각 캐시를 못 읽었다: " + dir, e);
        }
        for (Path f : files) {
            LocalDate day = LocalDate.parse(f.getFileName().toString().substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
            List<Object[]> batch = new ArrayList<>();
            List<Object[]> reposted = new ArrayList<>();
            try {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                int total = Integer.parseInt(lines.get(0).substring("total=".length()));
                if (lines.size() - 1 != total) {
                    throw new IllegalStateException("게시 시각 캐시가 깨졌다(줄 %d 대 총 %d): %s".formatted(lines.size() - 1, total, f));
                }
                for (String line : lines.subList(1, lines.size())) {
                    String[] c = line.split("\t", -1);
                    if (provisionalGate && LocalDate.parse(c[4]).isBefore(day)) {
                        reposted.add(new Object[]{c[0], day});
                        continue;
                    }
                    LocalTime time = LocalTime.parse(c[5]).withSecond(59);
                    batch.add(new Object[]{c[0], c[1].isEmpty() ? null : c[1], c[2], c[3], day,
                            Timestamp.from(day.atTime(time).atZone(KR_ZONE).toInstant())});
                }
            } catch (IOException e) {
                throw new UncheckedIOException("게시 시각 캐시를 못 읽었다: " + f, e);
            }
            jdbc.batchUpdate("""
                    INSERT INTO kr_filing_time (rcept_no, corp_code, corp_name, report_name, listed_on, opened_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (rcept_no) DO UPDATE SET corp_code = EXCLUDED.corp_code, corp_name = EXCLUDED.corp_name,
                        report_name = EXCLUDED.report_name, listed_on = EXCLUDED.listed_on, opened_at = EXCLUDED.opened_at, fetched_at = now()
                    WHERE (kr_filing_time.listed_on, kr_filing_time.opened_at) IS DISTINCT FROM (EXCLUDED.listed_on, EXCLUDED.opened_at)
                    """, batch);
            // 재게시 줄로 앞서 올라간 행 — 그 목록 날짜로 올라간 것만 걷는다(원래 날짜 목록이 올린 행은 목록 날짜가 달라 안 걸린다).
            jdbc.batchUpdate("DELETE FROM kr_filing_time WHERE rcept_no = ? AND listed_on = ?", reposted);
            loaded += batch.size();
        }
        return loaded;
    }

    private Path file(LocalDate day) {
        return dir.resolve(day.format(DateTimeFormatter.BASIC_ISO_DATE) + ".tsv");
    }
}
