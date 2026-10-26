package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts;
import com.stockscreener.pipeline.datasource.us.shareclass.CommonEquity;
import com.stockscreener.pipeline.datasource.us.shareclass.CommonEquityRows;
import com.stockscreener.pipeline.datasource.us.shareclass.CommonIncome;
import com.stockscreener.pipeline.datasource.us.shareclass.CommonIncomeRows;
import com.stockscreener.pipeline.datasource.us.shareclass.IssuerFacts;
import com.stockscreener.pipeline.datasource.us.shareclass.IssuerTimeline;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 주식수 클래스 축 판정을 저장된 재료만으로 다시 만든다 — 외부 호출 0(탐색 {@code SHARES-CLASS-PLAN-V5-1015.md} §2 · ADR 예정).
 *
 * <p>재료는 받기({@code ShareClassCollector})가 둔 둘 — 줄마다 가지친 companyfacts와 앵커 후보 공시의 인스턴스 팩트 — 와 분할
 * 원장이다. 그래서 분할 원장이 늦게 들어오거나 판정 규칙이 바뀌어도 다시 받지 않고 다시 판정한다. 화해 입력 유도
 * ({@code IndicatorReconciler.deriveInputs})가 <b>주식수 기저 판정보다 먼저</b> 부른다 — 기저 판정이 판독기가 읽는 계열(N_L)로
 * 배수를 재기 때문이다(§0-13).
 *
 * <p><b>MUST: 관문 설정이 켜졌을 때만 부른다</b> — 부르는 자리가 막는다.
 *
 * <p>실패 격리는 줄 하나다(그 줄의 판정은 지난 것 그대로 남고, 그 뒤의 새 정기 공시는 판독기가 「미판정 = 숨김」으로 읽는다 —
 * 옛 값으로 안 돌아간다). 실패한 줄은 이름으로 남긴다.
 */
@Component
public class ShareClassDeriver {

