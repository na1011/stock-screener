package com.stockscreener.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * 접근 제어. 관리자 영역을 별도 애플리케이션이 아니라 <b>권한으로</b> 분리한다
 * ({@code backend.md} — 인증·배포를 이중으로 관리하는 비용이 이 프로젝트 규모에 맞지 않는다).
 *
 * <p>OAuth 클라이언트 자격증명은 환경변수로만 주입한다. 자격증명이 없으면 OAuth 로그인만
 * 동작하지 않고 나머지는 그대로 뜬다 — 그래서 Google Console 작업을 기다리지 않고
 * 인가 로직을 완성·검증할 수 있다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AllowlistOidcUserService oidcUserService;

    public SecurityConfig(AllowlistOidcUserService oidcUserService) {
        this.oidcUserService = oidcUserService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // IMPORTANT — 마지막이 permitAll이다. 보호할 것은 반드시 그 앞에 명시해야 한다.
                // 뒤집힌 이유는 ADR-0018에 있다: 정적 셸(index.html·해시된 자원)이 익명에게
                // 열려야 로그인하지 않은 사용자가 로그인 버튼이라도 볼 수 있다. 서버가 그리는
                // 로그인 페이지가 없으므로(아래 401 방침) 셸이 닫히면 화면 자체가 없다.
                // 데이터는 전부 /api/** 뒤에 있고, 셸은 공개해도 아무것도 새지 않는다.
                .authorizeHttpRequests(auth -> auth
                        // 관리자 영역. 권한 없는 사용자에게는 존재 자체가 드러나지 않아야 한다.
                        .requestMatchers("/api/admin/**").hasAuthority(AllowlistOidcUserService.ROLE_ADMIN)
                        // actuator는 아직 클래스패스에 없다(Phase 6). 미리 잠가 두는 이유는
                        // 도입 시점에 이 permitAll이 조용히 구멍이 되기 때문이다.
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").hasAuthority(AllowlistOidcUserService.ROLE_ADMIN)
                        .requestMatchers("/api/**").authenticated()
                        // OAuth 흐름의 진입·콜백. anyRequest가 덮지만 명시해 둔다 —
                        // 나중에 기본값을 authenticated로 되돌리면 이 둘이 먼저 깨진다.
                        .requestMatchers("/login/**", "/oauth2/**", "/error").permitAll()
                        .anyRequest().permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/"))
                // API 요청에는 로그인 페이지로 리다이렉트하지 않고 401을 준다.
                // 프론트가 그것을 보고 재인증을 유도한다.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // 세션 쿠키 인증이므로 CSRF 방어를 켠 채로 둔다. 끄지 않는다 —
                // 로그인한 사용자가 악성 페이지를 열었을 때 그 페이지가 이 서비스로
                // 상태 변경 요청을 보낼 수 있게 되고, 조건 저장·워치리스트·관리자 기능이
                // 모두 그 대상이 된다.
                // 토큰을 쿠키로 내보내 SPA가 읽어 헤더에 실을 수 있게 한다(Vue 기본 동작과 맞는다).
                //
                // IMPORTANT — 핸들러의 지연 로딩을 끈다(setCsrfRequestAttributeName(null)).
                // 기본 동작은 요청 처리 중 아무도 토큰을 건드리지 않으면 쿠키를 내려보내지 않는데,
                // 우리 API는 대부분 토큰을 읽을 일이 없는 조회다. 그러면 SPA가 처음 띄운 뒤
                // 보내는 첫 POST가 토큰이 없어 403으로 실패하고, 그 증상은 인증 버그처럼 보인다.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(eagerCsrfTokenHandler()));

        // 허용목록 판정을 OAuth 로그인 흐름에 끼워 넣는다. 자격증명이 발급되지 않았어도
        // 등록 자체는 자리표시자로 존재하므로(application.yaml 참조) 여기서 분기하지 않는다.
        //
        // loginPage("/login")을 지정하는 이유는 화면을 SPA가 그리기 때문이다. 지정하지 않으면
        // DefaultLoginPageGeneratingFilter가 등록되어 /login을 가로채고 Spring 기본 로그인
        // 페이지를 그린다 — SPA 라우트가 그 뒤에 가려진다. 지정하면 생성이 꺼진다.
        //
        // 실패는 기본값대로 /login?error로 온다. SPA는 거기서 **사유를 밝히지 않는** 일반
        // 문구만 띄운다 — 허용목록의 존재 자체가 운영 정보다(ADR-0004).
        http.oauth2Login(oauth -> oauth
                .loginPage("/login")
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService)));

        return http.build();
    }

    /**
     * 매 요청마다 CSRF 토큰을 실제로 만들어 쿠키가 항상 내려가게 한다.
     *
     * <p>{@code setCsrfRequestAttributeName(null)}이 지연 로딩을 끄는 스위치다. 이름이
     * 동작을 전혀 설명하지 않아 지우기 쉬우므로 별도 메서드로 빼 두고 이유를 남긴다.
     */
    private static CsrfTokenRequestAttributeHandler eagerCsrfTokenHandler() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }
}
