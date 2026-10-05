package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.PriceDiscontinuity;
import com.stockscreener.pipeline.datasource.ShareRatioSnap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 수정계수의 정본. <b>원장에서 매번 다시 짓고 누적하지 않는다</b>(ADR-0049).
 *
 * <h2>계산 모델</h2>
 * <pre>
 * adj_factor(d) = Π { 원장의 적용 대상 사건 e : e.effective_date &gt; d } (1 / 가격배수(e))
 * 가격배수(e)   = e.price_ratio, 없으면 e.ratio(V60 — 분할·병합·무상증자는 주식수 배수와 같다)
 * adj_close(d)  = close(d) × adj_factor(d)
 *
 * 50:1 분할이 2018-05-04에 발생한 경우
 *   2018-05-03 이전 행: adj_factor = 1/50 = 0.02  → adj_close = close × 0.02
 *   2018-05-04 이후 행: adj_factor = 1            → adj_close = close
 * </pre>
 *
 * <p>이 방향을 뒤집으면 과거 시세가 50배로 부풀고, 그 오류는 분할 종목의 차트를 직접 보기
 * 전까지 드러나지 않는다.
 *
 * <h2>왜 곱하지 않고 다시 짓는가</h2>
 * <p>예전에는 사건마다 {@code adj_factor *= 1/ratio}를 곱했다. 시세 수집이 15일 창으로 돌아
 * 같은 날짜를 평일 11~12회 다시 보므로(ADR-0042) 소스가 사건을 주기 시작하는 순간 같은 분할이
 * 그만큼 반복 적용되고, 5:1을 15회 곱하면 {@code numeric(18,10)}이 <b>정확히 0</b>이 되어
 * {@code adj_close}가 전 구간 0이 되는데 <b>아무 에러도 나지 않는다.</b>
 *
 * <p>대입으로 바꾸면 몇 번을 돌려도 값이 같고, 원장에서 행 하나를 되돌리고 다시 짓는 것만으로
 * 반영이 취소된다. 되돌리기 수단이 DB 덤프뿐이 아니게 되는 것이 이 설계의 목적이다.
 *
 * <h2>왜 종목 전체가 하나의 트랜잭션인가</h2>
 * <p>절반만 조정된 시계열은 조정되지 않은 시계열보다 나쁘다. 후자는 분할일에 명백한 점프가
 * 보이지만, 전자는 임의의 날짜에 이상이 생겨 원인을 찾기 어렵다.
 */
@Component
public class AdjustmentRecalculator {

    private static final Logger log = LoggerFactory.getLogger(AdjustmentRecalculator.class);

    /**
     * 탐지 매퍼가 「거래재개 가격 발견이라 후보가 아니다」를 알리는 표식. {@code null}은 이미
     * 「주식수가 없어 판정 불가」가 쓰고 있어 둘을 섞으면 그 수(ADR-0100)가 부풀어 오른다.
     * 동일성으로만 비교하고 원장에 절대 닿지 않는다.
     */
    private static final CorporateAction RESUMED_AFTER_HALT = new CorporateAction(
            LocalDate.EPOCH, CorporateAction.Type.UNKNOWN, BigDecimal.ONE,
            CorporateAction.DetectionSource.HEURISTIC);

    /** {@code adj_factor numeric(18,10)}에 맞춘다. */
    private static final int FACTOR_SCALE = PriceDiscontinuity.RATIO_SCALE;

    /** {@code numeric(18,10)}의 정수부 상한. 넘으면 DB가 알아보기 힘든 오류를 낸다. */
    private static final BigDecimal FACTOR_LIMIT = new BigDecimal("100000000");

    /** 주식수 배수는 정수 나눗셈이라 유효자릿수를 넉넉히 둔다. 스냅이 그 뒤를 맡는다. */
    private static final MathContext SHARE_MATH = new MathContext(34);

