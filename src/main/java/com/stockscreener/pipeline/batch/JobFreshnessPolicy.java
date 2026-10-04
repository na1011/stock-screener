package com.stockscreener.pipeline.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 잡이 <b>얼마나 오래 안 돌았는가</b>를 판정한다. 알림과 관리 화면 배너가 <b>같은 이 판정</b>을 쓴다.
 *
 * <h2>왜 이것이 필요한가</h2>
 * <p>스케줄러에 걸린 잡이 둘뿐이던 시절, 나머지는 신선도가 <b>작업 세션의 우연</b>에 달려
 * 있었다. 그 결과가 실물이었다 — 국내 기업행위 수집이 2026-08-13 이후 안 돌아 셀트리온의
 * 무상증자(2026-06-04 · 1.0492721033)가 원장에 없는 채로 있었고, {@code adj_factor}가 1이라
 * <b>값이 전부 그럴듯했다.</b> 잡이 안 돈 것 자체는 어디에도 안 뜬다 —
 * {@code batch_execution}은 <b>돈 것</b>만 적기 때문이다.
 *
 * <h2>MUST: 판정을 서버가 하고 화면은 그리기만 한다</h2>
 * <p>예산이 여기 있으므로 화면이 경과일로 다시 판정하면 두 곳이 갈린다
 * ({@code .claude/rules/frontend.md} — <i>"서버가 판단해서 주는 값을 다른 필드로 다시 유도하지
 * 않는다"</i>). 배너는 이 클래스가 낸 {@link Staleness}를 그대로 그린다.
 *
 * <h2>MUST: 스케줄러 실행이 있으면 그것만 센다</h2>
 * <p>좁은 수동 실행이 시계를 되돌리기 때문이다. {@code corporate-action}의 2026-08-24 실행은
 * 대상이 9종목·1종목이었고 <b>전량은 08-13이 마지막</b>이었는데, 아무 성공이나 세면 08-24가
 * "최근"이 되어 <b>결손이 있는 채로 초록</b>이 된다.
 *
 * <p>{@code target_count}가 범위를 담기는 한다(350/1000/1350 대 1/9/15). 그것으로 안 묻는
 * 이유는 <b>잡마다 「전량」의 크기가 다르기 때문</b>이다 — {@code company-profile}의 전량은
 * 업종이 빈 종목뿐이라 평소 한 자리 수이고(실측 4), 그 수를 잡마다 다시 정의하면 그 정의가 낡는다.
 *
 * <p><b>그래서 이 판정은 불변식 하나에 통째로 기대고 있다 — 「스케줄러 실행은 언제나 전량이다」.</b>
 * 리밸런싱 꼬리({@code MembershipGapFiller})가 그것을 깰 수 있는 유일한 자리다. 꼬리는
 * 리밸런싱의 트리거를 물려받는데 <b>결손 티커로 좁혀</b> 부르기 때문이다. 그래서 좁힌 호출은
 * {@link BatchExecutionRecorder.TriggeredBy#GAP_FILL}로 기록한다 —
 * <b>MUST: 꼬리에 갈래를 더할 때 그 트리거를 함께 준다.</b> 안 주면 이 판정이 조용히 무력해지고,
 * 증상은 <b>연체 경보가 안 뜨는 것</b>이라 아무 데도 안 붉는다. 실제로 {@code initial-load}의
 * SCHEDULER 행이 {@code target_count = 2}로 남아 있었다(2026-09-19 꼬리 · 그 잡은 아래에서
 * 일부러 빠져 있어 무해했다).
 *
 * <p><b>승인된 한계</b> — 스케줄에 없는 잡은 이 방어가 없다. 그런 잡은 아무 성공이나 세므로
 * 좁은 수동 실행이 시계를 되돌린다. 지금 그 자리는 {@code market-calendar} 하나다.
 *
 * <h2>MUST NOT: 상시 붉을 것을 감시 대상에 넣지 않는다</h2>
 * <p>상시 붉은 펜스는 곧 무시되고, 그러면 <b>진짜 연체까지 함께 묻힌다.</b> 그래서 아래
 * {@link #BUDGETS}에 <b>일부러 뺀 잡과 그 이유</b>를 함께 적는다.
 */
@Component
public class JobFreshnessPolicy {

    /**
     * 잡별 연체 예산.
     *
     * <p><b>MUST: 예산의 근거를 각 줄에 적는다.</b> 근거 없는 예산은 붉을 때마다 느슨해진다.
     *
     * <p><b>일부러 감시하지 않는 것</b> — 넣으면 상시 붉는다.
     * <ul>
     *   <li>{@code market-index} — 일일 갱신이 지수를 함께 받는다. 이 잡은 과거 구간을 채우는
     *       도구이지 신선도의 경로가 아니다(실측: 지수 최신이 일일 갱신 대상일과 같다).</li>
     *   <li>{@code indicator-backfill} — <b>손 도구</b>다. 정기 경로는 매 평일 화해이고 그쪽의 ②
     *       ({@code indicator-derived-rebuild})가 예산을 갖는다.</li>
     *   <li>{@code initial-load} — <b>주기가 생겼지만 그 주기로 감시하지 않는다.</b>
     *       {@code MembershipGapFiller}가 리밸런싱 꼬리에서 부르는데, <b>대상이 보통 0이라
     *       안 도는 것이 정상</b>이다(ADR-0141). 예산을 주면 결손 없는 주마다 붉는다 —
     *       이 잡이 안 돌았다는 것은 <b>채울 것이 없었다</b>는 뜻이고, 채울 것이 있는데 안 돈
     *       상태는 {@code UniverseIntegrityAlerter}가 종목 이름으로 신고한다.</li>
     *   <li>{@code corporate-action-judge} — <b>사람의 판정</b>이다. 판정할 것이 없으면 안 도는
     *       것이 정상이고, 있으면 {@code CorporateActionJob}의 「판정 대기」 알림이 이미 말한다.</li>
     * </ul>
     */
    static final Map<String, Budget> BUDGETS = budgets();

    private static Map<String, Budget> budgets() {
        Map<String, Budget> map = new LinkedHashMap<>();
        // 실측 최장 공백이 4일이다(공휴일이 낀 주에 두 번). 3일로 잡으면 평시에 붉는다.
        map.put(DailyUpdateJob.JOB_NAME, new Budget(5, null));
        // 평일 14:00 크론. 금→월이 3일이라 그 위로 이틀 여유를 둔다.
        map.put(CorporateActionJob.JOB_NAME, new Budget(5, null));
        // 위와 한 사슬이라 같은 예산이다. 갈리면 사슬이 끊긴 것이다.
        map.put(AdjustmentRebuildJob.JOB_NAME, new Budget(5, null));
        // 평일 14:20 화해의 ①·②. 일일 갱신과 같은 주기라 같은 예산이다. 둘 다 두는 이유: 14:00 사슬이
        // 무효화가 있는 날 ②(창 전체)를 SCHEDULER로 돌리므로 ② 시계만 보면 14:20 ① 전량의 실패가
        // 가려진다. 사슬의 좁힌 ①은 GAP_FILL로 남아(IndicatorReconciler.originTrigger) 여기서 안 센다.
        map.put(IndicatorBackfillJob.ORIGIN_JOB_NAME,
                new Budget(5, "--job=indicator-reconcile"));
        map.put(IndicatorBackfillJob.DERIVED_JOB_NAME,
                new Budget(5, "--job=indicator-reconcile"));
        // 주 1회(토) 크론. 토요일 크론은 평일보다 발화가 드물어 여유를 크게 둔다.
        map.put(UniverseRebalanceJob.JOB_NAME, new Budget(12, null));
        // 리밸런싱 뒤에 붙는다. 같은 이유로 같은 폭이다.
        map.put(CompanyProfileJob.JOB_NAME, new Budget(12, null));
        // 월 1회(화). 거버넌스 값은 공시일에 계단으로 바뀌므로 그보다 자주 볼 이유가 없다.
        map.put(GovernanceLoadJob.JOB_NAME, new Budget(40,
                "--job=governance-load"));
        map.put(GovernanceDeriveJob.JOB_NAME, new Budget(40, null));
        // 월 1회(수).
        map.put(FullStatementRefillJob.JOB_NAME, new Budget(40,
                "--job=full-statement-refill --all-quarters=true"));
        // 월 1회(목). 이 잡의 값은 결손 채우기가 아니라 정정공시 반영이다.
        map.put(FinancialRefillJob.JOB_NAME, new Budget(40, "--job=financial-refill"));
        // 소스가 미래 휴장일을 모르므로 실행일까지만 찬다 — 그만큼 천천히 낡는다.
        map.put(MarketCalendarJob.JOB_NAME, new Budget(60, null));
        return Map.copyOf(map);
    }

    /**
     * @param days     이 일수를 <b>넘으면</b> 연체다.
     * @param howToRun 화면에 실행 버튼이 없는 잡의 CLI 명령. 버튼이 있으면 {@code null}이다 —
     *                 있는데도 적으면 사용자가 둘 중 무엇이 맞는지 묻게 된다.
     *                 {@code AdminJobLauncher}의 카탈로그가 그 구분의 정본이다.
     */
    public record Budget(int days, String howToRun) {
    }

    /**
     * 연체된 잡 하나.
     *
     * @param lastSuccessAt 한 번도 성공한 적이 없으면 {@code null}이고 {@code daysSince}는
     *                      {@code -1}이다. <b>0으로 접지 않는다</b> — "오늘 돌았다"와
     *                      "한 번도 안 돌았다"는 반대이고, 0으로 접으면 후자가 사라진다.
     */
    public record Staleness(String jobName, Instant lastSuccessAt, long daysSince,
                            int budgetDays, boolean scheduledOnly, String howToRun) {
    }

    private final JdbcTemplate jdbcTemplate;

    public JobFreshnessPolicy(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 예산을 넘긴 잡. 오래 밀린 것부터. 비어 있으면 배너를 안 그린다. */
    @Transactional(readOnly = true)
    public List<Staleness> stale(Instant now) {
        List<Staleness> stale = new ArrayList<>();
        for (Map.Entry<String, Budget> entry : BUDGETS.entrySet()) {
            String jobName = entry.getKey();
            Budget budget = entry.getValue();

            Instant scheduled = lastSuccessAt(jobName, true);
            Instant last = scheduled != null ? scheduled : lastSuccessAt(jobName, false);

            long daysSince = last == null
                    ? -1
                    : Duration.between(last, now).toDays();
            if (last != null && daysSince <= budget.days()) {
                continue;
            }
            stale.add(new Staleness(jobName, last, daysSince, budget.days(),
                    scheduled != null, budget.howToRun()));
        }
        // 한 번도 안 돈 것(-1)을 맨 앞에 둔다. 가장 오래 밀린 것과 같은 뜻이기 때문이다.
        stale.sort((a, b) -> {
            if (a.daysSince() < 0 || b.daysSince() < 0) {
                return Long.compare(a.daysSince(), b.daysSince());
            }
            return Long.compare(b.daysSince(), a.daysSince());
        });
        return List.copyOf(stale);
    }

    /**
     * 마지막 성공으로부터 지난 일수. 한 번도 성공한 적이 없으면 {@link Long#MAX_VALUE}다.
     *
     * <p><b>MUST: 「없음」을 0이 아니라 무한대로 낸다.</b> 호출부는 이 값으로 <i>"돌 때가
     * 됐는가"</i>를 묻는데, 0으로 접으면 <b>한 번도 안 돈 잡이 방금 돈 것으로 읽혀 영원히
     * 안 돈다.</b> 그 오답은 아무 에러도 내지 않는다.
     *
     * <p>{@link #stale}과 같은 술어를 쓴다 — 스케줄러 실행이 있으면 그것만 센다.
     */
    @Transactional(readOnly = true)
    public long daysSinceLastSuccess(String jobName, Instant now) {
        Instant scheduled = lastSuccessAt(jobName, true);
        Instant last = scheduled != null ? scheduled : lastSuccessAt(jobName, false);
        return last == null ? Long.MAX_VALUE : Duration.between(last, now).toDays();
    }

    /**
     * 원장은 승인됐는데 계수가 안 들어간 행 수. <b>사슬이 끊겼다는 것을 재는 유일한 술어다.</b>
     *
     * <p>{@code APPROVED}는 {@code CorporateActionLedger.findUnadjusted}가 안 돌려주므로
     * ADR-0055의 억제에도 {@code CorporateActionJob}의 「판정 대기」 알림에도 <b>안 걸린다.</b>
     * 그리고 ADR-0049의 불변식({@code adj_close = close × adj_factor})은 원장과 계수의 관계를
     * 묻지 않는다. 그래서 이 수가 0이 아닌 것은 <b>어디에서도 안 붉는다.</b>
     */
    @Transactional(readOnly = true)
    public long approvedWithoutCoefficient() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM corporate_action
                WHERE status = 'APPROVED' AND applied_at IS NULL
                """, Long.class);
        return count == null ? 0 : count;
    }

    /**
     * @param schedulerOnly 스케줄러가 남긴 실행만 볼 것인가. {@code true}로 한 번 물어 없으면
     *                      호출부가 {@code false}로 다시 묻는다 — 아직 크론이 한 번도 발화하지
     *                      않은 잡을 <b>「한 번도 안 돌았다」로 신고하지 않기 위해서다.</b>
     *
     * <p><b>MUST: 폴백에서도 {@code GAP_FILL}은 안 센다.</b> 리밸런싱 꼬리는 결손 티커로 좁혀
     * 부르는 실행이라(클래스 javadoc의 불변식) 전량이 아니다. 세면 스케줄러 실행이 아직 없는
     * 잡의 시계를 꼬리가 되돌린다 — 2026-10-03 꼬리의 {@code governance-load}·
     * {@code full-statement-refill}이 그 자리였고, 무거운 잡의 첫 정기 실행이 그만큼 밀렸다.
     * 폴백 자체에 두므로 {@link #stale}(배너·알림)과 {@link #daysSinceLastSuccess}(스케줄러)가
     * 같은 답을 낸다.
     */
    private Instant lastSuccessAt(String jobName, boolean schedulerOnly) {
        String triggerClause = schedulerOnly
                ? " AND triggered_by = 'SCHEDULER'"
                : " AND triggered_by <> 'GAP_FILL'";
        return jdbcTemplate.queryForObject("""
                SELECT max(started_at) FROM batch_execution
                WHERE job_name = ? AND status IN ('SUCCESS', 'PARTIAL_SUCCESS')
                """ + triggerClause, Instant.class, jobName);
    }
}
