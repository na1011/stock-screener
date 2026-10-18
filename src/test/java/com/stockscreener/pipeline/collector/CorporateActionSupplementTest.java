package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 원장 보충 파일(ADR-0181) — 근거 필수 · 닻으로만 붙는다 · 분사 꼴은 자식 (기호, asof)를 든다. */
class CorporateActionSupplementTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 8, 28);

    @Test
    @DisplayName("실제 파일 — 세 줄이 닻으로 붙고, UHAL은 자식 UHALB@2022-11-15 비율 9 · 주식수 배수 1의 분사 꼴이다")
    void realFileLoads() {
        CorporateActionSupplement supplement = new CorporateActionSupplement();
        assertThat(supplement.size()).isEqualTo(3);

        List<CorporateAction> uhal = supplement.forSecurity(anchored("UHAL"));
        assertThat(uhal).singleElement().satisfies(a -> {
            assertThat(a.effectiveDate()).isEqualTo(LocalDate.of(2022, 11, 10));
            assertThat(a.ratio()).as("Series N은 다른 클래스 — 보통주 수는 그대로").isEqualByComparingTo("1");
            assertThat(a.distribution().children()).singleElement().satisfies(c -> {
                assertThat(c.symbol()).isEqualTo("UHALB");
                assertThat(c.asOf()).isEqualTo(LocalDate.of(2022, 11, 15));
                assertThat(c.rate()).isEqualByComparingTo("9");
            });
            assertThat(a.sourceForm()).isEqualTo("8-K 0000004457-22-000100");
        });
        assertThat(supplement.forSecurity(anchored("CORZ")).get(0).distribution().children()).hasSize(2);
    }

    @Test
    @DisplayName("대조군 — 닻이 없거나 다른 행에는 안 붙는다")
    void onlyAnchoredRows() {
        CorporateActionSupplement supplement = new CorporateActionSupplement();
        assertThat(supplement.forSecurity(new SecurityRef(1L, "US", "UHAL"))).as("닻 없음").isEmpty();
        assertThat(supplement.forSecurity(anchored("AAPL"))).isEmpty();
    }

    @Test
    @DisplayName("없는 파일은 기동을 세운다")
    void missingFileFails() {
        assertThatThrownBy(() -> CorporateActionSupplement.load("corporate-action/missing.tsv"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static SecurityRef anchored(String symbol) {
        return new SecurityRef(1L, "US", symbol, new SecurityRef.Identity(symbol, ANCHOR, null, null, null, List.of()));
    }
}