    private final JdbcTemplate jdbcTemplate;
    private final CorporateActionLedger ledger;
    private final IndicatorInvalidationPort invalidationPort;

    public AdjustmentRecalculator(JdbcTemplate jdbcTemplate,
                                  CorporateActionLedger ledger,
                                  IndicatorInvalidationPort invalidationPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.ledger = ledger;
        this.invalidationPort = invalidationPort;
    }

    /**
     * @param changedRows 실제로 값이 바뀐 행 수. 0이면 이미 원장과 일치했다는 뜻이다.
     * @param earliestChanged 바뀐 행 중 가장 이른 거래일. 바뀐 것이 없으면 null.
     */
    public record RebuildResult(long changedRows, LocalDate earliestChanged, int appliedEvents) {
    }

    /**
     * 원장 전체에서 그 종목의 수정계수와 수정종가를 다시 짓는다.
     *
     * <p><b>이것이 저장된 시세를 소급해 다시 쓰는 유일한 지점이다.</b> 몇 번을 돌려도 결과가
     * 같고, 원장에 적용 대상이 하나도 없으면 계수를 전량 1로 되돌린다 — 그것이 반영을
     * 취소하는 방법이다.
     *
     * <p>MUST: {@link CorporateAction.Type} 별 부호를 여기서 다시 정하지 않는다.
     * 가격 배수({@link CorporateActionLedger.Entry#priceMultiple()})의 계약이 <i>"사건 전 가격을 몇으로
     * 나누는가"</i>이므로 종류와 무관하게 {@code 1/가격배수}가 옳다. 종류를 읽어 분기하면 부호를 정하는
     * 곳이 둘이 되고, <b>두 번 뒤집힌 값은 원래 값과 같아 보인다.</b>
     *
     * <p>MUST NOT: 여기서 {@code ratio}(주식수 배수)를 직접 읽지 않는다. 분사는 두 배수가 갈리는
     * 사건이라 주식수 배수를 가격에 실으면 분사 법인의 가치만큼 가짜 계단이 생긴다(ADR-0117 §규칙).
     */
    @Transactional
    public RebuildResult rebuild(long stockId) {
        List<CorporateActionLedger.Entry> events = ledger.findApplicable(stockId);
        List<Segment> segments = segmentsOf(events);

        RebuildResult result = assignFactors(stockId, segments, events.size());

        if (result.changedRows() == 0) {
            // DEBUG인 이유는 이 줄이 진단문이 아니라 **아무 일도 안 일어났다**를 종목 수만큼
            // 되풀이하기 때문이다. 실측으로 무변경 10,797줄 대 실제 재작성 3줄(3,599:1)이었고,
            // 그 하나가 운영 로그의 29.7%를 차지했다. 같은 사실을 AdjustmentRebuildJob이
            // 실행당 요약으로 내고 corporate_action 원장이 SQL 한 줄로 답한다.
            //
            // MUST: 문구를 지우지 말고 레벨만 내린다 — 켰을 때 읽히는 것이 이 줄의 값이다.
            // 켜는 법은 .env에 LOG_ADJUSTMENT=DEBUG 한 줄 + 재기동이다(application.yaml).
            log.debug("수정계수가 이미 원장과 일치한다. stockId={} 사건={}건 — 다시 쓸 것이 없다.",
                    stockId, events.size());
        } else {
            log.info("수정계수를 원장에서 다시 지었다. stockId={} 사건={}건 갱신={}행 최초변경일={}",
                    stockId, events.size(), result.changedRows(), result.earliestChanged());

            // IMPORTANT — 원자료가 소급 변경되었으므로 파생 지표도 무효화해야 한다. 이 호출이
            // 빠지면 저장된 SMA200이 조정 전 가격으로 계산된 채 남는다.
            //
            // MUST: 바뀐 행이 있을 때만 부른다. 무효화는 그 종목의 indicator_value를 전량
            // 지우므로(30종목이면 16만 행), 가드가 없으면 멱등한 재구축이 매 실행 그만큼을
            // 지우고 다음 백필까지 그 종목이 화면에서 통째로 사라진다.
            invalidationPort.invalidate(stockId, result.earliestChanged(),
                    IndicatorInvalidationPort.InvalidationReason.PRICE_ADJUSTED);
        }

        // 갱신 행이 0이어도 표시한다 — 상장 이전 사건은 조정할 봉이 없을 뿐 반영이 끝난 것이다.
        // 표시하지 않으면 "아직 반영 대기"와 구분되지 않는다.
        ledger.markApplied(events.stream().map(CorporateActionLedger.Entry::id).toList());
        return result;
    }

