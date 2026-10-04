package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.GovernanceFactDeriver;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 저장된 원문에서 {@code governance_fact}를 다시 짓는다. <b>외부 호출이 0이다.</b>
 *
 * <p>이 잡이 따로 있는 것이 P18의 저장 구조를 가른 이유다 — 유도 규칙이 틀린 것을 알았을 때
 * 수천 콜을 다시 태우지 않고 이 잡 한 번으로 끝난다. 그래서 관리자 화면에 올려도 무해하다.
 */
@Component
public class GovernanceDeriveJob {

    public static final String JOB_NAME = "governance-derive";

    private static final Logger log = LoggerFactory.getLogger(GovernanceDeriveJob.class);

    private final GovernanceFactDeriver deriver;
    private final StockRegistrar stockRegistrar;
    private final BatchExecutions batchExecutions;

    public GovernanceDeriveJob(GovernanceFactDeriver deriver, StockRegistrar stockRegistrar,
                               BatchExecutions batchExecutions) {
        this.deriver = deriver;
        this.stockRegistrar = stockRegistrar;
        this.batchExecutions = batchExecutions;
    }

    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           List<String> tickers) {
        List<SecurityRef> stocks = stockRegistrar.toSecurityRefs(resolveTargets(tickers));
        if (stocks.isEmpty()) {
            return batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0).finish();
        }
        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, stocks.size());
        execution.plan(CollectionPhase.GOVERNANCE, stocks.size());
        execution.stage("원문에서 거버넌스 사실 유도");

        try {
            for (SecurityRef stock : stocks) {
                execution.process(stock, CollectionPhase.GOVERNANCE,
                        security -> deriver.derive(security.stockId()));
            }
        } catch (com.stockscreener.pipeline.datasource.DataSourceException e) {
            // 외부 호출이 없어 원리적으로 안 나지만, process()의 계약이 checked라 잡아야 한다.
            // 삼키지 않고 다른 잡과 같은 자리로 보낸다.
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }

        // enum 상수의 이름을 바꿨을 때 그 사고가 드러나는 유일한 자리다. 리더가 모르는 키를
        // 던지지 않고 건너뛰므로 컴파일도 테스트도 초록인 채 그 지표만 전 종목 판정불가가 된다.
        Map<String, Long> unknown = deriver.unknownMetricKeys();
        if (!unknown.isEmpty()) {
            log.error("코드가 모르는 metric_key가 남아 있다: {} — GovernanceMetric에서 상수를 "
                    + "빼거나 이름을 바꾼 것이다. 그 지표는 전 종목 판정불가이고 화면은 등록된 "
                    + "결손 사유를 그대로 띄워 거짓 이유를 말한다.", unknown);
            execution.alertOperator(com.stockscreener.notification.AlertSeverity.ERROR,
                    "거버넌스 유도 — 모르는 지표 키", "남은 키: " + unknown);
        }
        return execution.finish();
    }

    private List<Long> resolveTargets(List<String> tickers) {
        if (tickers != null && !tickers.isEmpty()) {
            List<Long> matched = stockRegistrar.activeUniverseMemberIdsByTicker(tickers);
            if (matched.isEmpty()) {
                throw new IllegalArgumentException(
                        "준 티커 중 편입 중인 활성 종목이 하나도 없다: " + tickers);
            }
            return matched;
        }
        // 기본은 보존 창 편입이다 — 지표 화해(①)와 같은 모집단이어야 한다. 지금 편입만 유도하면
        // 과거 구성원은 화해가 지표를 다시 쓰는데 그 입력인 거버넌스 사실은 옛 규칙·옛 원문의
        // 유도에 멈춘다. 2026-10-04 사본에서 창 전원으로 다시 유도한 차이는 과거 편입 0칸이었다 —
        // 지금 어긋난 것은 없고, 유도 규칙이나 원문이 바뀌는 다음 날부터 갈리는 것을 막는다.
        return stockRegistrar.windowMemberIds();
    }
}
