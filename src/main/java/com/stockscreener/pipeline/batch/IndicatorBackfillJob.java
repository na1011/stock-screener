package com.stockscreener.pipeline.batch;

import com.stockscreener.indicator.IndicatorRetentionPolicy;
import com.stockscreener.indicator.IndicatorTradeDateIndex;
import com.stockscreener.indicator.IndicatorRunner;
import com.stockscreener.indicator.PercentileCalculator;
import com.stockscreener.indicator.SectorRelativeCalculator;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;

/**
 * 이미 적재된 원자료로 지표를 소급 계산한다.
 *
 * <p>MUST NOT: 이 분기를 {@link DailyUpdateJob}에 섞지 않는다({@code data-pipeline.md}).
 * 일일 갱신은 하루치를, 이 잡은 기간 전체를 다룬다. 섞으면 매일 "오늘이 백필해야 하는 날인가"를
 * 판단하게 되고, 그 판단이 틀리면 850종목 × 500일을 매일 다시 계산한다.
 *
 * <p>쓰는 때가 셋이다 — ① 초기 적재 직후 과거 구간을 한 번 채울 때, ② 지표를 새로 추가해
 * 과거 값이 비어 있을 때, ③ 액면분할·정정공시로 무효화된 구간을 다시 채울 때.
 *
 * <p>외부 API를 호출하지 않는다. 원자료는 이미 DB에 있고 계산만 다시 한다 — 그래서 쿼터를
 * 신경 쓸 필요가 없고, 실패해도 다시 돌리는 비용이 낮다.
 *
 * <h2>{@code tickers}가 순위의 모집단을 좁히지 않는 이유</h2>
 * <p>phase 기준이 <i>"재계산이 순위의 모집단을 좁히지 않는다"</i>를 명시한다(§P9 · ADR-0036과
 * 같은 자리다) — 일부 종목만 다시 계산해도 백분위·업종 상대값은 전 종목에서 나와야 한다.
 * 그것이 <b>구조로</b> 성립한다: {@link PercentileCalculator#calculate}와
 * {@link SectorRelativeCalculator#calculate}는 <b>거래일만 받고</b> {@code indicator_value}를
 * 활성 유니버스 전량과 조인하므로, 아래 종목 루프를 좁혀도 두 계산은 좁아지지 않는다.
 *
 * <p>MUST NOT: 두 호출을 {@code targets}로 좁히지 않는다. 좁히면 대상 밖 종목의 순위가
 * 조용히 낡는데 <b>값이 그럴듯해 화면에서 드러나지 않는다.</b>
 * {@code IndicatorBackfillTickerScopeTest}가 그것을 못박는다.
 *
 * <p>이 필터가 있어야 하는 이유는 <b>무효화가 낀 재백필의 복구 비용</b>이다.
 * {@code AdjustmentRecalculator.rebuild}가 값을 바꾸면 그 종목의 {@code indicator_value}가
 * <b>보존 창 전체</b>에서 지워진다({@code StoredIndicatorInvalidationPort}의 {@code >=}).
 * 필터가 없으면 한 종목의 판정을 고칠 때마다 180거래일 × 350종목을 다시 돌아야 하고
 * (실측 2.6~8시간), 그 비용이 <b>"판정을 한 번에 다 끝내야 한다"</b>는 제약을 만든다.
 *
 * <h2>{@code --universe} 갈래 — 순환을 끊는 자리이고, 반만 끊으면 조용히 빈다</h2>
 *
 * <p>이 잡은 활성 유니버스만 보므로 <b>「켜야 지표가 생기고, 지표가 없으면 켜면 안 되는」
 * 순환</b>이 있다(미국이 그 자리다 — 켜는 순간 값 하나 없는 8,504종목이 프리셋 결과에 실린다).
 * {@code corporate-action}·{@code adjustment-rebuild}·{@code initial-load}·
 * {@code company-profile}이 이미 {@code --universe}로 그 순환을 끊었고 <b>이 잡도 같은 갈래를
 * 갖는다</b>({@code BootstrapRunner.UNIVERSE_JOBS}에 함께 올라 있다 — 빠지면
 * {@code rejectMisplacedLoadOptions}가 첫 줄에서 거절한다).
 *
 * <p>대상 선정은 {@code StockRegistrar.oneUniverseMemberIds}를 그대로 쓴다 — 그쪽이 이미
 * {@code u.is_active}를 안 보고 코드 하나로 좁히며 {@code CollectedMarkets}로도 거른다.
 *
 * <p><b>MUST: 그때 위 두 계산을 함께 다룬다.</b> {@code percentileCalculator.calculate}와
 * {@code sectorRelativeCalculator.calculate}가 <b>{@code u.is_active}를 조인</b>하므로,
 * 비활성 유니버스에 백필하면 <b>원본 지표는 생기는데 {@code *_PCTL}·업종 상대값이 한 행도
 * 안 생기고 잡은 SUCCESS로 끝난다.</b> 나중에 그 유니버스를 켜도 <b>과거 179일자는 영영
 * 빈다</b> — 두 계산기가 거래일 하나씩만 다시 쓰기 때문이다.
 *
 * <p>바로 위 절과 충돌하는 것처럼 보이지만 아니다. 저기서 금지한 것은 <b>모집단을 좁히는
 * 것</b>이고, 여기서 필요한 것은 <b>모집단에 비활성 유니버스를 넣는 것</b>이다 — 방향이 반대다.
 * 그래서 두 계산기는 {@code u.is_active}를 <b>지우지 않고</b> {@code OR u.code = ?}로 하나만
 * 더한다. 통째로 열면 폐기된 지수의 편입 이력이 전부 들어와
 * {@code PercentileCalculator}의 전제(<b>한 종목이 최대 하나의 활성 유니버스</b>)가 즉시 깨지고,
 * JOIN이 행을 늘려 PostgreSQL이 cardinality violation으로 배치를 죽인다.
 *
 * <p><b>그 전제를 이 잡이 먼저 검사한다</b>({@code rejectOverlappingUniverse}). 실측으로
 * {@code US_TOP1000}의 1,000종목이 전부 {@code US_LISTED}에도 편입돼 있으므로(2026-08-31),
 * 겹치는 유니버스를 대상으로 주면 <b>읽기 어려운 cardinality violation</b> 대신 무엇이
 * 겹쳤는지를 말하고 멈춘다.
 */
