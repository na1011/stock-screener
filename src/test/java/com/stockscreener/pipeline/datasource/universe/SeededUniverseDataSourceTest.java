package com.stockscreener.pipeline.datasource.universe;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.UniverseSnapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시드 CSV의 <b>헤더가 데이터로 새지 않는지</b>를 고정한다.
 *
 * <p>파서는 원래 <b>무조건 첫 줄</b>을 헤더로 버렸다. 그런데 실제 파일 셋(코스피200·코스닥150·
 * S&amp;P500)은 첫 줄이 전부 주석({@code #})이고 진짜 헤더 {@code ticker,name}은 9~11번째 줄에
 * 있다. 주석은 뒤에서 {@code startsWith("#")}로 걸리지만 <b>헤더는 안 걸려</b> 티커
 * {@code "ticker"}가 유니버스 구성종목이 됐다.
 *
 * <p><b>지금 이 결함이 데이터에 안 보이는 이유는 고쳐졌기 때문이 아니다</b> — 이 소스가 답하는
 * {@code KOSPI200}·{@code KOSDAQ150}·{@code SP500} 셋이 전부 <b>비활성이고 편입 0건</b>이라
 * 실행되지 않았을 뿐이다. 되살리는 날 조용히 새므로 여기서 잠근다.
 */
class SeededUniverseDataSourceTest {

    private final SeededUniverseDataSource source = new SeededUniverseDataSource();

    @Test
    @DisplayName("주석 뒤의 헤더를 구성종목으로 넣지 않는다")
    void doesNotTreatHeaderAsConstituent() throws DataSourceException {
        for (String code : source.supportedUniverseCodes()) {
            UniverseSnapshot snapshot = source.fetchConstituents(code, LocalDate.of(2026, 9, 4));

            assertThat(snapshot.tickers())
                    .as("%s의 구성종목에 헤더 낱말이 섞였다", code)
                    .doesNotContain("ticker", "name");
            // 모집단을 함께 단언한다 — 「없다」와 「잴 것이 없었다」는 다르다.
            assertThat(snapshot.tickers())
                    .as("%s가 한 종목도 안 냈다 — 헤더 검사가 무의미해진다", code)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("첫 데이터 줄은 헤더이므로 버린다 — 실제 파일의 첫 종목이 남는다")
    void keepsFirstConstituentAfterHeader() throws DataSourceException {
        UniverseSnapshot kospi = source.fetchConstituents("KOSPI200", LocalDate.of(2026, 9, 4));

        // kospi200.csv는 주석 → `ticker,name` → `005930,삼성전자` 순이다.
        assertThat(kospi.tickers()).first().isEqualTo("005930");
    }
}
