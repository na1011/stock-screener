package com.stockscreener.pipeline.batch.web;

import java.time.LocalDate;

/**
 * 잡 기동 파라미터. 본문 전체가 선택이고, 비면 잡마다 정한 기본값을 쓴다.
 *
 * <p>필드를 잡별로 나누지 않고 셋을 한 record에 둔 이유는, 나누면 잡 이름과 본문 타입을
 * 짝지어야 하고 그 짝이 컨트롤러와 프론트 양쪽에 생기기 때문이다. 어느 칸을 그릴지는
 * {@code GET /api/admin/jobs}의 {@code parameters}가 이미 말해준다.
 *
 * @param targetDate 대상 일자 하나를 받는 잡용(일일 갱신·유니버스 리밸런싱).
 * @param from       구간을 받는 잡용(지표 백필).
 * @param to         구간을 받는 잡용(지표 백필).
 */
public record JobRunRequest(LocalDate targetDate, LocalDate from, LocalDate to) {
}
