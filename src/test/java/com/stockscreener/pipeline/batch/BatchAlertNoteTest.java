package com.stockscreener.pipeline.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「부분 실패」 알림 본문에 싣는 note의 길이.
 *
 * <p>웹훅이 본문 뒤를 2,000자에서 자르는데 실행 id가 본문 뒤에 그려진다 — 무효화 종목 목록을 통째로 싣는 note(재무 refill ·
 * javadoc 574종목 ≈ 4KB)를 그대로 실으면 실행을 찾는 열쇠가 잘린다.
 */
class BatchAlertNoteTest {

    @Test
    @DisplayName("짧은 note는 그대로 싣는다")
    void shortNoteIsAppended() {
        assertThat(BatchExecutionContext.noteForAlert("파생층 실패 거래일 1(2026-07-02)"))
                .isEqualTo(" — 파생층 실패 거래일 1(2026-07-02)");
    }

    @Test
    @DisplayName("IMPORTANT — 긴 note는 앞만 싣고 전체는 실행 기록에 있다고 적는다 — 실행 id가 잘리지 않게")
    void longNoteIsTruncated() {
        String note = "가".repeat(4_000);

        String appended = BatchExecutionContext.noteForAlert(note);

        assertThat(appended.length()).isLessThan(400);
        assertThat(appended).endsWith("…(전체는 실행 기록 note)");
    }

    @Test
    @DisplayName("note가 없으면 아무것도 안 붙인다")
    void blankNoteAddsNothing() {
        assertThat(BatchExecutionContext.noteForAlert(null)).isEmpty();
        assertThat(BatchExecutionContext.noteForAlert(" ")).isEmpty();
    }
}
