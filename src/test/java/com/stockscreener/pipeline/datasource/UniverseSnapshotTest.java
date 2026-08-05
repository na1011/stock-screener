package com.stockscreener.pipeline.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 유니버스 스냅샷이 <b>불완전한 목록을 전체로 오인</b>하지 않게 막는지 검증한다.
 *
 * <p>리밸런싱은 "목록에 없으면 제외"로 동작하므로, 여기서 걸러지지 않은 부분 목록은
 * 대량 오탈락이 된다. 그 결과는 스크리닝에서 종목이 사라지는 형태로 나타나 원인을 찾기 어렵다.
 */
class UniverseSnapshotTest {

    @Test
    @DisplayName("빈 목록을 거부한다 — 빈 스냅샷은 '전 종목 제외'를 뜻한다")
    void rejectsEmptyConstituents() {
        assertThatThrownBy(() -> new UniverseSnapshot("SP500", LocalDate.of(2026, 8, 5), List.of(), "seed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("빈 구성종목");
    }

    @Test
    @DisplayName("중복 티커를 거부한다 — 두면 EXCLUDE 제약에서 뒤늦게 터진다")
    void rejectsDuplicateTickers() {
        assertThatThrownBy(() -> new UniverseSnapshot(
                "KOSPI200", LocalDate.of(2026, 8, 5), List.of("005930", "000660", "005930"), "seed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중복");
    }

    @Test
    @DisplayName("소스 이름을 요구한다 — 폴백이 일어났는지 알 수 있어야 한다")
    void requiresSourceName() {
        assertThatThrownBy(() -> new UniverseSnapshot(
                "SP500", LocalDate.of(2026, 8, 5), List.of("AAPL"), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceName");
    }

    @Test
    @DisplayName("정상 스냅샷을 만들고 티커 목록을 불변으로 보관한다")
    void holdsImmutableTickers() {
        UniverseSnapshot snapshot = new UniverseSnapshot(
                "KOSPI200", LocalDate.of(2026, 8, 5), List.of("005930", "000660"), "seed:kospi200");

        assertThat(snapshot.size()).isEqualTo(2);
        assertThat(snapshot.sourceName()).isEqualTo("seed:kospi200");
        assertThatThrownBy(() -> snapshot.tickers().add("035720"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
