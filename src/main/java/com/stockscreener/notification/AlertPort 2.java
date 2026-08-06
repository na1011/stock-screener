package com.stockscreener.notification;

/**
 * 운영자·사용자에게 능동적으로 알린다.
 *
 * <p>MUST NOT: 배치 전용 통지 체계를 따로 만들지 않는다({@code data-pipeline.md}, spec §8).
 * 이 포트의 소비자는 셋이 될 예정이다 — 배치 실패(Phase 2), 워치리스트 조건 충족(Phase 6),
 * 애플리케이션 에러(Phase 6). 그래서 타입 이름에 {@code batch}가 들어가지 않고,
 * 패키지도 {@code pipeline} 아래가 아니라 최상위에 있다. 이름에 batch가 붙는 순간
 * Phase 6이 워치리스트용으로 또 하나를 만들게 되고, 그 시점에 규칙이 깨진다.
 *
 * <p>Phase 6이 이메일·메신저 구현체를 등록하면 이 인터페이스를 소비하는 코드는 한 줄도
 * 바뀌지 않는다. 그것이 이 이음매의 목적이다.
 *
 * <p>MUST NOT: 구현체가 알림 전송 실패로 예외를 던져 호출부를 깨뜨리지 않는다. 알림은
 * 부수 효과이며, 메신저 웹훅이 죽었다고 배치가 실패해서는 안 된다. 다만 <b>조용히</b>
 * 버려서도 안 되므로, 전송 실패는 반드시 로그로 남긴다.
 */
public interface AlertPort {

    void send(AlertEvent event);
}
