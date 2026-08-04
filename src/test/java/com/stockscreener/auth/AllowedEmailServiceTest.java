package com.stockscreener.auth;

import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 허용목록 판정. spec §9의 "허가되지 않은 계정은 접근이 차단된다"를 검증한다.
 *
 * <p>Google 자격증명이 전혀 필요 없다 — 입력이 이메일 문자열이고 출력이 판정 결과이기 때문이다.
 */
@JpaSliceTest
@Import(AllowedEmailService.class)
class AllowedEmailServiceTest {

    @Autowired
    private AllowedEmailService service;

    @Test
    @DisplayName("허용목록에 없는 이메일은 거부한다")
    void deniesUnknownEmail() {
        AccessDecision decision = service.decide("stranger@example.com");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.admin()).isFalse();
    }

    @Test
    @DisplayName("허용목록에 있으면 통과시킨다")
    void allowsListedEmail() {
        service.allow("friend@example.com", false, "지인 A");

        AccessDecision decision = service.decide("friend@example.com");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.admin()).isFalse();
    }

    @Test
    @DisplayName("관리자 권한이 판정에 실린다")
    void carriesAdminFlag() {
        service.allow("owner@example.com", true, "운영자");

        assertThat(service.decide("owner@example.com").admin()).isTrue();
    }

    @Test
    @DisplayName("대소문자를 무시한다 — Google이 돌려주는 이메일의 대소문자는 사용자가 입력한 대로다")
    void ignoresCase() {
        service.allow("Friend@Example.COM", false, null);

        assertThat(service.decide("friend@example.com").allowed()).isTrue();
        assertThat(service.decide("FRIEND@EXAMPLE.COM").allowed()).isTrue();
    }

    @Test
    @DisplayName("앞뒤 공백을 무시한다 — 복붙한 공백 하나가 지인을 조용히 차단하면 안 된다")
    void ignoresSurroundingWhitespace() {
        service.allow("  friend@example.com  ", false, null);

        assertThat(service.decide("friend@example.com").allowed()).isTrue();
        assertThat(service.decide(" friend@example.com ").allowed()).isTrue();
    }

    @Test
    @DisplayName("null·빈 문자열은 거부한다")
    void deniesBlank() {
        assertThat(service.decide(null).allowed()).isFalse();
        assertThat(service.decide("").allowed()).isFalse();
        assertThat(service.decide("   ").allowed()).isFalse();
    }

    @Test
    @DisplayName("권한을 회수하면 즉시 차단된다")
    void revokeBlocksAccess() {
        service.allow("temp@example.com", false, null);
        assertThat(service.decide("temp@example.com").allowed()).isTrue();

        boolean removed = service.revoke("temp@example.com");

        assertThat(removed).isTrue();
        assertThat(service.decide("temp@example.com").allowed()).isFalse();
    }

    @Test
    @DisplayName("같은 이메일을 다시 추가하면 관리자 권한이 갱신된다")
    void reAddUpdatesAdminFlag() {
        service.allow("friend@example.com", false, "지인");
        service.allow("friend@example.com", true, null);

        AccessDecision decision = service.decide("friend@example.com");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.admin()).isTrue();
    }

    @Test
    @DisplayName("허용되지 않은 계정에 관리자 권한을 주는 상태는 표현할 수 없다")
    void deniedCannotBeAdmin() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AccessDecision(false, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
