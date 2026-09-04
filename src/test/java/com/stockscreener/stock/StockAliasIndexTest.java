package com.stockscreener.stock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 별칭 인덱스의 파싱과 정규화를 잠근다. */
class StockAliasIndexTest {

    private static final String FIXTURE = "fixtures/stock-alias/test-alias.csv";

    @Test
    @DisplayName("주석과 헤더를 건너뛰고 한 종목의 별칭 여럿을 담는다")
    void readsAliasesSkippingCommentsAndHeader() {
        StockAliasIndex index = new StockAliasIndex(FIXTURE);

        // 첫 줄이 주석이라 「첫 줄을 무조건 버린다」로 짜면 진짜 헤더가 데이터로 들어간다.
        assertThat(index.lookup("market_code")).isEmpty();
        assertThat(index.size()).as("별칭 넷 — AAA와 BBB가 각각 둘을 갖는다").isEqualTo(4);
        assertThat(index.lookup("비비비"))
                .containsExactly(new StockAliasIndex.MarketTicker("SRCHMKT", "BBB"));
    }

    @Test
    @DisplayName("NFD로 친 검색어도 같은 별칭에 맞는다 — 안 맞으면 조용히 0건이다")
    void matchesRegardlessOfUnicodeNormalization() {
        StockAliasIndex index = new StockAliasIndex(FIXTURE);
        String nfd = Normalizer.normalize("비비비", Normalizer.Form.NFD);

        assertThat(nfd).as("NFD는 NFC와 다른 문자열이다").isNotEqualTo("비비비");
        assertThat(index.match(nfd).prefix())
                .containsExactly(new StockAliasIndex.MarketTicker("SRCHMKT", "BBB"));
    }

    @Test
    @DisplayName("접두와 포함을 갈라 낸다 — 검색의 정렬 등급이 둘을 다르게 다룬다")
    void separatesPrefixFromContains() {
        StockAliasIndex index = new StockAliasIndex(FIXTURE);

        StockAliasIndex.Matches byPrefix = index.match("비비비");
        assertThat(byPrefix.prefix()).isNotEmpty();

        StockAliasIndex.Matches byContains = index.match("코퍼레이션");
        assertThat(byContains.prefix()).as("접두가 아니다").isEmpty();
        assertThat(byContains.contains())
                .containsExactly(new StockAliasIndex.MarketTicker("SRCHMKT", "BBB"));
    }

    @Test
    @DisplayName("빈 검색어는 아무것도 안 낸다")
    void emptyQueryMatchesNothing() {
        assertThat(new StockAliasIndex(FIXTURE).match("   ").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("파일이 없으면 조용히 비우지 않고 실패한다")
    void failsLoudlyWhenFileIsMissing() {
        // 별칭이 없으면 한글 검색이 통째로 안 되는데, 그것은 화면에서 「일치하는 종목이
        // 없다」와 구별되지 않는다.
        assertThatThrownBy(() -> new StockAliasIndex("stock-alias/does-not-exist.csv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("별칭 파일이 없다");
    }
}
