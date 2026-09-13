package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.CorporateActionCollector;
import com.stockscreener.pipeline.collector.PriceCalendar;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;

/**
 * 기업 행위를 모아 원장에 적는다. <b>계수는 건드리지 않는다</b>(ADR-0049).
 *
 * <p>MUST NOT: {@code AdjustmentRecalculator.rebuild}를 여기서 부르지 않는다. 그것이 저장된
 * 시세를 소급해 다시 쓰는 유일한 지점이고, 되돌리기가 원장의 정확성 위에서만 성립하므로
 * <b>첫 호출 전에 {@code pg_dump}가 필요하다.</b> 이 잡의 산출물은 사람이 판정할 후보이고,
 * 그 판정 경로는 관리자 API 단계에서 열린다.
 *
 * <p>일일 갱신에 붙이지 않은 이유는 {@link CompanyProfileJob}과 같다 — 기업 행위는
 * <b>정기보고서에만 실려 최대 넉 달 늦으므로</b>(api-spec dart-corporate-action.md의 「증자(감자) 현황」 절)
 * 매일 불러 얻는 최신성이 0이고,
 * 콜만 태운다. 소스의 주기가 분기이므로 이 잡의 주기도 그쪽에 맞춘다.
 *
 * <p><b>소스가 답하지 못하는 구간이 있다.</b> 정기보고서 제출 전 구간(오늘 기준 최근 넉 달
 * 남짓)은 조회해도 원리적으로 0건이다. 그 구간의 불연속은 휴리스틱이 {@code OBSERVED} 후보로
 * 올리므로 <b>"그런 사건이 없다"로 읽히지는 않는다</b>(phase 기준 §P9).
 */
@Component
public class CorporateActionJob {

    public static final String JOB_NAME = "corporate-action";

    /** 알림 본문에 이름으로 적을 충돌의 수. 넘는 것은 로그가 답한다. */
    private static final int CONFLICTS_IN_ALERT = 10;

    private static final Logger log = LoggerFactory.getLogger(CorporateActionJob.class);

    private final CorporateActionCollector collector;
    private final StockRegistrar stockRegistrar;
    private final PriceCalendar priceCalendar;
    private final DataSourceRegistry registry;
    private final BatchExecutions batchExecutions;

    public CorporateActionJob(CorporateActionCollector collector, StockRegistrar stockRegistrar,
                              PriceCalendar priceCalendar, DataSourceRegistry registry,
                              BatchExecutions batchExecutions) {
        this.collector = collector;
        this.stockRegistrar = stockRegistrar;
        this.priceCalendar = priceCalendar;
        this.registry = registry;
        this.batchExecutions = batchExecutions;
    }