@Component
public class IndicatorBackfillJob {

    public static final String JOB_NAME = "indicator-backfill";

    private static final Logger log = LoggerFactory.getLogger(IndicatorBackfillJob.class);

    private final StockRegistrar stockRegistrar;
    private final BatchExecutions batchExecutions;
    private final IndicatorRunner indicatorRunner;
    private final PercentileCalculator percentileCalculator;
    private final SectorRelativeCalculator sectorRelativeCalculator;
    private final IndicatorRetentionPolicy retentionPolicy;
    private final IndicatorTradeDateIndex tradeDateIndex;
    private final JdbcTemplate jdbcTemplate;

    public IndicatorBackfillJob(StockRegistrar stockRegistrar, BatchExecutions batchExecutions,
                                IndicatorRunner indicatorRunner,
                                PercentileCalculator percentileCalculator,
                                SectorRelativeCalculator sectorRelativeCalculator,
                                IndicatorRetentionPolicy retentionPolicy,
                                IndicatorTradeDateIndex tradeDateIndex,
                                JdbcTemplate jdbcTemplate) {
        this.stockRegistrar = stockRegistrar;
        this.batchExecutions = batchExecutions;
        this.indicatorRunner = indicatorRunner;
        this.percentileCalculator = percentileCalculator;
        this.sectorRelativeCalculator = sectorRelativeCalculator;
        this.retentionPolicy = retentionPolicy;
        this.tradeDateIndex = tradeDateIndex;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@code from}부터 {@code to}까지 매 거래일의 지표를 계산한다.
     *
     * <p>거래일 목록은 {@code price_daily}에서 얻는다. 달력으로 만들면 휴장일까지 돌게 되고,
     * 그날은 시세가 없어 아무 값도 나오지 않는 헛일이 된다. 실제로 시세가 있는 날이
     * 곧 거래일이라는 사실을 쓰는 편이 정확하고 싸다.
     *
     * @param tickers 비면 편입 중인 활성 종목 전체. <b>좁혀도 백분위·업종 상대값은 전 종목에서
     *                나온다</b> — 클래스 javadoc의 근거를 참조한다.
     */
    public BatchStatus run(LocalDate from, LocalDate to,
                           BatchExecutionRecorder.TriggeredBy triggeredBy,
                           List<String> tickers, String universeCode) {
        rejectOverlappingUniverse(universeCode);
        List<LocalDate> tradeDates = tradeDatesBetween(from, to);
        if (tradeDates.isEmpty()) {
            log.info("해당 구간에 시세가 없어 백필할 것이 없다. from={} to={}", from, to);
            return BatchStatus.SUCCESS;
        }

        List<SecurityRef> targets =
                stockRegistrar.toSecurityRefs(resolveTargets(tickers, universeCode));

        // 보존 경계 밖은 계산해도 저장되지 않는다(ADR-0011). 그 사실을 여기서 말하지 않으면
        // 몇 시간을 돌고 0행으로 끝나는데 잡은 SUCCESS다 — 원인을 데이터나 계산기에서 찾게 된다.
        //
        // MUST: 경계를 시장마다 잰다. 전 시장 합계로 세면 국내가 휴장이고 미국이 개장한 날이
        // 전역 「거래일」이 되어 국내 경계가 뒤로 밀리고, 그러면 저장될 값이 있는 구간을
        // "전부 경계 밖"으로 읽어 **돌지 않고 SUCCESS로 끝난다.**
        Map<String, Optional<LocalDate>> retentionCutoffs = retentionPolicy.writeCutoffs();
        List<String> markets = targets.stream()
                .map(SecurityRef::marketCode).distinct().sorted().toList();

        // 「전부 경계 밖」은 **대상 시장 전부**에 대해 참일 때만 참이다. 한 시장이라도 쓸 값이
        // 있으면 돌아야 한다.
        boolean allOutside = !markets.isEmpty() && markets.stream().allMatch(market -> {
            Optional<LocalDate> cutoff = retentionCutoffs.getOrDefault(market, Optional.empty());
            return cutoff.isPresent()
                    && tradeDates.stream().allMatch(date -> !date.isAfter(cutoff.get()));
        });
        if (allOutside) {
            log.warn("요청 구간이 대상 시장 전부에서 보존 경계 밖이라 저장될 값이 없다. 돌지 않는다."
                    + " from={} to={} 거래일={}일 경계={}", from, to, tradeDates.size(),
                    retentionCutoffs);
            return BatchStatus.SUCCESS;
        }
        for (String market : markets) {
            Optional<LocalDate> cutoff = retentionCutoffs.getOrDefault(market, Optional.empty());
            long outside = cutoff
                    .map(c -> tradeDates.stream().filter(date -> !date.isAfter(c)).count())
                    .orElse(0L);
            if (outside > 0) {
                log.warn("{}: 요청 구간의 {}/{}거래일이 보존 경계({}) 밖이라 계산해도 저장되지 않는다."
                                + " 남은 {}거래일만 반영된다.", market, outside, tradeDates.size(),
                        cutoff.orElseThrow(), tradeDates.size() - outside);
            }
        }

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, to, triggeredBy, targets.size());

