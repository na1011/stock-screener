package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.CorporateActionLedger;
import com.stockscreener.pipeline.collector.CorporateActionStatus;
import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.GovernanceEndpoint;

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
 * 기동만으로 잡이 도는 것과 같은 사고가 난다 —
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
 * <p>{@code --universe=US_LISTED}를 주면 그 지수 하나만 채운다. <b>비활성 지수도 채울 수
 * 있다</b> — 구성만 쌓고 수집·스크리닝에는 넣지 않는 상태가 실재한다(ADR-0087).
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
 * # 재무만 받는다 — 시세가 과거 구간으로만 들어와 있는 시장에서 쓴다(미국이 그 상태다).
 * # --skip-price=true가 없으면 최근 봉이 함께 들어와 보존 경계가 밀리고, 그 어긋남은 자연
 * # 소멸하지 않는다(ADR-0088). **값을 반드시 붙인다** — 값 없는 --skip-price는 폴백 "false"가
 * # 되어 건너뛰라고 적어 놓고 시세를 받으므로 실행 전에 거절된다(rejectMalformedBooleans).
 * # --reload-existing이 필요한 이유는 유니버스
 * # 경로가 봉이 있는 종목을 걸러내 대상이 0건이 되고 잡이 **성공으로 끝나기** 때문이다.
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=initial-load \
 *     --universe=US_LISTED --skip-price=true --reload-existing=true --financial-years=3 \
 *     --tickers=AAPL,MSFT'
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
 * # 비활성 유니버스의 업종 — 미국은 이 경로로만 닿는다(US_LISTED가 비활성이라
 * # 기본 대상 질의의 is_active 조인에 한 건도 안 걸린다). 6,689콜, 약 22분
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=company-profile \
 *     --universe=US_LISTED'
 *
 * # 기업 행위 수집 — 계수는 바꾸지 않고 원장에만 적는다. 창은 저장된 시세 전 구간이 기본이고
 * # 종목당 콜 수는 창을 덮는 정기보고서 수가 정한다(2020~오늘이면 2콜)
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=corporate-action'
 *
 * # 종목·구간을 좁혀 관통 확인. MUST NOT: 최근 며칠짜리 창을 주지 않는다 — 소스가 정기보고서에만
 * # 실려 원리적으로 0건이고, 그것이 "사건이 없다"로 읽힌다
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=corporate-action \
 *     --tickers=058470,086280'
 *
 * # 수정계수 재구축 — 원장의 적용 대상으로 adj_factor·adj_close를 다시 짓는다.
 * # MUST: 첫 실행 전에 ops/dump-db.sh를 돌린다(ADR-0049). 이것이 저장된 시세를 소급해 다시 쓰는
 * # 유일한 경로이고, 보정된 종목의 지표가 보존 창 전체에서 지워지므로 **백필이 따라와야 끝난다.**
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=adjustment-rebuild'
 *
 * # 판정 하나를 고친 뒤 그 종목만 다시 짓는다. 백필도 같은 티커로 좁히면 몇 분에 끝난다
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=adjustment-rebuild \
 *     --tickers=058470'
 *
 * # 시장 지수 적재 — --from을 안 주면 소스 이력의 시작(2020-01-02)부터 받는다.
 * # 기간조회 1콜이라 전 구간을 받아도 지수당 1~2콜이다. 종목과 무관하므로 티커를 받지 않는다
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=market-index'
 *
 * # 거래소 세션 달력 적재 — 거래소당 1콜(NYSE·NASDAQ 둘)이라 창을 넓혀도 콜이 안 는다.
 * # --from을 안 주면 2016-01-01부터. --as-of가 미래면 거절한다(소스가 2030년부터 휴장일을
 * # 모르면서 평일을 그대로 세션으로 준다). 적재 뒤 봉 결측 요약이 알림으로 나간다
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --server.port=0 \
 *     --job=market-calendar'
 *
 * # 봉 결측 판정만 — 외부 호출 0, 아무것도 안 고치고 batch_execution 행도 안 만든다.
 * # 적재가 도는 중에 쳐도 안전하다(스위퍼를 안 돌린다)
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --server.port=0 \
 *     --job=bar-gap-review --tickers=NVDA,AAPL'
 *
 * # 지표 백필
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=backfill --from=2020-01-02'
 *
 * # 단일 날짜 실행 하나가 보존 창 전체를 채운다 — 읽기 창이 워밍업 최댓값 + 보존 일수라
 * # 어떤 지표든 min(창 − 워밍업 + 1, 보존 일수)가 보존 일수에서 포화한다(ADR-0079).
 * # 예외는 돌린 날짜에만 나는 *_PCTL·업종 상대값 하나다 — TRADING_DAYS_750은 2026-09-08에
 * # 창 안 산출 일자마다 값을 내게 되어 이 예외에서 빠졌다(ADR-0129).
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=backfill \
 *     --as-of=2026-08-11 --from=2026-08-11'
 *
 * # 무효화된 종목만 다시 채운다 — 백분위·업종 상대값은 그래도 전 종목에서 나온다
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=backfill \
 *     --from=2025-11-17 --tickers=058470,086280'
 *
 * # 아직 켜지 않은 유니버스를 채운다 — 「켜야 지표가 생기고, 지표가 없으면 켜면 안 되는」
 * # 순환을 끊는 자리다. 대상이 그 유니버스로 좁혀지고, 백분위 모집단에도 그 하나만 더해진다.
 * # 활성 유니버스와 종목이 겹치면 시작 전에 거절한다(IndicatorBackfillJob).
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=backfill \
 *     --universe=US_TOP1000 --as-of=2026-08-28 --from=2026-08-28'
 *
 * # 기업 행위 판정 대기열과 증거를 본다. 외부 호출 0, 원장을 고치지 않는다(ADR-0084).
 * # 필터를 안 주면 status=OBSERVED로 좁힌다 — 집계는 언제나 전체를 찍는다
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --server.port=0 \
 *     --job=corporate-action-review --ca-status=UNSUPPORTED'
 *
 * # 한 행과 그 행의 상장주식수 점프 전부. 고르는 것은 사람이다
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --server.port=0 \
 *     --job=corporate-action-review --ca-id=97'
 *
 * # 판정을 적는다. MUST: --ca-note를 큰따옴표로 열었으면 반드시 닫는다 —
 * # 안 닫으면 gradle이 뒤의 플래그를 통째로 삼키고, 러너가 그것을 거절한다
 * ./gradlew bootRun --args='--spring.profiles.active=bootstrap --server.port=0 \
 *     --job=corporate-action-judge --ca-id=97 --ca-status=APPROVED \
 *     --ca-type=BONUS_ISSUE --ca-ratio=1.4699 --ca-listing-date=2021-12-20 \
 *     --ca-note="권리락 당일 주식수 배수 1.0, 창 안 정합 점프 하나"'
 * </pre>
 *
 * <p><b>원장 잡 둘에는 {@code --server.port=0}이 사실상 필수다.</b> 그것이 대신하는 일이
 * "앱을 띄우고 로그인해서 PUT"이라 <b>운영 앱이 8080에 떠 있는 것이 정상 사용 조건</b>이다.
 *
 * <p><b>포트는 손으로 비우지 않는다 — {@code application.yaml}의 {@code bootstrap} 프로파일이
 * {@code server.port: 0}을 이미 건다.</b> 운영 앱이 8080에 떠 있어도 그냥 돌리면 되고,
 * {@code --server.port=0}은 있어도 무해하지만 필요하지 않다.
 *
 * <p>IMPORTANT — 그래도 {@code BUILD SUCCESSFUL}과 <b>종료코드 0</b>을 적재의 근거로 삼지
 * 않는다. 컨텍스트가 뜨기 전에 죽는 어떤 이유로든 <b>이 러너는 아예 돌지 않고</b>
 * {@link #run}의 실패 처리가 걸리지 않는다 — 그때도 gradle은 성공을 낸다(실측).
 * <b>잡을 고른 뒤의 실패는 다르다 — 종료코드 1로 끝난다.</b> 적재 여부는 저장된 행으로 센다.
 */
@Profile("bootstrap")
@Component
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final UniverseRebalanceJob universeRebalanceJob;
    private final UniverseRestoreJob universeRestoreJob;
    private final InitialLoadJob initialLoadJob;
    private final FinancialRefillJob financialRefillJob;
    private final FullStatementRefillJob fullStatementRefillJob;
    private final CompanyProfileJob companyProfileJob;
    private final CorporateActionJob corporateActionJob;
    private final AdjustmentRebuildJob adjustmentRebuildJob;
    private final MarketIndexJob marketIndexJob;
    private final IndicatorBackfillJob indicatorBackfillJob;
    private final GovernanceLoadJob governanceLoadJob;
    private final GovernanceDeriveJob governanceDeriveJob;
    private final MarketCalendarJob marketCalendarJob;
    private final CorporateActionJudgeJob corporateActionJudgeJob;
    private final BatchExecutionSweeper executionSweeper;
    private final ConfigurableApplicationContext context;

    public BootstrapRunner(UniverseRebalanceJob universeRebalanceJob,
                           UniverseRestoreJob universeRestoreJob,
                           InitialLoadJob initialLoadJob,
                           FinancialRefillJob financialRefillJob,
                           FullStatementRefillJob fullStatementRefillJob,
                           CompanyProfileJob companyProfileJob,
                           CorporateActionJob corporateActionJob,
                           AdjustmentRebuildJob adjustmentRebuildJob,
                           MarketIndexJob marketIndexJob,
                           IndicatorBackfillJob indicatorBackfillJob,
                           GovernanceLoadJob governanceLoadJob,
                           GovernanceDeriveJob governanceDeriveJob,
                           MarketCalendarJob marketCalendarJob,
                           CorporateActionJudgeJob corporateActionJudgeJob,
                           BatchExecutionSweeper executionSweeper,
                           ConfigurableApplicationContext context) {
        this.universeRebalanceJob = universeRebalanceJob;
        this.universeRestoreJob = universeRestoreJob;
        this.initialLoadJob = initialLoadJob;
        this.financialRefillJob = financialRefillJob;
        this.fullStatementRefillJob = fullStatementRefillJob;
        this.companyProfileJob = companyProfileJob;
        this.corporateActionJob = corporateActionJob;
        this.adjustmentRebuildJob = adjustmentRebuildJob;
        this.marketIndexJob = marketIndexJob;
        this.indicatorBackfillJob = indicatorBackfillJob;
        this.governanceLoadJob = governanceLoadJob;
        this.governanceDeriveJob = governanceDeriveJob;
        this.marketCalendarJob = marketCalendarJob;
        this.corporateActionJudgeJob = corporateActionJudgeJob;
        this.executionSweeper = executionSweeper;
        this.context = context;
    }

    /**
     * IMPORTANT — <b>예외를 던지는 것만으로는 실패가 셸에 닿지 않는다.</b> devtools가 러너를
     * {@code restartedMain} 스레드에서 돌려 {@code main}은 그냥 돌아오고, gradle은
     * {@code BUILD SUCCESSFUL}과 종료코드 0을 낸다. 그러면 <b>아무 일도 안 일어난 실행이
     * 성공한 실행과 구분되지 않는다.</b>
     *
     * <p><b>{@link Throwable}까지 잡는 것이 의도다.</b> 전역 지시문이 금지하는 catch-all은
     * <b>삼키는</b> 핸들러인데 이것은 반대다 — 스택 트레이스를 남기고 <b>프로세스를 실패로
     * 끝낸다.</b> {@code RuntimeException}만 잡으면 백필 중의 {@code OutOfMemoryError} 같은
     * {@link Error}가 그대로 새어 <b>위의 exit 0으로 되돌아간다.</b> 그리고 여기서 잡지 않으면
     * 그 예외가 갈 곳이 없다 — 받을 호출부가 없다.
     *
     * <p>MUST: 갈래 둘을 합치지 않는다. 아래 각 갈래의 주석이 이유를 따로 답한다.
     *
     * <p><b>이 경로에는 테스트가 없다.</b> {@code System.exit}은 JVM을 내리므로 단위 테스트로
     * 감쌀 수 없고, 이 저장소에는 종료코드를 재는 통합 테스트도 없다({@code rg 'System.exit'
     * src/test} → 0건). <b>두 줄을 지워도 컴파일도 테스트도 전부 초록이고, 그때 증상은 다시
     * 침묵이다.</b> 판정을 순수 함수로 떼어내 테스트하는 안은 일부러 버렸다 — 그러면
     * {@code System.exit} <b>배선이 사라져도</b> 그 테스트는 초록이라, 덮이지 않은 것을 덮인
     * 것처럼 보이게 만든다.
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            runJob(args);
        } catch (BatchShutdownException e) {
            // MUST NOT: 여기서 System.exit을 부르지 않는다. 이 예외는 **종료가 이미 시작된
            // 뒤에만** 난다(BatchExecutions.onContextClosed가 abandonedOnShutdown을 켠 뒤다).
            // 그 시점의 System.exit은 종료 훅 러너가 쥔 락을 기다리므로 이 스레드가 멈춘다.
            //
            // 실패가 아니라 프로세스가 내려간다는 신호이므로 info다(ADR-0078) — error로 찍으면
            // 종료마다 Sentry 이슈가 하나씩 난다(sentry.logging.minimum-event-level: error).
            log.info("부트스트랩 러너가 종료로 중단됐다. batch_execution에 ABORTED로 남는다.");
        } catch (Throwable e) {
            // MUST: exit을 finally에 둔다. OutOfMemoryError 상황에서 log.error는 메시지
            // 포맷과 스택 렌더링으로 다시 할당하는데, 거기서 두 번째 OOME가 나면 그 줄 뒤로
            // 못 가고 **닫으려던 그 exit 0으로 되돌아간다.** exit을 앞에 놓는 것으로는 안 된다 —
            // System.exit은 돌아오지 않으므로 로그가 아예 안 남는다. finally라야 둘 다 된다.
            try {
                log.error("부트스트랩 러너가 실패했다. 아무것도 적재되거나 판정되지 않았다.", e);
            } finally {
                System.exit(1);
            }
        }
    }

    private void runJob(ApplicationArguments args) {
        // 오타는 아무것도 하기 전에 막는다. 태운 외부 호출은 되돌릴 수 없다.
        rejectUnknownOptions(args);
        rejectMalformedBooleans(args);

        String job = required(args, "job");
        rejectLedgerOptionsOutsideLedgerJobs(args, job);
        rejectMisplacedLoadOptions(args, job);
        LocalDate asOf = date(args, "as-of", LocalDate.now());

        // 이전 부트스트랩 실행이 SIGKILL 등으로 남긴 행을 여기서 닫는다.
        //
        // IMPORTANT — 스위퍼의 `ApplicationReadyEvent` 리스너는 **이 프로파일에서 돌지 않는다.**
        // `SpringApplication.run()`이 `callRunners()`를 그 이벤트 발행보다 먼저 부르고, 아래에서
        // `System.exit()`으로 내려가기 때문이다. 그래서 직접 부른다.
        //
        // 내 잡의 행은 아래에서 만들어지므로 이 시점의 정리 대상이 아니다(스위퍼가 기동 시각으로 가른다).
        //
        // MUST NOT: 원장 잡에서는 돌리지 않는다(ADR-0084). 스윕은 기동 시각 이전의
        // `MANUAL` + `RUNNING`을 **전부** 닫으므로 **다른 JVM에서 지금 돌고 있는 행까지** 마감한다.
        // 적재끼리는 "둘을 동시에 띄우는 드문 실수"였지만(`BatchExecutionSweeper` javadoc),
        // 원장 조회·판정은 몇 초짜리라 **적재가 도는 중에 아무렇지 않게 치게 된다** — 그러면 몇
        // 시간짜리 백필의 실행 행이 ABORTED가 되고 백필 자신은 계속 돌다가 마감에 실패한다.
        // 이 둘은 고아를 거의 안 남기고, 남더라도 다음 적재 잡의 스윕이 닫는다.
        if (!isReadOnlyJob(job)) {
            executionSweeper.sweepBootstrapOrphans();
        }

        log.info("부트스트랩 러너를 시작한다. job={} asOf={}", job, asOf);
        BatchStatus status = switch (job) {
            case "universe" -> universeRebalanceJob.run(
                    asOf, BatchExecutionRecorder.TriggeredBy.MANUAL, value(args, "universe", null));
            // MUST: 쓰기가 옵트인이다(--apply=true). 맨 플래그는 false가 되므로 --dry-run으로
            // 두면 오타 한 번이 되돌릴 수 없는 쓰기가 된다(ADR-0135).
            case "universe-restore" -> {
                universeRestoreJob.run(required(args, "universe"),
                        flag(args, "apply"),
                        flag(args, "replace"),
                        dates(args, "dates"));
                yield BatchStatus.SUCCESS;
            }
            case "initial-load" -> initialLoadJob.run(
                    asOf, BatchExecutionRecorder.TriggeredBy.MANUAL, loadRange(args));
            case "financial-refill" -> financialRefillJob.run(
                    asOf, BatchExecutionRecorder.TriggeredBy.MANUAL,
                    optionalInteger(args, "financial-years"), csv(args, "tickers"),
                    value(args, "universe", null),
                    flag(args, "insert-earlier-vintages"));
            // --quarters는 티커와 함께여야 하고, 전 종목 분기는 --all-quarters로 명시한다.
            // 잡이 거절하며, 그 근거는 거기 있다.
            case "full-statement-refill" -> fullStatementRefillJob.run(
                    asOf, BatchExecutionRecorder.TriggeredBy.MANUAL,
                    optionalInteger(args, "financial-years"), csv(args, "tickers"),
                    flag(args, "quarters"),
                    flag(args, "all-quarters"));
            case "company-profile" -> companyProfileJob.run(
                    asOf, BatchExecutionRecorder.TriggeredBy.MANUAL, csv(args, "tickers"),
                    value(args, "universe", null));
            // --from을 안 주면 잡이 저장된 시세의 첫 거래일을 쓴다. 러너가 기본값을 정하지
            // 않는 이유는 backfill의 minusYears(2)와 같은 두 번째 정본을 만들지 않기 위해서다.
            case "corporate-action" -> corporateActionJob.run(
                    asOf, BatchExecutionRecorder.TriggeredBy.MANUAL,
                    date(args, "from", null), csv(args, "tickers"),
                    value(args, "universe", null));
            // MUST: 이 잡의 첫 실행 앞에 ops/dump-db.sh가 있어야 한다(ADR-0049).
            // guard-rebuild-without-dump.sh가 덤프가 하나도 없을 때 이 명령을 막는다.
            case "adjustment-rebuild" -> adjustmentRebuildJob.run(
                    asOf, BatchExecutionRecorder.TriggeredBy.MANUAL, csv(args, "tickers"),
                    value(args, "universe", null));
            // 지수는 종목과 무관하므로 --tickers를 받지 않는다. --from을 안 주면 잡이 소스
            // 이력의 시작을 쓴다 — 러너가 기본값을 정하면 두 번째 정본이 생긴다.
            case "market-index" -> marketIndexJob.run(
                    asOf, BatchExecutionRecorder.TriggeredBy.MANUAL, date(args, "from", null));
            case "backfill" -> indicatorBackfillJob.run(
                    date(args, "from", asOf.minusYears(2)), asOf,
                    BatchExecutionRecorder.TriggeredBy.MANUAL, csv(args, "tickers"),
                    value(args, "universe", null));
            // MUST: --governance-endpoints로 나눠 돌린다. 안 좁히면 두 번째 실행이 3개년을
            // 주는 표를 다시 불러 콜이 조용히 늘고, 그 초과는 아무 데도 안 뜬다.
            case "governance-load" -> governanceLoadJob.run(
                    asOf, BatchExecutionRecorder.TriggeredBy.MANUAL,
                    optionalInteger(args, "governance-year"),
                    optionalInteger(args, "governance-span"),
                    governanceEndpoints(args), csv(args, "tickers"));
            // 외부 호출이 0이다. 유도 규칙을 고친 뒤 이것만 다시 돌린다.
            case "governance-derive" -> governanceDeriveJob.run(
                    asOf, BatchExecutionRecorder.TriggeredBy.MANUAL, csv(args, "tickers"));
            case "market-calendar" -> marketCalendarJob.run(
                    asOf, BatchExecutionRecorder.TriggeredBy.MANUAL, date(args, "from", null));
            // 외부 호출이 0이고 읽기만 한다. 실행 행도 안 만든다 — 적재가 도는 중에 치게 되는
            // 명령이라, 행을 열면 그 자체가 관측 대상을 흔든다(ADR-0091).
            case "bar-gap-review" -> marketCalendarJob.review(
                    csv(args, "tickers"), date(args, "from", null));
            // 외부 호출이 0이고 원장을 읽기만 한다. 실행 행도 안 만든다(ADR-0084).
            case "corporate-action-review" -> corporateActionJudgeJob.review(
                    caStatus(args), caType(args), value(args, "ca-ticker", null),
                    optionalLong(args, "ca-id"), optionalInteger(args, "ca-limit"));
            // MUST: --ca-id가 필요하다. 대상을 못 고른 판정은 아무 의미가 없으므로 기본값을 두지 않는다.
            case "corporate-action-judge" -> corporateActionJudgeJob.run(
                    asOf, BatchExecutionRecorder.TriggeredBy.MANUAL,
                    requiredLong(args, "ca-id"), judgement(args));
            default -> throw new IllegalArgumentException(
                    "알 수 없는 잡이다: '" + job
                            + "' — universe | initial-load | financial-refill"
                            + " | full-statement-refill | company-profile | corporate-action"
                            + " | adjustment-rebuild | market-index | backfill"
                            + " | governance-load | governance-derive"
                            + " | corporate-action-review | corporate-action-judge"
                            + " | market-calendar | bar-gap-review");
        };

        log.info("부트스트랩 러너가 끝났다. job={} status={}", job, status);
        if (status == BatchStatus.FAILED) {
            throw new IllegalStateException(job + " 잡이 실패했다. 로그와 batch_execution을 확인하라.");
        }

        // IMPORTANT — 잡이 끝나면 애플리케이션을 내린다. 웹 스타터가 있어 그냥 두면 서버가
        // 계속 떠 있고, 스크립트로 여러 잡을 이어 돌릴 때 다음 명령으로 넘어가지 못한다.
        // 여기까지 왔으면 성공이라 0이다 — 실패는 run()이 1로 끝낸다.
        int exitCode = SpringApplication.exit(context, () -> 0);
        System.exit(exitCode);
    }

    private InitialLoadJob.LoadRange loadRange(ApplicationArguments args) {
        InitialLoadJob.LoadRange defaults = InitialLoadJob.LoadRange.defaults();
        return new InitialLoadJob.LoadRange(
                integer(args, "price-years", defaults.priceYears()),
                integer(args, "financial-years", defaults.financialYears()),
                flag(args, "reload-existing"),
                csv(args, "tickers"),
                value(args, "universe", null),
                flag(args, "skip-financial"),
                flag(args, "skip-price"));
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
            "job", "as-of", "universe", "price-years", "financial-years", "reload-existing",
            "skip-financial", "skip-price", "tickers", "from",
            "quarters", "all-quarters", "governance-year", "governance-span",
            "governance-endpoints",
            "ca-id", "ca-status", "ca-type", "ca-ticker", "ca-limit",
            "ca-ratio", "ca-effective-date", "ca-listing-date", "ca-note",
            "apply", "replace", "dates", "insert-earlier-vintages");

    /**
     * {@code --universe}가 뜻을 갖는 잡. 나머지에서는 조용히 무시되므로 거절한다.
     *
     * <p><b>IMPORTANT — 여기 적는 것은 {@code --job=}의 값이지 {@code JOB_NAME}이 아니다.</b>
     * 둘은 같지 않다 — 리밸런싱은 {@code --job=universe}인데 {@code JOB_NAME}이
     * {@code universe-rebalance}다. {@code JOB_NAME}을 쓰면 <b>정상 명령이 막히고</b>
     * 그 실패는 인자를 고치러 가게 만든다. {@code initial-load}는 둘이 우연히 같다.
     */
    private static final java.util.Set<String> UNIVERSE_JOBS = java.util.Set.of(
            "universe", "universe-restore", "initial-load", "corporate-action",
            "adjustment-rebuild", "company-profile", "financial-refill", "backfill");

    /**
     * {@code --skip-financial}·{@code --skip-price}가 뜻을 갖는 잡. 위와 같은 이유로
     * {@code --job=}의 값이다.
     */
    private static final String INITIAL_LOAD_JOB = "initial-load";

    /** 초기 적재에서만 뜻이 있는 인자. 나머지 잡에서는 조용히 무시되므로 거절한다. */
    /**
     * {@code --insert-earlier-vintages}가 뜻을 갖는 잡. 다른 잡에 붙이면 조용히 무시되는데, 그 인자를
     * 준 사람은 이른 판이 들어갔다고 믿는다 — 아래 거절이 막는 사고다.
     */
    private static final String FINANCIAL_REFILL_JOB = "financial-refill";

    private static final java.util.Set<String> INITIAL_LOAD_ONLY_OPTIONS =
            java.util.Set.of("skip-financial", "skip-price");

    /**
     * {@code --universe}·{@code --skip-*}를 뜻이 없는 잡에서 쓰면 즉시 실패시킨다.
     *
     * <p>IMPORTANT — {@link #KNOWN_OPTIONS}가 잡별이 아니라 <b>전역</b>이라, 뜻이 없는 잡에
     * 붙은 이 인자들은 <b>아무 말 없이 무시된다.</b> {@code --universe=US_LISTED}를 붙였는데
     * 잡 이름을 잘못 적으면 <b>전 종목</b>이 대상이 되고 잡은 성공으로 끝난다 —
     * {@link #rejectLedgerOptionsOutsideLedgerJobs}가 접두어 오타에 대해 막는 것과 같은 사고다.
     */
    static void rejectMisplacedLoadOptions(ApplicationArguments args, String job) {
        if (!UNIVERSE_JOBS.contains(job) && args.getOptionNames().contains("universe")) {
            throw new IllegalArgumentException(
                    "--universe는 " + UNIVERSE_JOBS.stream().sorted().toList()
                            + "에서만 쓴다 — 지금 잡은 '" + job + "'이고, 그대로 두면 인자가 조용히"
                            + " 무시된 채 전 종목이 대상이 된다.");
        }
        if (!FINANCIAL_REFILL_JOB.equals(job) && args.getOptionNames().contains("insert-earlier-vintages")) {
            throw new IllegalArgumentException(
                    "--insert-earlier-vintages는 '" + FINANCIAL_REFILL_JOB + "'에서만 쓴다 — 지금 잡은 '"
                            + job + "'이고, 그대로 두면 인자가 조용히 무시된다.");
        }
        if (!INITIAL_LOAD_JOB.equals(job)) {
            for (String option : INITIAL_LOAD_ONLY_OPTIONS) {
                if (args.getOptionNames().contains(option)) {
                    throw new IllegalArgumentException(
                            "--" + option + "은 '" + INITIAL_LOAD_JOB + "'에서만 쓴다 — 지금 잡은 '"
                                    + job + "'이다.");
                }
            }
        }
    }

    /** 원장을 보거나 고치는 잡. {@code --ca-*}는 여기서만 뜻이 있다. */
    private static final java.util.Set<String> LEDGER_JOBS = java.util.Set.of(
            CorporateActionJudgeJob.REVIEW_JOB_NAME, CorporateActionJudgeJob.JOB_NAME);

    /**
     * 외부 호출 없이 읽기만 하는 잡. <b>고아 실행 스위퍼를 돌리지 않는다.</b>
     *
     * <p>{@link #LEDGER_JOBS}와 갈라 둔 이유는 <b>두 집합이 다른 질문에 답하기</b> 때문이다 —
     * 저쪽은 "{@code --ca-*}가 뜻을 갖는가", 이쪽은 "스윕을 돌려도 되는가"다. 하나로 두면
     * 읽기 전용 잡을 더할 때 <b>{@code --ca-*}까지 열리거나, 안 열려고 빼면 스윕이 돈다.</b>
     *
     * <p>스윕은 기동 시각 이전의 {@code MANUAL} + {@code RUNNING}을 전부 닫으므로
     * <b>다른 JVM에서 지금 돌고 있는 행까지 마감한다.</b> 이 잡들은 몇 초짜리라 적재가 도는
     * 중에 아무렇지 않게 치게 되고, 그러면 몇 시간짜리 적재의 실행 행이 ABORTED가 된다.
     */
    private static final java.util.Set<String> READ_ONLY_JOBS = java.util.Set.of(
            CorporateActionJudgeJob.REVIEW_JOB_NAME, CorporateActionJudgeJob.JOB_NAME,
            MarketCalendarJob.REVIEW_JOB_NAME);

    private static boolean isLedgerJob(String job) {
        return LEDGER_JOBS.contains(job);
    }

    // package-private — 이 집합이 갈리면 읽기 전용 명령이 남의 적재를 마감한다.
    // 그 증상은 조회 쪽에 안 나타나므로 테스트가 이름으로 잠근다.
    static boolean isReadOnlyJob(String job) {
        return READ_ONLY_JOBS.contains(job);
    }

    /**
     * {@code --ca-*}를 원장 잡 밖에서 쓰면 즉시 실패시킨다.
     *
     * <p>IMPORTANT — {@link #KNOWN_OPTIONS}가 잡별이 아니라 <b>전역</b>이고
     * {@code corporate-action}이 원장 잡 두 이름의 <b>접두어</b>라, 뒤의 {@code -judge}를
     * 빠뜨린 {@code --job=corporate-action --ca-id=97 --ca-status=APPROVED}가 그냥 통과한다.
     * 그러면 판정은 한 건도 안 들어간 채 <b>수집 잡</b>이 돌아 350종목 700콜을 태우고
     * {@code SUCCESS}로 끝난다. 이 저장소가 {@link #rejectUnknownOptions}를 만든 이유
     * ("오타 하나가 쿼터를 태운다")와 같은 사고이며, <b>접두어 관계 때문에 오타 확률이
     * 구조적으로 높다.</b>
     */
    static void rejectLedgerOptionsOutsideLedgerJobs(ApplicationArguments args, String job) {
        if (isLedgerJob(job)) {
            requireCleanNote(args);
            return;
        }
        List<String> ledgerOptions = args.getOptionNames().stream()
                .filter(name -> name.startsWith("ca-"))
                .sorted()
                .toList();
        if (!ledgerOptions.isEmpty()) {
            throw new IllegalArgumentException(
                    "--ca-* 인자는 원장 잡에서만 쓴다: " + ledgerOptions + " — 지금 잡은 '" + job
                            + "'이다. '" + CorporateActionJudgeJob.JOB_NAME + "'에서 뒤를 빠뜨린 것이면"
                            + " 그대로 두었을 때 판정은 한 건도 안 들어가고 수집 잡이 돌아 쿼터를 태운다."
                            + " 원장 잡: " + LEDGER_JOBS.stream().sorted().toList());
        }
    }

    /**
     * 문서화된 줄바꿈이 남기는 토큰. 이것만은 non-option 인자로 있어도 정상이다.
     *
     * <p>실측 — {@code --args='… \}+개행 형태는 작은따옴표 안이라 <b>리터럴 백슬래시</b>가
     * 그대로 넘어온다({@code od -c}로 확인). 이 저장소의 skill과 javadoc이 그 형태로 명령을
     * 싣고 있으므로, 걸러내지 않으면 정상 명령이 통째로 막힌다.
     */
    private static final String LINE_CONTINUATION = "\\";

    /**
     * {@code --ca-note}가 쪼개졌거나 다른 플래그를 품었는지 본다.
     *
     * <p>IMPORTANT — <b>메모에 공백이 있는데 따옴표로 안 감싸면 첫 낱말만 남고 나머지는 조용히
     * 사라진다.</b> gradle이 공백에서 쪼개고 남은 낱말은 non-option 인자가 된다
     * (실측: {@code --ca-note=메모 두 낱말}이 성공으로 끝났다). 판정은 그 잘린 메모를 그대로
     * 원장에 적는다. <b>낱말 자체는 {@link #rejectUnknownOptions}가 전 잡에서 거절하므로</b>
     * 여기 남은 몫은 아래 <b>따옴표가 뒤 플래그를 품은</b> 형태다.
     *
     * <p>따옴표를 <b>안 닫은</b> 경우는 여기까지 오지 않는다 — gradle이
     * {@code unbalanced quotes in …}로 빌드를 세운다(실측). 남는 위험은 <b>닫혀 있는데 그
     * 안에 뒤 플래그가 들어간</b> 형태이고, 그때 원장의 {@code UPDATE}가
     * {@code status = coalesce(?, status)}라 <b>상태를 안 바꾼 채 note만 덮고 SUCCESS로 끝난다.</b>
     */
    private static void requireCleanNote(ApplicationArguments args) {
        List<String> strays = strayWords(args);
        if (!strays.isEmpty()) {
            throw new IllegalArgumentException(
                    "인자에 붙지 않은 낱말이 있다: " + strays + " — 공백이 든 값(대개 --ca-note)을"
                            + " 따옴표로 감싸지 않으면 첫 낱말만 남고 나머지는 조용히 사라진다.");
        }
        String note = value(args, "ca-note", null);
        if (note != null && (note.contains("--ca-") || note.contains("--job="))) {
            throw new IllegalArgumentException(
                    "--ca-note 안에 다른 플래그가 들어 있다: '" + note + "' — 따옴표가 뒤 플래그를"
                            + " 품으면 그 플래그는 적용되지 않고, 상태는 안 바뀐 채 note만 덮이면서"
                            + " 성공으로 끝난다.");
        }
    }

    /**
     * Spring 자신의 인자는 통과시킨다.
     *
     * <p>{@code --spring.profiles.active}·{@code --server.port}가 그것이고, 전부 점을 포함한다.
     * 우리 인자는 전부 점이 없는 kebab-case이므로 <b>점 없는 미지의 이름은 오타로 본다.</b>
     * 점이 없는 Spring 플래그({@code --debug}·{@code --trace})만 예외로 적어 둔다.
     */
    private static final java.util.Set<String> SPRING_FLAGS = java.util.Set.of("debug", "trace");

    /** 어느 옵션에도 붙지 않은 낱말. 문서화된 줄바꿈의 백슬래시는 뺀다. */
    private static List<String> strayWords(ApplicationArguments args) {
        return args.getNonOptionArgs().stream()
                .filter(arg -> !LINE_CONTINUATION.equals(arg))
                .toList();
    }

    /** 패키지 공개인 이유는 컨텍스트 없이 검사만 테스트하기 위해서다 — {@code run()}은 끝에서 JVM을 내린다. */
    static void rejectUnknownOptions(ApplicationArguments args) {
        // 원장 잡만의 문제가 아니다. 공백·줄바꿈으로 펼친 티커 목록은 --tickers에 첫 티커만 남기고 나머지를
        // non-option 인자로 떨어뜨리는데, 이름 검사는 그것을 못 봐 적재 잡이 첫 종목만 돌고 성공으로 끝난다.
        List<String> strays = strayWords(args);
        if (!strays.isEmpty()) {
            throw new IllegalArgumentException(
                    "인자에 붙지 않은 낱말이 있다: " + strays.stream().limit(10).toList() + " — 목록을 공백이나 줄바꿈으로"
                            + " 펼치면 첫 값만 남고 나머지는 조용히 버려진다. 쉼표로 이어 붙이고(--tickers=A,B,C),"
                            + " 공백이 든 값은 따옴표로 감싼다.");
        }
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
        // 값이 빈 인자(--tickers=)는 셸 변수가 비었을 때 생긴다. csv()가 List.of()를 내고 잡은 그것을 「티커를 안 줬다」로
        // 읽어 기본 대상 — 활성 유니버스 전량(국내+미국) — 으로 넓힌다. 쓰기 잡이면 엉뚱한 종목을 교정·무효화하고 성공으로 끝난다.
        List<String> empty = args.getOptionNames().stream()
                .filter(name -> {
                    List<String> values = args.getOptionValues(name);
                    return values != null && values.stream().anyMatch(String::isBlank);
                })
                .sorted()
                .toList();
        if (!empty.isEmpty()) {
            throw new IllegalArgumentException(
                    "값이 빈 인자다: " + empty + " — 빈 --tickers·--universe는 「안 줬다」로 읽혀 대상이 활성 유니버스 전량으로"
                            + " 넓어진다. 목록을 만든 변수·파일이 비지 않았는지 본다.");
        }
        // Spring은 같은 이름을 두 번 받으면 값을 둘 다 담는데 value()는 첫 값만 읽는다. 티커 파일 둘을
        // --tickers로 나눠 주면 두 번째 목록이 조용히 빠진 채 성공으로 끝난다.
        List<String> repeated = args.getOptionNames().stream()
                .filter(name -> {
                    List<String> values = args.getOptionValues(name);
                    return values != null && values.size() > 1;
                })
                .sorted()
                .toList();
        if (!repeated.isEmpty()) {
            throw new IllegalArgumentException(
                    "같은 인자를 두 번 줬다: " + repeated + " — 러너는 첫 값만 읽으므로 나머지가 조용히"
                            + " 버려진다. 값을 하나로 합쳐라(예: --tickers=A,B,C).");
        }
    }

    /**
     * 값이 붙지 않았거나 {@code true}·{@code false}가 아닌 불리언 인자를 거절한다.
     *
     * <p>IMPORTANT — <b>{@code --skip-price}는 「건너뛴다」가 아니라 {@code false}다.</b>
     * Spring의 {@code SimpleCommandLineArgsParser}는 값 없는 {@code --foo}를 <b>빈 목록</b>으로
     * 담고, {@link #value}가 그것을 {@code isEmpty()}로 보아 폴백 {@code "false"}를 낸다.
     * 그래서 시세를 건너뛰라고 적어 놓고 <b>시세를 받는다</b> — 그러면 최근 봉이 함께 들어와
     * 보존 경계가 밀리고, 그 어긋남은 자연 소멸하지 않는다(ADR-0088).
     *
     * <p>이 검사가 없으면 <b>실행은 성공으로 끝나고</b> 잘못된 것은 데이터에만 남는다.
     * 2026-09-04까지 이 클래스의 javadoc 예시와 {@code warn-bootstrap-args.sh}의 처방이 둘 다
     * 값 없이 적혀 있었다 — <b>문서를 고치는 것만으로는 다음 사람이 같은 자리를 밟는다.</b>
     *
     * <p>{@code =1}·{@code =yes}도 같은 자리다 — {@code Boolean.parseBoolean}이 {@code true} 말고는 전부
     * 거짓으로 읽는다. {@code --insert-earlier-vintages=1}이면 이른 판을 안 넣은 채 교정·무효화만 도는
     * 운영 쓰기가 성공으로 끝난다.
     */
    static void rejectMalformedBooleans(ApplicationArguments args) {
        List<String> malformed = BOOLEAN_OPTIONS.stream()
                .filter(name -> args.containsOption(name))
                .filter(name -> {
                    List<String> values = args.getOptionValues(name);
                    return values == null || values.size() != 1
                            || !(values.get(0).equalsIgnoreCase("true")
                                    || values.get(0).equalsIgnoreCase("false"));
                })
                .sorted()
                .toList();
        if (!malformed.isEmpty()) {
            throw new IllegalArgumentException(
                    "값이 없거나 true·false가 아닌 불리언 인자다: " + malformed + " — 값 없는 --skip-price나"
                            + " --skip-price=1은 건너뛰기가 아니라 false가 되어 정반대로 동작하고, 실행은"
                            + " 성공으로 끝난다. --skip-price=true 처럼 true·false를 붙여라.");
        }
    }

    /**
     * 불리언으로 읽는 인자 전부 — {@link #rejectMalformedBooleans}가 실행 전에 이 목록을 검사한다.
     *
     * <p><b>MUST: 불리언은 {@link #flag}로만 읽는다.</b> {@code flag}가 이 목록에 없는 이름을 거절하므로
     * 새 인자를 여기 안 더하면 그 잡이 첫 실행에서 멈춘다. 2026-09-17까지는 호출부가
     * {@code Boolean.parseBoolean}을 직접 불렀고 javadoc이 「시험이 이 목록과 호출부를 대조한다」고 적었는데
     * <b>그런 시험은 없었다</b> — 그래서 {@code insert-earlier-vintages}가 빠진 채 초록이었다.
     */
    static final java.util.Set<String> BOOLEAN_OPTIONS = java.util.Set.of(
            "skip-financial", "skip-price", "reload-existing", "quarters", "all-quarters",
            "apply", "replace", "insert-earlier-vintages");

    static boolean flag(ApplicationArguments args, String name) {
        if (!BOOLEAN_OPTIONS.contains(name)) {
            throw new IllegalStateException("--" + name + "이 BOOLEAN_OPTIONS에 없다 — 실행 전 검사가 이"
                    + " 인자의 값 없음·오타를 못 본다. 목록에 더하라.");
        }
        return Boolean.parseBoolean(value(args, name, "false"));
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

    private static Long optionalLong(ApplicationArguments args, String name) {
        String raw = value(args, name, null);
        return raw == null ? null : Long.valueOf(raw);
    }

    private static long requiredLong(ApplicationArguments args, String name) {
        return Long.parseLong(required(args, name));
    }

    /**
     * 판정할 값. 비운 항목은 원장이 그대로 둔다.
     *
     * <p>MUST NOT: 여기서 값들 사이의 관계를 검증하지 않는다({@code type}과 {@code ratio}가 함께
     * 왔는가, 승인인데 종류를 아는가). 그 판정의 정본은 {@code CorporateActionLedger}이고,
     * 러너가 자기 사본을 들면 HTTP 경로와 갈린다 — {@code CorporateActionJudgementRequest}가
     * 같은 이유로 Jakarta 제약을 하나도 안 쓴다.
     */
    private static CorporateActionLedger.Judgement judgement(ApplicationArguments args) {
        return new CorporateActionLedger.Judgement(
                caStatus(args), caType(args),
                optionalDecimal(args, "ca-ratio"),
                date(args, "ca-effective-date", null),
                date(args, "ca-listing-date", null),
                value(args, "ca-note", null));
    }

    private static java.math.BigDecimal optionalDecimal(ApplicationArguments args, String name) {
        String raw = value(args, name, null);
        return raw == null ? null : new java.math.BigDecimal(raw);
    }

    /**
     * MUST: 모르는 이름에 <b>즉시 실패한다.</b> {@link #governanceEndpoints}와 같은 계약이다 —
     * 조용히 null로 떨어뜨리면 오타 하나가 「상태를 안 고친 판정」이 되는데, 원장이 그것을
     * 정당한 경로로 받아들여 성공으로 끝난다.
     */
    private static CorporateActionStatus caStatus(ApplicationArguments args) {
        String raw = value(args, "ca-status", null);
        if (raw == null) {
            return null;
        }
        try {
            return CorporateActionStatus.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("모르는 원장 상태다: '" + raw + "' — 아는 이름: "
                    + java.util.Arrays.toString(CorporateActionStatus.values()));
        }
    }

    private static CorporateAction.Type caType(ApplicationArguments args) {
        String raw = value(args, "ca-type", null);
        if (raw == null) {
            return null;
        }
        try {
            return CorporateAction.Type.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("모르는 기업 행위 종류다: '" + raw + "' — 아는 이름: "
                    + java.util.Arrays.toString(CorporateAction.Type.values()));
        }
    }

    /** 안 주면 null. 잡이 자기 기본값을 아는 편이 낫다 — 러너가 두 번째 정본이 되지 않는다. */
    private static Integer optionalInteger(ApplicationArguments args, String name) {
        String raw = value(args, name, null);
        return raw == null ? null : Integer.valueOf(raw);
    }

    /** 쉼표로 구분한 목록. 빈 조각은 버린다 — 후행 쉼표가 빈 티커가 되면 안 된다. */
    /**
     * {@code --governance-endpoints=DIVIDEND,AUDIT_OPINION}. 비면 여덟 전부다.
     *
     * <p>MUST: 모르는 이름에 <b>즉시 실패한다.</b> 조용히 걸러내면 오타 하나가 「그 표만 안
     * 받힌 실행」이 되는데, 성공으로 끝나 다음 세션이 적재됐다고 믿는다.
     */
    private static List<GovernanceEndpoint> governanceEndpoints(ApplicationArguments args) {
        List<GovernanceEndpoint> endpoints = new java.util.ArrayList<>();
        for (String name : csv(args, "governance-endpoints")) {
            try {
                endpoints.add(GovernanceEndpoint.valueOf(name.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("모르는 거버넌스 표 이름이다: '" + name
                        + "' — 아는 이름: " + java.util.Arrays.toString(GovernanceEndpoint.values()));
            }
        }
        return endpoints;
    }

    /**
     * 쉼표로 준 날짜 목록. 복원 대조가 <b>원장이 실제로 쓴 날짜</b>를 그대로 재생할 때 쓴다 —
     * 주간 격자로는 그 날짜들이 안 걸린다(관측 하한 뒤라 격자에서 잘리고, 손으로 돌린 실행은
     * 그 주의 마지막 거래일도 아니다).
     */
    private static List<LocalDate> dates(ApplicationArguments args, String name) {
        return csv(args, name).stream().map(LocalDate::parse).toList();
    }

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
