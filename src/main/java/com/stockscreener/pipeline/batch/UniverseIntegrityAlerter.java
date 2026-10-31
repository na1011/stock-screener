package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.MembershipGap;
import com.stockscreener.pipeline.collector.StockRegistrar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 편입 계약이 깨진 종목을 <b>운영자에게 알린다.</b> 판정은
 * {@link StockRegistrar#membershipGaps()}가 하고 여기서는 <b>언제 다시 보낼지</b>만 정한다.
 *
 * <p>{@link JobFreshnessAlerter} 옆에 같은 형태로 둔다. <b>합치지 않는 이유는 억제 키의 단위가
 * 다르기 때문</b>이다 — 저쪽은 잡 이름당이고 여기는 <b>(축, 종목)당</b>이다. 합치면 한 종목의
 * 한 축을 알린 것이 다른 종목의 다른 축을 7일 동안 막는다.
 *
 * <h2>MUST: 같은 결손을 매일 보내지 않는다</h2>
 * <p>결손은 <b>고칠 때까지 계속 참</b>이라, 매 평일 보내면 며칠 안에 아무도 그 알림을 보지
 * 않게 된다. {@link JobFreshnessAlerter}와 <b>같은 교환</b>을 택한다 — 메모리에만 두고,
 * 재시작하면 다시 알린다. 억제 상태를 DB에 두면 <b>DB 장애를 알리려는 알림이 DB 때문에
 * 막힌다.</b>
 *
 * <p>{@code AlertSuppressor}를 쓰지 않는 이유는 그 창이 전역 10분이고 키가
 * {@code severity|dedupeKey}라 <b>7일 간격의 반복을 원리적으로 못 막기 때문</b>이다.
 *
 * <h2>MUST: 면제된 것을 버리지 않는다</h2>
 * <p>{@code KnownSourceGaps}가 뺀 축은 <b>사실이 없어진 것이 아니라 OK 문장으로 옮겨진 것</b>이다
 * (ADR-0044 §펜스의 형태). 알림이 발화할 때 이름으로 함께 적고, 발화하지 않는 날은 아래
 * {@code log.info}가 센다 — <b>그 줄이 면제 목록이 낡았는지를 드러내는 유일한 자리</b>다.
 */
@Component
public class UniverseIntegrityAlerter {

    /** 같은 (축, 종목)을 다시 알리기까지의 간격. */
    private static final Duration REPEAT_AFTER = Duration.ofDays(7);

    /** 한 축에서 본문에 이름으로 적을 종목 수. 넘는 것은 로그가 답한다. */
    private static final int STOCKS_PER_AXIS = 15;

    private static final Logger log = LoggerFactory.getLogger(UniverseIntegrityAlerter.class);

    private final Map<String, Instant> lastAlertedAt = new ConcurrentHashMap<>();

    private final StockRegistrar stockRegistrar;
    private final AlertPort alertPort;
    private final Clock clock;

    // MUST: @Autowired를 지우지 않는다. 생성자가 둘이면 Spring이 고르지 못해 컨텍스트가
    // 기동에 실패하는데, bootRun은 그때도 BUILD SUCCESSFUL과 종료코드 0을 낸다
    // (JobFreshnessAlerter가 같은 이유로 같은 모양이다).
    @Autowired
    public UniverseIntegrityAlerter(StockRegistrar stockRegistrar, AlertPort alertPort) {
        this(stockRegistrar, alertPort, Clock.systemUTC());
    }

    /** 테스트가 시간을 밀 수 있게 시계를 주입받는다. */
    public UniverseIntegrityAlerter(StockRegistrar stockRegistrar, AlertPort alertPort, Clock clock) {
        this.stockRegistrar = stockRegistrar;
        this.alertPort = alertPort;
        this.clock = clock;
    }

    /**
     * 지금 깨진 것을 재서 <b>필요하면</b> 한 건으로 알린다.
     *
     * <p>MUST NOT: {@code batch_execution}에 행을 만들지 않는다. 이것은 읽기이고, 실행 이력에
     * 섞이면 <b>「잡이 돌았다」와 「잡이 안 돈 것을 확인했다」가 같은 목록에 뜬다.</b>
     *
     * @return 보냈으면 {@code true}. 테스트가 이것으로 억제를 검사한다.
     */
    public boolean evaluateAndAlert() {
        return alert(stockRegistrar.membershipGaps());
    }

    /**
     * 이미 재 둔 결손으로 알린다.
     *
     * <p>꼬리 사슬이 <b>채운 뒤 다시 잰 결과</b>를 그대로 넘기는 자리다 — 거기서
     * {@link #evaluateAndAlert()}를 부르면 <b>질의가 한 번 더 돈다.</b>
     */
    public boolean alert(List<MembershipGap> gaps) {
        Instant now = clock.instant();
        logExempt(gaps);

        Map<MembershipGap.Axis, List<MembershipGap>> byAxis = new LinkedHashMap<>();
        for (MembershipGap.Axis axis : MembershipGap.Axis.values()) {
            List<MembershipGap> hits = gaps.stream()
                    .filter(gap -> gap.missing().contains(axis))
                    .filter(gap -> shouldSend(axis, gap.ticker(), now))
                    .toList();
            if (!hits.isEmpty()) {
                byAxis.put(axis, hits);
            }
        }
        if (byAxis.isEmpty()) {
            log.debug("편입 결손이 없거나 이미 알렸다. 결손 종목={}", gaps.size());
            return false;
        }

        StringBuilder body = new StringBuilder(
                "아래 편입 종목의 원자료가 비어 있다. 그 종목은 지표가 판정불가가 되어 "
                        + "프리셋에서 조용히 빠지는데 배치는 전부 SUCCESS다.\n");
        byAxis.forEach((axis, hits) -> {
            body.append('\n').append(describe(axis)).append('\n');
            hits.stream().limit(STOCKS_PER_AXIS)
                    .forEach(gap -> body.append("  - ").append(gap.ticker()).append(' ')
                            .append(gap.name()).append(" (").append(gap.marketCode()).append(")\n"));
            if (hits.size() > STOCKS_PER_AXIS) {
                body.append("  나머지 %d종목은 로그가 답한다.\n"
                        .formatted(hits.size() - STOCKS_PER_AXIS));
            }
            body.append("  닫는 법: ").append(howToClose(axis, hits)).append('\n');
            hits.forEach(gap -> lastAlertedAt.put(key(axis, gap.ticker()), now));
        });
        appendExempt(body, gaps);

        alertPort.send(AlertEvent.toOperator(AlertSeverity.WARNING, "편입 원자료 결손",
                body.toString(), "universe-integrity"));
        return true;
    }

    /**
     * 면제된 것을 <b>알림이 안 뜨는 날에도</b> 남긴다.
     *
     * <p>MUST: 이 줄을 지우지 않는다. 면제는 「돌려도 안 닫힌다」가 근거인데 그 근거는 낡는다 —
     * 종목이 편출되거나 소스가 값을 주기 시작하면 목록을 줄여야 하고, <b>그것을 알아챌 자리가
     * 여기뿐</b>이다(알림은 면제된 것 때문에 발화하지 않는다).
     */
    private void logExempt(List<MembershipGap> gaps) {
        List<String> exempt = gaps.stream()
                .filter(gap -> !gap.exempt().isEmpty())
                .map(gap -> "%s%s".formatted(gap.ticker(), gap.exempt()))
                .toList();
        if (!exempt.isEmpty()) {
            log.info("편입 결손 중 면제 {}건 — 돌려도 안 닫히는 것들이다. 편출되거나 소스가 주기 "
                    + "시작하면 KnownSourceGaps와 ADR-0044 펜스를 함께 줄인다. {}",
                    exempt.size(), exempt);
        }
    }

    private void appendExempt(StringBuilder body, List<MembershipGap> gaps) {
        List<String> exempt = gaps.stream()
                .filter(gap -> !gap.exempt().isEmpty())
                .map(gap -> "%s%s".formatted(gap.ticker(), gap.exempt()))
                .toList();
        if (exempt.isEmpty()) {
            return;
        }
        body.append("\n면제 %d건(돌려도 안 닫힌다 — 소스가 안 주거나 통화 게이트에 걸린다): %s\n"
                .formatted(exempt.size(), String.join(" ", exempt)));
    }

    private static String describe(MembershipGap.Axis axis) {
        return switch (axis) {
            case PRICE_HISTORY -> "[시세 이력] 시세가 편입 이전으로 뻗어 있지 않다 — "
                    + "창이 긴 지표가 원리적으로 안 난다.";
            case PRICE_HISTORY_RETRY -> "[시세 이력 재시도] 초기 적재의 이력 받기가 실패한 채 남아 있다 — "
                    + "첫 봉은 편입일 앞(평일 창)이어도 이력이 짧을 수 있어 창이 긴 지표가 안 날 수 있다.";
            case INDUSTRY -> "[업종] 업종 코드가 비어 업종 상대 조건이 전부 판정불가다.";
            case FINANCIAL -> "[재무] 재무가 한 행도 없다 — 재무 지표 전부가 안 난다.";
            case CASH_FLOW -> "[영업활동현금흐름] 재무는 있는데 현금흐름이 없다.";
            case GOVERNANCE -> "[거버넌스] 거버넌스 원문이 한 행도 없다 — "
                    + "배당·지배구조 지표가 전부 안 난다.";
            case PRICE_COVERAGE -> "[시세 구멍] 첫 봉 이후 보존 창에 봉이 거의 없다 — "
                    + "봉은 있으나 지표가 사실상 안 난다.";
            case INDICATOR_DROP -> "[지표 급감] 어제 나던 지표가 오늘 안 난다.";
        };
    }

    /**
     * 그 축을 닫는 명령.
     *
     * <p><b>MUST NOT: 닫지 못하는 명령을 적지 않는다.</b> 적으면 받은 사람이 돌리고, 잡은
     * 성공으로 끝나는데 결손은 그대로다 — 이 기제가 없애려는 조용한 0건을 스스로 만든다.
     * {@code full-statement-refill}에 <b>US 소스가 등록돼 있지 않고</b>(ADR-0056이 DART 전용),
     * 시세 구멍·지표 급감은 <b>돌려서 닫는 종류가 아니다.</b>
     */
    private static String howToClose(MembershipGap.Axis axis, List<MembershipGap> hits) {
        String tickers = String.join(",", hits.stream().map(MembershipGap::ticker).toList());
        return switch (axis) {
            case PRICE_HISTORY -> bootstrap(
                    "--job=initial-load --skip-financial=true --price-years=7 --tickers=" + tickers);
            case PRICE_HISTORY_RETRY -> bootstrap(
                    "--job=initial-load --skip-financial=true --price-years=7 --tickers=" + tickers);
            case INDUSTRY -> bootstrap("--job=company-profile");
            case FINANCIAL -> bootstrap(
                    "--job=initial-load --skip-price=true --tickers=" + tickers);
            case CASH_FLOW -> cashFlowHint(hits);
            // 시장을 가르지 않는다 — GovernanceLoadJob이 시장마다 표를 좁히므로 국내·미국 모두
            // 이 한 쌍으로 닫힌다(CASH_FLOW와 다른 점이다). 유도를 함께 적는 이유는 적재만
            // 하면 governance_fact가 안 바뀌는데 둘 다 성공으로 끝나기 때문이다.
            case GOVERNANCE -> bootstrap("--job=governance-load --tickers=" + tickers)
                    + " 그다음 " + bootstrap("--job=governance-derive --tickers=" + tickers);
            // MUST NOT: 여기에 「소스가 안 준다」를 적지 않는다 — 2026-09-18에 거짓으로 판명됐다.
            // 그렇게 적혀 있던 동안 RH·VTEX는 우리 DB가 2024-12-31에서 멈춰 있었는데
            // Alpaca는 2025-09-15~2026-09-18 구간에 255봉을 주고 있었다. 소스의 한계 주장은
            // 외부 콜로만 갈리는데, 한 번 처방에 박히면 아무도 다시 안 묻는다.
            case PRICE_COVERAGE -> bootstrap(
                    "--job=initial-load --skip-financial=true --price-years=7 --tickers=" + tickers)
                    + " 다시 받아 그대로면 그때가 소스에 물을 자리다 — 거래정지·상장폐지로 소스에도 "
                    + "없는 구간이면 편입에서 빼야 하는지가 판단이고, 그때까지 그 자리는 "
                    + "정상 종목 하나를 밀어낸 채 비어 있다.";
            case INDICATOR_DROP -> "없다 — 원인을 먼저 가른다. 기업행위 재구축이 지운 것이면 "
                    + "backfill이 짝이고, 재무 정정이면 그 종목의 단일 날짜 백필이다.";
        };
    }

    private static String cashFlowHint(List<MembershipGap> hits) {
        // MUST: 술어를 여기에 따로 쓰지 않는다. 꼬리가 실제로 부를 대상과 이 문장이 갈리면
        // 「닫는 명령」을 적어 놓고 안 부르게 되는데, 그 어긋남은 양쪽 다 조용하다.
        List<String> kr = hits.stream().filter(MembershipGap::refillableStatements)
                .map(MembershipGap::ticker).toList();
        List<String> us = hits.stream().filter(gap -> !gap.refillableStatements())
                .map(MembershipGap::ticker).toList();
        List<String> parts = new ArrayList<>();
        if (!kr.isEmpty()) {
            parts.add(bootstrap("--job=full-statement-refill --tickers=" + String.join(",", kr)));
        }
        if (!us.isEmpty()) {
            parts.add("US(%s)는 닫는 명령이 없다 — full-statement-refill에 US 소스가 등록돼 있지 "
                    + "않다(ADR-0056은 DART 전용이다). 사람이 판단한다."
                    .formatted(String.join(" ", us)));
        }
        return String.join(" / ", parts);
    }

    private static String bootstrap(String args) {
        return "bootRun --args='--spring.profiles.active=bootstrap --server.port=0 " + args + "'";
    }

    private static String key(MembershipGap.Axis axis, String ticker) {
        return axis + ":" + ticker;
    }

    private boolean shouldSend(MembershipGap.Axis axis, String ticker, Instant now) {
        Instant last = lastAlertedAt.get(key(axis, ticker));
        return last == null || Duration.between(last, now).compareTo(REPEAT_AFTER) >= 0;
    }
}
