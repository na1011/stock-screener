package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.identity.DelistedIntake;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.RegistrantLookup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 손 잡 {@code us-delisted-intake} — 보존 창 안에 피인수 · 가치 없음 제거로 사라졌는데 행이 없는 실체를 행으로 들인다
 * ({@link DelistedIntake} · 탐색 {@code US-PAST-LISTING-IMPL-1018.md} U3).
 *
 * <p><b>기본은 계획만 낸다</b>({@code --apply=false}) — 운영 쓰기라 관문 승인 안에서 {@code --apply=true}로 친다. 원자료(업종 · 재무 ·
 * 시세)는 이 잡이 안 받는다 — 관문의 후보 재적재와 복원 꼬리가 받는다. 그래서 관문 실행기에서 {@code 03-refill} 앞에 둔다.
 *
 * <p>창 시작은 보존 창의 첫날이다({@code indicator_trade_date} 미국 최소 — 복원 격자의 첫날과 같다). 실행 기록 요약이 들인 것 ·
 * 사유별 안 들인 것을 싣는다(펜스 「마지막 인입의 안 묶은 수」가 읽는다).
 */
@Component
public class UsDelistedIntakeJob {

    public static final String JOB_NAME = "us-delisted-intake";

    private static final Logger log = LoggerFactory.getLogger(UsDelistedIntakeJob.class);

    private final ObjectProvider<IdentitySource> identities;
    private final ObjectProvider<RegistrantLookup> registrants;
    private final DelistedIntake intake;
    private final BatchExecutions batchExecutions;
    private final JdbcTemplate jdbc;

    public UsDelistedIntakeJob(ObjectProvider<IdentitySource> identities, ObjectProvider<RegistrantLookup> registrants,
                               DelistedIntake intake, BatchExecutions batchExecutions, JdbcTemplate jdbc) {
        this.identities = identities;
        this.registrants = registrants;
        this.intake = intake;
        this.batchExecutions = batchExecutions;
        this.jdbc = jdbc;
    }

    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy, boolean apply) {
        BatchExecutionContext execution = batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0,
                BatchExecutionRecorder.DeclaredScope.FULL);
        IdentitySource source = identities.getIfAvailable();
        RegistrantLookup lookup = registrants.getIfAvailable();
        if (source == null || lookup == null) {
            execution.failFatally(new DataSourceException(DataSourceException.Kind.AUTH, "identity",
                    "신원 소스나 등록인 조회가 없다(Alpaca·SEC 키)."));
            return BatchStatus.FAILED;
        }
        LocalDate from = jdbc.queryForObject("SELECT min(trade_date) FROM indicator_trade_date WHERE market_code = 'US'",
                LocalDate.class);
        if (from == null) {
            execution.failUnexpectedly(new IllegalStateException("미국 보존 창이 없다(indicator_trade_date 0행) — 들일 창을 못 정한다."));
            return BatchStatus.FAILED;
        }
        DelistedIntake.Plan plan;
        List<Long> created = List.of();
        try {
            plan = intake.plan(from, asOf, source, lookup);
            if (apply) {
                created = intake.apply(plan);
            }
        } catch (DataSourceException e) {
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        plan.intakes().forEach(i -> log.info("들임{} {} 효력일 {} 시대 ~{} CIK {} {} 「{}」 · {}", apply ? "" : "(계획)", i.label(),
                i.effective(), i.eraTo(), i.cik(), i.venue(), i.name(), i.evidence()));
        plan.skipped().stream().filter(s -> !"행 있음".equals(s.reason()) && !"끝 아님".equals(s.reason()))
                .forEach(s -> log.info("안 들임 {} {} {} · {}", s.reason(), s.symbol(), s.effective(), s.evidence()));
        return execution.finish("%s창 %s~%s · 들일 것 %d · 만든 행 %d · 안 들인 것 %s".formatted(apply ? "" : "계획만 · ", from, asOf,
                plan.intakes().size(), created.size(), plan.reasons()));
    }
}
