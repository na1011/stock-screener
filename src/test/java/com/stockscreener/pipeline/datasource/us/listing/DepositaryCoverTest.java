package com.stockscreener.pipeline.datasource.us.listing;

import com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 표지 꼴은 2026-10-22 SEC 표지 탐침에서 옮겼다(탐색 {@code ADS-BASIS-PLAN-1022.md} §1 · §2 v3). */
class DepositaryCoverTest {

    @Test
    @DisplayName("ADS 비율이 1이 아니면 뺀다 — HDB 꼴(이름이 평범해도 표지가 말한다)")
    void excludesRatioOtherThanOne() {
        ClassFacts cover = cover(line("m1", "HDB", "American Depositary Shares, each representing three Equity Shares, Par value Rs. 1.0 per share", null));

        DepositaryCover.Verdict v = DepositaryCover.judge(cover, "HDB", false);

        assertThat(v.excluded()).isTrue();
        assertThat(v.reason()).contains("3");
    }

    @Test
    @DisplayName("비율 1이면 남긴다 — TEVA 꼴(값이 옳다)")
    void keepsRatioOne() {
        ClassFacts cover = cover(line("-", "TEVA", "American Depositary Shares, each representing one Ordinary Share", null));

        assertThat(DepositaryCover.judge(cover, "TEVA", false).outcome()).isEqualTo(DepositaryCover.Outcome.RATIO_ONE);
    }

    @Test
    @DisplayName("기호가 보통주 줄과 ADS 줄에 함께 달리면 ADS다 — TAL 꼴(보통주 줄이 먼저 와도)")
    void anySymbolLineThatIsDepositaryCounts() {
        ClassFacts cover = cover(
                line("ordinary", "TAL", "Class A common shares, par value $0.001 per share", null),
                line("ads", "TAL", "American Depositary Shares, each three representing one Class A common share", "0.33"));

        DepositaryCover.Verdict v = DepositaryCover.judge(cover, "TAL", false);

        assertThat(v.excluded()).as("1 ADS = 1/3주 — 1이 아니다").isTrue();
    }

    @Test
    @DisplayName("ADS인데 비율을 표지가 안 실으면 뺀다 — BZ 꼴")
    void excludesUnknownRatio() {
        ClassFacts cover = cover(
                line("ads", "BZ", "American depositary shares", null),
                line("a", null, "Class A ordinary shares, par value US$0.0001 per share", null));

        assertThat(DepositaryCover.judge(cover, "BZ", true).excluded()).isTrue();
    }

    @Test
    @DisplayName("이름 비율과 태그가 다르면 모른다 — NTES 꼴(태그가 옛 비율 25)")
    void excludesWhenTitleAndTagDisagree() {
        ClassFacts cover = cover(line("-", "NTES",
                "American Depositary Shares, each representing 5 ordinary shares, par value US$0.0001 per share", "25"));

        DepositaryCover.Verdict v = DepositaryCover.judge(cover, "NTES", false);

        assertThat(v.excluded()).isTrue();
        assertThat(v.reason()).contains("갈린다");
    }

    @Test
    @DisplayName("ADS 줄이 없는 해외 발행사는 남긴다 — 보통주 직상장(CHKP 꼴)")
    void keepsNonDepositary() {
        ClassFacts cover = cover(line("-", "CHKP", "Ordinary shares, NIS 0.01 nominal value", null));

        assertThat(DepositaryCover.judge(cover, "CHKP", false).outcome()).isEqualTo(DepositaryCover.Outcome.NOT_DEPOSITARY);
    }

    @Test
    @DisplayName("이름이 예탁증권인데 표지가 ADS 줄을 안 보이면 뺀다 — EDU 꼴(ADS 줄 이름 자리에 거래소 이름)")
    void excludesWhenNameSaysDepositaryButCoverDoesNot() {
        ClassFacts cover = cover(line("-", "EDU", "Name of Exchange on Which Registered", null));

        assertThat(DepositaryCover.judge(cover, "EDU", true).excluded()).isTrue();
        assertThat(DepositaryCover.judge(cover, "EDU", false).outcome()).isEqualTo(DepositaryCover.Outcome.NOT_DEPOSITARY);
    }

    @Test
    @DisplayName("기호 줄이 없으면 표지 전체로 본다 — DSGX 꼴(표지 기호 오타 DGSX) · 점과 하이픈은 같다")
    void fallsBackToWholeCoverWhenSymbolIsMissing() {
        ClassFacts typo = cover(line("-", "DGSX", "Common Shares, no par value", null));
        ClassFacts dotted = cover(line("-", "BRK-B", "American Depositary Shares, each representing two ordinary shares", null));

        assertThat(DepositaryCover.judge(typo, "DSGX", false).outcome()).isEqualTo(DepositaryCover.Outcome.NOT_DEPOSITARY);
        assertThat(DepositaryCover.judge(dotted, "BRK.B", false).excluded()).isTrue();
    }

    private record Line(String member, String symbol, String title, String ratioTag) {
    }

    private static Line line(String member, String symbol, String title, String ratioTag) {
        return new Line(member, symbol, title, ratioTag);
    }

    private static ClassFacts cover(Line... lines) {
        Map<String, LinkedHashMap<String, String>> cover = new LinkedHashMap<>();
        for (Line l : lines) {
            if (l.symbol() != null) {
                cover.computeIfAbsent(ClassFactReader.TRADING_SYMBOL, k -> new LinkedHashMap<>()).put(l.member(), l.symbol());
            }
            cover.computeIfAbsent(ClassFactReader.SECURITY_TITLE, k -> new LinkedHashMap<>()).put(l.member(), l.title());
            if (l.ratioTag() != null) {
                cover.computeIfAbsent(ClassFactReader.DEPOSITORY_RATIO, k -> new LinkedHashMap<>()).put(l.member(), l.ratioTag());
            }
        }
        return new ClassFacts(new LinkedHashMap<>(), cover, "20-F");
    }
}
