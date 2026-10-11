package com.stockscreener.pipeline.batch.web;

import com.stockscreener.pipeline.batch.JobFreshnessPolicy;

import java.time.Instant;
import java.util.List;

/**
 * 예산을 넘겨 안 돈 잡 하나. 관리 화면 상단 배너가 이것을 그린다.
 *
 * <p><b>MUST: 판정을 서버가 실어 보낸다.</b> 화면이 {@code lastSuccessAt}에서 경과일을 다시
 * 세면 예산이 두 곳에 있게 되고, 그때 어느 쪽이 참인지 알 수 없다
 * ({@code .claude/rules/frontend.md}).
 *
 * <p><b>MUST NOT: 잡 이름을 사람이 읽는 라벨로 바꾸지 않는다.</b> 바로 아래 요약 카드가
 * {@code jobName}을 그대로 그리므로, 배너만 라벨을 쓰면 <b>같은 잡이 한 화면에서 두 이름으로
 * 뜬다.</b> 라벨이 필요하면 그 카드와 함께 바꾼다 — 정본은 {@code AdminJobLauncher}의 카탈로그다.
 *
 * @param daysSince 마지막 성공으로부터 지난 일수. <b>{@code -1}은 「한 번도 성공한 적이 없다」</b>
 *                  이고 0과 반대 뜻이다. 화면이 0으로 접으면 그 상태가 사라진다.
 * @param scheduledOnly 스케줄러 실행만 세서 나온 값인가. {@code false}면 좁은 수동 실행이
 *                      이 시계를 되돌릴 수 있다는 뜻이라, 화면이 그 사실을 함께 말한다.
 * @param howToRun 화면에 실행 버튼이 <b>없는</b> 잡의 CLI 인자. 버튼이 있으면 {@code null}이다.
 * @param scheduledAttempts 마지막으로 센 성공 뒤의 정기 시도 수. 0이면 「안 돌았다」, 0보다 크면 「돌았지만 시계에 안 셌다」다
 *                  (ADR-0174) — 화면이 둘을 다른 문장으로 그린다.
 * @param incompleteMarkets 가장 최근 정기 시도가 미완료로 판정한 시장. 비어 있지 않으면 화면은 손 실행보다 원인 확인을 먼저 권한다.
 */
public record StaleJobResponse(
        String jobName,
        Instant lastSuccessAt,
        long daysSince,
        int budgetDays,
        boolean scheduledOnly,
        String howToRun,
        int scheduledAttempts,
        List<String> incompleteMarkets) {

    public static StaleJobResponse of(JobFreshnessPolicy.Staleness staleness) {
        return new StaleJobResponse(
                staleness.jobName(),
                staleness.lastSuccessAt(),
                staleness.daysSince(),
                staleness.budgetDays(),
                staleness.scheduledOnly(),
                staleness.howToRun(),
                staleness.scheduledAttempts(),
                staleness.incompleteMarkets());
    }
}