    /**
     * {@code [from, asOf]}에 효력일이 드는 기업 행위를 모은다.
     *
     * @param from    비면 <b>저장된 시세의 첫 거래일</b>. MUST NOT: 시세의 15일 창을 그대로 쓰지
     * 않는다 — 소스가 정기보고서에만 실려 <b>원리적으로 0건</b>이 된다(dart-corporate-action.md의 「증자(감자)
     * 현황」 절).
     *                창을 넓혀도 콜은 거의 안 는다(전 구간 종목당 2콜, 한 달 1콜).
     * @param tickers      비면 편입 중인 활성 종목 전체.
     * @param universeCode 손으로 고른 유니버스. 주면 <b>활성 여부를 보지 않는다</b>(ADR-0090).
     *                     자동 실행은 언제나 null이다 — 그래야 "활성"의 뜻이 한 가지로 남는다.
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           LocalDate from, List<String> tickers, String universeCode) {
        List<SecurityRef> targets =
                stockRegistrar.toSecurityRefs(resolveTargets(tickers, universeCode));

        // 시세가 하나도 없으면 소스를 부를 근거가 없다. 어댑터가 우리 시계열에서 일자와 비율을
        // 유도하므로, 이 확인이 없으면 종목마다 콜을 태우고 전부 유도 실패로 끝난다.
        //
        // MUST: 첫 거래일을 **시장마다** 묻는다. 전 시장 최솟값을 쓰면 국내가 정한 2020-01-02이
        // 미국의 기본 창이 되는데, 미국 봉이 없는 구간에서는 원장의 근접중복 창이 두 날짜 사이의
        // **봉 수**로 5거래일을 세므로 45일 안의 두 사건이 무조건 한 사건으로 붙는다 — 그리고
        // 알림 없이 log.info로 끝난다(ADR-0090).
        Map<String, LocalDate> earliestByMarket = new LinkedHashMap<>();
        for (SecurityRef target : targets) {
            earliestByMarket.computeIfAbsent(target.marketCode(),
                    market -> priceCalendar.earliestTradeDate(market).orElse(null));
        }
        earliestByMarket.values().removeIf(Objects::isNull);

        if (targets.isEmpty() || earliestByMarket.isEmpty()) {
            log.info("기업 행위를 모을 대상이 없다. 대상={}종목 저장된 시세={} — 시세를 먼저 적재한다.",
                    targets.size(), earliestByMarket.isEmpty() ? "없음" : earliestByMarket);
            return batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0).finish();
        }

        log.info("기업 행위 수집을 시작한다. 대상 {}종목, 창 {} ~ {}"
                        + " — 종목당 콜 수는 창을 덮는 정기보고서의 최소 집합이 정한다.",
                targets.size(),
                from != null ? from.toString() : "시장별 첫 거래일 " + earliestByMarket, asOf);

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, targets.size());
        // 시장별로 접어 루프 뒤에 한 줄씩 낸다. 종목마다 찍던 때 한 판에 312줄이 stockId만
        // 다른 채 나왔고(2026-09-13 실측 · 380 B/줄) 그 312가 전부 US라 **국내 신호가 원리적으로
        // 묻혀 있었다** — 시장 축이 ADR-0100의 의도를 살리는 자리다.
        // MUST: 시장 목록을 코드에 박지 않는다. Map의 키로 두면 새 시장이 저절로 들어온다 —
        // ADR-0100 §규칙이 「시장으로 분기하면 고치지 않은 시장이 조용히 옛 임계를 쓴다」로
        // 경계한 것이 그 하드코딩이다.
        // 이 루프는 순차다(BatchExecutionContext.process에 병렬 없음). 워커로 흩으면 이
        // 카운터는 예외 없이 조용히 틀린다 — 그때 함께 고친다(ADR-0138이 지표 백필에 한 것처럼).
        Map<String, Integer> skippedNoSharesByMarket = new java.util.TreeMap<>();
        List<CorporateActionCollector.NearDuplicate> conflicts = new ArrayList<>();
        List<CorporateActionCollector.PendingCandidate> pending = new ArrayList<>();

        try {
            // MUST: 창 검증을 실행 행을 연 **뒤에** 한다. 앞에서 던지면 batch_execution에 아무
            // 흔적도 안 남고, 관리자 화면으로 띄운 경우 런처의 스레드 경계 로그만 남는다 —
            // 화면에는 202 뒤 아무 일도 안 일어난 것으로 보인다(ADR-0050).
            for (Map.Entry<String, LocalDate> e : earliestByMarket.entrySet()) {
                LocalDate start = from != null ? from : e.getValue();
                if (start.isAfter(asOf)) {
                    throw new IllegalArgumentException(
                            "조회 창의 시작이 대상일보다 늦다: " + e.getKey() + ' '
                                    + start + " > " + asOf);
                }
            }

            // MUST: 루프에 들어가기 전에 소스를 해석한다. 등록되지 않았을 때 나오는 NOT_FOUND는
            // fatal()이 아니라, 루프 안에 두면 **전 종목이 같은 이유로 실패하고 배치는 끝까지
            // 돈다.** 키가 없으면 수정계수가 영영 1로 남는데 그것이 부분 성공으로 기록된다.
            requireSources(targets);
            reportWindowCoverage(targets, earliestByMarket, from, asOf);

            execution.plan(CollectionPhase.ADJUSTMENT, targets.size());
            execution.stage("종목별 기업 행위 수집");

            for (SecurityRef target : targets) {
                LocalDate windowStart = from != null
                        ? from : earliestByMarket.get(target.marketCode());
                if (windowStart == null) {
                    // 그 시장에 봉이 하나도 없다. 종목을 건너뛰되 조용히 넘기지 않는다 —
                    // 0건과 「적재할 것이 없다」가 구별되지 않으면 나중에 원인을 못 찾는다.
                    log.warn("시세가 없는 시장이라 기업 행위를 건너뛴다. {}/{}",
                            target.marketCode(), target.ticker());
                    continue;
                }
                execution.process(target, CollectionPhase.ADJUSTMENT, security -> {
                    CorporateActionCollector.Result result =
                            collector.collect(security, windowStart, asOf);
                    conflicts.addAll(result.nearDuplicates());
                    pending.addAll(result.pending());
                    if (result.skippedNoShares() > 0) {
                        skippedNoSharesByMarket.merge(
                                security.marketCode(), result.skippedNoShares(), Integer::sum);
                    }
                    return result.recorded();
                });
            }

            reportSkippedNoShares(skippedNoSharesByMarket);
            alertOnConflicts(execution, conflicts);
            alertOnPendingJudgement(execution, pending);
        } catch (DataSourceException e) {
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        return execution.finish();
    }

    /**
     * 대상 종목의 시장마다 담당 소스가 있는지 확인한다.
     *
     * <p>IMPORTANT — 이 확인의 값은 <b>루프 앞에 있다는 것</b>이다. 국내 어댑터가
     * {@code @ConditionalOnApiKey}라 키가 없으면 빈으로 등록조차 되지 않고, 그때 나오는
     * {@code NOT_FOUND}는 종목 실패로 기록될 뿐 배치를 멈추지 않는다. {@code DataSourceRegistry}가
     * 이 포트에 {@code Optional} 변형을 금지한 이유와 같은 자리다.
     */
    /**
     * 주식수가 없어 후보로 못 올린 불연속을 <b>시장마다 한 줄</b>로 적는다(ADR-0100 §규칙).
     *
     * <p>MUST: <b>0인 시장은 찍지 않는다.</b> 국내는 {@code listed_shares} 결손이 0행이라 이 줄이
     * 안 뜨는 것이 정상이고, <b>뜨는 것 자체</b>가 V9 이전 형태의 행이 다시 들어왔다는 신호다
     * ({@code AdjustmentRecalculator.withoutUnjudgeable} javadoc). 0을 늘 찍으면 그 신호가
     * 「늘 있는 줄 안의 숫자 변화」가 되어 눈에 안 띈다.
     *
     * <p>MUST NOT: 이것을 실패나 경고로 올리지 않는다. 건너뛴 것은 <b>판정할 재료가 없는 것</b>이지
     * 실패가 아니다 — 값은 그 수가 기본 레벨에서 읽힌다는 데 있다.
     */
    private void reportSkippedNoShares(Map<String, Integer> byMarket) {
        byMarket.forEach((market, count) -> log.info(
                "[{}] 상장주식수가 없어 후보로 올리지 않은 가격 불연속이 {}건이다 — 종류도 비율도"
                        + " 못 내는 후보는 OBSERVED로 쌓여 그 효력일을 창에 삼키는 지표를 통째로"
                        + " 억제한다. 종목별은 DEBUG다(.env의 LOG_ADJUSTMENT=DEBUG).",
                market, count));
    }

