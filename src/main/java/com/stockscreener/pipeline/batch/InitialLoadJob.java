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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
    // MUST: 이 값 × 연 거래일이 IndicatorRegistry.maxEffectiveLookback()(지금 1,261봉,
    // RetainedToMarket4yCalculator.LOOKBACK이 정한다)보다 커야 한다 —
    // FinancialLoadWindowTest가 그 부등식을 단언한다. 시세는 종목당 1콜(기간조회)이라
    // 창을 넓혀도 콜이 안 늘고, MembershipGapFiller는 이미 7을 쓴다.
    static final int PRICE_HISTORY_YEARS = 7;

    /** spec §7 — 성장률 계산을 위한 최소 재무 이력. */
    // MUST: 위 둘과 같은 부등식이 걸린다(FinancialLoadWindowTest). 여기가 특히 조용하다 —
    // MembershipGapFiller가 LoadRange.defaults()로 신규 편입 종목을 채우므로, 이 값이
    // 지표 요구보다 작으면 리밸런싱마다 이력이 짧은 종목이 새로 들어온다.
    static final int FINANCIAL_HISTORY_YEARS = 8;

    private static final Logger log = LoggerFactory.getLogger(InitialLoadJob.class);

    private final PriceCollector priceCollector;
    private final FinancialCollector financialCollector;
    private final StockRegistrar stockRegistrar;
    private final BatchExecutions batchExecutions;
    private final JdbcTemplate jdbcTemplate;
    private final FinancialLoadWindow loadWindow;

    public InitialLoadJob(PriceCollector priceCollector, FinancialCollector financialCollector,
                          StockRegistrar stockRegistrar, BatchExecutions batchExecutions,
                          JdbcTemplate jdbcTemplate, FinancialLoadWindow loadWindow) {
        this.priceCollector = priceCollector;
        this.financialCollector = financialCollector;
        this.stockRegistrar = stockRegistrar;
        this.batchExecutions = batchExecutions;
        this.jdbcTemplate = jdbcTemplate;
        this.loadWindow = loadWindow;
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
        List<Long> targetIds = resolveTargets(range);
        // 이름이 range.skipPrice()와 비슷하지만 뜻이 다르다. 이쪽은 「봉이 이미 있어 재수집이
        // 불필요한 종목」이라는 종목별 판단이고, 저쪽은 「이번 실행은 시세를 아예 안 받는다」는
        // 전역 플래그다. 둘을 한 기제로 합치면 --skip-price가 유니버스 경로에서 무력화된다 —
        // stocksToSkipPrice는 hasUniverse()면 빈 집합을 조기 반환하기 때문이다.
        Set<Long> skippedForResume = stocksToSkipPrice(range, targetIds);
        List<SecurityRef> targets = stockRegistrar.toSecurityRefs(targetIds);
        if (targets.isEmpty()) {
            log.info("초기 적재가 필요한 종목이 없다. 모든 편입 종목에 이력이 있다.");
            BatchExecutionContext empty =
                    batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0);
            return empty.finish();
        }

        log.info("초기 적재를 시작한다. 대상 {}종목, 시세 {}·재무 {} (대상 선정: {})",
                targets.size(),
                range.skipPrice() ? "건너뜀" : range.priceYears() + "년",
                range.skipFinancial() ? "건너뜀" : range.financialYears() + "년",
                range.hasUniverse()
                        ? "유니버스 " + range.universeCode()
                                + (range.hasTickers() ? " ∩ 티커 " + range.tickers().size() + "개" : "")
                        : range.hasTickers() ? "지정한 티커 " + range.tickers().size() + "개"
                                : range.reloadExisting() ? "편입 전 종목" : "이력이 없는 종목만");

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, targets.size());

        // 종목당 시세·재무 두 단계를 돌되, 재무 결손 때문에 잡힌 종목은 시세를 건너뛴다.
        // 계획 수가 실제와 어긋나면 관리자 화면의 진행률이 100%에 못 미친 채 끝난다.
        if (!range.skipPrice()) {
            // MUST: 건너뛰면 계획도 함께 뺀다. 재무 쪽과 같은 이유다 — 안 빼면 진행률이
            // 절반에서 끝나고, 그것은 멎은 것과 구분되지 않는다.
            execution.plan(CollectionPhase.PRICE, targets.size() - skippedForResume.size());
        }
        if (!range.skipFinancial()) {
            // MUST: 건너뛰면 계획도 함께 뺀다. 안 빼면 진행률이 절반에서 끝나고, 그것은
            // 멎은 것과 구분되지 않는다.
            execution.plan(CollectionPhase.FINANCIAL, targets.size());
        }

        LocalDate priceFrom = asOf.minusYears(range.priceYears());
        int fromFiscalYear = asOf.getYear() - range.financialYears();
        if (!range.skipFinancial()) {
            loadWindow.warnIfShorterThanIndicators(JOB_NAME, range.financialYears());
        }

        // 이 잡은 APPEND_ONLY지만 그것이 정정공시를 막지는 않는다 — 막는 것은
        // FILL_WITHOUT_REVISION 하나뿐이다. --tickers로 이미 재무가 있는 종목을 다시 받으면
        // 여기서도 개정본이 쌓이고 그 종목의 지표가 지워진다. 세지 않으면 그 사실이 로그에만
        // 남는다.
        AtomicInteger revised = new AtomicInteger();

        try {
            for (SecurityRef target : targets) {
                // 시세와 재무를 각각 독립된 단계로 처리한다. 재무만 실패한 종목의 시세를
                // 재실행에서 다시 받지 않으려면 실패 기록이 단계를 구분해야 한다.
                if (!range.skipPrice() && !skippedForResume.contains(target.stockId())) {
                    execution.process(target, CollectionPhase.PRICE,
                            security -> priceCollector.collect(security, priceFrom, asOf));
                }
                if (!range.skipFinancial()) {
                    execution.process(target, CollectionPhase.FINANCIAL, security -> {
                        FinancialWriter.Outcome outcome = financialCollector.collectDetailed(
                                security, fromFiscalYear, FinancialWriter.WriteMode.APPEND_ONLY);
                        revised.addAndGet(outcome.revised());
                        return outcome.changed();
                    });
                }
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
        if (range.skipFinancial()) {
            return execution.finish("재무 건너뜀 (그 시장에 재무 소스가 없다)");
        }
        return execution.finish(range.skipPrice()
                ? "시세 건너뜀 · 정정=%d".formatted(revised.get())
                : "정정=%d".formatted(revised.get()));
    }

    /**
     * 대상 종목을 정한다.
     *
     * <p>IMPORTANT — <b>티커를 주면 {@code StockRegistrar.activeUniverseMemberIdsNeedingHistory()}의 {@code NOT EXISTS}를
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
        if (range.hasUniverse()) {
            return resolveUniverseTargets(range);
        }
        if (!range.hasTickers()) {
            return range.reloadExisting()
                    ? stockRegistrar.activeUniverseMemberIds()
                    : stockRegistrar.activeUniverseMemberIdsNeedingHistory();
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
     * 유니버스를 손으로 골랐을 때의 대상.
     *
     * <p>{@code StockRegistrar.oneUniverseMemberIds}가 <b>활성 여부를 보지 않는다</b> —
     * 비활성인 채로 적재해야 하는 상태가 실재하기 때문이다. 그 대신 {@code CollectedMarkets}가
     * 유일한 게이트이므로 <b>0건이면 예외로 끝낸다.</b> 조용한 0건은 "적재할 것이 없다"와
     * 구별되지 않는다.
     *
     * <p>{@code reloadExisting}이 아니면 <b>봉이 없는 종목만</b> 남긴다 — 쿼터가 마르거나
     * 프로세스가 죽어도 다음 실행이 이어서 처리하게 하는 것이 이 잡의 재개 방식이다.
     */
    private List<Long> resolveUniverseTargets(LoadRange range) {
        List<Long> members =
                stockRegistrar.oneUniverseMemberIds(range.universeCode(), range.tickers());
        if (members.isEmpty()) {
            throw new IllegalArgumentException(
                    "유니버스 '" + range.universeCode() + "'에 대상이 하나도 없다"
                            + (range.hasTickers() ? " (티커 " + range.tickers() + ")" : "")
                            + " — 유니버스 코드와, 그 시장이 CollectedMarkets에 있는지 확인하라."
                            + " 대상 0건으로 성공시키지 않는다.");
        }
        if (range.reloadExisting()) {
            return members;
        }
        Set<Long> loaded = stocksWithBars(members);
        List<Long> remaining = members.stream().filter(id -> !loaded.contains(id)).toList();
        log.info("유니버스 {} 편입 {}종목 중 시세가 없는 {}종목이 대상이다.",
                range.universeCode(), members.size(), remaining.size());
        return remaining;
    }

    private Set<Long> stocksWithBars(List<Long> stockIds) {
        if (stockIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = stockIds.stream().map(id -> "?").collect(Collectors.joining(","));
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT stock_id FROM price_daily WHERE stock_id IN (" + placeholders + ")",
                Long.class, stockIds.toArray()));
    }

    /**
     * 시세를 건너뛸 종목. <b>재무 결손 때문에 잡힌 종목</b>이 여기 든다.
     *
     * <p>대상 선정을 넓히기만 하면 그 종목이 루프에서 시세도 다시 받는다 — 이 잡의 제약이
     * 쿼터이므로 그것은 고침이 아니라 다른 낭비다.
     *
     * <p>MUST NOT: {@code --tickers}·{@code --reload-existing} 경로에 이 가드를 적용하지 않는다.
     * 둘은 <b>"이미 있어도 다시 받아라"는 명시적 지시</b>이고, 가드를 걸면 그 지시가 조용히
     * 무시되어 "돌렸는데 아무 일도 없었다"가 된다.
     */
    private Set<Long> stocksToSkipPrice(LoadRange range, List<Long> targetIds) {
        if (range.hasTickers() || range.reloadExisting() || range.hasUniverse()
                || targetIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = targetIds.stream().map(id -> "?").collect(Collectors.joining(","));
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT stock_id FROM price_daily WHERE stock_id IN (" + placeholders + ")",
                Long.class, targetIds.toArray()));
    }

    /**
     * 적재 기간과 대상 범위.
     *
     * @param priceYears     시세를 몇 년 거슬러 받을지
     * @param financialYears 재무를 몇 년 거슬러 받을지
     * @param reloadExisting 이미 시세가 있는 종목도 다시 받을지. 기간 확장 시 참이다.
     * @param tickers        비면 위 두 규칙대로 전 종목. 값이 있으면 <b>그 종목만</b>이고
     *                       {@code reloadExisting}은 무시된다 — 아래 참조.
     * @param skipFinancial  재무를 건너뛴다. 그 시장에 재무 소스가 없을 때 쓴다.
     * @param skipPrice      시세를 건너뛴다. <b>{@code skipFinancial}과 대칭이다.</b>
     *                       재무만 받아야 하는 상태가 실재하기 때문에 있다 — 미국 시세는
     *                       파일럿 창(과거 구간)으로만 들어와 있고, 재무를 받으려고 이 잡을
     *                       다시 돌리면 <b>최근 시세가 함께 들어온다.</b> 그러면 시장을 안
     *                       가르고 거래일을 세는 읽기들(지표 기준일·보존 경계·결손 판정·
     *                       백필 대상)이 함께 밀리고, {@code purge()}는 {@code indicator_value}
     *                       기준이라 그 어긋남이 자연 소멸하지 않는다(ADR-0088 §배경).
     */
    public record LoadRange(int priceYears, int financialYears, boolean reloadExisting,
                            List<String> tickers, String universeCode, boolean skipFinancial,
                            boolean skipPrice) {

        public LoadRange {
            if (priceYears < 0 || financialYears < 0) {
                throw new IllegalArgumentException(
                        "적재 연수는 음수일 수 없다: 시세=" + priceYears + " 재무=" + financialYears);
            }
            if (skipPrice && skipFinancial) {
                // 둘 다 건너뛰면 이 잡이 아무것도 하지 않고 성공으로 끝난다. 조용한 0건이다.
                throw new IllegalArgumentException(
                        "시세와 재무를 둘 다 건너뛰면 이 잡이 할 일이 없다.");
            }
            tickers = tickers == null ? List.of() : List.copyOf(tickers);
            universeCode = universeCode == null || universeCode.isBlank() ? null : universeCode;
        }

        public LoadRange(int priceYears, int financialYears, boolean reloadExisting,
                         List<String> tickers, String universeCode, boolean skipFinancial) {
            this(priceYears, financialYears, reloadExisting, tickers, universeCode,
                    skipFinancial, false);
        }

        public LoadRange(int priceYears, int financialYears, boolean reloadExisting,
                         List<String> tickers) {
            this(priceYears, financialYears, reloadExisting, tickers, null, false, false);
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

        /** 유니버스를 손으로 골랐는가. 그러면 활성 여부를 보지 않는다. */
        public boolean hasUniverse() {
            return universeCode != null;
        }
    }
}
