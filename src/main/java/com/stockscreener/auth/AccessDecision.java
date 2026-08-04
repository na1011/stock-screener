package com.stockscreener.auth;

/**
 * 허용목록 판정 결과.
 *
 * <p>이메일 하나에 대한 순수한 판정이다. Google이나 OAuth와 아무 관계가 없다 —
 * 그래서 자격증명 없이 완전히 구현하고 테스트할 수 있다.
 *
 * @param allowed 접근이 허용되는가
 * @param admin   관리자 권한이 있는가. {@code allowed}가 거짓이면 항상 거짓이다.
 */
public record AccessDecision(boolean allowed, boolean admin) {

    private static final AccessDecision DENIED = new AccessDecision(false, false);

    public AccessDecision {
        if (!allowed && admin) {
            throw new IllegalArgumentException("허용되지 않은 계정에 관리자 권한을 줄 수 없다");
        }
    }

    public static AccessDecision denied() {
        return DENIED;
    }

    public static AccessDecision allowed(boolean admin) {
        return new AccessDecision(true, admin);
    }
}
