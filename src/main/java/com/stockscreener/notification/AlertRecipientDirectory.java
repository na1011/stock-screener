package com.stockscreener.notification;

import java.util.Optional;

/**
 * 사용자 id로 통지 주소를 찾는다. 워치리스트 알림이 이것을 쓴다.
 *
 * <p>{@code notification}이 {@code auth}를 직접 부르지 않게 하려고 둔 이음매다. 방향이
 * 반대여야 하는 이유는 <b>소비자가 셋</b>이기 때문이다 — 배치·워치리스트·(Phase 6 뒤의)
 * 관리자가 모두 알림을 부르고, 그중 워치리스트는 {@code auth}에도 걸린다. 알림 쪽이
 * {@code auth}를 알면 패키지 의존이 순환한다.
 *
 * <p>구현은 {@code auth}에 있다. 그쪽이 {@code app_user}를 아는 유일한 자리다.
 */
public interface AlertRecipientDirectory {

    /**
     * 이 사용자의 이메일 주소.
     *
     * @return 사용자가 없으면 비어 있음. 호출자가 그것을 <b>오류로</b> 다룬다 — 조용히
     *         건너뛰면 워치리스트 알림이 아무 흔적 없이 사라진다.
     */
    Optional<String> emailOf(long userId);
}
