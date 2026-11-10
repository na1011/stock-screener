package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.kr.xbrl.KrXbrlStore.Filing;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrXbrlStore.RowVintage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 재무 행 판의 짝 — 국내 관문 켬(K5)은 XBRL 없는 첨부정정 판을 같은 날 다른 판이 있으면 건너뛴다. */
class KrXbrlBoundTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 13);
    private static final RowVintage FY = new RowVintage(2025, "FY", DAY);
    private static final Filing ORIGINAL = new Filing("20260313001191", DAY, 2025, "11011", "OK", 3);
    private static final Filing ATTACHMENT = new Filing("20260313002000", DAY, 2025, "11011", "NO_FILE", null);

    @Test
    @DisplayName("꺼짐은 같은 날 가장 늦은 접수번호(첨부정정 NO_FILE)를, 켬은 원 판을 고른다")
    void skipsTheAttachmentCorrectionWithoutXbrl() {
        assertThat(KrXbrlCollector.bound(FY, List.of(ORIGINAL, ATTACHMENT))).as("꺼짐 — 지금 동작").isEqualTo(ATTACHMENT);
        assertThat(KrXbrlCollector.bound(FY, List.of(ORIGINAL, ATTACHMENT), true)).isEqualTo(ORIGINAL);
    }

    @Test
    @DisplayName("켬이라도 같은 날 판이 NO_FILE 하나뿐이면 그 판이다(짝 없음으로 접지 않는다)")
    void keepsTheOnlyFilingEvenWithoutXbrl() {
        assertThat(KrXbrlCollector.bound(FY, List.of(ATTACHMENT), true)).isEqualTo(ATTACHMENT);
    }

    @Test
    @DisplayName("아직 안 받은 원 판(상태 없음)도 고른다 — 다음 수집이 받는다")
    void picksTheUnfetchedOriginal() {
        Filing unfetched = new Filing("20260313001191", DAY, 2025, "11011", null, null);
        assertThat(KrXbrlCollector.bound(FY, List.of(unfetched, ATTACHMENT), true)).isEqualTo(unfetched);
    }
}