    /**
     * 시세 급변으로 기업 행위 후보를 탐지한다.
     *
     * <p>MUST NOT: 결과를 그대로 반영하지 않는다. 반환되는 사건은 모두
     * {@link CorporateAction.DetectionSource#HEURISTIC}이므로 원장에 {@code OBSERVED}로
     * 들어가고, 사람이 확인해야 적용 대상이 된다.
     *
     * <h2>가격은 "사건이 있었다"까지만 말한다</h2>
     * <p><b>비율과 종류는 같은 날 {@code listed_shares}가 답한다</b>(ADR-0049 · api-spec
     * dart-corporate-action.md의 「분할·병합의 비율과 효력일」 절). 종가 배수는 그날 시장이 움직인 만큼
     * 흐려지기 때문이다 — 대한전선 2023은
     * 종가로 재면 {@code 0.094073}인데 주식수로 재면 <b>정확히 0.1</b>이고, 그 6% 차이는
     * 승인하는 순간 2020~2023 전 구간에 소급 적용된다. 값은 언제나 그럴듯하다.
     *
     * <p>위 절의 3분류를 그대로 옮긴다. 전수 대조가 전부 아래 셋으로 갈렸다.
     * <ul>
     *   <li>주식수 배수가 <b>단순 유리수로 스냅되고</b> 가격 움직임과 어긋나지 않으면 →
     *       {@code SPLIT}/{@code REVERSE_SPLIT} + 스냅된 비율 (11건 + 무상감자 1건)
     *   <li>그 밖 → {@code UNKNOWN} + 관측된 가격 배수. 주식수가 그대로면 무상증자류
     *       권리락이고(26건), 스냅되지 않으면 인적분할·복합이다(6건).
     * </ul>
     *
     * <p>IMPORTANT — <b>{@code UNKNOWN}의 {@code ratio}는 계수가 아니라 관측값이다.</b>
     * {@link CorporateActionLedger#findApplicable}이 그 종류를 제외하므로 사람이 종류를
     * 확정하기 전에는 적용될 수 없다. 예전에는 무상증자 권리락이 여기서 {@code SPLIT}
     * 딱지를 달고 나갔고, 그것을 승인하면 틀린 계수가 들어갔다.
     *
     * <p><b>남은 한계</b> — 인적분할의 분할비율이 우연히 단순 유리수면 여기서 분할·병합으로
     * 읽힌다. 그것을 가르는 것은 소스의 낱말이고 국내에 그 경로가 아직 없다
     * (dart-corporate-action.md의 「카탈로그」 절 — 「회사분할 결정」은 미착수).
     * 후보가 {@code OBSERVED}에서 멈추는 것이 그 자리의 방어다.
     *
     * <h2>MUST: 상장주식수가 없으면 후보를 올리지 않는다</h2>
     * <p>임계 {@code 1.30}/{@code 0.70}은 <b>KRX 가격제한폭</b>이다({@link PriceDiscontinuity}의
     * 상수 javadoc). 그 제도가 없는 시장에서는 <b>정상 매매가 그 폭을 넘고</b>, 게다가
     * {@code listed_shares}가 비면 {@link #parValueRatio}가 언제나 비어 종류도 비율도 못 낸다 —
     * 후보가 전부 {@code UNKNOWN}으로 쌓인다.
     *
     * <p><b>그 행은 무해하지 않다.</b> {@code CorporateActionLedger.findUnadjusted}가
     * {@code OBSERVED}를 그대로 돌려주고 {@code UnadjustedActionGuard}가 <b>그 효력일을 창에
     * 삼키는 산출 일자를 억제한다</b> — 워밍업이 1,261봉인 지표는 한 건만 걸려도 보존 창 전체가
     * 빈다. 배치는 SUCCESS이고 화면은 「값 없음」이라 <b>조건 미달과 구별되지 않는다.</b>
     *
     * <p>실측(2026-08-31): 같은 술어가 국내에서 이웃 쌍 543,002 중 <b>72건·67종목</b>인데
     * 미국에서는 1,652,647 중 <b>771건·317종목</b>이다(3.5배). 그 771의 배수 분포가
     * 1.3~1.9배 414 · 0.25~0.7배 260으로 <b>대부분이 분할이 아니라 큰 하루</b>다.
     *
     * <p><b>축은 시장이 아니라 주식수의 유무다.</b> 국내는 {@code listed_shares} 결손이 0행이라
     * 이 술어로 후보가 <b>한 건도 안 줄고</b>(72 → 72), 미국은 전 행이 NULL이라 0이 된다(실측).
     * 시장 코드로 분기하면 새 시장마다 그 목록을 고쳐야 하고, <b>고치지 않은 시장은 조용히
     * 옛 임계를 쓴다.</b>
     *
     * <p>MUST NOT: 이것을 「그 시장에는 기업행위 탐지가 없다」로 읽지 않는다. 소스가 분할·병합을
     * 직접 주는 시장에서는 <b>그쪽이 정본</b>이고(ADR-0090), 이 휴리스틱은 <b>소스가 액면병합을
     * 안 싣는 국내를 위한 두 번째 그물</b>이다(ADR-0049).
     */
    public Detection detectCandidates(long stockId) {
        int resumptionFrame = PriceDiscontinuity.RESUMPTION_SHARE_WINDOW_BARS + 2;
        return withoutUnjudgeable(stockId, jdbcTemplate.query("""
                SELECT trade_date, close, prev_close, listed_shares, prev_listed_shares,
                       prev_halted, forward_bars, forward_min_shares, forward_max_shares FROM (
                    SELECT trade_date, close, listed_shares,
                           lag(close) OVER w AS prev_close,
                           lag(listed_shares) OVER w AS prev_listed_shares,
                           coalesce(lag(volume) OVER w = 0
                                    AND lag(open) OVER w = lag(high) OVER w
                                    AND lag(high) OVER w = lag(low) OVER w
                                    AND lag(low) OVER w = lag(close) OVER w, false) AS prev_halted,
                           count(*) OVER f AS forward_bars,
                           min(listed_shares) OVER f AS forward_min_shares,
                           max(listed_shares) OVER f AS forward_max_shares
                    FROM price_daily WHERE stock_id = ?
                    WINDOW w AS (ORDER BY trade_date),
                           f AS (ORDER BY trade_date ROWS BETWEEN 1 PRECEDING AND %d FOLLOWING)
                ) t
                WHERE prev_close IS NOT NULL AND close > 0 AND prev_close > 0
                  AND (close / prev_close > ? OR close / prev_close < ?)
                ORDER BY trade_date
                """.formatted(PriceDiscontinuity.RESUMPTION_SHARE_WINDOW_BARS),
                (rs, rowNum) -> {
                    LocalDate date = rs.getObject("trade_date", LocalDate.class);
                    BigDecimal close = rs.getBigDecimal("close");
                    BigDecimal prevClose = rs.getBigDecimal("prev_close");

                    if (rs.getBigDecimal("listed_shares") == null
                            || rs.getBigDecimal("prev_listed_shares") == null) {
                        // 판정 재료가 없다. null은 아래에서 걸러지고 그 수가 로그로 남는다.
                        return null;
                    }

                    // 거래정지 뒤 첫 거래 봉의 가격 발견은 기업행위가 아니다 — 앞쪽 창에서 주식수가
                    // 한 주도 안 바뀌었고 창이 다 찼을 때만(RESUMPTION_SHARE_WINDOW_BARS javadoc).
                    // MUST: 조용히 버리지 않는다. 드물어서(국내 전 이력 7건) 종목마다 INFO로 남긴다.
                    BigDecimal forwardMin = rs.getBigDecimal("forward_min_shares");
                    BigDecimal forwardMax = rs.getBigDecimal("forward_max_shares");
                    if (rs.getBoolean("prev_halted")
                            && rs.getInt("forward_bars") == resumptionFrame
                            && forwardMin != null && forwardMin.compareTo(forwardMax) == 0) {
                        log.info("거래정지 뒤 첫 거래 봉의 가격 발견이라 기업행위 후보로 올리지 않는다. "
                                        + "stockId={} 효력일={} 관측배수={} — 앞 {}거래일 동안 상장주식수가 그대로다.",
                                stockId, date, PriceDiscontinuity.observedRatio(close, prevClose),
                                PriceDiscontinuity.RESUMPTION_SHARE_WINDOW_BARS);
                        return RESUMED_AFTER_HALT;
                    }

                    Optional<BigDecimal> fromShares = parValueRatio(
                            rs.getBigDecimal("prev_listed_shares"), rs.getBigDecimal("listed_shares"),
                            close, prevClose);
                    if (fromShares.isPresent()) {
                        BigDecimal ratio = fromShares.get();
                        CorporateAction.Type type = ratio.compareTo(BigDecimal.ONE) > 0
                                ? CorporateAction.Type.SPLIT
                                : CorporateAction.Type.REVERSE_SPLIT;
                        return new CorporateAction(date, type, ratio,
                                CorporateAction.DetectionSource.HEURISTIC);
                    }
                    // 종류를 모른다. ratio는 관측된 가격 배수이며 계수가 아니다.
                    // MUST: 방향의 정본은 PriceDiscontinuity 하나다 — UNKNOWN은 도메인도 DB도
                    // 부호를 안 보므로 여기서 뒤집으면 어디에서도 안 걸린다.
                    return new CorporateAction(date, CorporateAction.Type.UNKNOWN,
                            PriceDiscontinuity.observedRatio(close, prevClose),
                            CorporateAction.DetectionSource.HEURISTIC);
                },
                stockId, PriceDiscontinuity.LIMIT_UPPER, PriceDiscontinuity.LIMIT_LOWER));
    }

