package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.FinancialCollector;
import com.stockscreener.pipeline.collector.FinancialWriter;
import com.stockscreener.pipeline.collector.PriceCollector;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 초기 적재. 시세 2년·재무 3년을 한 번 받아온다(spec §7).
 *
 * <p>MUST: 일일 갱신과 <b>분리된 잡</b>이다({@code data-pipeline.md}). 성격이 다른 1회성
 * 작업이며, 조회 기간과 쓰기 패턴이 다르다. 하나로 합쳐 "첫 실행인가"로 분기하면 그 판단이
 * 틀렸을 때 850종목의 2년치를 매일 다시 받는다.
 *
 * <p>수동 실행을 전제한다 — 기동만으로는 아무 잡도 돌지 않는다. 애플리케이션을 재시작할
 * 때마다 시작되면 안 되기 때문이다.
 *
 * <h2>쿼터 소진과 재시작</h2>
 * <p>일일 호출 한도가 있는 소스에서 850종목 × 2년을 한 번에 받는 것은 <b>한 번에 끝나지 않는
 * 것이 정상</b>이다. 이미 적재된 종목을 건너뛰므로, 중단된 뒤 다음 날 다시 실행하면 이어서
 * 진행된다. <b>재개는 체크포인트가 아니라 이 잡의 대상 선정과 멱등 upsert가 준다</b>(ADR-0075).
 */
@Component
public class InitialLoadJob {

    public static final String JOB_NAME = "initial-load";

    /** spec §7 — 기술적 지표 계산을 위한 최소 시세 이력. */
    private static final int PRICE_HISTORY_YEARS = 2;

    /** spec §7 — 성장률 계산을 위한 최소 재무 이력. */
    private static final int FINANCIAL_HISTORY_YEARS = 3;

    private static final Logger log = LoggerFactory.getLogger(InitialLoadJob.class);

    private final PriceCollector priceCollector;
    private final FinancialCollector financialCollector;
    private final StockRegistrar stockRegistrar;
    private final BatchExecutions batchExecutions;
    private final JdbcTemplate jdbcTemplate;

    public InitialLoadJob(PriceCollector priceCollector, FinancialCollector financialCollector,
                          StockRegistrar stockRegistrar, BatchExecutions batchExecutions,
                          JdbcTemplate jdbcTemplate) {
        this.priceCollector = priceCollector;
        this.financialCollector = financialCollector;
        this.stockRegistrar = stockRegistrar;
        this.batchExecutions = batchExecutions;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 아직 적재되지 않은 종목의 이력을 spec 기본값(시세 2년·재무 3년)으로 받아온다.
     *
     * @param asOf 적재 기준일. 여기서 2년·3년을 거슬러 올라간다.
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy) {
        return run(asOf, triggeredBy,
                new LoadRange(PRICE_HISTORY_YEARS, FINANCIAL_HISTORY_YEARS, false));
    }

    /**
     * 기간과 대상 범위를 지정해 적재한다.
     *
     * <p>기간을 인자로 받는 이유는 <b>단계적 적재</b> 때문이다. 소수 종목 2년으로 검증한 뒤
     * 전 기간으로 넓히는 순서를 밟으려면, 상수로 박힌 기간을 바꿔 재배포할 수는 없다.
     * 기본값은 spec §7의 2년·3년으로 두어 스케줄러 경로의 의미는 그대로다.
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           LoadRange range) {
        List<SecurityRef> targets = stockRegistrar.toSecurityRefs(resolveTargets(range));
        if (targets.isEmpty()) {
            log.info("초기 적재가 필요한 종목이 없다. 모든 편입 종목에 이력이 있다.");
            BatchExecutionContext empty =
                    batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0);
            return empty.finish();
        }

        log.info("초기 적재를 시작한다. 대상 {}종목, 시세 {}년·재무 {}년 (대상 선정: {})",
                targets.size(), range.priceYears(), range.financialYears(),
                range.hasTickers() ? "지정한 티커 " + range.tickers().size() + "개"
                        : range.reloadExisting() ? "편입 전 종목" : "이력이 없는 종목만");

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, targets.size());

        // 종목당 시세·재무 두 단계를 돌므로 작업 단위는 종목 수의 두 배다.
        execution.plan(CollectionPhase.PRICE, targets.size());
        execution.plan(CollectionPhase.FINANCIAL, targets.size());

        LocalDate priceFrom = asOf.minusYears(range.priceYears());
        int fromFiscalYear = asOf.getYear() - range.financialYears();

        // 이 잡은 APPEND_ONLY지만 그것이 정정공시를 막지는 않는다 — 막는 것은
        // FILL_WITHOUT_REVISION 하나뿐이다. --tickers로 이미 재무가 있는 종목을 다시 받으면
        // 여기서도 개정본이 쌓이고 그 종목의 지표가 지워진다. 세지 않으면 그 사실이 로그에만
        // 남는다.
        AtomicInteger revised = new AtomicInteger();

        try {
            for (SecurityRef target : targets) {
                // 시세와 재무를 각각 독립된 단계로 처리한다. 재무만 실패한 종목의 시세를
                // 재실행에서 다시 받지 않으려면 실패 기록이 단계를 구분해야 한다.
                execution.process(target, CollectionPhase.PRICE,
                        security -> priceCollector.collect(security, priceFrom, asOf));
                execution.process(target, CollectionPhase.FINANCIAL, security -> {
                    FinancialWriter.Outcome outcome = financialCollector.collectDetailed(
                            security, fromFiscalYear, FinancialWriter.WriteMode.APPEND_ONLY);
                    revised.addAndGet(outcome.revised());
                    return outcome.changed();
                });
            }
        } catch (DataSourceException e) {
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }

        if (revised.get() > 0) {
            // 초기 적재에서 정정이 나오는 것은 --tickers로 기존 종목을 다시 받았을 때뿐이다.
            // 드물지만 결과는 결손 보정과 같으므로 같은 무게로 알린다.
            execution.alertOperator(AlertSeverity.WARNING, "정정공시 반영 — 백필이 남았다",
                    ("초기 적재 중 정정공시 %d기간을 반영했다. **그 종목의 지표가 보존 창 전체에서 "
                            + "지워졌다.** 백필이 따라오지 않으면 화면에서 통째로 사라지는데 에러도 "
                            + "경고도 나지 않는다. 어느 종목인지는 로그의 '정정공시를 반영한다'가 답한다.")
                            .formatted(revised.get()));
        }
        return execution.finish("정정=%d".formatted(revised.get()));
    }

    /**
     * 대상 종목을 정한다.
     *
     * <p>IMPORTANT — <b>티커를 주면 {@link #stocksNeedingHistory()}의 {@code NOT EXISTS}를
     * 적용하지 않는다.</b> 교집합으로 만들면 봉이 한 개라도 있는 종목을 지정했을 때 대상이
     * 0건이 되고, 아래 호출부가 "적재가 필요한 종목이 없다"며 <b>성공으로 끝난다</b> —
     * 그것은 이 경로가 고치려는 결함(신규 편입 종목이 1~2봉만 있어 영원히 건너뛰어지는 것)과
     * 정확히 같은 모양이다. 티커를 손으로 적은 것 자체가 "이 종목을 다시 받아라"는 뜻이므로
     * {@code reloadExisting}도 함께 무시한다.
     *
     * <p>매칭이 하나도 없으면 <b>예외로 즉시 실패시킨다.</b> 오타 하나가 조용한 0건으로
     * 끝나면 안 된다({@link FinancialRefillJob}과 같은 기준이다).
     */
    private List<Long> resolveTargets(LoadRange range) {
        if (!range.hasTickers()) {
            return range.reloadExisting()
                    ? stockRegistrar.activeUniverseMemberIds()
                    : stocksNeedingHistory();
        }
        List<Long> matched = stockRegistrar.activeUniverseMemberIdsByTicker(range.tickers());
        if (matched.isEmpty()) {
            throw new IllegalArgumentException(
                    "준 티커 중 편입 중인 활성 종목이 하나도 없다: " + range.tickers()
                            + " — 종목코드를 확인하라. 대상 0건으로 성공시키지 않는다.");
        }
        if (matched.size() < range.tickers().size()) {
            log.warn("티커 {}개 중 {}개만 편입 중인 활성 종목이다. 나머지는 건너뛴다: {}",
                    range.tickers().size(), matched.size(), range.tickers());
        }
        return matched;
    }

