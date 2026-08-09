package com.stockscreener.pipeline.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 배치 잡을 손으로 한 번 돌리는 진입점.
 *
 * <p>{@code bootstrap} 프로파일에서만 등록된다. 기본 프로파일에 두면
 * {@code spring.batch.job.enabled=false}로 막아둔 것과 같은 사고가 난다 —
 * 애플리케이션을 재시작할 때마다 850종목 수집이 시작된다.
 *
 * <p>Phase 4의 관리자 API를 앞당기지 않은 이유는 적재가 <b>지금 필요한 것</b>이고 API는
 * 인증·권한·화면까지 딸린 별개의 작업이기 때문이다. 관리자 API가 생기면 이 러너는 지워도 된다.
 *
 * <h2>쓰는 법</h2>
 * <pre>
 * # 유니버스 갱신
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=universe'
 *
 * # 소수 종목으로 관통 검증(시세 2년·재무 3년)
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=initial-load'
 *
 * # 전 기간 재적재(이미 적재된 종목도 다시 받는다)
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=initial-load \
 *     --price-years=7 --financial-years=7 --reload-existing=true'
 *
 * # 지표 백필
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=backfill --from=2020-01-02'
 * </pre>
 */
@Profile("bootstrap")
@Component
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final UniverseRebalanceJob universeRebalanceJob;
    private final InitialLoadJob initialLoadJob;
    private final IndicatorBackfillJob indicatorBackfillJob;
    private final BatchExecutionSweeper executionSweeper;
    private final ConfigurableApplicationContext context;

    public BootstrapRunner(UniverseRebalanceJob universeRebalanceJob,
                           InitialLoadJob initialLoadJob,
                           IndicatorBackfillJob indicatorBackfillJob,
                           BatchExecutionSweeper executionSweeper,
                           ConfigurableApplicationContext context) {
        this.universeRebalanceJob = universeRebalanceJob;
        this.initialLoadJob = initialLoadJob;
        this.indicatorBackfillJob = indicatorBackfillJob;
        this.executionSweeper = executionSweeper;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 이전 부트스트랩 실행이 SIGKILL 등으로 남긴 행을 여기서 닫는다.
        //
        // IMPORTANT — 스위퍼의 `ApplicationReadyEvent` 리스너는 **이 프로파일에서 돌지 않는다.**
        // `SpringApplication.run()`이 `callRunners()`를 그 이벤트 발행보다 먼저 부르고, 아래에서
        // `System.exit()`으로 내려가기 때문이다. 그래서 직접 부른다.
        //
        // 내 잡의 행은 아래에서 만들어지므로 이 시점의 정리 대상이 아니다(스위퍼가 기동 시각으로 가른다).
        executionSweeper.sweepBootstrapOrphans();

        String job = required(args, "job");
        LocalDate asOf = date(args, "as-of", LocalDate.now());

        log.info("부트스트랩 러너를 시작한다. job={} asOf={}", job, asOf);
        BatchStatus status = switch (job) {
            case "universe" -> universeRebalanceJob.run(
                    asOf, BatchExecutionRecorder.TriggeredBy.MANUAL);
            case "initial-load" -> initialLoadJob.run(
                    asOf, BatchExecutionRecorder.TriggeredBy.MANUAL, loadRange(args));
            case "backfill" -> indicatorBackfillJob.run(
                    date(args, "from", asOf.minusYears(2)), asOf,
                    BatchExecutionRecorder.TriggeredBy.MANUAL);
            default -> throw new IllegalArgumentException(
                    "알 수 없는 잡이다: '" + job + "' — universe | initial-load | backfill");
        };

        log.info("부트스트랩 러너가 끝났다. job={} status={}", job, status);
        if (status == BatchStatus.FAILED) {
            throw new IllegalStateException(job + " 잡이 실패했다. 로그와 batch_execution을 확인하라.");
        }

        // IMPORTANT — 잡이 끝나면 애플리케이션을 내린다. 웹 스타터가 있어 그냥 두면 서버가
        // 계속 떠 있고, 스크립트로 여러 잡을 이어 돌릴 때 다음 명령으로 넘어가지 못한다.
        // 종료 코드는 0이다 — 실패는 위에서 예외로 이미 드러냈다.
        int exitCode = SpringApplication.exit(context, () -> 0);
        System.exit(exitCode);
    }

    private InitialLoadJob.LoadRange loadRange(ApplicationArguments args) {
        InitialLoadJob.LoadRange defaults = InitialLoadJob.LoadRange.defaults();
        return new InitialLoadJob.LoadRange(
                integer(args, "price-years", defaults.priceYears()),
                integer(args, "financial-years", defaults.financialYears()),
                Boolean.parseBoolean(value(args, "reload-existing", "false")));
    }

    private static String required(ApplicationArguments args, String name) {
        String value = value(args, name, null);
        if (value == null) {
            throw new IllegalArgumentException(
                    "--" + name + " 인자가 필요하다. 예: --job=initial-load");
        }
        return value;
    }

    private static String value(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }

    private static LocalDate date(ApplicationArguments args, String name, LocalDate fallback) {
        String raw = value(args, name, null);
        return raw == null ? fallback : LocalDate.parse(raw);
    }

    private static int integer(ApplicationArguments args, String name, int fallback) {
        String raw = value(args, name, null);
        return raw == null ? fallback : Integer.parseInt(raw);
    }
}
