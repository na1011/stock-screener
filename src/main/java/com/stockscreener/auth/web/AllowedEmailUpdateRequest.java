package com.stockscreener.auth.web;

import jakarta.validation.constraints.Size;

/**
 * 허용목록 항목의 수정.
 *
 * <p>이메일 칸이 없는 것이 의도다 — 주소를 바꾸는 것은 다른 사람을 초대하는 것이고, 그때
 * 기존 항목의 생성 시각과 메모가 그대로 따라가면 초대 이력이 어긋난다.
 */
public record AllowedEmailUpdateRequest(
        boolean admin,
        @Size(max = AllowedEmailRequest.ALLOWED_EMAIL_NOTE_MAX_LENGTH) String note) {
}
