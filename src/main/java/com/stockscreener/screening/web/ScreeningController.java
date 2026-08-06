package com.stockscreener.screening.web;

import com.stockscreener.web.CurrentUserId;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 스크리닝 실행.
 *
 * <p>{@code GET}이 아니라 {@code POST}인 이유가 둘이다 — 조건 트리를 쿼리스트링에 담을 수 없고,
 * spec §8이 "스크리닝 실행"을 기록 대상 행위로 지목한다(Phase 6의 인터랙션 로깅).
 *
 * <p>IMPORTANT — CSRF가 켜져 있으므로 조회성 요청인데도 {@code X-XSRF-TOKEN} 헤더가 필요하다.
 * 없으면 403이 나오는데 그 증상은 인증 문제처럼 보인다.
 */
@RestController
@RequestMapping("/api/screening")
public class ScreeningController {

    private final ScreeningService screeningService;

    public ScreeningController(ScreeningService screeningService) {
        this.screeningService = screeningService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ScreeningResponse run(@CurrentUserId long userId,
                                 @Valid @RequestBody ScreeningRequest request) {
        return screeningService.run(request, userId);
    }
}
