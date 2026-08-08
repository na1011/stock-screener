package com.stockscreener.pipeline.batch.web;

import com.stockscreener.pipeline.batch.BatchExecutionReader;

import java.time.Instant;

/**
 * 실패한 종목 하나.
 *
 * <p>{@code stackTrace}를 싣지 않는다. 350건이 실패한 날 응답이 수 MB가 되고, 재실행 여부를
 * 판단하는 데 필요한 것은 {@code failureKind}와 {@code message}다. 깊은 진단은 Sentry가
 * 전담한다(spec §4 — 에러 로그를 별도 화면으로 중복 구축하지 않는다).
 *
 * @param failedPhase 무엇이 실패했는가. 시세만 실패한 종목의 재무를 다시 받지 않기 위한 구분이다.
 * @param failureKind 재시도 가치가 있는 실패인가. {@code UNEXPECTED}는 우리 코드의 버그일 가능성이 높다.
 * @param resolved    재실행으로 해결됐는가. 해결된 실패는 "미해결 에러 건수"에서 빠진다.
 */
public record BatchFailureResponse(
        long id,
        long stockId,
        String ticker,
        String stockName,
        String failedPhase,
        String sourceName,
        String failureKind,
        String message,
        boolean resolved,
        Instant occurredAt) {

    public static BatchFailureResponse of(BatchExecutionReader.Failure failure) {
        return new BatchFailureResponse(
                failure.id(),
                failure.stockId(),
                failure.ticker(),
                failure.stockName(),
                failure.failedPhase(),
                failure.sourceName(),
                failure.failureKind(),
                failure.message(),
                failure.resolved(),
                failure.occurredAt());
    }
}
