package com.stockscreener.auth;

import com.stockscreener.notification.AlertRecipientDirectory;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link AlertRecipientDirectory}를 {@code app_user}로 구현한다.
 *
 * <p>{@code auth}에 두는 이유는 이 패키지가 {@code app_user}를 아는 유일한 자리이기
 * 때문이다. 반대로 {@code notification}이 {@link AppUserService}를 직접 부르면 패키지
 * 의존이 순환한다 — 인터페이스 쪽 주석에 그 근거가 있다.
 *
 * <p>허용목록에서 제거된 사용자에게도 주소를 돌려준다. {@code allowed_email}과
 * {@code app_user} 사이에 FK가 없는 것이 그 설계고(V4), 워치리스트 항목도 함께 남아 있다.
 * 접근 차단은 로그인 경로에서 이미 이뤄지므로 여기서 다시 판정하지 않는다.
 */
@Component
public class AppUserAlertRecipients implements AlertRecipientDirectory {

    private final AppUserService appUserService;

    public AppUserAlertRecipients(AppUserService appUserService) {
        this.appUserService = appUserService;
    }

    @Override
    public Optional<String> emailOf(long userId) {
        return appUserService.findProfileById(userId)
                .map(AppUserService.UserProfile::email);
    }
}
