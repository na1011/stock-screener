package com.stockscreener.auth.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 허용목록에 새로 넣을 이메일.
 *
 * <p>{@code @Email} 형식 검증을 거는 이유는 오타가 조용히 통과하기 때문이다 — 오타난 주소는
 * 아무도 로그인할 수 없는 행으로 남고, 초대받은 사람은 "권한이 없다"는 화면만 본다.
 * 정규화(소문자·trim)는 서버가 한다(ADR-0004).
 *
 * @param admin 관리자 권한. 기본은 거짓이고, 참으로 주면 그 사람도 이 화면에 들어올 수 있다.
 */
public record AllowedEmailRequest(
        @NotBlank @Email @Size(max = ALLOWED_EMAIL_MAX_LENGTH) String email,
        boolean admin,
        @Size(max = ALLOWED_EMAIL_NOTE_MAX_LENGTH) String note) {

    /** {@code allowed_email.email}의 컬럼 폭과 같다(V4). */
    public static final int ALLOWED_EMAIL_MAX_LENGTH = 255;

    /** {@code allowed_email.note}의 컬럼 폭과 같다(V4). */
    public static final int ALLOWED_EMAIL_NOTE_MAX_LENGTH = 200;
}
