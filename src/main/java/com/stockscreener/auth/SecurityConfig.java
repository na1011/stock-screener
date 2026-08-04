package com.stockscreener.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

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
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/login/**", "/oauth2/**", "/error").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // 관리자 영역. 권한 없는 사용자에게는 존재 자체가 드러나지 않아야 한다.
                        .requestMatchers("/api/admin/**").hasAuthority(AllowlistOidcUserService.ROLE_ADMIN)
                        .anyRequest().authenticated())
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
                .csrf(csrf -> csrf.csrfTokenRepository(
                        org.springframework.security.web.csrf.CookieCsrfTokenRepository.withHttpOnlyFalse()));

        // 허용목록 판정을 OAuth 로그인 흐름에 끼워 넣는다. 자격증명이 발급되지 않았어도
        // 등록 자체는 자리표시자로 존재하므로(application.yaml 참조) 여기서 분기하지 않는다.
        http.oauth2Login(oauth -> oauth
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService)));

        return http.build();
    }
}