    /**
     * 휴리스틱이 올린 후보와, <b>주식수가 없어 판정하지 못해 버린 수</b>.
     *
     * <p>수를 값으로 돌려주는 이유는 <b>세는 자리와 찍는 자리가 달라야 하기 때문</b>이다.
     * 여기는 종목 하나만 알고 시장을 모른다 — 시장별로 접는 것은 {@code CorporateActionJob}이
     * 한다. 어댑터·계산기에 누적 상태를 두지 않는 것은 종목 루프를 워커로 흩어도 안전해야
     * 하기 때문이고, 그 근거는 {@code CorporateActionDataSource}의 {@code windowCoverage}
     * javadoc에 같은 말로 적혀 있다.
     */
    public record Detection(List<CorporateAction> candidates, int skippedNoShares) {
    }

    /**
     * 주식수가 없어 판정할 수 없었던 불연속을 걸러 내고 <b>그 수를 값으로 돌려준다.</b>
     *
     * <p>조용히 건너뛰면 이 저장소가 가장 싫어하는 형태가 된다 — 그래서 센다(ADR-0100 §규칙).
     * 국내에서는 {@code listed_shares} 결손이 0행이라 <b>국내 줄이 안 뜨고</b>(실측), 뜨면 그것이
     * <b>V9 이전 형태의 행이 다시 들어왔다</b>는 신호다. <b>그 신호의 형태가 「부재 → 출현」이므로
     * 집계 쪽도 0인 시장은 찍지 않는다</b> — 0을 늘 찍으면 신호가 「늘 있는 줄 안의 숫자 변화」가
     * 되어 눈에 안 띈다.
     *
     * <p>종목별 줄은 <b>DEBUG</b>다. 지운 것이 아니라 내린 것이고 {@code .env}의
     * {@code LOG_ADJUSTMENT=DEBUG} + 재기동이면 되살아난다. 기본 레벨에서 <b>종목 귀속을
     * 잃는 것</b>이 이 강등이 파는 대가다 — 되살리는 재실행은 외부 호출이 없어 쿼터를 안 태운다.
     */
    private Detection withoutUnjudgeable(long stockId, List<CorporateAction> detected) {
        // 거래재개 가격 발견은 탐지 매퍼가 이미 INFO로 남겼다. 여기서는 후보 목록에서만 뺀다 —
        // 값 동등이 아니라 **동일성**으로 가른다(레코드 equals가 우연히 같은 행을 삼키지 않게).
        List<CorporateAction> rows = detected.stream()
                .filter(action -> action != RESUMED_AFTER_HALT)
                .toList();
        int skipped = (int) rows.stream().filter(java.util.Objects::isNull).count();
        if (skipped > 0) {
            log.debug("상장주식수가 없어 후보로 올리지 않은 가격 불연속이 {}건이다. stockId={} — "
                    + "종류도 비율도 못 내는 후보는 OBSERVED로 쌓여 그 효력일을 창에 삼키는 "
                    + "지표를 통째로 억제한다(AdjustmentRecalculator.detectCandidates javadoc).",
                    skipped, stockId);
        }
        return new Detection(rows.stream().filter(java.util.Objects::nonNull).toList(), skipped);
    }

