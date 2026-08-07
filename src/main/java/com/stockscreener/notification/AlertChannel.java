package com.stockscreener.notification;

/**
 * 알림이 실제로 나가는 경로 하나. 메신저 웹훅과 이메일이 각각 하나다.
 *
 * <p>{@link AlertPort}와 <b>일부러 다른 타입</b>이다. 포트는 소비자(배치·워치리스트)가 보는
 * 단일 진입점이고, 채널은 그 뒤에서 팬아웃되는 대상이다. 하나로 합치면 소비자가 채널을 골라
 * 부를 수 있게 되고, 그 순간 "배치는 메신저로, 워치리스트는 메일로" 같은 분기가 호출부로
 * 흩어진다 — spec §8이 금지한 통지 체계의 중복이 그렇게 시작된다.
 *
 * <p>분리의 실질적 이득은 <b>예외 계약이 갈린다</b>는 것이다. {@code AlertPort}는 전송 실패로
 * 호출부를 깨뜨려서는 안 되지만, 채널은 <b>던져도 된다</b> — {@link CompositeAlertPort}가
 * 그것을 잡아 로그로 남기고 다른 채널을 계속 시도한다. 채널마다 try/catch를 반복하면
 * 언젠가 하나가 그것을 빠뜨리고, 그 채널의 실패는 배치를 통째로 죽인다.
 */
public interface AlertChannel {

    /** 로그에 남길 채널 이름. 어느 채널이 실패했는지 구분하는 데 쓴다. */
    String name();

    /**
     * 이 채널이 쓸 수 있는 상태인가. 자격증명이 없으면 거짓이다.
     *
     * <p>빈 등록 자체를 조건부로 만들지 않고 여기서 판정하는 이유는 {@code AlertConfig}에 있다 —
     * 활성 채널이 하나도 없을 때 무엇을 쓸지를 <b>같은 자리에서</b> 정해야 알림이 증발하지 않는다.
     */
    boolean isConfigured();

    /**
     * 이 사건을 이 채널로 보낼 것인가. 심각도 임계와 수신 대상 판정이 여기 있다.
     *
     * <p>모든 알림을 두 채널로 보내면 곧 아무도 보지 않게 된다({@link AlertSeverity} 주석).
     */
    boolean accepts(AlertEvent event);

    /**
     * 전송한다.
     *
     * <p>실패하면 <b>던진다.</b> 여기서 삼키면 {@link CompositeAlertPort}가 "아무 채널도 받지
     * 않았다"를 알 수 없고, 그러면 마지막 방어선인 로그 폴백이 동작하지 않는다.
     */
    void deliver(AlertEvent event);
}
