package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 연체된 잡을 <b>운영자에게 알린다.</b> 판정은 {@link JobFreshnessPolicy}가 하고 여기서는
 * <b>언제 다시 보낼지</b>만 정한다.
 *
 * <h2>MUST: 같은 연체를 매일 보내지 않는다</h2>
 * <p>연체는 <b>고칠 때까지 계속 참</b>이라, 매 평일 보내면 며칠 안에 아무도 그 알림을 보지
 * 않게 된다 — {@code CorporateActionJob.alertOnPendingJudgement}가 "이번 실행이 새로 만든
 * 후보만 센다"로 푼 것과 같은 문제다. 여기서는 <b>연체로 넘어간 날 한 번 보내고 그 뒤로는
 * 7일에 한 번</b>만 보낸다.
 *
 * <p>{@code AlertSuppressor}를 쓰지 않는 이유는 그 창이 전역 10분이기 때문이다(하루 간격의
 * 반복을 못 막는다). 대신 {@code AlertSuppressor}와 <b>같은 교환</b>을 택한다 — 메모리에만
 * 두고, 재시작하면 다시 알린다. 재시작 직후의 알림은 오히려 보고 싶은 것이고, 억제 상태를
 * DB에 두면 <b>DB 장애를 알리려는 알림이 DB 때문에 막힌다.</b>
 *
 * <h2>MUST: 사슬이 끊긴 것을 같은 자리에서 함께 잰다</h2>
 * <p>{@code APPROVED}인데 계수가 안 들어간 행은 억제에도 알림에도 안 걸린다
 * ({@link JobFreshnessPolicy#approvedWithoutCoefficient}의 javadoc). 그것을 재는 자리가
 * 여기뿐이므로 <b>연체가 0이어도</b> 이 수가 0이 아니면 보낸다.
 */
@Component
public class JobFreshnessAlerter {

    /** 같은 잡의 연체를 다시 알리기까지의 간격. */
    private static final Duration REPEAT_AFTER = Duration.ofDays(7);

    /** 본문에 이름으로 적을 잡 수. 넘는 것은 로그가 답한다. */
    private static final int JOBS_IN_ALERT = 10;

    private static final Logger log = LoggerFactory.getLogger(JobFreshnessAlerter.class);

    private final Map<String, Instant> lastAlertedAt = new ConcurrentHashMap<>();

    private final JobFreshnessPolicy policy;
    private final AlertPort alertPort;
    private final Clock clock;

    // MUST: @Autowired를 지우지 않는다. 생성자가 둘이면 Spring이 고르지 못해 **컨텍스트가
    // 기동에 실패한다** — 그리고 bootRun은 그때도 BUILD SUCCESSFUL과 종료코드 0을 낸다.
    @Autowired
    public JobFreshnessAlerter(JobFreshnessPolicy policy, AlertPort alertPort) {
        this(policy, alertPort, Clock.systemUTC());
    }

    /** 테스트가 시간을 밀 수 있게 시계를 주입받는다. */
    public JobFreshnessAlerter(JobFreshnessPolicy policy, AlertPort alertPort, Clock clock) {
        this.policy = policy;
        this.alertPort = alertPort;
        this.clock = clock;
    }

    /**
     * 지금 연체된 것을 재서 <b>필요하면</b> 한 건으로 알린다.
     *
     * <p>MUST NOT: {@code batch_execution}에 행을 만들지 않는다. 이것은 읽기이고, 실행 이력에
     * 섞이면 <b>「잡이 돌았다」와 「잡이 안 돈 것을 확인했다」가 같은 목록에 뜬다.</b>
     *
     * @return 보냈으면 {@code true}. 테스트가 이것으로 억제를 검사한다.
     */
    public boolean evaluateAndAlert() {
        Instant now = clock.instant();
        List<JobFreshnessPolicy.Staleness> stale = policy.stale(now);
        long brokenChain = policy.approvedWithoutCoefficient();

        List<JobFreshnessPolicy.Staleness> toReport = stale.stream()
                .filter(item -> shouldSend(item.jobName(), now))
                .toList();

        if (toReport.isEmpty() && brokenChain == 0) {
            log.debug("연체된 잡이 없거나 이미 알렸다. 연체={}건", stale.size());
            return false;
        }

        StringBuilder body = new StringBuilder();
        if (!toReport.isEmpty()) {
            // 「안 돌았다」만 말하면 정기 실행이 돌았지만 시계에 안 센 경우(시장 미완료 · ADR-0174)가 거짓이 된다 —
            // 줄마다 시도 수로 가른다.
            body.append("아래 잡이 예산을 넘겨 신선도 시계가 안 움직였다. 정기 시도가 없으면 안 돈 것이고"
                    + "(그것은 batch_execution에 흔적이 없다 — 그 표는 돈 것만 적는다), 있으면 돌았지만 시계에 안 셌다.\n");
            toReport.stream().limit(JOBS_IN_ALERT).forEach(item -> body.append(describe(item)));
            if (toReport.size() > JOBS_IN_ALERT) {
                body.append("나머지 %d건은 관리 화면 상단에 있다.\n"
                        .formatted(toReport.size() - JOBS_IN_ALERT));
            }
        }
        if (brokenChain > 0) {
            body.append(("\n원장이 APPROVED인데 계수가 안 들어간 행이 %d건이다. "
                    + "이 상태는 지표 억제에도 판정 대기 알림에도 안 걸려 값이 그럴듯하게 틀린다 "
                    + "— adjustment-rebuild가 그것을 닫는다.\n").formatted(brokenChain));
        }

        toReport.forEach(item -> lastAlertedAt.put(item.jobName(), now));
        alertPort.send(AlertEvent.toOperator(AlertSeverity.WARNING, "배치 신선도",
                body.toString(), "job-freshness"));
        return true;
    }

    static String describe(JobFreshnessPolicy.Staleness item) {
        String when = item.daysSince() < 0
                ? "한 번도 성공한 적이 없다"
                : "%d일째 (마지막 성공 %s)".formatted(item.daysSince(), item.lastSuccessAt());
        String attempts = attemptsNote(item);
        String how = item.howToRun() == null
                ? "관리 화면에서 띄운다"
                : "bootRun --args='--spring.profiles.active=bootstrap --server.port=0 "
                        + item.howToRun() + "'";
        // 미완료로 끝난 정기 실행이 있으면 손으로 전량을 다시 돌리기 전에 원인을 본다 — 소스 장애·한도면 같은 손 실행도
        // 같은 자리에서 못 받고, 우리 코드(UNEXPECTED)면 몇 번을 돌려도 같다. 다음 정기 차례가 어차피 다시 돈다.
        if (!item.incompleteMarkets().isEmpty()) {
            how = "먼저 그 실행의 note·실패 요약에서 미완료 원인(소스 장애·한도·우리 코드)을 본다. 손으로 돌리려면 " + how;
        }
        return "- %s: %s%s / 예산 %d일 — %s\n"
                .formatted(item.jobName(), when, attempts, item.budgetDays(), how);
    }

    /** 「(n)번 시도 · (시장) 미완료」. 정기 시도가 없으면 빈 문자열이다. */
    static String attemptsNote(JobFreshnessPolicy.Staleness item) {
        if (item.scheduledAttempts() == 0) {
            return "";
        }
        String note = " · 정기 %d번 시도".formatted(item.scheduledAttempts());
        return item.incompleteMarkets().isEmpty() ? note
                : note + " · " + String.join(",", item.incompleteMarkets()) + " 미완료";
    }

    private boolean shouldSend(String jobName, Instant now) {
        Instant last = lastAlertedAt.get(jobName);
        return last == null || Duration.between(last, now).compareTo(REPEAT_AFTER) >= 0;
    }
}
