package com.stockscreener.probe.backtest;

import com.stockscreener.support.ReadOnlyDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>3.6시간짜리 적재가 중간에 죽어도 처음부터가 되지 않는가</b>를 증명한다.
 *
 * <p>이 탐침이 있는 이유는 비용이다 — 그 실행은 실패하면 비싸고, 재개가 되는지는
 * <b>실패해 봐야만</b> 알 수 있다. 값 계산은 이 질문과 무관하므로 <b>가짜 값</b>으로 돌린다.
 * DB를 안 건드리므로 몇 초면 끝나고, 그래서 자주 돌릴 수 있다.
 *
 * <p>확인하는 것 넷.
 * <ol>
 *   <li>끝난 날짜는 <b>다시 계산하지 않는다</b>(매니페스트가 근거)</li>
 *   <li>지문이 바뀌면 <b>끝난 날짜도 다시 한다</b> — 낡은 값을 쓰느니 느린 편이 낫다</li>
 *   <li>죽은 실행이 남긴 반쪽 파일이 <b>「끝났다」로 읽히지 않는다</b></li>
 *   <li>`.tmp`가 최종 이름을 갖는 순간이 <b>없다</b></li>
 * </ol>
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class PitStoreResumeProbeTest {

    private static final String CCY = "KRW";
    private static final LocalDate D1 = LocalDate.of(2026, 7, 30);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 31);
    private static final Set<String> CODES = Set.of("PER", "ROE");
    private static final String IDX = "index-fp-1";
    private static final Instant AT = Instant.parse("2026-09-04T00:00:00Z");

    private static PitStore.Stamp stamp(Map<Long, String> fp, Set<Long> failed) {
        return new PitStore.Stamp(350, CODES, fp, IDX, "digest-1", AT, failed);
    }

    /** 종목 지문. 값이 바뀌면 그 종목만 낡는다. */
    private static Map<Long, String> fp(String s1, String s2, String s3) {
        Map<Long, String> m = new LinkedHashMap<>();
        m.put(1L, s1);
        m.put(2L, s2);
        m.put(3L, s3);
        return m;
    }

    @Test
    @DisplayName("종목 단위 지문이 바뀐 종목만 골라내고, 실패가 있으면 완료로 적지 않는다")
    void perStockInvalidationAndFailureIsolation() throws Exception {
        Path dir = Files.createTempDirectory("pit-resume");
        PitStore store = new PitStore(dir);
        Set<Long> noFailure = Set.of();

        // --- 1) 정상 적재 ---
        store.writeDate(CCY, D1, fakeValues(), stamp(fp("a", "b", "c"), noFailure));
        assertThat(store.freshnessOf(CCY, D1, fp("a", "b", "c"), IDX).complete())
                .as("정상 적재한 날짜가 완료로 안 읽힌다").isTrue();

        // --- 2) 한 종목만 값이 바뀌었다 (실측 사례: 2026-09-03 14:00 사슬의 APH) ---
        PitStore.Freshness one = store.freshnessOf(CCY, D1, fp("a", "CHANGED", "c"), IDX);
        assertThat(one.usable()).as("종목 하나가 바뀌었다고 날짜를 통째로 버리면 안 된다").isTrue();
        assertThat(one.staleStocks())
                .as("바뀐 종목만 골라야 한다 — 전역 지문이면 여기서 날짜 전체가 죽는다")
                .containsExactly(2L);
        assertThat(one.complete()).isFalse();

        // --- 3) 지수가 바뀌면 날짜 전체가 못 쓴다 ---
        PitStore.Freshness idx = store.freshnessOf(CCY, D1, fp("a", "b", "c"), "index-fp-2");
        assertThat(idx.usable())
                .as("지수는 모집단이 함께 쓴다 — 어느 한 종목의 낡음으로 돌릴 수 없다").isFalse();
        assertThat(idx.unusableReason()).isEqualTo("지수 지문 불일치");

        // --- 4) 매니페스트 없는 데이터는 「끝났다」가 아니다 ---
        Files.copy(dir.resolve(CCY + "-" + D1 + ".csv.gz"),
                dir.resolve(CCY + "-" + D2 + ".csv.gz"), StandardCopyOption.REPLACE_EXISTING);
        assertThat(store.freshnessOf(CCY, D2, fp("a", "b", "c"), IDX).unusableReason())
                .as("죽은 실행의 반쪽 파일이 완료로 읽힌다").isEqualTo("매니페스트 없음");

        // --- 5) 실패가 있으면 완료로 적지 않는다 ---
        store.writeDate(CCY, D2, fakeValues(), stamp(fp("a", "b", "c"), Set.of(7L, 9L)));
        assertThat(store.freshnessOf(CCY, D2, fp("a", "b", "c"), IDX).usable())
                .as("실패가 있는 날짜가 완료로 읽히면 생존분만 든 저장본을 쓰게 된다").isFalse();
        assertThat(store.readManifest(CCY, D2).get("failed")).isEqualTo("7 9");
        assertThat(store.readManifest(CCY, D2).get("complete")).isEqualTo("false");

        // --- 6) 실패를 고쳐 다시 쓰면 완료가 된다 ---
        store.writeDate(CCY, D2, fakeValues(), stamp(fp("a", "b", "c"), noFailure));
        assertThat(store.freshnessOf(CCY, D2, fp("a", "b", "c"), IDX).complete()).isTrue();

        // --- 7) 표지 행·매니페스트가 살아 오는가 ---
        Map<Long, Map<String, BigDecimal>> back = store.readDate(CCY, D2);
        assertThat(back).hasSize(3);
        assertThat(back.get(3L)).as("「계산했으나 값 없음」 표지").isEmpty();
        assertThat(back.get(1L).get("PER")).isEqualByComparingTo(new BigDecimal("12.34567890"));
        assertThat(store.readManifest(CCY, D2).get("fp:2")).isEqualTo("b");

        // --- 8) .tmp가 남지 않는가 ---
        try (var st = Files.list(dir)) {
            assertThat(st.filter(x -> x.getFileName().toString().endsWith(".tmp")).toList())
                    .as(".tmp가 남았다 — 원자적 이동이 안 걸렸다").isEmpty();
        }

        System.out.printf("%n===== 재개·무효화 기제 =====%n");
        System.out.println("종목 단위 무효화 · 지수는 날짜 단위 · 실패 시 미완료 · 반쪽 파일 거부 · .tmp 0개");

        try (var st = Files.list(dir)) {
            for (Path x : st.toList()) {
                Files.deleteIfExists(x);
            }
        }
        Files.deleteIfExists(dir);
    }

    /** 값 계산은 이 질문과 무관하다 — 표지 행 하나를 섞어 그 경로도 함께 태운다. */
    private static Map<Long, Map<String, BigDecimal>> fakeValues() {
        Map<Long, Map<String, BigDecimal>> m = new LinkedHashMap<>();
        m.put(1L, new LinkedHashMap<>(Map.of(
                "PER", new BigDecimal("12.34567890"), "ROE", new BigDecimal("8.5"))));
        m.put(2L, new LinkedHashMap<>(Map.of("PER", new BigDecimal("-3.25"))));
        m.put(3L, new LinkedHashMap<>());
        return m;
    }
}
