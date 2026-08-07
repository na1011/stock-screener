package com.stockscreener.auth.web;

/**
 * 로그인한 사용자. SPA가 기동 직후 이것을 물어 세션이 살아 있는지 판별한다.
 *
 * <p>이 엔드포인트가 필요한 이유는 인증이 세션 쿠키 기반이기 때문이다 — 쿠키는 HttpOnly라
 * 자바스크립트가 읽을 수 없고, 읽을 수 있더라도 서버가 그 세션을 아직 유효하다고 보는지는
 * 물어봐야만 안다. 익명이면 다른 API와 똑같이 401이 오고, 그것이 곧 "로그인 화면을 그려라"다.
 *
 * @param admin 관리자 영역을 그릴지 여부. <b>세션의 권한</b>에서 온다 —
 *              {@code app_user.is_admin}은 로그인 시점의 사본이라 권한을 회수해도 재로그인
 *              전까지 참으로 남고, 그러면 화면은 메뉴를 그리는데 요청은 403이 된다.
 * @param name  Google이 이름을 주지 않으면 null이다. 화면이 이메일로 대체한다.
 */
public record MeResponse(long userId, String email, String name, boolean admin) {
}
