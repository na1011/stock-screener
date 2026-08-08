package com.stockscreener.interaction.web;

import com.stockscreener.interaction.InteractionAction;
import com.stockscreener.interaction.InteractionLogReader;

import java.time.Instant;
import java.util.Map;

/**
 * 인터랙션 기록 한 줄.
 *
 * @param userId     사용자를 특정하지 못한 기록은 {@code null}이다. 행위 자체는 남는다.
 * @param userEmail  이메일을 함께 주는 이유는 화면이 "누가"를 말할 수 있어야 하기 때문이다.
 *                   id만 주면 관리자가 계정 화면을 따로 열어 대조해야 한다.
 * @param statusCode 실패한 시도도 남는다. 성공만 남기면 사용자가 겪은 마찰이 안 보인다.
 * @param detail     URI 경로 변수와 지정한 쿼리 파라미터. 요청 본문은 담지 않는다(ADR-0028).
 */
public record InteractionLogResponse(
        long id,
        Long userId,
        String userEmail,
        String userName,
        InteractionAction action,
        String httpMethod,
        String path,
        Integer statusCode,
        Integer durationMs,
        Map<String, String> detail,
        Instant occurredAt) {

    public static InteractionLogResponse of(InteractionLogReader.Entry entry) {
        return new InteractionLogResponse(
                entry.id(), entry.userId(), entry.userEmail(), entry.userName(),
                entry.action(), entry.httpMethod(), entry.path(),
                entry.statusCode(), entry.durationMs(), entry.detail(), entry.occurredAt());
    }
}