    /**
     * 소스가 창의 뒤쪽을 못 덮으면 <b>시장마다 한 줄</b>로 적는다.
     *
     * <p>MUST: 종목 루프 <b>앞</b>에서 부른다. 커버리지는 창과 시장의 성질이라 종목 수만큼
     * 되풀이해도 더해지는 정보가 0이다 — 어댑터 안에서 종목마다 찍던 때 한 판에 350줄이
     * 티커만 다른 채 똑같이 나왔고 그것이 운영 로그의 12.7%였다(2026-09-11 실측).
     *
     * <p>MUST NOT: 이것을 실패나 경고로 올리지 않는다. 정기보고서가 아직 안 나온 것은
     * 정상이고, 그 구간의 사건은 휴리스틱이 후보로 올린다. 여기서 하는 일은
     * <b>무엇을 못 봤는지 말할 수 있게</b> 하는 것뿐이다(phase 기준 §P9).
     */
    private void reportWindowCoverage(List<SecurityRef> targets,
                                      Map<String, LocalDate> earliestByMarket,
                                      LocalDate from, LocalDate asOf) throws DataSourceException {
        Set<String> markets = new LinkedHashSet<>();
        for (SecurityRef target : targets) {
            markets.add(target.marketCode());
        }
        for (String market : markets) {
            LocalDate start = from != null ? from : earliestByMarket.get(market);
            if (start == null) {
                continue;
            }
            Optional<String> gap = registry.corporateActionSource(market).windowCoverage(start, asOf);
            gap.ifPresent(text -> log.info("[{}] {}", market, text));
        }
    }

