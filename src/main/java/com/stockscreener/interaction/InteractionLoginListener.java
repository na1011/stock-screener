package com.stockscreener.interaction;

import com.stockscreener.auth.CurrentUserLookup;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * 로그인 성공을 인터랙션으로 남긴다.
 *
 * <p>spec §8이 기록 대상으로 <b>로그인을 가장 먼저</b> 지목한다. 그런데 로그인은 컨트롤러
 * 핸들러가 아니라 필터가 처리하므로 {@code @RecordInteraction}이 붙을 자리가 없다 — 그래서
 * 이 경로만 이벤트로 받는다.
 *
 * <p>이것이 "기능마다 손으로 로깅을 넣는 방식"으로 미끄러지는 것이 아니다. 붙는 자리가
 * <b>인증 하나</b>뿐이고, 새 기능이 늘어난다고 여기에 코드가 붙지 않는다. 새 엔드포인트의
 * 기록 비용은 여전히 애너테이션 한 줄이다.
 *
 * <p>{@code AuthenticationSuccessEvent}가 아니라 {@code Interactive…}를 듣는다. 전자는
 * 세션이 살아 있는 매 요청의 재인증에서도 발화해 <b>로그인 한 번이 수백 건으로 늘어난다.</b>
 * 후자는 실제로 로그인 흐름을 마쳤을 때만 발화한다.
 */
@Component
public class InteractionLoginListener {

    private final InteractionRecorder recorder;
    private final CurrentUserLookup userLookup;

    public InteractionLoginListener(InteractionRecorder recorder, CurrentUserLookup userLookup) {
        this.recorder = recorder;
        this.userLookup = userLookup;
    }

    @EventListener
    public void onLogin(InteractiveAuthenticationSuccessEvent event) {
        // 경로·상태·소요시간을 남기지 않는다. 로그인은 리디렉션 여러 번을 거치는 흐름이라
        // 그 값들이 가리키는 것이 사용자가 한 행위와 대응하지 않는다 — 적으면 오히려 오해를 만든다.
        recorder.record(
                InteractionAction.LOGIN,
                userLookup.resolve(event.getAuthentication()).orElse(null),
                null, null, null, null, null);
    }
}
