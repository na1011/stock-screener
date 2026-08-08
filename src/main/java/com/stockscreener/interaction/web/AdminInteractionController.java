package com.stockscreener.interaction.web;

import com.stockscreener.interaction.InteractionAction;
import com.stockscreener.interaction.InteractionLogReader;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 인터랙션 로그 조회 (spec §4 "누가 언제 어떤 주요 행위를 했는지 조회·필터링").
 *
 * <p>조회 자체에는 {@code @RecordInteraction}을 붙이지 않는다. 붙이면 관리자가 로그를 볼
 * 때마다 로그가 늘어나 <b>자기 자신으로 목록을 채운다</b> — 정작 보려던 사용자 행위가 그
 * 사이에 묻힌다.
 */
@RestController
@RequestMapping("/api/admin/interactions")
public class AdminInteractionController {

    private final InteractionLogReader reader;

    public AdminInteractionController(InteractionLogReader reader) {
        this.reader = reader;
    }

    @GetMapping
    public List<InteractionLogResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) InteractionAction action,
            @RequestParam(required = false) Integer limit) {

        return reader.list(from, to, userId, action, limit).stream()
                .map(InteractionLogResponse::of)
                .toList();
    }

    /** 필터 선택지. 기록에 실제로 등장한 사용자만 준다. */
    @GetMapping("/actors")
    public List<InteractionActorResponse> actors() {
        return reader.actors().stream().map(InteractionActorResponse::of).toList();
    }
}
