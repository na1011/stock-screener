package com.stockscreener.pipeline.batch;

/**
 * 손 쓰기 잡을 언제 돌려도 되는지 알림이 사람에게 건네는 문 — 문장의 정본은 여기 하나다.
 *
 * <p>문은 시각이 아니라 상태다(ADR-0175 · 사용자 결정 「규칙 유지 + 잠금은 백업」): {@code ops/crons-done.sh}가 0이고
 * 적재 JVM이 없을 때 시작해, 그 출력의 다음 명목 발화 전에 끝낸다. 평일 크론은 직렬이라 앞 잡이 길면 뒤가 밀린다 —
 * 2026-10-19에는 G 적재와 후보 소비가 14:20 화해를 16:33으로 밀어 「13:30~15:40 밖」 안내가 사슬 도중을 가리켰다.
 * 끝 조항이 없으면 {@code crons-done} 0(다음 발화까지 5분 넘게 남음)만으로 13:20 · 토 19:30 시작이 허용된다.
 */
final class ManualWriteDoor {

    /** 「…돌린다」 앞에 붙는 조건 구. */
    static final String WHEN = "ops/crons-done.sh가 0이고 적재 JVM이 없을 때 시작해 그 출력의 다음 명목 발화 전에 끝낼 수 있으면"
            + "(평일 13:30~15:40은 그와 무관하게 피한다)";

    private ManualWriteDoor() {
    }
}
