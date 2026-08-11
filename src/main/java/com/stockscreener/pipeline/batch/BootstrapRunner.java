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
 * # 특정 종목만 적재 — 신규 편입 종목이 1~2봉만 있어 건너뛰어질 때. 종목당 시세 1콜 + 재무 16콜
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=initial-load \
 *     --tickers=082640,121600 --price-years=7'
 *
 * # 재무 결손 보정 — 스키마에 컬럼이 늘어 NULL로 남은 자리만 채운다(ADR-0040)
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=financial-refill \
 *     --financial-years=3 --tickers=005930,035420'
 *
 * # 업종·결산월 수집 — 기본 대상은 업종이 비어 있는 편입 종목뿐이다. 종목당 1콜
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=company-profile'
 *
 * # 이미 있는 업종을 다시 받는다 — 티커를 주면 결손 여부와 무관하게 갱신한다
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=company-profile \
 *     --tickers=005930,035420'
 *
 * # 지표 백필
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=backfill --from=2020-01-02'
 * </pre>
 *
 * <p>IMPORTANT — 운영 앱이 도는 중이면 {@code --server.port=0}을 함께 준다. 포트 8080 충돌로
 * 컨텍스트가 죽는데 gradle은 {@code BUILD SUCCESSFUL}을 내므로, 로그를 열지 않으면 적재된
 * 것으로 읽힌다.
 */
@Profile("bootstrap")
@Component
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final UniverseRebalanceJob universeRebalanceJob;
    private final InitialLoadJob initialLoadJob;
    private final FinancialRefillJob financialRefillJob;
    private final CompanyProfileJob companyProfileJob;
    private final IndicatorBackfillJob indicatorBackfillJob;
    private final BatchExecutionSweeper executionSweeper;
    private final ConfigurableApplicationContext context;

    public BootstrapRunner(UniverseRebalanceJob universeRebalanceJob,
                           InitialLoadJob initialLoadJob,
                           FinancialRefillJob financialRefillJob,
                           CompanyProfileJob companyProfileJob,
                           IndicatorBackfillJob indicatorBackfillJob,
                           BatchExecutionSweeper executionSweeper,
                           ConfigurableApplicationContext context) {
        this.universeRebalanceJob = universeRebalanceJob;
        this.initialLoadJob = initialLoadJob;
        this.financialRefillJob = financialRefillJob;
        this.companyProfileJob = companyProfileJob;
        this.indicatorBackfillJob = indicatorBackfillJob;
        this.executionSweeper = executionSweeper;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 오타는 아무것도 하기 전에 막는다. 태운 외부 호출은 되돌릴 수 없다.
        rejectUnknownOptions(args);

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
            case "financial-refill" -> financialRefillJob.run(
                    asOf, BatchExecutionRecorder.TriggeredBy.MANUAL,
                    optionalInteger(args, "financial-years"), csv(args, "tickers"));
            case "company-profile" -> companyProfileJob.run(
                    asOf, BatchExecutionRecorder.TriggeredBy.MANUAL, csv(args, "tickers"));
            case "backfill" -> indicatorBackfillJob.run(
                    date(args, "from", asOf.minusYears(2)), asOf,
                    BatchExecutionRecorder.TriggeredBy.MANUAL);
            default -> throw new IllegalArgumentException(
                    "알 수 없는 잡이다: '" + job
                            + "' — universe | initial-load | financial-refill | company-profile"
                            + " | backfill");
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
                Boolean.parseBoolean(value(args, "reload-existing", "false")),
                csv(args, "tickers"));
    }

    /**
     * 이 러너가 아는 인자. 모르는 이름이 오면 즉시 실패시킨다.
     *
     * <p>IMPORTANT — 이것이 없으면 <b>오타 하나가 쿼터를 태운다.</b> {@code --ticker=…}처럼
     * s를 빠뜨리면 {@link #csv}가 빈 목록을 내고 대상이 "전 종목"으로 넓어지는데,
     * {@code --reload-existing=true}가 함께 있으면 {@code warn-bootstrap-args.sh}도 조용히
     * 통과시킨다. 태운 호출은 되돌릴 수 없으므로 실행 전에 막는 것이 유일한 방어다.
     */
    private static final java.util.Set<String> KNOWN_OPTIONS = java.util.Set.of(
            "job", "as-of", "price-years", "financial-years", "reload-existing", "tickers", "from");

    /**
     * Spring 자신의 인자는 통과시킨다.
     *
     * <p>{@code --spring.profiles.active}·{@code --server.port}가 그것이고, 전부 점을 포함한다.
     * 우리 인자는 전부 점이 없는 kebab-case이므로 <b>점 없는 미지의 이름은 오타로 본다.</b>
     * 점이 없는 Spring 플래그({@code --debug}·{@code --trace})만 예외로 적어 둔다.
     */
    private static final java.util.Set<String> SPRING_FLAGS = java.util.Set.of("debug", "trace");

    /** 패키지 공개인 이유는 컨텍스트 없이 검사만 테스트하기 위해서다 — {@code run()}은 끝에서 JVM을 내린다. */
    static void rejectUnknownOptions(ApplicationArguments args) {
        List<String> unknown = args.getOptionNames().stream()
                .filter(name -> !name.contains("."))
                .filter(name -> !KNOWN_OPTIONS.contains(name))
                .filter(name -> !SPRING_FLAGS.contains(name))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "모르는 인자다: " + unknown + " — 오타이면 그대로 두었을 때 대상이 전 종목으로"
                            + " 넓어져 쿼터를 태운다. 아는 인자: "
                            + KNOWN_OPTIONS.stream().sorted().toList());
        }
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

    /** 안 주면 null. 잡이 자기 기본값을 아는 편이 낫다 — 러너가 두 번째 정본이 되지 않는다. */
    private static Integer optionalInteger(ApplicationArguments args, String name) {
        String raw = value(args, name, null);
        return raw == null ? null : Integer.valueOf(raw);
    }

    /** 쉼표로 구분한 목록. 빈 조각은 버린다 — 후행 쉼표가 빈 티커가 되면 안 된다. */
    private static List<String> csv(ApplicationArguments args, String name) {
        String raw = value(args, name, null);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .toList();
    }
}
