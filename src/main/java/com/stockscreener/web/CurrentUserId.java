package com.stockscreener.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 현재 로그인한 사용자의 {@code app_user.id}를 주입한다.
 *
 * <p>컨트롤러가 {@code SecurityContextHolder}를 직접 만지지 않게 하는 것이 목적이다.
 * 직접 만지면 주체 타입·널 처리·미인증 판단이 엔드포인트마다 복제되고, 한 곳이 어긋나면
 * <b>소유자가 잘못 저장된다</b> — 그 오류는 다른 사용자의 화면이 보일 때까지 드러나지 않는다.
 *
 * <p>Phase 6의 인터랙션 로깅 애스펙트는 "누가"를 SecurityContext에서 스스로 얻으므로,
 * 이 파라미터가 있어도 컨트롤러 시그니처를 다시 고칠 일이 없다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface CurrentUserId {
}
