package com.stockscreener.pipeline.batch.web;

import com.stockscreener.pipeline.batch.BatchExecutionReader;

/**
 * 같은 종류·같은 소스의 실패를 묶은 것.
 *
 * <p>350행짜리 실패 목록을 눈으로 세지 않고 "무엇이 깨졌나"에 답하기 위해 있다. 대응이
 * 종류마다 달라서 이 구분이 곧 다음 행동을 정한다 — {@code RATE_LIMITED}는 기다리면 되고,
 * {@code MALFORMED}가 대량이면 어댑터를 고쳐야 하며, {@code UNEXPECTED}는 우리 코드의 버그다.
 *
 * @param sourceName 어느 벤더가 깨졌는가. 외부 호출이 없는 단계의 실패는 {@code null}이다.
 */
public record BatchFailureGroupResponse(
        String failureKind,
        String sourceName,
        int totalCount,
        int unresolvedCount) {

    public static BatchFailureGroupResponse of(BatchExecutionReader.FailureGroup group) {
        return new BatchFailureGroupResponse(
                group.failureKind(),
                group.sourceName(),
                group.totalCount(),
                group.unresolvedCount());
    }
}
