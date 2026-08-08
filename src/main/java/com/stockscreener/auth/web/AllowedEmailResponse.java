package com.stockscreener.auth.web;

import com.stockscreener.auth.AllowedEmailService;

import java.time.Instant;

/**
 * 허용목록 한 줄.
 *
 * @param lastLoginAt 아직 로그인하지 않았으면 {@code null}. <b>정상 상태다</b> — 허용목록에만
 *                    있고 로그인하지 않은 사람이 있을 수 있는 것이 두 테이블을 나눈 이유다
 *                    (ADR-0004). 화면이 이 값으로 "초대했지만 아직 안 들어온 사람"을 구분한다.
 */
public record AllowedEmailResponse(
        long id,
        String email,
        boolean admin,
        String note,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt) {

    public static AllowedEmailResponse of(AllowedEmailService.AllowedEmail row) {
        return new AllowedEmailResponse(
                row.id(), row.email(), row.admin(), row.note(),
                row.createdAt(), row.updatedAt(), row.lastLoginAt());
    }
}
