package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.CorporateActionDataSource;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 한 종목의 기업 행위를 모아 원장에 적는다. <b>계수는 건드리지 않는다</b>(ADR-0049).
 *
 * <p>경로가 둘이고 <b>둘 다 있어야 원장이 채워진다.</b>
 * <ul>
 *   <li><b>공시</b>({@link CorporateActionDataSource}) — 무엇이 가격을 바꾸는 행위인지 알려준다.
 *       국내는 DART이고 <b>액면병합은 원리적으로 실리지 않으며</b>(대한전선 2023) 최대 넉 달 늦다.
 *   <li><b>휴리스틱</b>({@link AdjustmentRecalculator#detectCandidates}) — 가격 불연속을 후보로
 *       올린다. 공시가 못 덮는 하락형 사건과 소스 지연 구간을 이쪽이 덮는다.
 * </ul>
 *
 * <p>MUST NOT: <b>여기서 {@link AdjustmentRecalculator#rebuild}를 부르지 않는다.</b> 그것이
 * 저장된 시세를 소급해 다시 쓰는 유일한 지점이고 되돌리기가 원장의 정확성에 의존한다.
 * 이 클래스는 {@code AdjustmentRecalculator}를 주입받아 <b>손이 닿는 자리</b>라 못박아 둔다.
 *
 * <p>{@code @Transactional}을 붙이지 않는다 — {@link com.stockscreener.pipeline.batch.StockTaskRunner}가
 * {@code REQUIRES_NEW}로 이미 감싸고, 여기서 다시 열면 종목 단위 격리가 두 겹이 된다.
 */
@Component
public class CorporateActionCollector {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionCollector.class);

    private final DataSourceRegistry registry;
    private final CorporateActionLedger ledger;
    private final AdjustmentRecalculator recalculator;
    private final PriceCalendar priceCalendar;

    public CorporateActionCollector(DataSourceRegistry registry, CorporateActionLedger ledger,
                                    AdjustmentRecalculator recalculator, PriceCalendar priceCalendar) {
        this.registry = registry;
        this.ledger = ledger;
        this.recalculator = recalculator;
        this.priceCalendar = priceCalendar;
    }

    /**
     * 적지 못하고 사람에게 넘긴 사건.
     *
     * <p>MUST NOT: 이것을 세지 않고 버리지 않는다. 진짜 별개 사건이면 원장에 자리가 없어 영영
     * 반영되지 않고, 같은 사건이면 일자 보정이 어긋났다는 신호다.
     */
    public record NearDuplicate(String ticker, LocalDate proposed, LocalDate existing,
                                CorporateAction.DetectionSource source) {
    }

    /**
     * 사람의 판정을 기다리며 <b>그 사이 지표를 억제하는</b> 새 후보(ADR-0055).
     *
     * <p>판정 전까지 이 종목의 장기창 지표가 화면에서 사라진다. 알림이 없으면 종목이 없어진
     * 것을 아무도 모르고, {@code ICHI_*}는 사건 뒤 <b>그 지표의 억제 창</b>(1,100거래일 ≈ 4.4년)까지 이어진다.
     */
    public record PendingCandidate(String ticker, LocalDate effectiveDate,
                                   CorporateAction.DetectionSource source) {
    }

    /**
     * @param recorded       실제로 새로 적히거나 승격된 행 수. <b>재실행에서 0이 정상이고 그 0이
     *                       멱등의 증거다</b>({@code CompanyProfileCollector}와 같은 판단).
     * @param nearDuplicates 사람이 갈라야 하는 충돌. 잡이 요약해 알린다.
     * @param pending        이번 실행이 새로 만든 판정 대기 후보. 잡이 요약해 알린다.
     */
    public record Result(long recorded, List<NearDuplicate> nearDuplicates,
                         List<PendingCandidate> pending, int skippedNoShares) {

        static Result empty() {
            return new Result(0L, List.of(), List.of(), 0);
        }
    }

    /**
     * {@code [from, to]}에 효력일이 드는 기업 행위를 공시와 휴리스틱 양쪽에서 모아 적는다.
     *
     * <p>MUST: <b>공시를 먼저 적는다.</b> 두 경로가 같은 사건에 며칠 다른 날짜를 낼 때 원장의
     * 근접중복 규칙이 <b>먼저 들어온 쪽에 자리를 준다.</b> 신뢰할 수 있는 쪽이 그 자리를 가져야
     * 한다. (뒤늦게 온 공시가 기존 후보를 옮겨 덮는 경로도 원장에 있지만, 그것은 회복 경로이지
     * 여기서 순서를 아무렇게나 둬도 된다는 뜻이 아니다.)
     *
     * <p>봉이 없는 종목은 <b>소스를 부르지 않고</b> 건너뛴다. 어댑터가 우리 시계열에서 일자와
     * 비율을 유도하므로 봉이 없으면 유도가 조용히 실패하고, 헛콜만 남는다.
     *
     * @return 적힌 행 수와 사람이 갈라야 할 충돌. 예외를 삼키지 않는다.
     */
    public Result collect(SecurityRef security, LocalDate from, LocalDate to)
            throws DataSourceException {
        if (!priceCalendar.hasBars(security.stockId())) {
            log.info("저장된 봉이 없어 기업 행위를 건너뛴다. ticker={} — 소스가 우리 시계열에서"
                    + " 일자와 비율을 유도하므로 부를 근거가 없다.", security.ticker());
            return Result.empty();
        }

        CorporateActionDataSource source = registry.corporateActionSource(security.marketCode());
        List<CorporateAction> fromSource = source.fetchCorporateActions(security, from, to);

        // 소스가 종류를 확정하지 못한 사건은 값이 아니라 **이름만** 기여한다. 그것을 record로
        // 보내면 공시가 먼저 도는 이 순서 때문에 뒤에 올 휴리스틱의 확정된 종류·비율이
        // IGNORED로 버려진다(ADR-0050). 주석은 두 패스가 끝난 뒤에 붙인다.
        Result disclosed = recordAll(security, fromSource.stream()
                .filter(action -> action.type() != CorporateAction.Type.UNKNOWN)
                .toList());
        List<CorporateAction> nameOnly = fromSource.stream()
                .filter(action -> action.type() == CorporateAction.Type.UNKNOWN
                        && action.sourceForm() != null)
                .toList();

        // 휴리스틱은 외부 호출이 없다. 창 밖 후보를 거르는 이유는 포트 계약과 같은 창을 쓰기
        // 위해서다 — 안 거르면 --from을 좁힌 실행이 창 밖 사건까지 매번 다시 올린다.
        AdjustmentRecalculator.Detection detection =
                recalculator.detectCandidates(security.stockId());
        List<CorporateAction> detected = detection.candidates().stream()
                .filter(action -> !action.effectiveDate().isBefore(from)
                        && !action.effectiveDate().isAfter(to))
                .toList();
        Result heuristic = recordAll(security, detected);

        List<NearDuplicate> conflicts = new ArrayList<>(disclosed.nearDuplicates());
        conflicts.addAll(heuristic.nearDuplicates());
        List<PendingCandidate> pending = new ArrayList<>(disclosed.pending());
        pending.addAll(heuristic.pending());

        // MUST: 주석이 **새로 만든** 행을 판정 대기에 합친다. 붙일 자리가 없으면 annotate가
        // OBSERVED 행을 새로 만드는데, 그것도 findUnadjusted에 걸려 ADR-0055가 그 종목의
        // 장기창 지표를 억제한다 — 즉 **그 종목이 모든 프리셋에서 사라진다.** 합치지 않으면
        // 그 일이 log.info 한 줄만 남기고 조용히 일어난다.
        for (CorporateAction action : nameOnly) {
            if (ledger.annotate(security.stockId(), action)) {
                pending.add(new PendingCandidate(security.ticker(), action.effectiveDate(),
                        action.source()));
            }
        }
        // MUST: 창 필터 **앞의** 수를 싣는다. detectCandidates의 SQL에 날짜 경계가 없어
        // 건너뛴 불연속은 실행 창과 무관하게 같은 수이고, 창으로 자르면 --from을 좁힌 실행이
        // 「결손이 줄었다」로 읽힌다.
        return new Result(disclosed.recorded() + heuristic.recorded(), List.copyOf(conflicts),
                List.copyOf(pending), detection.skippedNoShares());
    }

    /**
     * 관측한 사건들을 원장에 적는다. <b>원장에 적는 경로를 하나로 유지하는 지점이다.</b>
     *
     * <p>{@link PriceCollector}도 이것을 부른다 — 그쪽은 시세와 기업 행위를 함께 주는 소스
     * (v1.1의 미국, ADR-0049 §버린 대안)를 위한 자리다. 두 곳이 각자 원장을 만지면
     * {@code NEAR_DUPLICATE}를 한쪽만 알리게 되고, 그 어긋남은 알림이 안 오는 형태로만 드러난다.
     */
    public Result recordAll(SecurityRef security, List<CorporateAction> actions) {
        long recorded = 0;
        List<NearDuplicate> conflicts = new ArrayList<>();
        List<PendingCandidate> pending = new ArrayList<>();
        for (CorporateAction action : actions) {
            CorporateActionLedger.RecordResult result = ledger.record(security.stockId(), action);
            if (result.outcome() == CorporateActionLedger.Outcome.NEAR_DUPLICATE) {
                // MUST NOT: 사람이 이미 판정한 행과의 충돌을 알림으로 올리지 않는다. 그 행은
                // 옮겨지지도 덮이지도 않으므로 충돌이 **영구적**이고, 매 실행 같은 알림이
                // 나가면 며칠 안에 아무도 그 알림을 보지 않게 된다(Phase 6 기준). 사람이 이미
                // 봤다는 사실이 원장에 남아 있는 것이 곧 "확인했다"이다.
                if (result.conflictsWithJudgedRow()) {
                    log.info("사람이 판정한 행과 겹쳐 적지 않았다. ticker={} 새 일자={} 기존 일자={}"
                                    + " 기존 상태={} — 판정이 남아 있으므로 알리지 않는다.",
                            security.ticker(), action.effectiveDate(), result.conflictingDate(),
                            result.conflictingStatus());
                    continue;
                }
                conflicts.add(new NearDuplicate(security.ticker(), action.effectiveDate(),
                        result.conflictingDate(), action.source()));
                continue;
            }
            if (result.changed()) {
                recorded++;
            }
            // 판정 전까지 이 종목의 장기창 지표가 화면에서 사라진다(ADR-0055). 세지 않으면
            // 잡이 알릴 근거가 없고, 그러면 종목이 조용히 없어진다.
            if (result.awaitsJudgement()) {
                pending.add(new PendingCandidate(
                        security.ticker(), action.effectiveDate(), action.source()));
            }
        }
        return new Result(recorded, List.copyOf(conflicts), List.copyOf(pending), 0);
    }
}