    /**
     * 같은 날 주식수 배수에서 액면 변경 비율을 낸다. 액면 변경이 아니면 비어 있음.
     *
     * <p>주식수가 없으면 <b>종류를 모르는 것으로 둔다.</b> 가격으로 대신 내면 이 메서드가
     * 없애려는 바로 그 6% 오차가 돌아온다.
     */
    private static Optional<BigDecimal> parValueRatio(BigDecimal before, BigDecimal after,
                                                      BigDecimal close, BigDecimal prevClose) {
        if (before == null || after == null || before.signum() <= 0 || after.signum() <= 0
                || before.compareTo(after) == 0) {
            return Optional.empty();
        }
        Optional<BigDecimal> snapped = ShareRatioSnap.snap(after.divide(before, SHARE_MATH));
        if (snapped.isEmpty() || snapped.get().compareTo(BigDecimal.ONE) == 0) {
            return Optional.empty();
        }
        // 가격이 그 비율로 설명되는가. 이론 배수는 1/ratio이고, 관측이 그것의 몇 배인지 본다.
        return PriceDiscontinuity.supportsShareRatio(snapped.get(), close, prevClose)
                ? snapped
                : Optional.empty();
    }

    /**
     * 구간 하나 — {@code trade_date < boundary}인 행이 갖는 계수.
     *
     * <p>마지막 구간(모든 사건 이후)은 계수가 1이므로 목록에 담지 않는다. SQL의 {@code ELSE}가
     * 그 자리다.
     */
    private record Segment(LocalDate boundary, BigDecimal factor) {
    }