        // MUST: 거래일 수를 곱한다. 이 잡은 종목 하나를 거래일마다 다시 처리하므로 작업 단위가
        // 종목 수가 아니다. 종목 수만 신고하던 시절에 화면이 43400/350으로 떴다 —
        // 350종목 × 124거래일을 350으로 나눈 값이었고, 데이터가 부풀어 보였다.
        execution.plan(CollectionPhase.INDICATOR, tradeDates.size() * targets.size());

        log.info("지표 백필을 시작한다. 거래일={}일 종목={}개{}", tradeDates.size(), targets.size(),
                tickers == null || tickers.isEmpty()
                        ? ""
                        : " (티커로 좁혔다: " + tickers + " — 백분위·업종 상대값은 그래도 전 종목에서 낸다)");

        try {
            // 날짜가 바깥 루프인 이유는 백분위 때문이다. 백분위는 "그날 전 종목의 값"을
            // 요구하므로, 한 날짜를 전 종목에 대해 끝낸 뒤에야 그날의 순위를 낼 수 있다.
            //
            // MUST NOT: 아래 두 계산을 targets로 좁히지 않는다. 둘은 거래일만 받고 활성 유니버스
            // 전량을 조인하므로, 종목 루프를 좁혀도 순위의 모집단은 그대로다(클래스 javadoc).
            for (int i = 0; i < tradeDates.size(); i++) {
                LocalDate tradeDate = tradeDates.get(i);
                // 진행률만으로는 남은 시간을 가늠할 수 없다. 어느 날짜를 돌고 있는지가
                // 이 잡에서 실제로 알고 싶은 것이다.
                execution.stage("%s · %d/%d거래일".formatted(tradeDate, i + 1, tradeDates.size()));

                // 지수는 날짜마다 한 번이다. 종목 루프 안에서 읽으면 350번 같은 쿼리를 던진다.
                Map<String, NavigableMap<LocalDate, BigDecimal>> marketIndexes =
                        indicatorRunner.marketIndexesFor(tradeDate);

                for (SecurityRef target : targets) {
                    Optional<LocalDate> cutoff = retentionCutoffs
                            .getOrDefault(target.marketCode(), Optional.empty());
                    execution.process(target, CollectionPhase.INDICATOR,
                            security -> indicatorRunner.run(
                                    security.stockId(), tradeDate, cutoff, marketIndexes));
                }
                percentileCalculator.calculate(tradeDate, universeCode);
                // 업종 상대값도 같은 자리다 — 그날 전 종목의 값이 있어야 정해진다.
                // 백분위와 순서를 가릴 이유는 없다. 둘 다 원본 지표만 읽고 서로를 읽지 않는다.
                sectorRelativeCalculator.calculate(tradeDate, universeCode);
            }

            // MUST: 날짜 루프 **밖**이다(ADR-0080). 러너가 「값을 그만 낸」 지표의 옛 행을 보존 창
            // 전체에서 지우면 파생 행이 그만큼 고아가 되고, 날짜별 정리는 그날 하나만 본다.
            // 루프 안에서 부르면 같은 일을 거래일 수만큼 반복한다(지표당 약 200ms × 18).
            // 경계는 시장별 최솟값을 쓴다. 창이 넓어질 뿐이고 판정("그날 원본이 없다")은
            // 시장과 무관하므로 넓은 쪽이 언제나 안전하다.
            Optional<LocalDate> orphanCutoff = retentionCutoffs.values().stream()
                    .flatMap(Optional::stream).min(LocalDate::compareTo);
            execution.stage("고아 파생값 정리");
            percentileCalculator.removeOrphansInWindow(orphanCutoff);
            sectorRelativeCalculator.removeOrphansInWindow(orphanCutoff);

            // MUST: 쓰기와 지우기가 전부 끝난 뒤다. 화면 기준일이 이 색인을 읽으므로
            // (ADR-0095) 여기를 빠뜨리면 새로 채운 날짜가 화면에 영영 안 보인다.
            execution.stage("거래일 색인 갱신");
            log.info("지표 거래일 색인을 갱신했다. 색인={}건", tradeDateIndex.rebuild());
        } catch (DataSourceException e) {
            // 외부 호출이 없으므로 정상적으로는 도달하지 않는다. StockTask의 시그니처가
            // 이 예외를 선언하고 있어 형식상 잡을 뿐이며, 실제로 온다면 그것 자체가 이상 신호다.
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        return execution.finish();
    }