    private static final Logger log = LoggerFactory.getLogger(ShareClassDeriver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDate HISTORY_FROM = LocalDate.of(2015, 1, 1);

    /** @param written 새로 쓰거나 바뀐 판정 행 · {@code removed} 이번 판정에 없어 지운 행 */
    public record Tally(int lines, int verdicts, int hidden, int written, int removed, List<String> failedLines) {
    }

    private final ShareClassStore store;
    private final TransactionTemplate transaction;

    public ShareClassDeriver(ShareClassStore store, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public Tally derive(String marketCode) {
        IssuerTimeline timeline = IssuerTimeline.standard();
        Map<Long, Map<String, ClassFacts>> factsByCik = new HashMap<>();
        int verdicts = 0;
        int hidden = 0;
        int written = 0;
        int removed = 0;
        List<String> failed = new ArrayList<>();
        List<ShareClassStore.Input> inputs = store.inputs(marketCode);
        for (ShareClassStore.Input input : inputs) {
            try {
                int[] counts = transaction.execute(status -> deriveLine(input, timeline, factsByCik));
                verdicts += counts[0];
                hidden += counts[1];
                written += counts[2];
                removed += counts[3];
            } catch (RuntimeException e) {
                // 줄 하나의 실패가 시장 전체의 판정을 세우지 않는다(종목 단위 격리) — 이름과 원인을 남긴다.
                failed.add(input.ticker());
                log.error("클래스 축 판정을 못 만들었다 — 이 줄은 지난 판정이 남고 새 정기 공시는 미판정(숨김)으로 읽힌다. ticker={} cik={}",
                        input.ticker(), input.cik(), e);
            }
        }
        log.info("클래스 축 판정. 시장={} 줄={} 판정={} 숨김={} 쓴행={} 지운행={} 실패={}{}",
                marketCode, inputs.size(), verdicts, hidden, written, removed, failed.size(), failed);
        return new Tally(inputs.size(), verdicts, hidden, written, removed, failed);
    }

    private int[] deriveLine(ShareClassStore.Input input, IssuerTimeline timeline, Map<Long, Map<String, ClassFacts>> factsByCik) {
        IssuerFacts companyFacts = IssuerFacts.fromCompanyFacts(MAPPER.readTree(input.companyFacts()));
        IssuerFacts cf = companyFacts.withFilings(store.supplementedFilings(input.cik()));
        Map<String, ClassFacts> facts = factsByCik.computeIfAbsent(input.cik(), store::okFacts);
        List<IssuerTimeline.Verdict> judged = timeline.judge(input.symbol(), cf, cf.anchorCandidates(), facts::get,
                store.splits(input.stockId()), HISTORY_FROM, IssuerFacts.INSTANCE_FROM);
        List<ShareClassStore.VerdictRow> rows = new ArrayList<>(judged.size());
        int hidden = 0;
        for (IssuerTimeline.Verdict v : judged) {
            rows.add(row(v, cf, facts.get(v.accession())));
            if (v.status() == 'X') {
                hidden++;
            }
        }
        int[] replaced = store.replaceVerdicts(input.stockId(), input.cik(), rows);
        commonIncome(input, cf, companyFacts.filings().keySet(), timeline, facts, judged);
        return new int[]{rows.size(), hidden, replaced[0], replaced[1]};
    }

    /**
     * 재무 행마다 보통주 귀속 이익(주당 지표 분자 · 탐색 {@code PERSHARE-NUM-PLAN-1024.md} v2.1) — 판정과 같은 재료 · 같은 판정의 n으로
     * 공시 × 기간 값을 정하고 행의 판에 맞춰 붙인다. 판정과 한 트랜잭션이라 둘이 갈린 채 남지 않는다.
     */
    private void commonIncome(ShareClassStore.Input input, IssuerFacts cf, java.util.Set<String> companyFactsFilings, IssuerTimeline timeline,
                              Map<String, ClassFacts> facts, List<IssuerTimeline.Verdict> judged) {
        Map<IssuerFacts.Key, CommonIncome.Value> keys =
                timeline.commonIncomes(cf, cf.anchorCandidates(), facts::get, store.splits(input.stockId()), judged);
        Map<Long, CommonIncomeRows.Row> rows = store.financialRows(input.stockId());
        List<Long> ids = new ArrayList<>(rows.keySet());
        List<CommonIncomeRows.Resolved> resolved =
                CommonIncomeRows.resolve(new ArrayList<>(rows.values()), keys);
        Map<Long, CommonIncomeRows.Resolved> byId = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            byId.put(ids.get(i), resolved.get(i));
        }
        // 보통주 자본(탐색 PERSHARE-DEN-PLAN-1026.md v1.1) — 같은 저장 인스턴스 재료로 (공시, 날짜)마다 비보통 청구권을 정하고 같은 행에 붙인다.
        List<CommonEquityRows.Resolved> equity = CommonEquityRows.resolve(new ArrayList<>(rows.values()), CommonEquity.judge(facts),
                cf.filings(), companyFactsFilings, cf.preferredSignal());
        Map<Long, CommonEquityRows.Resolved> equityById = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            equityById.put(ids.get(i), equity.get(i));
        }
        store.replaceCommonIncome(byId, equityById);
    }

    static ShareClassStore.VerdictRow row(IssuerTimeline.Verdict v, IssuerFacts cf, ClassFacts facts) {
        String form = cf.filings().get(v.accession()).form();
        boolean shown = v.status() == 'O';
        boolean currentPath = shown && (v.kind().equals("d") || "cur".equals(v.branch()));
        Double lineShares = null;
        if (shown && !currentPath) {
            lineShares = v.value();
        } else if (currentPath && v.kind().equals("d")) {
            lineShares = positive(cf.coverShares().get(v.accession()));
        } else if (currentPath && facts != null) {
            String axisless = facts.coverOf(ClassFactReader.SHARES_OUTSTANDING).get(ClassFacts.AXISLESS);
            lineShares = axisless == null ? null : positive(Double.parseDouble(axisless));
        }
        return new ShareClassStore.VerdictRow(v.accession(), form, v.filed(), v.kind().charAt(0), v.status(), v.branch(),
                currentPath, shown ? null : failure(v.category()), lineShares, v.denominator());
    }

    private static Double positive(Double v) {
        return v == null || v <= 0 ? null : v;
    }

    private static String failure(String category) {
        if (category == null) {
            return null;
        }
        return switch (category) {
            case "어긋남" -> "LEDGER";
            case "대용" -> "PROXY";
            case "안줌" -> "ABSENT";
            default -> throw new IllegalStateException("모르는 실패 출처다: " + category);
        };
    }
}