    /**
     * 이력이 없는 편입 종목.
     *
     * <p>이미 적재된 종목을 건너뛰는 것이 재실행 가능성의 근거다. 쿼터 소진으로 중단해도
     * 다음 실행이 남은 종목부터 이어서 처리한다.
     *
     * <p>{@code NOT EXISTS}로 판정하는 이유는 "한 봉이라도 있으면 적재된 것으로 본다"가
     * 이 시점에 충분하기 때문이다. 부분 적재의 정밀한 판정은 시세 이력의 실제 시작일을
     * 봐야 하는데, 상장일이 2년 이내인 종목과 구분할 수 없어 오히려 틀린다.
     *
     * <p>IMPORTANT — 그 대가로 <b>기간을 늘려도 대상이 0건이 된다.</b> 2년치를 받아둔 상태에서
     * 7년으로 넓히면 봉이 이미 있어 전 종목이 걸러지고, 잡은 "적재할 종목이 없다"며 성공으로
     * 끝난다. 기간을 늘렸는데 아무 일도 일어나지 않고 성공으로 보이는 것이 그 결함의 성질이며,
     * {@code reloadExisting}이 존재하는 이유가 그것이다.
     */
    private List<Long> stocksNeedingHistory() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                WHERE m.effective_to IS NULL AND s.is_active
                  AND NOT EXISTS (SELECT 1 FROM price_daily p WHERE p.stock_id = m.stock_id)
                ORDER BY m.stock_id
                """, Long.class);
    }

    /**
     * 적재 기간과 대상 범위.
     *
     * @param priceYears     시세를 몇 년 거슬러 받을지
     * @param financialYears 재무를 몇 년 거슬러 받을지
     * @param reloadExisting 이미 시세가 있는 종목도 다시 받을지. 기간 확장 시 참이다.
     * @param tickers        비면 위 두 규칙대로 전 종목. 값이 있으면 <b>그 종목만</b>이고
     *                       {@code reloadExisting}은 무시된다 — 아래 참조.
     */
    public record LoadRange(int priceYears, int financialYears, boolean reloadExisting,
                            List<String> tickers) {

        public LoadRange {
            if (priceYears < 0 || financialYears < 0) {
                throw new IllegalArgumentException(
                        "적재 연수는 음수일 수 없다: 시세=" + priceYears + " 재무=" + financialYears);
            }
            tickers = tickers == null ? List.of() : List.copyOf(tickers);
        }

        public LoadRange(int priceYears, int financialYears, boolean reloadExisting) {
            this(priceYears, financialYears, reloadExisting, List.of());
        }

        /** spec §7의 기본값 — 시세 2년·재무 3년, 미적재 종목만. */
        public static LoadRange defaults() {
            return new LoadRange(PRICE_HISTORY_YEARS, FINANCIAL_HISTORY_YEARS, false);
        }

        public boolean hasTickers() {
            return !tickers.isEmpty();
        }
    }
}