    /**
     * 사건 목록을 구간 계수로 바꾼다.
     *
     * <p>MUST: 비율의 곱을 먼저 정확히 구하고 나눗셈은 <b>구간마다 한 번</b>만 한다. 역수를
     * 구해 곱해 나가면 반올림 오차가 사건 수만큼 누적되고, 그것이 이 클래스가 없앤 바로 그
     * 병의 축소판이다. 5 × 2 = 10을 한 번 나눠 0.1을 얻는 것과 0.2 × 0.5를 곱하는 것은 다르다.
     */
    private List<Segment> segmentsOf(List<CorporateActionLedger.Entry> events) {
        List<Segment> segments = new ArrayList<>(events.size());
        // 뒤에서부터 누적하면 구간 k의 분모가 "k번째 이후 모든 비율의 곱"이 된다.
        BigDecimal suffixProduct = BigDecimal.ONE;
        for (int k = events.size() - 1; k >= 0; k--) {
            suffixProduct = suffixProduct.multiply(events.get(k).priceMultiple());
            BigDecimal factor = BigDecimal.ONE.divide(suffixProduct, FACTOR_SCALE, RoundingMode.HALF_UP);
            if (factor.abs().compareTo(FACTOR_LIMIT) >= 0) {
                throw new IllegalStateException(
                        "수정계수가 numeric(18,10)의 상한을 넘는다. factor=" + factor
                                + " effectiveDate=" + events.get(k).effectiveDate()
                                + " — 원장의 비율이 뒤집혔거나 같은 사건이 여러 행으로 들어와 있다.");
            }
            segments.add(0, new Segment(events.get(k).effectiveDate(), factor));
        }
        return segments;
    }

