package com.stockscreener.indicator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.stockscreener.indicator.IndicatorDataReader.FinancialVisibility;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 한 종목의 지표를 전부 계산해 저장한다.
 *
 * <p>이 클래스가 지표 계층의 조립점이다. 원자료를 한 번 읽고
 * ({@link IndicatorDataReader}), {@link IndicatorRegistry#executionOrder()}가 정한 순서대로
 * 계산기를 돌리며, 계산된 시계열을 뒤따르는 파생 지표에 넘겨준다
 * ({@link IndicatorContext#dependency(String)}).
 *
 * <p>IMPORTANT — 계산 순서가 위상 정렬이라는 점이 파생 지표의 유일한 근거다. MACD-V가
 * MACD·ATR을 다시 구현하지 않는 이유는 그 둘이 <b>먼저 계산되어 컨텍스트에 담겨</b> 오기
 * 때문이다. 순서를 코드에 손으로 적으면 지표를 추가할 때마다 그 목록을 고쳐야 하고,
 * 그것이 확장 비용 1을 깨뜨린다.
 *
 * <p>실패 격리 단위는 <b>종목</b>이다({@code data-pipeline.md}). 다만 이 클래스는 한 종목만
 * 다루며, 격리와 실패 기록은 이것을 호출하는 배치 계층이
 * {@code StockTaskExecutor}로 담당한다 — 트랜잭션 경계가 거기 있기 때문이다.
 */
@Component
public class IndicatorRunner {

    private static final Logger log = LoggerFactory.getLogger(IndicatorRunner.class);

    private final IndicatorRegistry registry;
    private final IndicatorDataReader reader;
    private final IndicatorValueWriter writer;

    public IndicatorRunner(IndicatorRegistry registry,
                           IndicatorDataReader reader,
                           IndicatorValueWriter writer) {
        this.registry = registry;
        this.reader = reader;
        this.writer = writer;
    }

    /**
     * 한 종목의 모든 지표를 {@code asOf} 기준으로 계산해 저장한다.
     *
     * <p>원자료는 <b>종목당 한 번만</b> 읽는다. 지표마다 읽으면 지표를 추가할 때마다 조회가
     * 늘어 850종목 × N지표의 N+1이 된다.
     *
     * <p>IMPORTANT — 계산은 창 전체로 하고 <b>저장만</b> 보존 경계로 자른다. 재귀 지표
     * ({@code ATR_14}·{@code RSI_14}·{@code MACD}·{@code MACD_V})는 창의 앞쪽부터 이어져야
     * 마지막 값이 맞으므로, 입력을 줄이면 값 자체가 달라진다. 여기서 거르는 것은
     * {@link IndicatorValueWriter}에 넘길 목록뿐이다.
     *
     * <p><b>저장 계약 — 봉의 값은 그 봉 시점에 공시돼 있던 재무로 낸다</b>(ADR-0128).
     * 가시성이 {@link FinancialVisibility#KNOWN_ON}인 것이 그 계약의 절반이고, 나머지 절반은
     * 계산기가 {@code FinancialMath.byPublication}을 거치는 것이다. <b>둘 중 하나만 있으면
     * 아무 일도 일어나지 않는다</b> — 리더만 바꾸면 창의 마지막 봉 기준 스냅샷 하나가 그대로
     * 펴지고, 계산기만 바꾸면 펼 대상이 여전히 오늘 재무다. 실측으로 리더만 바꾸는 것은
     * <b>오늘 asOf에서 값이 한 칸도 안 바뀐다</b>(미래 공시 0행 · {@code is_latest}와
     * 최대 개정본이 갈리는 기 0).
     *
     * <p><b>MUST NOT: 이 경로가 창 전체를 쓴다는 성질을 좁히지 않는다.</b> 넷이 거기 얹혀 있다 —
     * 정정공시 무효화({@code StoredIndicatorInvalidationPort}가 상한 없이 지우는데
     * {@code PipelineScheduler.runHeavy}가 백필을 잇지 않는다) · 미보정 기업행위 억제(ADR-0055) ·
     * 꼬리 걷기(ADR-0085) · {@code adj_close} 재작성 복구(자동 백필이 10종목에서 포기한다).
     * 지금 그 넷을 메우는 유일한 기제가 <b>다음 실행이 창 전체를 다시 쓰는 것</b>이다.
     *
     * @param retentionCutoff 이 날짜 <b>이하</b>는 저장하지 않는다
     *                        ({@link IndicatorRetentionPolicy#writeCutoff()}).
     *                        비어 있으면 거르지 않는다 — 시세가 보존 기간보다 적게 쌓인 초기 상태다
     * @param marketIndexes   지수 코드 → 종가 시계열. 잡이 <b>종목 루프 밖에서 한 번</b> 읽어
     *                        넘긴다({@link #marketIndexesFor}). 여기서 읽으면 350번 같은 쿼리를
     *                        던진다. 이 종목의 시장에 해당하는 것만 골라 컨텍스트에 담는다
     * @return 저장한 행 수
     */
    public long run(long stockId, LocalDate asOf, Optional<LocalDate> retentionCutoff,
                    Map<String, NavigableMap<LocalDate, BigDecimal>> marketIndexes) {
        RawData raw = readRawData(stockId, asOf, marketIndexes, FinancialVisibility.KNOWN_ON);
        IndicatorContext base = raw.context();
        if (base.priceBars().isEmpty()) {
            // 시세가 없으면 계산할 것이 없다. 신규 편입 직후나 거래정지 종목에서 정상적으로 생긴다.
            log.debug("시세가 없어 지표 계산을 건너뛴다. stockId={} asOf={}", stockId, asOf);
            return 0L;
        }

        Computed computed = compute(base);
        long written = 0;
        long deleted = 0;
        int suppressedCodes = 0;
        long stale = 0;
        int staleCodes = 0;

        // 계산기가 값을 그만 냈을 때 지울 대상(ADR-0080). 창의 거래일이므로 상한이 asOf이고,
        // 그래서 이 삭제는 **읽은 창 밖을 건드릴 수 없다** — 과거 일자로 백필해도 그 뒤의
        // 라이브 행은 안전하다. 종목당 한 번만 만든다.
        Collection<LocalDate> windowDates = retainDates(
                base.priceBars().stream().map(PriceBar::tradeDate)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                retentionCutoff);

        for (IndicatorCalculator calculator : registry.executionOrder()) {
            String code = calculator.code();
            List<IndicatorPoint> points = computed.points().getOrDefault(code, List.of());
            Set<LocalDate> suppressed = computed.suppressed().getOrDefault(code, Set.of());
            // 게이트와 삭제 범위가 **같은 값**을 본다. 삭제는 보존 창으로 거르는데 아래 걷기의
            // 게이트가 거르지 않은 집합을 보면, 억제 일자가 전부 컷오프 아래일 때
            // **삭제는 0행이고 걷기는 거부되는** 조합이 생긴다 — 그 자리에서 옛 행이 영영
            // 안 걷힌다(ADR-0085). 실제로 UNSUPPORTED 기업 행위를 가진 종목들이 그 상태였다.
            Collection<LocalDate> retainedSuppressed = retainDates(suppressed, retentionCutoff);

            // MUST: 이 진입 조건은 **거르지 않은** suppressed를 본다. retainedSuppressed로
            // 바꾸면 억제가 컷오프 아래로만 있는 종목에서 suppressedCodes가 안 올라가
            // "미보정 기업 행위로 지표를 뺐다" 로그가 사라진다 — 아래가 적듯 그것이
            // "이 종목이 왜 화면에서 빠졌는가"를 답할 수 있는 유일한 흔적이다.
            if (!suppressed.isEmpty()) {
                // MUST: 아래 points.isEmpty() 분기보다 **앞**이다. 전부 억제되면 points가 비고,
                // 거기서 continue하면 이미 저장된 옛 행이 그대로 남는다 — 값이 그럴듯해서
                // 화면에도 테스트에도 드러나지 않는다. 러너가 "쓰지 않는다"로 끝내면 제외가
                // 성립하지 않는다는 것이 P8이 적어 둔 함정이고, 여기가 그 자리다.
                long removed = writer.deleteAt(stockId, code, retainedSuppressed);
                if (removed > 0 || !points.isEmpty()) {
                    suppressedCodes++;
                }
                deleted += removed;
            }

            // 계산할 수 없으면 행을 쓰지 않는다(ADR-0002). 워밍업이 부족한 구간에서 정상이다.
            if (points.isEmpty()) {
                // MUST: 억제로 비었을 때는 여기서 지우지 않는다. 위 분기가 이미 제 범위를
                // 지웠고, 여기서 또 지우면 **억제 창 밖까지** 번진다.
                //
                // MUST: 게이트가 보는 것은 **보존 창으로 거른** 억제 집합이다. 거르지 않은 것을
                // 보면 위 삭제가 0행인데도 여기가 막혀, 억제 효력일이 보존 창보다 오래된 종목은
                // 이 걷기에서 영구히 면제된다.
                if (retainedSuppressed.isEmpty() && !windowDates.isEmpty()
                        && inputsWereReadable(calculator, base, raw.currencyMismatched())) {
                    long removed = writer.deleteAt(stockId, code, windowDates);
                    if (removed > 0) {
                        stale += removed;
                        staleCodes++;
                    }
                }
                continue;
            }
            // 안 낸 날짜 걷기(ADR-0085 → ADR-0130). 위 분기는 **창 전체가 빌 때만** 지우므로,
            // 창의 일부만 내는 계산기의 나머지 날짜에 있는 옛 행은 지워지지도 덮이지도 않는다 —
            // write가 UPSERT라 **산출한 날짜만** 덮기 때문이다. 006400 삼성SDI의 배당성향
            // 112행이 꼬리 쪽 사례였고(앵커가 적자 연도로 옮겨가 분모 가드가 값을 안 내는데
            // FY2024로 계산된 11.63%가 남았다), **앞쪽에는 ADR-0128 이전 판의 값이 남아
            // 과거 기준일 조회의 판정에 실리고 있었다.**
            long culled = writer.deleteAt(stockId, code,
                    windowDatesWithoutProduction(windowDates, points, suppressed, calculator, base,
                            raw.currencyMismatched()));
            if (culled > 0) {
                stale += culled;
                staleCodes++;
            }
            written += writer.write(stockId, code, retain(points, retentionCutoff));
        }

        if (staleCodes > 0) {
            // 계산기가 값을 그만 낸 자리다. 원자료가 바뀌어(FILL_MISSING처럼 무효화를 안 부르는
            // 경로 포함) 값이 사라졌는데 옛 행만 남아 있던 것을 걷은 것이다 — 그 행들은
            // 화면에서 그럴듯한 숫자로 보이므로 여기 말고는 드러나는 자리가 없다.
            log.info("계산이 그만 난 지표의 옛 행을 걷었다. stockId={} asOf={} 지표={}개 삭제={}행",
                    stockId, asOf, staleCodes, stale);
        }

        if (suppressedCodes > 0 || deleted > 0) {
            // "이 종목이 왜 화면에서 빠졌는가"를 나중에 답할 수 있는 유일한 흔적이다.
            log.info("미보정 기업 행위로 지표를 뺐다. stockId={} asOf={} 효력일={} 지표={}개 삭제={}행",
                    stockId, asOf, base.unadjustedActions(), suppressedCodes, deleted);
        }
        return written;
    }

    /**
     * 이번 실행이 <b>안 낸</b> 창의 거래일. 거기 남은 옛 행이 낡은 것이다(ADR-0085 → ADR-0130).
     *
     * <p><b>꼬리만 보던 것을 창 전체로 넓혔다.</b> ADR-0080 §버린 대안이 「창 안에서 안 낸
     * 날짜를 전부 지운다」를 기각했고 그 근거가 둘이었는데 <b>둘 다 그 뒤에 무너졌다.</b>
     * <ol>
     *   <li><i>"정상적으로 일부만 내는 계산기의 보존 창이 매 실행 깎인다"</i> —
     *       <b>ADR-0079</b>가 읽기 창을 {@code maxEffectiveLookback() + RETAINED_TRADING_DAYS}로
     *       두어 <b>보존 창의 모든 날짜가 어떤 {@code asOf}에서든 최대 워밍업을 받는다.</b>
     *       그래서 지금 비는 자리는 「창이 짧아서」가 아니라 <b>그 날짜에 값이 성립하지 않아서</b>다.
     *   <li><i>"뒤 날짜가 앞 날짜를 지운다"</i> — <b>ADR-0128</b>이 재무 지표를 봉마다
     *       {@code KNOWN_ON}으로 내게 해 <b>날짜 d의 값이 {@code asOf}와 무관</b>해졌다.
     * </ol>
     *
     * <p><b>왜 넓혀야 했나.</b> 꼬리만 보면 <b>앞쪽에 남은 잔재</b>를 원리적으로 못 본다.
     * ADR-0128이 계산을 바꾸면서 값은 다시 썼지만 <b>새 규칙이 못 내는 날짜의 옛 행은 안 지웠고</b>
     * ({@code write}가 UPSERT라 산출한 날짜만 덮는다), 그 행이 지금도 과거 기준일 조회의
     * 판정에 실린다. 실측으로 그 행들은 {@code KNOWN_ON}으로도 {@code LATEST}로도 재현되지
     * 않는다 — 즉 <b>어느 시점 규칙의 값도 아니다.</b>
     *
     * <p>백필의 날짜 바깥 루프는 여전히 안전하다 — 창이 {@code asOf}에서 끝나고 삭제 후보가
     * 창의 거래일이라 <b>삭제 상한이 구조적으로 묶인다.</b>
     *
     * <p>MUST: <b>억제 일자를 뺀다.</b> 그 날짜는 위 억제 분기가 소유하고, 그쪽은 보존 창으로
     * 거른 범위만 지운다. 여기서 또 넣으면 소유가 둘이 되어 한쪽을 고칠 때 다른 쪽이 조용히 남는다.
     *
     * <p>MUST: <b>시장 지수를 쓰는 코드는 그 날짜에 지수가 있는 날짜만 후보로 둔다.</b>
     * {@code ExcessReturn.at}이 지수를 정확 일치로 묻고 없으면 그 날짜를 건너뛰므로,
     * 지수 결손은 {@code points}를 비우지 않으면서 그 날짜만 「안 낸 날짜」로 만든다 —
     * 그대로 두면 지수 재적재가 실패한 날 {@code BETA_104W}·{@code EXCESS_RETURN_*}의 행이
     * 조용히 지워진다. {@code DataRequirement}의 축은 「통째로 없는가」만 가르므로 이
     * <b>부분 결손</b>은 거기서 못 잡는다.
     *
     * <p>MUST NOT: 같은 필터를 거버넌스에 걸지 않는다. 앵커가 없어 값이 없는 것은 <b>결손이
     * 아니라 사실</b>이고, 그것이 정확히 이 걷기가 걷어야 하는 것이다.
     *
     * <p>MUST: {@code points}가 비면 여기서 아무것도 안 한다. 그 갈래는 호출부의 빈-목록
     * 분기가 소유하며 {@code inputsWereReadable}로 「아직 모른다」를 이미 가른다.
     */
    private static Collection<LocalDate> windowDatesWithoutProduction(
            Collection<LocalDate> windowDates,
            List<IndicatorPoint> points,
            Set<LocalDate> suppressed,
            IndicatorCalculator calculator,
            IndicatorContext base,
            boolean currencyMismatched) {
        if (windowDates.isEmpty() || points.isEmpty()
                || !inputsWereReadable(calculator, base, currencyMismatched)) {
            return List.of();
        }
        Set<LocalDate> produced = points.stream()
                .map(IndicatorPoint::tradeDate)
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
        boolean indexAware = calculator.dataRequirement().needsMarketIndex();
        return windowDates.stream()
                .filter(date -> !produced.contains(date))
                .filter(date -> !suppressed.contains(date))
                .filter(date -> !indexAware || base.marketIndex().containsKey(date))
                .toList();
    }

    /**
     * 원자료를 <b>읽기는 했는가.</b> 「값이 없다」와 「입력을 못 읽었다」를 가른다(ADR-0080).
     *
     * <p>IMPORTANT — 이 구분이 없으면 <b>공시가 늦은 구간에서 재무 지표의 이력이 통째로 사라진다.</b>
     * 계산기가 빈 목록을 내는 이유는 둘이다 — 값이 성립하지 않거나(TTM이 음수라 PER이 없다),
     * 애초에 입력이 없거나(그 종목의 재무를 한 행도 못 읽었다). 앞엣것만 「옛 행이 낡았다」는
     * 근거가 되고, 뒤엣것은 <b>아직 모른다</b>일 뿐이다. 뒤엣것에 삭제를 걸면 분기 하나가 늦게
     * 들어오는 동안 그 종목의 재무 지표가 보존 창에서 사라지고, 다음 실행에 다시 쓰인다 —
     * ADR-0045가 닫은 「삭제 → 재기록」 왕복이다.
     *
     * <p>선언으로 판정할 수 있는 것은 <b>재무·거버넌스·시장 지수 셋</b>이다. 시세는
     * {@code run}의 조기 반환이 이미 막는다.
     *
     * <p>IMPORTANT — 세 축 모두 <b>「통째로 없는가」만</b> 가른다. 날짜별 결손은 여기서 못
     * 보므로, 꼬리 걷기가 날짜 단위로 한 번 더 거른다(ADR-0085).
     *
     * <p><b>못 읽은 이유가 「우리가 건 게이트」면 그것은 「값이 없다」이다</b>(ADR-0094).
     * 통화 게이트에 걸린 종목은 재무가 0행으로 오는데, 그것을 「아직 모른다」로 읽으면
     * <b>이미 저장된 오염값이 영영 안 걷힌다</b> — 게이트는 새 계산만 막기 때문이다.
     * 판정은 {@link IndicatorDataReader.StockAttributes#financialCurrencyMismatched()}가
     * 종목당 한 번 도는 질의에 얹어 낸다. <b>MUST: 이 축을 더한다고 재무 0행 종목이 함께
     * 열리지 않는다</b> — 판정이 「통화가 다른 재무 행이 있는가」라 재무가 통째로 없으면
     * 거짓이고, 맥쿼리인프라처럼 재무가 원래 없는 종목은 그대로 보호된다.
     */
    private static boolean inputsWereReadable(IndicatorCalculator calculator, IndicatorContext base,
                                              boolean currencyMismatched) {
        DataRequirement requirement = calculator.dataRequirement();
        if (requirement.needsFinancial()
                && base.annualFigures().isEmpty() && base.quarterlyFigures().isEmpty()
                // 통화가 안 맞아 **우리가 막은** 것은 「아직 모른다」가 아니라 「값이 없다」이다
                // (ADR-0094). 안 가르면 그 종목의 오염된 옛 행이 영영 안 걷힌다 — 게이트는
                // 새 계산만 막고, 저장된 값은 화면에서 그럴듯한 숫자로 남는다.
                && !currencyMismatched) {
            return false;
        }
        // 거버넌스도 같은 판정을 받는다(ADR-0081). 선언하지 않은 채 늘 읽히면 적재 전이나
        // 적재가 반쯤 된 구간에서 **부모 행은 남는데 이 지표 행만 지워진다**.
        if (requirement.needsGovernance() && base.governanceFacts().isEmpty()) {
            return false;
        }
        // 시장 지수가 셋째 축이다(ADR-0085). 이 선언이 없던 동안 지수 시계열이 통째로 비면
        // BETA_104W·EXCESS_RETURN_*(실측 180,735행)가 이 경로로 지워졌다 — ADR-0080 §결과가
        // 「못 가른다」로 적어 둔 구멍이고, 보상 통제인 지수 결손 경보는 일일 갱신에만 있어
        // 백필에서는 아무것도 막지 않았다.
        return !requirement.needsMarketIndex() || !base.marketIndex().isEmpty();
    }

    /**
     * 삭제 대상에서도 보존 경계 밖을 뺀다.
     *
     * <p>{@link #retain}과 <b>같은 부등호를 쓴다.</b> 어긋나면 러너가
     * {@link IndicatorRetentionPolicy}가 소유한 구간을 침범하고, 그 침범은 지운 뒤에 다시
     * 쓰이지 않으므로 <b>아무 증상도 남기지 않는다.</b>
     */
    private Collection<LocalDate> retainDates(Set<LocalDate> dates, Optional<LocalDate> cutoff) {
        if (cutoff.isEmpty()) {
            return dates;
        }
        LocalDate boundary = cutoff.get();
        return dates.stream().filter(date -> date.isAfter(boundary)).toList();
    }

    /**
     * 보존 경계보다 오래된 포인트를 뺀다.
     *
     * <p>부등호가 {@link IndicatorRetentionPolicy#purge()}의 {@code trade_date <= cutoff}와
     * 같은 방향이어야 한다. 어긋나면 경계일 하루가 <b>매 실행 쓰였다가 매 정리에 지워지며</b>,
     * 그 왕복은 어떤 값도 바꾸지 않아 화면으로는 드러나지 않는다.
     */
    private List<IndicatorPoint> retain(List<IndicatorPoint> points, Optional<LocalDate> cutoff) {
        if (cutoff.isEmpty()) {
            return points;
        }
        LocalDate boundary = cutoff.get();
        return points.stream()
                .filter(point -> point.tradeDate().isAfter(boundary))
                .toList();
    }

    /**
     * 지수 시계열을 {@code asOf} 단위로 한 번 읽는다. 잡이 <b>종목 루프 밖에서</b> 부른다.
     *
     * <p>폭이 시세 창과 같은 이유는, 계산기가 창 안의 <b>모든 산출 일자</b>에 대해 지수를
     * 필요로 하기 때문이다. 대상일 근처만 읽으면 오래된 산출 일자가 전부 "지수 결손"으로 접혀
     * 사라지고, 백필은 몇 일자만 쓰고 <b>SUCCESS로 끝난다.</b>
     */
    public Map<String, NavigableMap<LocalDate, BigDecimal>> marketIndexesFor(LocalDate asOf) {
        return reader.readMarketIndexes(asOf, registry.readWindowBars());
    }

    /**
     * 한 종목의 지표를 {@code asOf} 기준으로 계산해 <b>값만 돌려준다 — 저장도 삭제도 하지 않는다.</b>
     *
     * <p>시점 재현의 진입점이다(ADR-0071). <b>저장 경로와 같은 가시성을 쓰지만 값을 저장하지
     * 않는다</b> — {@link #run}이 2026-09-07에 {@code KNOWN_ON}으로 옮겨졌으므로(ADR-0128)
     * 둘의 차이는 이제 「저장하는가」뿐이다.
     *
     * <p><b>백테스트 스윕은 이제 이 경로를 안 쓴다</b> — 저장된 {@code indicator_value}를
     * 화면과 같은 질의로 읽는다(ADR-0137). 여기가 남는 이유는 <b>그 저장 값이 옳은지 물을
     * 독립 대조군이 이것뿐</b>이기 때문이고, 실제로 그 대조가 ADR-0130·0132의 결함 둘을 찾았다.
     * <b>MUST NOT: 대조군을 저장 값으로 갈아끼우지 않는다</b> — 그러면 그 검사가 동어반복이 된다.
     *
     * <p>MUST: 마지막 봉의 일자가 {@code asOf}와 <b>정확히 같을 때만</b> 값을 낸다. 라이브
     * 스크리닝이 {@code trade_date = asOf}로 정확히 일치시키므로({@code ScreeningRepository}),
     * 여기서 "asOf 이하의 마지막 봉"으로 느슨하게 잡으면 <b>거래정지 종목이 화면에서는
     * 판정불가인데 백테스트에서는 통과</b>하게 된다. 그 차이는 어느 쪽도 에러를 내지 않는다.
     *
     * <p>휴장일을 {@code asOf}로 주면 <b>전 종목이 빈 맵</b>이 되어 필수 조건이 모두
     * 판정불가가 되고 통과가 0이 된다. 게이트 탓으로 오독하기 쉬우므로 호출부가 거래일을
     * 준다(ADR-0071 §규칙).
     *
     * @param visibility 재무를 어느 시점의 눈으로 볼 것인가. 백테스트는
     *                   {@link FinancialVisibility#KNOWN_ON}이다
     * @return 지표 코드 → {@code asOf}의 값. 시세가 없거나 마지막 봉이 {@code asOf}가 아니면 빈 맵
     */
    public Map<String, BigDecimal> computeAt(long stockId, LocalDate asOf,
                                             Map<String, NavigableMap<LocalDate, BigDecimal>> marketIndexes,
                                             FinancialVisibility visibility) {
        IndicatorContext base = readRawData(stockId, asOf, marketIndexes, visibility).context();
        List<PriceBar> bars = base.priceBars();
        if (bars.isEmpty() || !bars.get(bars.size() - 1).tradeDate().equals(asOf)) {
            return Map.of();
        }
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        compute(base).points().forEach((code, points) -> {
            IndicatorPoint last = points.get(points.size() - 1);
            if (last.tradeDate().equals(asOf)) {
                values.put(code, last.value());
            }
        });
        return values;
    }

    /**
     * 계산기를 순서대로 돌린 결과. <b>저장·삭제와 무관하다.</b>
     *
     * @param points    지표 코드 → 억제를 걷어낸 뒤 남은 포인트. <b>빈 것은 담기지 않는다</b>
     * @param suppressed 지표 코드 → 미보정 기업 행위로 억제된 일자. 억제가 없으면 담기지 않는다
     */
    private record Computed(Map<String, List<IndicatorPoint>> points,
                            Map<String, Set<LocalDate>> suppressed) {
    }

    /**
     * 계산 루프. {@link #run}과 {@link #computeAt}이 <b>이것 하나를 공유한다.</b>
     *
     * <p>MUST NOT: 백테스트 쪽에 루프를 따로 만들지 않는다. 두 벌이 되면 억제 규칙(ADR-0055)이나
     * 파생 지표의 순서가 한쪽에서만 바뀌는데, <b>양쪽 다 값이 나오므로</b> 어긋난 것을 알 길이
     * 화면에도 테스트에도 없다.
     */
    private Computed compute(IndicatorContext base) {
        // 이미 계산된 지표의 시계열. 뒤따르는 파생 지표가 여기서 부모를 찾는다.
        Map<String, NavigableMap<LocalDate, BigDecimal>> series = new HashMap<>();
        Map<String, List<IndicatorPoint>> points = new LinkedHashMap<>();
        Map<String, Set<LocalDate>> suppressedByCode = new HashMap<>();

        for (IndicatorCalculator calculator : registry.executionOrder()) {
            String code = calculator.code();
            List<IndicatorPoint> computed = calculator.calculate(contextFor(base, calculator, series));

            // 창이 미보정 기업 행위를 삼키는 산출 일자는 값을 내지 않는다(ADR-0055).
            // 사건이 없는 종목에서는 빈 집합이라 아래가 통째로 건너뛰어진다.
            Set<LocalDate> suppressed = UnadjustedActionGuard.suppressedDates(
                    base.priceBars(), base.unadjustedActions(), registry.effectiveLookback(code));
            if (!suppressed.isEmpty()) {
                computed = computed.stream()
                        .filter(point -> !suppressed.contains(point.tradeDate()))
                        .toList();
                suppressedByCode.put(code, suppressed);
            }
            if (computed.isEmpty()) {
                continue;
            }

            // 파생 지표에는 창 전체를 넘긴다. 저장만 자르는 것이므로 여기는 자르기 전 값이다.
            //
            // 억제된 일자가 여기서도 빠진 채 넘어가는 것이 옳다. 자식의 창은 부모의 창을
            // 포함하므로(effectiveLookback = 자기 몫 + 부모 최대) 부모에 구멍이 있는 날짜는
            // 자식도 억제 대상이며, 자식이 성립하는 날짜에서는 부모에 구멍이 없다.
            series.put(code, toSeries(computed));
            points.put(code, computed);
        }
        return new Computed(points, suppressedByCode);
    }

    /**
     * 원자료와, 그것을 <b>우리가 일부러 막았는지</b>를 함께 낸다.
     *
     * <p>통화 판정을 {@link IndicatorContext}에 담지 않는 이유는 그것이 <b>계산기의 입력이
     * 아니기 때문</b>이다 — 걷기 판정에만 쓰이고, 담으면 생성 지점 68곳이 함께 움직인다.
     */
    private record RawData(IndicatorContext context, boolean currencyMismatched) {
    }

    private RawData readRawData(long stockId, LocalDate asOf,
                                Map<String, NavigableMap<LocalDate, BigDecimal>> indexes,
                                FinancialVisibility visibility) {
        int bars = registry.readWindowBars();
        int years = registry.maxFinancialYears();
        // 종목당 한 번만 읽는다. 지표마다 읽으면 850종목 × N지표의 N+1이 된다.
        IndicatorDataReader.StockAttributes attributes = reader.readStockAttributes(stockId);
        return new RawData(new IndicatorContext(
                stockId,
                attributes.industryDivisionKey(),
                reader.readPriceBars(stockId, asOf, bars),
                reader.readAnnualFigures(stockId, asOf, years, visibility),
                reader.readQuarterlyFigures(stockId, asOf, years, visibility),
                // 빠뜨려도 컴파일된다 — 전 종목이 빈 목록을 받아 권리락 구간 보정이 통째로
                // 사라지고 값은 그럴듯하다. IndicatorRunnerTest가 이 자리를 지킨다.
                reader.readShareAdjustments(stockId),
                // 위의 짝이다. 빠뜨리면 보정 안 된 계단을 넘는 창으로 낸 값이 그대로 나간다
                // (ADR-0055). 이쪽도 증상이 "값이 나온다"라 화면에서 드러나지 않는다.
                reader.readUnadjustedActions(stockId),
                // MUST: 시장별로 고르는 것이 여기서 끝난다(ADR-0046 §승인된 이탈 4).
                // 계산기에 지수 여럿을 넘기면 "코스닥 종목의 지수는 코스닥"이라는 시장의 사실을
                // 지표마다 다시 알아야 하고, 한 곳만 안 고쳐도 그 지표만 조용히 다른 벤치마크를 쓴다.
                // 시장을 모르거나 그 지수가 없으면 null이고, 그때 계산기가 값을 내지 않는다.
                indexes == null ? null : indexes.get(attributes.marketCode()),
                // 거버넌스 사실. 빠뜨려도 컴파일되고, 빠지면 예외가 아니라 **값이 없는 지표**로
                // 나타난다 — 필수 조건이면 전 종목 판정불가인데 에러도 경고도 없다.
                reader.readGovernanceFacts(stockId, asOf, registry.maxGovernanceYears(), visibility),
                Map.of()),
                attributes.financialCurrencyMismatched());
    }

    /**
     * 계산기가 선언한 의존만 담은 컨텍스트를 만든다.
     *
     * <p>선언하지 않은 지표를 넘기지 <b>않는</b> 것이 중요하다.
     * {@link IndicatorContext#dependency(String)}가 미선언 접근에 예외를 던지므로,
     * 선언 없이 몰래 쓰는 계산기는 테스트에서 즉시 드러난다. 그 선언이 곧 워밍업 산정의
     * 근거이기도 해서, 몰래 쓰면 읽어오는 봉 수가 모자라게 된다.
     */
    private IndicatorContext contextFor(IndicatorContext base,
                                        IndicatorCalculator calculator,
                                        Map<String, NavigableMap<LocalDate, BigDecimal>> computed) {
        var dependsOn = calculator.dataRequirement().dependsOn();
        if (dependsOn.isEmpty()) {
            return base;
        }

        Map<String, NavigableMap<LocalDate, BigDecimal>> dependencies = new HashMap<>();
        for (String code : dependsOn) {
            // 부모가 값을 내지 못했으면(워밍업 부족) 빈 시계열을 준다. 자식도 자연히 값을
            // 내지 못하며, 그것이 옳다 — 없는 부모로 계산한 파생 값은 틀린 값이다.
            dependencies.put(code, computed.getOrDefault(code, new TreeMap<>()));
        }
        // MUST: 생성자로 다시 조립하지 않는다. 필드가 늘 때마다 여기서 조용히 빠지고,
        // 빠진 필드는 예외가 아니라 "값이 없는 지표"로 나타난다.
        return base.withDependencies(dependencies);
    }

    private static NavigableMap<LocalDate, BigDecimal> toSeries(List<IndicatorPoint> points) {
        NavigableMap<LocalDate, BigDecimal> series = new TreeMap<>();
        for (IndicatorPoint point : points) {
            series.put(point.tradeDate(), point.value());
        }
        return series;
    }
}
