package com.stockscreener.interaction.web;

import com.stockscreener.interaction.InteractionLogReader;

/**
 * 로그 필터의 사용자 선택지.
 *
 * <p>허용목록이 아니라 <b>기록에 실제로 등장한 사용자</b>다. 권한 회수가 기록을 지우지 않으므로
 * 허용목록에서 빠진 사람의 과거 행위도 남아 있고, 그 사람으로 못 좁히면 조회의 절반이 막힌다.
 */
public record InteractionActorResponse(long userId, String email, String name) {

    public static InteractionActorResponse of(InteractionLogReader.Actor actor) {
        return new InteractionActorResponse(actor.userId(), actor.email(), actor.name());
    }
}