    /**
     * 대상 종목을 정한다.
     *
     * <p>IMPORTANT — 티커를 줬는데 하나도 매칭되지 않으면 <b>예외로 즉시 실패시킨다.</b>
     * {@link CorporateActionJob}과 같은 자리다 — 오타로 대상이 빈 것과 계산할 것이 없는 것이
     * 로그에서 구분되지 않는다. 이 잡에서는 특히 나쁘다: 무효화 뒤 복구로 부르는 경우가 많고,
     * 그때 <b>0종목 성공</b>은 "그 종목이 화면에서 사라진 채로 끝났다"와 같은 뜻이다.
     */
    private List<Long> resolveTargets(List<String> tickers, String universeCode) {
        List<String> names = tickers == null ? List.of() : tickers;
        if (universeCode != null) {
            // 비활성 유니버스도 대상이 된다 — 그것이 이 갈래의 존재 이유다(클래스 javadoc).
            List<Long> members = stockRegistrar.oneUniverseMemberIds(universeCode, names);
            if (members.isEmpty()) {
                throw new IllegalArgumentException(
                        "유니버스 " + universeCode + "에 대상 종목이 하나도 없다"
                                + (names.isEmpty() ? "" : " (티커 " + names + ")")
                                + " — 유니버스 코드와 수집 대상 시장을 확인하라."
                                + " 대상 0건으로 성공시키지 않는다.");
            }
            return members;
        }
        if (names.isEmpty()) {
            return stockRegistrar.activeUniverseMemberIds();
        }
        List<Long> matched = stockRegistrar.activeUniverseMemberIdsByTicker(tickers);
        if (matched.isEmpty()) {
            throw new IllegalArgumentException(
                    "준 티커 중 편입 중인 활성 종목이 하나도 없다: " + tickers
                            + " — 종목코드를 확인하라. 대상 0건으로 성공시키지 않는다.");
        }
        if (matched.size() < tickers.size()) {
            log.warn("티커 {}개 중 {}개만 편입 중인 활성 종목이다. 나머지는 건너뛴다: {}",
                    tickers.size(), matched.size(), tickers);
        }
        return matched;
    }