    /**
     * 계수를 <b>대입한다.</b> 기존 값에 곱하지 않는다.
     *
     * <p>값이 실제로 달라지는 행만 건드리고, 바뀐 행의 수와 가장 이른 날짜를 함께 받는다 —
     * 무효화 범위가 그 날짜에서 시작해야 하고, 0건이면 무효화 자체를 하면 안 되기 때문이다.
     * {@code JdbcTemplate.update()}로는 둘을 못 얻으므로 CTE로 감싼다.
     */
    private RebuildResult assignFactors(long stockId, List<Segment> segments, int eventCount) {
        // 파라미터 순서는 SQL 텍스트의 등장 순서다 — CASE가 두 stock_id보다 앞에 있다.
        // 적용 대상이 하나도 없으면 CASE 자체가 없다(빈 CASE는 문법 오류다). 그때 계수는
        // 전량 1이고, 그것이 곧 "반영을 되돌린 상태"다.
        List<Object> params = new ArrayList<>();
        StringBuilder factorExpr = new StringBuilder();
        if (segments.isEmpty()) {
            factorExpr.append("1");
        } else {
            factorExpr.append("CASE ");
            for (Segment segment : segments) {
                factorExpr.append("WHEN trade_date < ? THEN ?::numeric ");
                params.add(segment.boundary());
                params.add(segment.factor());
            }
            factorExpr.append("ELSE 1 END");
        }
        params.add(stockId);   // target CTE
        params.add(stockId);   // upd CTE

        // adj_factor와 adj_close에 같은 f를 쓴다. ADR-0049의 유일한 불변식
        // (adj_close = round(close × adj_factor, 6))이 이것으로 원리적으로 성립한다.
        String sql = """
                WITH target AS (
                    SELECT trade_date, close, (%s)::numeric(18,10) AS f
                    FROM price_daily WHERE stock_id = ?
                ), upd AS (
                    UPDATE price_daily p SET
                        adj_factor = t.f,
                        adj_close  = round(p.close * t.f, 6),
                        updated_at = now()
                    FROM target t
                    WHERE p.stock_id = ? AND p.trade_date = t.trade_date
                      AND (p.adj_factor IS DISTINCT FROM t.f
                           OR p.adj_close IS DISTINCT FROM round(p.close * t.f, 6))
                    RETURNING p.trade_date
                )
                SELECT count(*) AS changed, min(trade_date) AS earliest FROM upd
                """.formatted(factorExpr);

        return jdbcTemplate.queryForObject(sql,
                (rs, rowNum) -> new RebuildResult(
                        rs.getLong("changed"),
                        rs.getObject("earliest", LocalDate.class),
                        eventCount),
                params.toArray());
    }
}
