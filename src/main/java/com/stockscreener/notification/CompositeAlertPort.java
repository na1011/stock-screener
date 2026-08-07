package com.stockscreener.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 구성된 모든 채널로 알림을 팬아웃한다. 소비자가 보는 유일한 {@link AlertPort}다.
 *
 * <p>이 클래스가 지키는 것이 셋이다.
 * <ol>
 *   <li><b>한 채널의 실패가 다른 채널을 막지 않는다.</b> 웹훅이 죽어도 메일은 나간다
 *       (spec §8이 "두 채널 모두"를 요구하는 이유가 이것이다).
 *   <li><b>채널 실패가 호출부를 깨뜨리지 않는다.</b> 메신저 웹훅이 죽었다고 배치가
 *       실패해서는 안 된다({@link AlertPort} 계약).
 *   <li><b>그러나 조용히 사라지지도 않는다.</b> 아무 채널도 받지 않았거나 전부 실패했으면
 *       로그 폴백이 받는다. 이것이 없으면 "알림이 갔다고 생각했는데 아무 데도 가지 않은"
 *       상태가 되고, 그 사실은 원래 알리려던 사건보다 늦게 발견된다.
 * </ol>
 */
public class CompositeAlertPort implements AlertPort {

    private static final Logger log = LoggerFactory.getLogger(CompositeAlertPort.class);

    private final List<AlertChannel> channels;
    private final AlertSuppressor suppressor;

    /**
     * 아무 채널도 받지 못한 사건이 도달하는 마지막 자리.
     *
     * <p>기본 구현을 폴백으로 <b>재사용</b>한다 — 채널이 없을 때 쓰던 것과 같은 물건이다.
     * 여기서 다시 로깅 코드를 쓰면 두 곳의 형식이 갈리고, 로그를 찾는 사람이 두 패턴을
     * 알아야 한다.
     */
    private final AlertPort fallback;

    public CompositeAlertPort(List<AlertChannel> channels, AlertSuppressor suppressor,
                              AlertPort fallback) {
        this.channels = List.copyOf(channels);
        this.suppressor = suppressor;
        this.fallback = fallback;
    }

    @Override
    public void send(AlertEvent event) {
        if (suppressor.suppress(event)) {
            // debug가 아니라 info다. "왜 알림이 안 왔나"를 묻는 사람이 가장 먼저 보는 줄이고,
            // debug에 두면 그 사람은 로그 레벨부터 올려야 한다.
            log.info("최근 같은 알림을 보내 억제한다. key={}, severity={}",
                    event.dedupeKey(), event.severity());
            return;
        }

        int delivered = 0;
        for (AlertChannel channel : channels) {
            if (!channel.accepts(event)) {
                continue;
            }
            try {
                channel.deliver(event);
                delivered++;
            } catch (RuntimeException e) {
                // MUST NOT: 여기서 예외를 다시 던지지 않는다. 알림은 부수 효과이며
                // 채널 하나가 죽었다고 배치나 워치리스트 판정이 실패해서는 안 된다.
                // 그러나 삼키지도 않는다 — error 레벨이라 Phase 6의 Sentry가 이것을 잡는다.
                log.error("알림 채널 전송에 실패했다. channel={}, title={}",
                        channel.name(), event.title(), e);
            }
        }

        if (delivered == 0) {
            log.warn("어느 채널도 이 알림을 보내지 못했다. 로그로 남긴다. channels={}",
                    channels.stream().map(AlertChannel::name).toList());
            fallback.send(event);
        }
    }
}