    /**
     * 대상 유니버스가 <b>활성 유니버스와 종목을 겹치면</b> 시작 전에 멈춘다.
     *
     * <p>겹친 채로 돌면 두 계산기의 모집단에 같은 종목이 두 번 들어가 {@code ON CONFLICT DO
     * UPDATE}가 같은 행을 두 번 건드리고, PostgreSQL이 <b>cardinality violation</b>으로 배치를
     * 죽인다. 그 예외는 원인을 말하지 않으므로 여기서 무엇이 겹쳤는지를 세어 말한다.
     *
     * <p>MUST NOT: 겹침을 <b>허용</b>하는 쪽으로 고치지 않는다. {@code indicator_value}에
     * 유니버스 축이 없어 종목당 값이 하나뿐이고, 그래서 어느 모집단의 순위인지가 정해지지 않는다.
     *
     * <p><b>MUST: 자기 자신을 겹침에서 뺀다</b>({@code b.universe_code <> a.universe_code}).
     * 이 갈래는 <b>비활성</b> 유니버스를 채우려고 만들었지만 대상이 <b>활성</b>일 수도 있고
     * (리밸런싱 뒤 채우기 · 보존 창 복구), 그때 자기 조인이 <b>전 종목을 자기와 겹치는 것으로</b>
     * 신고한다. 실측으로 {@code US_TOP1000}이 켜진 뒤 1,000종목 전부가 그렇게 걸렸고
     * (자기 제외 시 0), 그 메시지가 <b>「겹치는 유니버스를 먼저 비활성으로 내려라」라는 실행
     * 불가능한 처방</b>을 냈다 — 내려야 할 대상이 자기 자신이다.
     */
    private void rejectOverlappingUniverse(String universeCode) {
        if (universeCode == null) {
            return;
        }
        Long overlapping = jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT a.stock_id)
                FROM universe_membership a
                JOIN universe_membership b
                  ON b.stock_id = a.stock_id AND b.effective_to IS NULL
                 AND b.universe_code <> a.universe_code
                JOIN universe u ON u.code = b.universe_code AND u.is_active
                WHERE a.universe_code = ? AND a.effective_to IS NULL
                """, Long.class, universeCode);
        if (overlapping != null && overlapping > 0) {
            throw new IllegalStateException(
                    "유니버스 " + universeCode + "의 " + overlapping
                            + "종목이 이미 활성 유니버스에도 편입돼 있다 — 백분위 모집단이"
                            + " 겹쳐 배치가 cardinality violation으로 죽는다."
                            + " 겹치는 유니버스를 먼저 비활성으로 내리거나 대상을 바꿔라.");
        }
    }

    /**
     * 구간 안의 거래일 — <b>전 시장의 합집합</b>이다.
     *
     * <p>MUST NOT: 이것을 시장별로 가르지 않는다. 날짜 루프의 목적은 <b>어느 날짜를 한 번씩
     * 돌 것인가</b>이고, 그 답은 합집합이 맞다. 한 시장이 쉰 날에 그 시장 종목을 돌려도
     * 러너는 <b>그 종목의 마지막 봉 날짜</b>로 같은 값을 다시 쓸 뿐이라 멱등이다.
     *
     * <p>시장으로 갈라야 하는 것은 이 목록이 아니라 <b>보존 경계</b>다 — 그쪽은 날짜가 밀리면
     * 「저장될 값이 없다」로 읽혀 잡이 돌지 않고 SUCCESS로 끝난다.
     */
    private List<LocalDate> tradeDatesBetween(LocalDate from, LocalDate to) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT trade_date FROM price_daily
                WHERE trade_date BETWEEN ? AND ?
                ORDER BY trade_date
                """, LocalDate.class, from, to);
    }

}
