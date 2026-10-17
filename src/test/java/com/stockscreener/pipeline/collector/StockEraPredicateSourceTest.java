package com.stockscreener.pipeline.collector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시대 술어의 성질 시험(ADR-0180 · 계획 {@code US-PAST-LISTING-PLAN-1017.md} P0-c). {@code stock.is_active}는 어떤 코드도 거짓으로
 * 안 쓰므로 「활성 종목」 술어는 시대가 닫힌 행을 거르지 못한다 — 닫힌 행이 오늘의 후보 풀에 들었다(★14).
 *
 * <p>그래서 {@code stock}을 {@code is_active}로 거르는 SQL 문장마다 둘 중 하나를 요구한다: 같은 문장에서
 * {@link StockRegistrar#eraCovers}로 그 선택이 뜻하는 날짜의 시대를 걸거나, 왜 시대와 무관한지 적은 표지
 * {@code -- 시대 무관: <이유>}를 SQL 안에 단다. 손으로 고른 메서드 목록이 아니라 <b>문장의 성질</b>로 세므로 새 질의가 생기면 붉다
 * ({@code NOT EXISTS}로 후보 술어를 직접 적은 자리도 걸린다).
 */
class StockEraPredicateSourceTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
    private static final Pattern FROM_STOCK = Pattern.compile("(?i)\\b(FROM|JOIN)\\s+stock\\b(?!_)");
    /** 종목 표의 활성 — 별칭 없는 {@code is_active} 또는 {@code s.}/{@code stock.} 접두. 유니버스·지표·화면의 {@code u.is_active} 등은 아니다. */
    private static final Pattern STOCK_ACTIVE = Pattern.compile("(?<![\\w.])(?:s\\.|stock\\.)?is_active\\b");
    private static final String MARKER = "-- 시대 무관:";

    @Test
    @DisplayName("stock을 is_active로 거르는 SQL 문장은 시대 술어를 쓰거나 시대 무관의 이유를 단다")
    void everyActiveStockSelectionNamesItsEra() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher block = TEXT_BLOCK.matcher(source);
                while (block.find()) {
                    String sql = block.group(1);
                    if (!FROM_STOCK.matcher(sql).find() || !STOCK_ACTIVE.matcher(sql).find()) {
                        continue;
                    }
                    int start = source.lastIndexOf(';', block.start()) + 1;
                    int end = source.indexOf(';', block.end());
                    String statement = source.substring(start, end < 0 ? source.length() : end);
                    if (statement.contains("eraCovers(") || sql.contains(MARKER)) {
                        continue;
                    }
                    long line = source.substring(0, block.start()).chars().filter(c -> c == '\n').count() + 1;
                    offenders.add(MAIN.relativize(file) + ":" + line);
                }
            }
        }
        assertThat(offenders).as("시대 술어도 「시대 무관」 이유도 없는 활성 종목 선택").isEmpty();
    }
}
