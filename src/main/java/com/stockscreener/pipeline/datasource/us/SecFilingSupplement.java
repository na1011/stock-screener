package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * companyfacts가 빠뜨린 정기 공시를 그 공시의 XBRL 인스턴스로 보충한다(ADR-0173).
 *
 * <p><b>뿌리(2026-10-06 실측)</b>: companyfacts가 정기 공시 일부의 us-gaap 팩트를 통째로 안 싣는다 — 83 CIK 표본에서
 * 창 안 87건(2026 인라인 10-Q 78 · 정정 6 · 원본 3). 원장(SEC)은 그 공시의 인스턴스로 같은 값을 준다(AAPL 283/283).
 * 그러면 그 분기 라벨이 통째로 비거나 다음 공시의 비교열이 그 판을 대신한다 — 가짜 한계다.
 *
 * <p><b>보충 대상</b>: 받는 창(라벨 창 시작 한 해 앞부터) 안의 {@link #SUPPLEMENT_FORMS} 공시 중 원장이 XBRL이 있다고
 * 말하고({@code isXBRL}) companyfacts에 그 접수번호의 us-gaap 팩트가 하나도 없는 것. 창을 한 해 앞까지 넓히는 이유는
 * 회계연도를 여기서 다시 매기지 않기 위해서다 — 라벨 회계연도의 정본은 {@code SecEdgarFacts.build}이고 거기서 자른다.
 *
 * <p><b>실패 계약</b> — 보충 하나가 실패해도 종목을 세우지 않는다(main보다 후퇴하지 않는다).
 * <ul>
 *   <li>출력 = companyfacts + 쓸 수 있는 저장분(옛 판 포함).</li>
 *   <li>실패한 공시의 제출일을 {@link Outcome#unconfirmedVintages()}로 낸다 — 저장 계층이 그 날짜의 판을 회수에서 지킨다.
 *       그 공시를 못 실은 출력으로 「여기 없는 공시일」을 판단하면 멀쩡한 판이 지워진다.</li>
 *   <li>실패를 저장 표에 {@code FAILED}로 남긴다. 나중에 성공하면 {@link Outcome#recoveredVintages()}로 내고, 저장 계층이
 *       그 공시일의 판을 이른 판이어도 넣는다 — 실패가 먼저 왔든 성공이 먼저 왔든 같은 상태에 닿게 한다.</li>
 *   <li>공시 목록을 못 받으면 대상을 모른다 — 저장분만 합치고 {@link Outcome#vintagesUncertain()}으로 그 종목의 회수를
 *       통째로 멈춘다.</li>
 * </ul>
 *
 * <p><b>결정적 실패(MALFORMED · NOT_FOUND)는 읽기 규칙의 판이 같으면 다시 받지 않는다</b> — 같은 파일 · 같은 규칙이면
 * 같은 결과다. 일시 실패는 다음 실행이 다시 받는다.
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class SecFilingSupplement implements FilingSupplementer {

    private static final Logger log = LoggerFactory.getLogger(SecFilingSupplement.class);

    /**
     * 보충하는 폼. <b>{@code SecEdgarFacts}의 정기보고서 목록과 따로 둔다</b> — 그쪽에 8-K를 더하는 변경(8-K 수용)이 이
     * 목록을 따라 넓어지면 보충 대상이 약 81배가 된다(83 CIK 실측: 8-K XBRL 8,315 중 companyfacts us-gaap 0이 7,012).
     * 여기 없는 폼을 그쪽이 읽지 않으면 보충해도 버려지므로, 이 목록은 그쪽의 부분집합이어야 한다(시험이 단언한다).
     */
    static final Set<String> SUPPLEMENT_FORMS = Set.of(
            "10-K", "10-K/A", "10-Q", "10-Q/A", "20-F", "20-F/A", "40-F", "40-F/A", "6-K", "6-K/A");

    /**
     * 보충의 결과.
     *
     * @param merged             트리에 더한 팩트 노드 수
     * @param fromStore          저장분으로 합친 공시 수
     * @param fetched            이번에 받아 합친 공시 수
     * @param failed             이번 실행에서 보충에 실패한 접수번호
     * @param unconfirmedVintages 실패한 공시의 제출일 — 회수에서 지킨다
     * @param recoveredVintages  실패였다가 이번에 성공한 공시의 제출일 — 이른 판이어도 넣는다
     * @param vintagesUncertain  공시 목록을 못 받아 대상을 모른다 — 그 종목의 회수를 멈춘다
     */
    public record Outcome(int merged, int fromStore, int fetched, List<String> failed,
                          Set<LocalDate> unconfirmedVintages, Set<LocalDate> recoveredVintages,
                          boolean vintagesUncertain) {

        static Outcome none() {
            return new Outcome(0, 0, 0, List.of(), Set.of(), Set.of(), false);
        }
    }

    private final SecFilingClient client;
    private final SupplementLedger store;

    public SecFilingSupplement(SecFilingClient client, SupplementLedger store) {
        this.client = client;
        this.store = store;
    }

    /**
     * companyfacts의 {@code facts} 트리에 보충한 팩트를 더한다(제자리 변경).
     *
     * <p>던지지 않는다 — 한도 응답(403·429)까지 공시 하나의 실패로 남긴다. 종목을 세우면 companyfacts 몫까지 못 써서
     * 보충이 없는 코드보다 후퇴한다. 종목이 실패하는 것은 저장 표 쓰기 자체가 실패할 때뿐이다(트랜잭션이 깨진다).
     */
    @Override
    public Outcome supplement(long cik, ObjectNode facts, int fromFiscalYear, String context) {
        LocalDate since = windowStart(fromFiscalYear);
        List<SecFilingClient.Filing> filings;
        try {
            filings = client.filings(cik, since);
        } catch (DataSourceException e) {
            return fromStoreOnly(cik, facts, since, context, e);
        }

        List<SecFilingClient.Filing> targets = targets(filings, usGaapAccessions(facts));
        if (targets.isEmpty()) {
            return Outcome.none();
        }

        Map<String, SecFilingSupplementStore.Row> stored =
                store.find(targets.stream().map(SecFilingClient.Filing::accession).toList());
        int merged = 0;
        int fromStore = 0;
        int fetched = 0;
        List<String> failed = new ArrayList<>();
        Set<LocalDate> unconfirmed = new LinkedHashSet<>();
        Set<LocalDate> recovered = new LinkedHashSet<>();
        for (SecFilingClient.Filing filing : targets) {
            String filingContext = "%s %s %s %s".formatted(context, filing.accession(), filing.form(), filing.filed());
            SecFilingSupplementStore.Row row = stored.get(filing.accession());
            boolean current = row != null && row.readerVersion() == XbrlInstanceReader.READER_VERSION;
            if (current && row.ok()) {
                Integer added = mergeStored(facts, filing, row, filingContext);
                if (added != null) {
                    merged += added;
                    fromStore++;
                    continue;
                }
            } else if (current && isDeterministic(row.failureKind())) {
                // 같은 파일 · 같은 규칙이면 같은 실패다 — 다시 받지 않고 그 날짜만 지킨다.
                unconfirmed.add(filing.filed());
                failed.add(filing.accession());
                continue;
            }
            try {
                Fetched result = fetch(cik, filing, filingContext);
                merged += XbrlInstanceReader.mergeInto(facts, result.chosen(), filing.accession(), filing.form(),
                        filing.filed(), result.instance().fiscalYearFocus(), result.instance().fiscalPeriodFocus());
                store.saveOk(filing, cik, result.fileName(), result.fileSize(), result.instance());
                fetched++;
                if (row != null && !row.ok()) {
                    recovered.add(filing.filed());
                }
            } catch (DataSourceException e) {
                if (row != null && row.ok()) {
                    // 판을 올려 다시 읽다 실패했다 — 옛 판의 저장분은 여전히 그 공시의 값이다. 그것을 쓰고 행은 그대로 둔다.
                    Integer added = mergeStored(facts, filing, row, filingContext);
                    if (added != null) {
                        merged += added;
                        fromStore++;
                        log.warn("보충 공시를 새 읽기 규칙으로 다시 읽지 못해 옛 판의 저장분을 쓴다. {} — {}",
                                filingContext, e.getMessage());
                        continue;
                    }
                }
                log.warn("보충 공시를 받지 못했다 — 그 공시일의 판을 회수에서 지킨다. {} 종류={} — {}",
                        filingContext, e.kind(), e.getMessage());
                store.saveFailed(filing, cik, e.kind().name(), e.getMessage());
                unconfirmed.add(filing.filed());
                failed.add(filing.accession());
            }
        }
        return new Outcome(merged, fromStore, fetched, List.copyOf(failed), Set.copyOf(unconfirmed),
                Set.copyOf(recovered), false);
    }

    private record Fetched(String fileName, long fileSize, XbrlInstanceReader.Instance instance,
                           List<XbrlInstanceReader.Fact> chosen) {
    }

    private Fetched fetch(long cik, SecFilingClient.Filing filing, String context) throws DataSourceException {
        String name = client.instanceName(cik, filing.accession());
        byte[] bytes = client.instance(cik, filing.accession(), name);
        XbrlInstanceReader.Instance instance =
                XbrlInstanceReader.read(new ByteArrayInputStream(bytes), cik, context + " " + name);
        XbrlInstanceReader.requireIdentity(instance, filing.form(), context + " " + name);
        // 고르기를 저장 전에 한 번 돌려 본다 — 같은 정밀도 충돌은 그 공시의 실패로 남긴다(저장분이 늘 고를 수 있게).
        List<XbrlInstanceReader.Fact> chosen = XbrlInstanceReader.choose(instance.facts(), context + " " + name);
        return new Fetched(name, bytes.length, instance, chosen);
    }

    /** 저장분을 합친다. 고르기가 실패하면(고르기 규칙이 바뀐 뒤) {@code null} — 호출부가 다시 받는다. */
    private static Integer mergeStored(ObjectNode facts, SecFilingClient.Filing filing,
                                       SecFilingSupplementStore.Row row, String context) {
        try {
            List<XbrlInstanceReader.Fact> chosen = XbrlInstanceReader.choose(row.facts(), context);
            return XbrlInstanceReader.mergeInto(facts, chosen, filing.accession(), filing.form(), filing.filed(),
                    row.fiscalYearFocus(), row.fiscalPeriodFocus());
        } catch (DataSourceException e) {
            log.warn("저장분을 고르지 못했다 — 다시 받는다. {} — {}", context, e.getMessage());
            return null;
        }
    }

    /** 공시 목록을 못 받았다 — 저장분만 합치고 그 종목의 회수를 멈춘다. */
    private Outcome fromStoreOnly(long cik, ObjectNode facts, LocalDate since, String context,
                                  DataSourceException cause) {
        log.warn("공시 목록을 받지 못해 저장분만 합치고 이 종목의 판 회수를 멈춘다. {} 종류={} — {}",
                context, cause.kind(), cause.getMessage());
        Set<String> covered = usGaapAccessions(facts);
        int merged = 0;
        int fromStore = 0;
        for (SecFilingSupplementStore.Row row : store.okRowsOf(cik).values()) {
            if (row.filed().isBefore(since) || covered.contains(row.accession())) {
                continue;
            }
            SecFilingClient.Filing filing = new SecFilingClient.Filing(row.accession(), row.form(), row.filed(),
                    null, true);
            Integer added = mergeStored(facts, filing, row, context + " " + row.accession());
            if (added != null) {
                merged += added;
                fromStore++;
            }
        }
        return new Outcome(merged, fromStore, 0, List.of(), Set.of(), Set.of(), true);
    }

    /** 받는 창의 시작 — 라벨 창(회계연도 ≥ from)보다 한 해 앞이다. 자르는 것은 {@code SecEdgarFacts.build}다. */
    static LocalDate windowStart(int fromFiscalYear) {
        return LocalDate.of(fromFiscalYear - 1, 1, 1);
    }

    /** 보충 대상 — 보충 폼 · XBRL 있음 · companyfacts가 그 접수번호의 us-gaap를 하나도 안 싣는다. 수집 도구도 이것을 쓴다. */
    static List<SecFilingClient.Filing> targets(List<SecFilingClient.Filing> filings, Set<String> covered) {
        List<SecFilingClient.Filing> out = new ArrayList<>();
        for (SecFilingClient.Filing filing : filings) {
            if (filing.xbrl() && SUPPLEMENT_FORMS.contains(filing.form()) && !covered.contains(filing.accession())) {
                out.add(filing);
            }
        }
        return out;
    }

    static boolean isDeterministic(String failureKind) {
        return DataSourceException.Kind.MALFORMED.name().equals(failureKind)
                || DataSourceException.Kind.NOT_FOUND.name().equals(failureKind);
    }

    /** companyfacts가 us-gaap 팩트를 하나라도 싣는 접수번호. */
    static Set<String> usGaapAccessions(JsonNode facts) {
        Set<String> out = new HashSet<>();
        JsonNode usGaap = facts.path("us-gaap");
        for (String concept : usGaap.propertyNames()) {
            JsonNode units = usGaap.path(concept).path("units");
            for (String unit : units.propertyNames()) {
                for (JsonNode node : units.path(unit)) {
                    String accn = node.path("accn").asString("");
                    if (!accn.isEmpty()) {
                        out.add(accn);
                    }
                }
            }
        }
        return out;
    }
}
