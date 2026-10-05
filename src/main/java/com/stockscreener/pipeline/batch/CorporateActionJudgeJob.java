package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.CorporateActionLedger;
import com.stockscreener.pipeline.collector.CorporateActionReader;
import com.stockscreener.pipeline.collector.CorporateActionStatus;
import com.stockscreener.pipeline.datasource.CorporateAction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 기업 행위 원장을 <b>셸에서</b> 보고 판정한다(ADR-0084). 외부 호출이 0이다.
 *
 * <p>같은 일을 하는 HTTP 경로가 {@code AdminCorporateActionController}에 있고 그쪽이 먼저
 * 생겼다. 여기가 따로 있는 이유는 <b>인증 때문이다</b> — {@code /api/admin/**} 앞의
 * {@code ROLE_ADMIN}에 닿는 길이 OIDC 세션 쿠키뿐이라, 판정 한 건에 앱을 띄우고 로그인하고
 * 개발자 도구 콘솔에서 {@code fetch}를 쳐야 했다. <b>ADR-0049가 세운 관문은 "사람이 판정한다"이지
 * "브라우저로 판정한다"가 아니다</b> — 값은 여기서도 사람이 argv로 준다.
 *
 * <h2>조회와 판정을 한 클래스에 두는 이유</h2>
 * <p><b>대기열의 정의가 둘로 갈리면 사람이 본 적 없는 행을 판정하게 된다.</b> {@link #review}가
 * 기본으로 보여 주는 집합과 {@link #run}이 고칠 수 있는 집합이 어긋나면, 화면에 안 뜬 행이
 * 원장에 남은 채로 장기창 지표를 억제한다(ADR-0055) — 그리고 그것은 아무 에러도 내지 않는다.
 *
 * <h2>MUST NOT: 여기서 후보를 고르지 않는다</h2>
 * <p>{@link CorporateActionReader}가 계산한 증거를 <b>전부</b> 찍고 하나를 제안하지 않는다.
 * 근거는 ADR-0050의 루닛 2026-05-04 실측이다 — 권리락 이후 창에 주식수 점프가 셋이고, 제안값
 * 하나를 내면 그 모호함이 사라진다. 고르는 규칙이 틀린 날 사람은 그것을 알 방법이 없다.
 */
@Component
public class CorporateActionJudgeJob {

    /** 판정(쓰기). {@code batch_execution.job_name}으로도 그대로 쓴다. */
    public static final String JOB_NAME = "corporate-action-judge";

    /** 조회(읽기). 실행 행을 만들지 않으므로 {@code batch_execution}에는 안 나타난다. */
    public static final String REVIEW_JOB_NAME = "corporate-action-review";

    /**
     * 조회에 필터를 하나도 안 줬을 때의 상태.
     *
     * <p>이 잡이 답하는 질문이 "지금 사람이 판정할 것이 무엇인가"이기 때문이다. MUST: 무엇으로
     * 좁혔는지 함께 찍는다 — 기본값이 조용히 좁히면 {@code UNSUPPORTED}로 남겨 둔 행이 없는
     * 것으로 읽힌다.
     */
    private static final CorporateActionStatus DEFAULT_REVIEW_STATUS = CorporateActionStatus.OBSERVED;

    private static final Logger log = LoggerFactory.getLogger(CorporateActionJudgeJob.class);

    private final CorporateActionLedger ledger;
    private final CorporateActionReader reader;
    private final BatchExecutions batchExecutions;

    public CorporateActionJudgeJob(CorporateActionLedger ledger, CorporateActionReader reader,
                                   BatchExecutions batchExecutions) {
        this.ledger = ledger;
        this.reader = reader;
        this.batchExecutions = batchExecutions;
    }

    /**
     * 원장을 훑어 찍는다. <b>한 글자도 고치지 않는다.</b>
     *
     * <p>{@code batch_execution} 행을 만들지 않는 이유는 이것이 상태를 바꾸지 않기 때문이다.
     * 그 표는 "무엇이 언제 <b>돌았는가</b>"를 담고, 조회를 거기 남기면 판정 이력이 조회 이력에
     * 묻힌다.
     */
    public BatchStatus review(CorporateActionStatus status, CorporateAction.Type type,
                              String ticker, Long id, Integer limit) {
        for (CorporateActionReader.StatusCount count : reader.summary()) {
            log.info("원장 집계 — {} {}행 (그중 종류 미상 {}행)",
                    count.status(), count.total(), count.unknownType());
        }

        if (id != null) {
            reader.findById(id).ifPresentOrElse(
                    this::logDetail,
                    () -> log.warn("원장에 그 행이 없다: id={}", id));
            return BatchStatus.SUCCESS;
        }

        CorporateActionStatus effective = status != null ? status : DEFAULT_REVIEW_STATUS;
        log.info("조회 필터 — status={}{} type={} ticker={} limit={}",
                effective, status != null ? "" : "(기본값)", type, ticker,
                limit != null ? limit : CorporateActionReader.DEFAULT_LIMIT);

        var rows = reader.list(effective, type, ticker, null, null, limit);
        if (rows.isEmpty()) {
            log.info("그 조건에 맞는 행이 없다. 위 집계가 원장 전체다.");
            return BatchStatus.SUCCESS;
        }
        for (CorporateActionReader.Row row : rows) {
            log.info("id={} {} {} 효력일={} 신주상장={} type={} ratio={} status={} 관측배수={} 당일주식수배수={} 소스낱말={}",
                    row.id(), row.ticker(), row.stockName(), row.effectiveDate(), row.listingDate(),
                    row.type(), row.ratio(), row.status(), row.observedRatio(),
                    row.sameDayShareRatio(), row.sourceForm());
        }
        // 리더가 limit을 스스로 잘라 내므로 그 경계를 사람이 볼 수 있어야 한다. 조용히 잘리면
        // 대기열이 비어 가는 것처럼 보인다.
        log.info("{}행을 찍었다. 상한에 닿았으면 --ca-limit으로 넓힌다(최대 {}).",
                rows.size(), CorporateActionReader.MAX_LIMIT);
        return BatchStatus.SUCCESS;
    }

    /**
     * 사람의 판정을 원장에 적는다.
     *
     * <p>MUST: 판정이 <b>성공한 뒤에</b> 실행 행을 연다. 반대로 두면 사람의 argv 오타 하나가
     * {@code failUnexpectedly} → {@code alertPort.send(ERROR, …)}를 타 <b>운영자 알림 채널로
     * 나간다.</b> 그리고 {@link CorporateActionLedger#judge}가 {@code @Transactional}이라 실패하면
     * 아무것도 안 쓰이므로, 실패에 남길 실행 행 자체가 없다.
     *
     * <p>MUST NOT: 예외를 삼키지 않는다. 여기서 잡는 것은 <b>어느 항목이 걸렸는지 찍기 위한 것</b>
     * 뿐이고 그대로 다시 던진다 — 러너가 예외로 죽어야 셸이 실패를 안다.
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           long id, CorporateActionLedger.Judgement judgement) {

        // MUST: 쓰기 전에 찍는다. 사람이 친 줄과 실제로 파싱된 것은 다를 수 있고(따옴표가
        // 값을 쪼개거나 뒤 플래그를 품는다, ADR-0084), 이 한 줄이 그 차이를 드러내는 유일한
        // 자리다. 러너의 가드가 아는 형태는 막지만 **가드가 아는 형태만** 막는다.
        log.info("판정할 내용 — id={} status={} type={} ratio={} 효력일={} 신주상장={} note={}",
                id, judgement.status(), judgement.type(), judgement.ratio(),
                judgement.effectiveDate(), judgement.listingDate(), judgement.note());

        try {
            ledger.judge(id, judgement);
        } catch (CorporateActionLedger.InvalidJudgementException e) {
            log.error("판정이 원장의 불변식을 깼다. id={} 항목={} — {}", id, e.field(), e.getMessage());
            throw e;
        }

        // 원장 한 행의 판정이다 — 정기 실행이 없고 전량이라는 범위도 없다.
        BatchExecutionContext execution = batchExecutions.start(JOB_NAME, asOf, triggeredBy, 1,
                BatchExecutionRecorder.Scope.NARROW);
        // 분모를 신고한다. 안 하면 화면의 진행률이 `—`로 뜬다 — NPE가 나지는 않는다
        // (`byPhase`가 전 단계를 미리 채운다).
        execution.plan(CollectionPhase.ADJUSTMENT, 1);
        execution.stage("원장 판정");
        // 처리 단위가 종목이 아니라 원장 행이라 process()를 쓸 수 없다 —
        // `batch_execution_failure.stock_id`가 NOT NULL + FK다(CollectionPhase.MARKET_INDEX의
        // javadoc이 지정한 자리). 여기서 안 세면 실행 행이 「대상 1 / 성공 0」으로 남는다.
        execution.countExternally(CollectionPhase.ADJUSTMENT, 1, 0, 1);

        CorporateActionReader.Detail detail = reader.findById(id).orElseThrow(
                () -> new IllegalStateException("방금 판정한 행을 다시 읽지 못했다: id=" + id));
        logDetail(detail);

        // MUST: 계수가 안 바뀌었다는 것을 말한다. APPROVED는 "적용 대상"이고 APPLIED가
        // "반영됨"이며, 그 사이를 잇는 것은 재구축뿐이다(ADR-0050 §결과).
        log.info("판정은 계수를 바꾸지 않는다. 다음 평일 14:00 사슬이 재구축과 화해를 둘 다 한다 — "
                        + "그 전에 반영하려면 평일 13:30~15:40 밖에서 --job=adjustment-rebuild --tickers={} 뒤에 "
                        + "--job=indicator-reconcile --tickers={} (재구축이 그 종목의 지표를 보존 창 전체에서 지운다)",
                detail.row().ticker(), detail.row().ticker());

        return execution.finish("id=%d status=%s type=%s ratio=%s"
                .formatted(id, detail.row().status(), detail.row().type(), detail.row().ratio()));
    }

    /**
     * 원장 한 행과 증거를 찍는다.
     *
     * <p>MUST: {@code shareJumps}를 <b>전부</b> 찍는다. 하나로 좁히는 순간 이 잡이 ADR-0050이
     * 금지한 "후보를 골라 제안하는 것"이 된다.
     */
    private void logDetail(CorporateActionReader.Detail detail) {
        CorporateActionReader.Row row = detail.row();
        log.info("id={} {} {} — 효력일={} 신주상장={} 소스일자={} type={} ratio={} status={} 탐지={} 낱말={}",
                row.id(), row.ticker(), row.stockName(), row.effectiveDate(), row.listingDate(),
                row.sourceDate(), row.type(), row.ratio(), row.status(),
                row.detectionSource(), row.sourceForm());
        log.info("  효력일 관측 — 전일종가={} 종가={} 관측배수={} 당일주식수배수={} 스냅={} 가격과맞는가={}",
                row.prevClose(), row.close(), row.observedRatio(),
                row.sameDayShareRatio(), row.sameDaySnapped(), row.sameDayMatchesPrice());
        if (row.note() != null) {
            log.info("  기존 note — {}", row.note());
        }
        if (detail.shareJumps().isEmpty()) {
            log.info("  효력일 이후 창에 상장주식수 점프가 없다 — 분할·병합이면 당일 배수가 정본이고,"
                    + " 무상증자면 신주상장을 못 찾은 것이다.");
            return;
        }
        for (CorporateActionReader.ShareJump jump : detail.shareJumps()) {
            log.info("  점프 {} {} → {} 배수={} 관측배수×배수={} 가격과맞는가={}",
                    jump.date(), jump.sharesBefore(), jump.sharesAfter(),
                    jump.ratio(), jump.relative(), jump.matchesPrice());
        }
        log.info("  위에서 하나를 고르는 것은 사람이다. 가격과 맞는 점프가 둘 이상이면"
                + " 전환권행사·유상증자가 섞인 것이다(ADR-0050).");
    }
}
