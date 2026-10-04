package com.stockscreener.indicator;

/**
 * 지표 층 한 번의 쓰기가 <b>실제로 바꾼</b> 행 수 — 쓴 행과 지운 행을 따로 든다.
 *
 * <p>쓴 행만 세면 값이 사라지는 변화가 안 보인다. 계산기가 값을 그만 내거나(걷기 · ADR-0130),
 * 미보정 기업 행위로 억제되거나(ADR-0055), 종목이 그날 모집단을 벗어나면(파생층의 정리 ·
 * ADR-0085) 행이 <b>지워지는데</b>, 그것이 실행 기록에 안 남으면 「매 평일 화해가 아무것도
 * 안 바꿨다」와 「값이 통째로 빠졌다」가 같은 수로 보인다.
 *
 * <p>둘 다 <b>값이 달라진</b> 행만 센다 — UPSERT는 값이 같으면 0행이고({@code IS DISTINCT FROM}),
 * 지우기는 있던 행만 센다. 그래서 같은 입력으로 다시 돌리면 둘 다 0이다.
 */
public record LayerChange(long written, long deleted) {

    public static final LayerChange NONE = new LayerChange(0, 0);

    public LayerChange plus(LayerChange other) {
        return new LayerChange(written + other.written, deleted + other.deleted);
    }
}