    private void requireSources(List<SecurityRef> targets) throws DataSourceException {
        Set<String> markets = new LinkedHashSet<>();
        for (SecurityRef target : targets) {
            markets.add(target.marketCode());
        }
        for (String market : markets) {
            registry.corporateActionSource(market);
        }
    }

    /**
     * 사람이 갈라야 할 충돌을 <b>요약 한 건</b>으로 알린다.
     *
     * <p>MUST NOT: 종목마다 보내지 않는다. 그리고 MUST NOT: 로그로만 두지 않는다 — 진짜 별개
     * 사건이면 원장에 자리가 없어 영영 반영되지 않고, 같은 사건이면 일자 보정이 어긋났다는
     * 신호다. 둘 다 사람이 봐야 한다.
     */
    private void alertOnConflicts(BatchExecutionContext execution,
                                  List<CorporateActionCollector.NearDuplicate> conflicts) {
        if (conflicts.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder(
                "%d건이 기존 원장 행과 며칠 안에 겹쳐 적지 못했다. 같은 사건의 일자 보정이 어긋났거나"
                        .formatted(conflicts.size())
                        + " 진짜 별개 사건인데 원장에 자리가 없는 것이다.\n");
        conflicts.stream().limit(CONFLICTS_IN_ALERT).forEach(conflict -> body.append(
                "- %s: 새 %s / 기존 %s (%s)\n".formatted(conflict.ticker(), conflict.proposed(),
                        conflict.existing(), conflict.source())));
        if (conflicts.size() > CONFLICTS_IN_ALERT) {
            body.append("나머지 %d건은 로그에 있다.".formatted(conflicts.size() - CONFLICTS_IN_ALERT));
        }
        execution.alertOperator(AlertSeverity.WARNING, "기업 행위 근접 중복", body.toString());
    }

    /**
     * 새로 생긴 판정 대기 후보를 <b>요약 한 건</b>으로 알린다.
     *
     * <p>MUST NOT: 로그로만 두지 않는다. ADR-0055부터 판정되지 않은 후보는 그 종목의 장기창
     * 지표를 억제하므로, 후보 하나가 생기는 순간 <b>그 종목이 모든 프리셋에서 사라진다</b> —
     * 스크리닝은 최신 일자 한 장만 읽기 때문이다. 회복은 사람이 판정할 때까지이고
     * {@code ICHI_*}는 사건 뒤 <b>그 지표의 억제 창</b>(1,100거래일 ≈ 4.4년)까지 이어진다.
     *
     * <p>이번 실행이 <b>새로 만든</b> 후보만 센다. 남아 있는 후보 전체를 세면 판정이 밀리는
     * 동안 매 실행 같은 알림이 나가고, 며칠 안에 아무도 그 알림을 보지 않게 된다
     * ({@link #alertOnConflicts}가 판정된 행을 빼는 것과 같은 이유다).
     */
    private void alertOnPendingJudgement(BatchExecutionContext execution,
                                         List<CorporateActionCollector.PendingCandidate> pending) {
        if (pending.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder(
                ("%d건이 판정을 기다린다. 판정 전까지 아래 종목의 장기창 지표는 계산되지 않고, "
                        + "그 종목은 프리셋 결과에서 빠진다.\n").formatted(pending.size()));
        pending.stream().limit(CONFLICTS_IN_ALERT).forEach(candidate -> body.append(
                "- %s: 효력일 %s (%s)\n".formatted(
                        candidate.ticker(), candidate.effectiveDate(), candidate.source())));
        if (pending.size() > CONFLICTS_IN_ALERT) {
            body.append("나머지 %d건은 로그에 있다.".formatted(pending.size() - CONFLICTS_IN_ALERT));
        }
        body.append("판정은 PUT /api/admin/corporate-actions/{id}이다 — 화면은 없다.");
        execution.alertOperator(AlertSeverity.WARNING, "기업 행위 판정 대기", body.toString());
    }

    /**
     * 대상 종목을 정한다.
     *
     * <p>IMPORTANT — 티커를 줬는데 하나도 매칭되지 않으면 <b>예외로 즉시 실패시킨다.</b>
     * 이 잡은 <b>재실행에서 0행이 정상</b>이므로, 오타로 대상이 빈 것과 이미 다 적힌 것이
     * 로그에서 구분되지 않는다({@link CompanyProfileJob}과 같은 자리다).
     */
    private List<Long> resolveTargets(List<String> tickers, String universeCode) {
        if (universeCode != null && !universeCode.isBlank()) {
            return resolveUniverseTargets(universeCode, tickers);
        }
        if (tickers != null && !tickers.isEmpty()) {
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
        return stockRegistrar.activeUniverseMemberIds();
    }

    /**
     * 유니버스를 손으로 골랐을 때의 대상(ADR-0090).
     *
     * <p>MUST: <b>0건이면 예외로 끝낸다.</b> {@code oneUniverseMemberIds}는 활성 여부를 보지
     * 않는 대신 <b>예외를 던지지 않고 빈 목록을 낸다</b> — 그 자리를 여기서 채우지 않으면
     * 위쪽 {@code targets.isEmpty()} 갈래로 빠져 <b>SUCCESS로 끝난다.</b> 조용한 0건은
     * "적재할 것이 없다"와 구별되지 않는다(ADR-0088 §규칙 · {@code InitialLoadJob}과 같은 자리).
     */
    private List<Long> resolveUniverseTargets(String universeCode, List<String> tickers) {
        List<String> requested = tickers == null ? List.of() : tickers;
        List<Long> members = stockRegistrar.oneUniverseMemberIds(universeCode, requested);
        if (members.isEmpty()) {
            throw new IllegalArgumentException(
                    "유니버스 '" + universeCode + "'에 대상이 하나도 없다"
                            + (requested.isEmpty() ? "" : " (티커 " + requested + ")")
                            + " — 유니버스 코드와, 그 시장이 CollectedMarkets에 있는지 확인하라."
                            + " 대상 0건으로 성공시키지 않는다.");
        }
        log.info("유니버스 {} 편입 {}종목이 대상이다. 활성 여부는 보지 않았다.",
                universeCode, members.size());
        return members;
    }
}
