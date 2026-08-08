package com.stockscreener.interaction;

/**
 * 기록 대상 행위.
 *
 * <p>spec §8은 "모든 화면 조작을 다 남길 필요는 없으며, 기록할 가치가 있는 행위만 선별적으로
 * 남기는 구조"를 요구한다. 그 선별의 정본이 이 enum이고, 실제로 어느 엔드포인트에 붙는지는
 * {@link RecordInteraction}이 표시한다.
 *
 * <p>MUST NOT: 단순 조회를 여기 더하지 않는다. 목록·상세·검색은 화면을 여는 것만으로 발생해
 * 로그를 채우고, 그러면 정작 의도가 드러나는 행위가 그 사이에 묻힌다. 조회를 남겨야 할 이유가
 * 생기면 그때 이유와 함께 더한다.
 *
 * <p>MUST: 값을 더하면 {@code src/test/resources/contract/wire-contract.json}에도 함께
 * 적는다(ADR-0020). 관리자 화면의 행위 필터가 이 목록을 거울로 들고 있어, 어긋나면 새 행위가
 * <b>필터에서 조용히 빠진다</b>.
 */
public enum InteractionAction {

    /** 로그인 성공. 엔드포인트가 아니라 인증 이벤트에서 온다({@code InteractionLoginListener}). */
    LOGIN,

    /** 스크리닝 실행. 이 서비스에서 사용자의 의도가 가장 뚜렷하게 드러나는 행위다. */
    SCREENING_RUN,

    SCREEN_CREATE,
    SCREEN_UPDATE,
    SCREEN_DELETE,

    WATCHLIST_ADD,
    WATCHLIST_UPDATE,
    WATCHLIST_REMOVE,

    /** 관리자가 배치를 손으로 띄웠다. 쿼터를 태우는 행위라 누가 눌렀는지 남아야 한다. */
    ADMIN_JOB_RUN,
    /** 관리자가 실패한 배치를 재실행했다. */
    ADMIN_BATCH_RETRY,

    ADMIN_ALLOWLIST_ADD,
    ADMIN_ALLOWLIST_UPDATE,
    ADMIN_ALLOWLIST_REMOVE
}
