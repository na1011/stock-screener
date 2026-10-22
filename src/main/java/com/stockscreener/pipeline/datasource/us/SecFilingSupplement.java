package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p><b>보충한 공시의 판은 이른 판이어도 넣는다</b>({@link Outcome#supplementedVintages()}). 그 판은 원장이 처음부터 준
 * 판인데 companyfacts가 빠뜨렸을 뿐이다. 「이번 실행에서 실었는가」만 보고 이력을 안 보므로, 성공·실패의 순서와 배포
 * 시점의 저장 상태(늦은 판이 이미 있음)에 무관하게 같은 판 집합에 닿는다. 이미 있는 판이면 저장 계층이 채우기만 한다.
 *
 * <p><b>실패 계약</b> — 보충 하나가 실패해도 종목을 세우지 않는다(main보다 후퇴하지 않는다).
 * <ul>
 *   <li>출력 = companyfacts + 쓸 수 있는 저장분(옛 판 포함).</li>
 *   <li>실패한 공시의 제출일을 {@link Outcome#unconfirmedVintages()}로 낸다 — 저장 계층이 그 날짜의 판을 회수에서 지킨다.</li>
 *   <li>실패를 저장 표에 {@code FAILED}로 남긴다. 관문이 그것을 구성원마다 판정한다.</li>
 *   <li>공시 목록을 못 받으면 대상을 모른다 — 저장분만 합치고 {@link Outcome#vintagesUncertain()}으로 그 종목의 회수를
 *       통째로 멈춘다.</li>
 * </ul>
 *
 * <p><b>다시 받을 가치는 실패의 층이 정한다.</b> 받은 바이트를 해석하지 못한 것(인스턴스를 못 고름 · 읽기 · 신원 ·
 * 같은 정밀도 충돌 — {@link #UNREADABLE})은 같은 파일 · 같은 규칙이면 같은 결과라 읽기 규칙의 판이 같은 동안 다시 받지
 * 않는다. 받지 못한 것(404 · 5xx · 본문 이상 · 한도)은 다음 실행이 다시 받는다 — 일시 404 한 번을 영구로 만들지 않는다.
 * 폴더에 XBRL 산출물이 하나도 없으면 원장이 「XBRL 없음」을 확정한 것이라 실패가 아니다 — 팩트 0으로 끝낸다.
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class SecFilingSupplement implements FilingSupplementer {

    private static final Logger log = LoggerFactory.getLogger(SecFilingSupplement.class);

    /** 받은 바이트를 해석하지 못했다 — 읽기 규칙의 판이 같은 동안 다시 받지 않는 유일한 실패 종류다. */
    static final String UNREADABLE = "UNREADABLE";

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
     * @param merged               트리에 더한 팩트 노드 수
     * @param fromStore            저장분으로 합친 공시 수
     * @param fetched              이번에 받은 공시 수(XBRL 없음 포함)
     * @param staleReused          해석 규칙의 판이 바뀌었는데 다시 받지 못했거나(받기 실패 · 공시 목록 실패) 옛 판 저장분을 쓴
     *                             공시 수 — 미해결이다({@code stale_used_at})
     * @param failed               이번 실행에서 보충에 실패한 접수번호
     * @param unconfirmedVintages  실패한 공시의 제출일 — 회수에서 지킨다
     * @param supplementedVintages 보충으로 실은 공시의 제출일과, 실패였다가 companyfacts가 실은 공시의 제출일 — 이른 판이어도 넣는다
     * @param vintagesUncertain    공시 목록을 못 받아 대상을 모른다 — 그 종목의 회수를 멈춘다
     * @param unfetched            이번 실행에서 <b>받지 못한</b> 것의 종류 — 공시 받기 실패(옛 판 저장분으로 대신한 것 포함) 하나마다,
     *                             공시 목록 실패 하나. {@link #UNREADABLE}(해석 실패)은 안 든다 — 그것만 「다시 받지 않는 종료」다
     *                             (위 실패 계약). 비어 있지 않으면 그 실행의 미국 시장이 미완료다(ADR-0174 · 종목 층 술어를 옮기지
     *                             않는다 — 보충 층에서는 404·본문 이상도 「다음 실행이 다시 받는다」이므로)
     */
    public record Outcome(int merged, int fromStore, int fetched, int staleReused, List<String> failed,
                          Set<LocalDate> unconfirmedVintages, Set<LocalDate> supplementedVintages,
                          boolean vintagesUncertain, List<DataSourceException.Kind> unfetched) {

        public Outcome {
            unfetched = List.copyOf(unfetched);
        }

        static Outcome none() {
            return new Outcome(0, 0, 0, 0, List.of(), Set.of(), Set.of(), false, List.of());
        }
    }

    /** 받은 바이트를 해석하지 못했다. */
    static final class UnreadableFiling extends Exception {
        UnreadableFiling(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final SecFilingClient client;
    private final SupplementLedger store;

    /**
     * 운영 배선은 원장을 <b>구체형</b>으로 받는다 — 인터페이스로 받으면 다른 {@code SupplementLedger} 빈(람다 · 프록시 · 조건식
     * 람다 · 생성자 인자 람다 어느 꼴이든)이 판 범위 밖에서 저장을 바꿔도 판 고정이 못 본다(v11 반증 — 네 꼴이 구문 검사를
     * 지났다). 구체형이면 바꿀 길이 하위 클래스뿐이고 그것은 {@code ReaderVersionPinTest}가 막는다.
     */
    @Autowired
    public SecFilingSupplement(SecFilingClient client, SecFilingSupplementStore store) {
        this(client, (SupplementLedger) store);
    }

    /** 스윕 · 시험 전용 — 메모리 원장을 끼운다. */
    SecFilingSupplement(SecFilingClient client, SupplementLedger store) {
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
        return supplement(cik, facts, fromFiscalYear, context, false);
    }

    /**
     * @param usGate 미국 관문 설정 — 켜지면 companyfacts가 {@code ifrs-full}로 실은 공시도 「덮였다」로 센다(ADR-0182). 꺼지면
     *               그 공시가 보충 대상으로 남는다 — 어댑터가 IFRS를 안 읽으므로 값은 그대로다.
     */
    @Override
    public Outcome supplement(long cik, ObjectNode facts, int fromFiscalYear, String context, boolean usGate) {
        LocalDate since = windowStart(fromFiscalYear);
        List<SecFilingClient.Filing> filings;
        try {
            filings = client.filings(cik, since);
        } catch (DataSourceException e) {
            return fromStoreOnly(cik, facts, since, context, e, usGate);
        }

        Map<String, Set<LocalDate>> coveredFiled = coveredFilings(facts, usGate);
        Set<String> covered = coveredFiled.keySet();
        List<SecFilingClient.Filing> targets = targets(filings, covered);
        Set<String> targetAccessions = new HashSet<>();
        targets.forEach(t -> targetAccessions.add(t.accession()));
        Set<LocalDate> supplemented = new LinkedHashSet<>();
        settle(cik, coveredFiled, targetAccessions, supplemented, context);
        store.touch(cik, targetAccessions);
        if (targets.isEmpty()) {
            return new Outcome(0, 0, 0, 0, List.of(), Set.of(), Set.copyOf(supplemented), false, List.of());
        }

        Map<String, SecFilingSupplementStore.Row> stored = store.find(cik, targetAccessions);
        int merged = 0;
        int fromStore = 0;
        int fetched = 0;
        int staleReused = 0;
        List<String> failed = new ArrayList<>();
        List<DataSourceException.Kind> unfetched = new ArrayList<>();
        Set<LocalDate> unconfirmed = new LinkedHashSet<>();
        for (SecFilingClient.Filing filing : targets) {
            String filingContext = "%s %s %s %s".formatted(context, filing.accession(), filing.form(), filing.filed());
            SecFilingSupplementStore.Row row = stored.get(filing.accession());
            boolean current = row != null && row.readerVersion() == XbrlInstanceReader.READER_VERSION;
            if (current && row.ok()) {
                Integer added = mergeStored(facts, filing, row, filingContext);
                if (added != null) {
                    merged += added;
                    fromStore++;
                    openIfAdded(supplemented, filing, added);
                    continue;
                }
            } else if (current && UNREADABLE.equals(row.failureKind())) {
                // 같은 파일 · 같은 규칙이면 같은 해석 실패다 — 다시 받지 않고 그 날짜만 지킨다.
                unconfirmed.add(filing.filed());
                failed.add(filing.accession());
                continue;
            }
            try {
                Fetched result = fetch(cik, filing, filingContext);
                int added = XbrlInstanceReader.mergeInto(facts, result.chosen(), filing.accession(), filing.form(),
                        filing.filed(), result.instance().fiscalYearFocus(), result.instance().fiscalPeriodFocus());
                merged += added;
                if (result.fileName() == null) {
                    store.saveNoXbrl(filing, cik);
                } else {
                    store.saveOk(filing, cik, result.fileName(), result.fileSize(), result.instance());
                }
                fetched++;
                openIfAdded(supplemented, filing, added);
            } catch (UnreadableFiling e) {
                failed.add(filing.accession());
                unconfirmed.add(filing.filed());
                log.warn("보충 공시를 해석하지 못했다 — 그 공시일의 판을 회수에서 지킨다. {} — {}", filingContext, e.getMessage());
                store.saveFailed(filing, cik, UNREADABLE, e.getMessage());
            } catch (DataSourceException e) {
                unfetched.add(e.kind());
                if (row != null && row.ok()) {
                    // 판을 올려 다시 읽다 받지 못했다 — 옛 판의 저장분은 여전히 그 공시의 값이다. 그것을 쓰고 행은 그대로
                    // 둔다(옛 판이 남아 관문의 미해결로 센다).
                    Integer added = mergeStored(facts, filing, row, filingContext);
                    if (added != null) {
                        merged += added;
                        fromStore++;
                        staleReused++;
                        store.markStaleUsed(filing.accession(), cik);
                        openIfAdded(supplemented, filing, added);
                        log.warn("보충 공시를 새 읽기 규칙으로 다시 받지 못해 옛 판의 저장분을 쓴다. {} — {}",
                                filingContext, e.getMessage());
                        continue;
                    }
                }
                failed.add(filing.accession());
                unconfirmed.add(filing.filed());
                log.warn("보충 공시를 받지 못했다 — 그 공시일의 판을 회수에서 지키고 다음 실행이 다시 받는다. {} 종류={} — {}",
                        filingContext, e.kind(), e.getMessage());
                store.saveFailed(filing, cik, e.kind().name(), e.getMessage());
            }
        }
        // 같은 날 실은 공시와 실패한 공시가 겹치면 보호가 이긴다 — 그 날짜의 판은 출력에 일부만 있다.
        supplemented.removeAll(unconfirmed);
        return new Outcome(merged, fromStore, fetched, staleReused, List.copyOf(failed), Set.copyOf(unconfirmed),
                Set.copyOf(supplemented), false, unfetched);
    }

    /**
     * 그 공시가 트리에 무언가를 더했으면 그 제출일의 판을 이른 판이어도 넣게 연다. 더한 것이 없으면(XBRL 없음 · companyfacts가
     * 이미 실은 dei뿐) 그 공시의 판은 출력에 없으므로 열 것이 없다 — 열면 같은 날 다른 공시의 판까지 이른 판으로 들인다.
     */
    private static void openIfAdded(Set<LocalDate> supplemented, SecFilingClient.Filing filing, int added) {
        if (added > 0) {
            supplemented.add(filing.filed());
        }
    }

    /**
     * companyfacts가 나중에 실은 공시의 행을 상태와 무관하게(OK·NO_XBRL·FAILED) {@code COVERED}로 바꾸고, 그런 행의 제출일을
     * <b>매 실행</b> 연다 — 판은 이제 companyfacts가 내지만, 그 전에 늦은 판이 먼저 들어왔을 수 있어 그 판을 이른 판이어도
     * 넣어야 「처음부터 companyfacts가 실었을 때」와 같은 상태에 닿는다. 여는 날짜는 <b>companyfacts가 그 공시에 붙인
     * {@code filed}</b>뿐이다 — 덮인 뒤 그 공시의 판은 그 날짜로만 나오고(17:30 ET 뒤 접수면 목록 날짜보다 하루 이틀 늦다 ·
     * 15 / 55,736 접수번호 · 미리 못 가른다), 목록 날짜를 열면 그날 다른 공시의 판만 이른 판으로 들어온다.
     *
     * <p><b>MUST NOT: 행을 지우지 않는다 · 실패 행만 고르지 않는다.</b> 한 번만 열면 그 실행이 APPEND_ONLY(Writer가 열림을 안
     * 씀)거나 같은 날 다른 공시가 실패해(보호가 이김) 열림이 사라지고 다시 안 온다. 받았던 공시(OK)도 같다 — 받은 실행의
     * 열림이 그렇게 사라졌으면, 실패였던 공시와 다른 판 집합에 닿는다(2026-10-06 v3 반증 · 실측 전이 EXE 1건). 받는 창 밖으로
     * 나간 행도 그대로 둔다 — 창이 좁은 손 실행이 기본 창의 행을 지우면 안 된다.
     */
    private void settle(long cik, Map<String, Set<LocalDate>> coveredFiled, Set<String> targetAccessions,
                        Set<LocalDate> supplemented, String context) {
        for (SecFilingSupplementStore.Row row : store.rowsOf(cik).values()) {
            if (targetAccessions.contains(row.accession()) || !coveredFiled.containsKey(row.accession())) {
                continue;
            }
            if (!"COVERED".equals(row.status())) {
                store.markCovered(row.accession(), cik);
                log.info("보충 공시를 companyfacts가 실었다 — 그 판을 매 실행 연다. {} {} 이전 상태={}", context,
                        row.accession(), row.status());
            }
            // 덮인 뒤 그 공시의 판은 companyfacts가 붙인 날짜로만 나온다 — 목록 날짜가 다르면 그날엔 그 공시의 판이 없고,
            // 열면 그날 다른 공시의 판만 이른 판으로 들어온다(2026-10-06 v5 반증 · 지금 0쌍).
            supplemented.addAll(coveredFiled.get(row.accession()));
        }
    }

    record Fetched(String fileName, long fileSize, XbrlInstanceReader.Instance instance,
                           List<XbrlInstanceReader.Fact> chosen) {

        static Fetched noXbrl() {
            return new Fetched(null, 0, new XbrlInstanceReader.Instance(null, null, null, 0, List.of()), List.of());
        }
    }

    /**
     * 받기(폴더 · 인스턴스)와 해석(고르기 · 읽기 · 신원 · 정밀도)을 가른다 — 앞은 {@link DataSourceException}, 뒤는
     * {@link UnreadableFiling}이다.
     */
    private Fetched fetch(long cik, SecFilingClient.Filing filing, String context)
            throws DataSourceException, UnreadableFiling {
        List<SecFilingClient.FolderItem> items = client.folder(cik, filing.accession());
        String name = pickInstance(items.stream().map(SecFilingClient.FolderItem::name).toList(), context);
        if (name == null) {
            log.info("원장이 XBRL이 있다고 했지만 공시 폴더에 XBRL 산출물이 없다 — 팩트 0으로 끝낸다. {}", context);
            return Fetched.noXbrl();
        }
        SecFilingClient.FolderItem item = items.stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
        byte[] bytes = client.instance(cik, filing.accession(), item);
        return interpret(bytes, cik, filing.form(), name, context);
    }

    /**
     * 받은 인스턴스를 해석한다 — 읽기 · 신원 · 고르기. 여기서 난 실패는 전부 해석 실패({@link UnreadableFiling})이고 판이
     * 같은 동안 다시 받지 않는다. 판 범위의 일부라 {@code ReaderVersionPinTest}가 이것을 직접 부른다.
     */
    static Fetched interpret(byte[] bytes, long cik, String form, String name, String context) throws UnreadableFiling {
        try {
            XbrlInstanceReader.Instance instance =
                    XbrlInstanceReader.read(new ByteArrayInputStream(bytes), cik, context + " " + name);
            XbrlInstanceReader.requireIdentity(instance, form, context + " " + name);
            // 고르기를 저장 전에 한 번 돌려 본다 — 같은 정밀도 충돌은 그 공시의 실패로 남긴다(저장분이 늘 고를 수 있게).
            List<XbrlInstanceReader.Fact> chosen = XbrlInstanceReader.choose(instance.facts(), context + " " + name);
            return new Fetched(name, bytes.length, instance, chosen);
        } catch (DataSourceException e) {
            throw new UnreadableFiling(e.getMessage(), e);
        }
    }

    /**
     * 공시 폴더에서 인스턴스를 고른다. {@code .xml} 중 {@code FilingSummary.xml}과 링크베이스를 뺀 것이 하나면 그것이다.
     *
     * @return 폴더에 XBRL 산출물(인스턴스 후보 · 스키마 · 링크베이스)이 하나도 없으면 {@code null} — 원장이 「XBRL 없음」을
     *         확정한 것이다(2026-10-06: 대상 242 중 15 · 6-K 2017 14 · ESLT 2025-06)
     * @throws UnreadableFiling 후보가 둘 이상이거나, 스키마·링크베이스는 있는데 인스턴스가 없다 — 고를 근거가 없다
     */
    static String pickInstance(List<String> names, String context) throws UnreadableFiling {
        List<String> candidates = names.stream().filter(SecFilingClient::isInstanceCandidate).toList();
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty() && names.stream().noneMatch(SecFilingClient::isXbrlArtifact)) {
            return null;
        }
        throw new UnreadableFiling("인스턴스 후보가 하나가 아니다: %s %s".formatted(candidates, context), null);
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
                                  DataSourceException cause, boolean usGate) {
        log.warn("공시 목록을 받지 못해 저장분만 합치고 이 종목의 판 회수를 멈춘다. {} 종류={} — {}",
                context, cause.kind(), cause.getMessage());
        Map<String, Set<LocalDate>> coveredFiled = coveredFilings(facts, usGate);
        Set<String> covered = coveredFiled.keySet();
        int merged = 0;
        int fromStore = 0;
        int staleReused = 0;
        Set<LocalDate> supplemented = new LinkedHashSet<>();
        // 정리는 목록이 필요 없다 — 대상은 companyfacts가 안 싣는 공시뿐이라 덮인 공시는 어느 쪽이든 대상이 아니다.
        settle(cik, coveredFiled, Set.of(), supplemented, context);
        // 같은 날 보호도 정상 갈래와 같게 — 창 안의 덮이지 않은 실패 행은 그 제출일의 판이 일부만 있다(목록이 없어 다시
        // 받지도 못했다). 열면 그 날짜의 부분 판이 이른 판으로 들어온다(2026-10-06 v5 반증).
        Set<LocalDate> unconfirmed = new LinkedHashSet<>();
        Set<String> protectedFailures = new HashSet<>();
        for (SecFilingSupplementStore.Row row : store.rowsOf(cik).values()) {
            if (row.failed() && !row.filed().isBefore(since) && !covered.contains(row.accession())) {
                unconfirmed.add(row.filed());
                protectedFailures.add(row.accession());
            }
        }
        // 이 실행이 그 실패 행을 보고 날짜를 지켰다 — 실행 기록의 미해결에 든다.
        store.touch(cik, protectedFailures);
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
                if (row.readerVersion() != XbrlInstanceReader.READER_VERSION) {
                    // 목록이 없어 다시 받을지조차 못 정했다 — 지금 판이 아닌 저장분을 쓴 것이다. 미해결로 센다. 「낮다」가 아니라
                    // 「다르다」다 — 더 새 코드(배포 전 main의 손 실행)가 쓴 높은 판도 지금 규칙의 저장분이 아니다(v8 반증).
                    staleReused++;
                    store.markStaleUsed(row.accession(), cik);
                }
                openIfAdded(supplemented, filing, added);
            }
        }
        supplemented.removeAll(unconfirmed);
        return new Outcome(merged, fromStore, 0, staleReused, List.of(), Set.copyOf(unconfirmed),
                Set.copyOf(supplemented), true, List.of(cause.kind()));
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

    /** companyfacts가 us-gaap 팩트를 하나라도 싣는 접수번호. */
    static Set<String> usGaapAccessions(JsonNode facts) {
        return usGaapFilings(facts).keySet();
    }

    /** 「companyfacts가 덮은 공시」 — 관문 켬은 {@code ifrs-full}로 실은 공시도 든다(ADR-0182). */
    static Map<String, Set<LocalDate>> coveredFilings(JsonNode facts, boolean usGate) {
        Map<String, Set<LocalDate>> out = usGaapFilings(facts);
        if (usGate) {
            taxonomyFilings(facts, "ifrs-full").forEach((accn, dates) ->
                    out.computeIfAbsent(accn, a -> new java.util.TreeSet<>()).addAll(dates));
        }
        return out;
    }

    /** companyfacts가 us-gaap 팩트를 싣는 접수번호와, 그 접수번호에 companyfacts가 붙인 {@code filed} 날짜들. */
    static Map<String, Set<LocalDate>> usGaapFilings(JsonNode facts) {
        return taxonomyFilings(facts, "us-gaap");
    }

    private static Map<String, Set<LocalDate>> taxonomyFilings(JsonNode facts, String taxonomy) {
        Map<String, Set<LocalDate>> out = new java.util.HashMap<>();
        JsonNode usGaap = facts.path(taxonomy);
        for (String concept : usGaap.propertyNames()) {
            JsonNode units = usGaap.path(concept).path("units");
            for (String unit : units.propertyNames()) {
                for (JsonNode node : units.path(unit)) {
                    String accn = node.path("accn").asString("");
                    if (accn.isEmpty()) {
                        continue;
                    }
                    Set<LocalDate> dates = out.computeIfAbsent(accn, a -> new java.util.TreeSet<>());
                    String filed = node.path("filed").asString("");
                    if (!filed.isEmpty()) {
                        dates.add(LocalDate.parse(filed));
                    }
                }
            }
        }
        return out;
    }
}
