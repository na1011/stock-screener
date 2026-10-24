package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader;
import com.stockscreener.pipeline.datasource.us.shareclass.IssuerFacts;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 클래스 축 판정의 재료를 받는다 — 관문 설정이 켜졌을 때만 {@link SecEdgarFinancialAdapter}가 부른다(탐색
 * {@code SHARES-CLASS-PLAN-V5-1015.md} §2·§3 · 받기 15).
 *
 * <ol>
 *   <li>그 종목의 companyfacts를 판정이 읽는 것만 남겨 {@code sec_share_class_input}에 둔다 — <b>보충 전</b> 트리다(판정 엔진이
 *       드라이런 1에서 companyfacts 그대로로 검증됐다). 유도기는 이것을 다시 읽어 외부 호출 없이 판정한다.</li>
 *   <li>앵커 후보 공시({@link IssuerFacts#anchorCandidates()})의 인스턴스를
 *       {@link ClassFactReader}로 읽어 {@code sec_filing_class_fact}에 둔다. 공시는 안 바뀌므로 평생 한 번 받는다.</li>
 * </ol>
 *
 * <p><b>공시 보충(ADR-0173)과 따로 둔다.</b> 보충의 받기·읽기 파일은 판 고정 범위({@code ReaderVersionPinTest})라 한 글자도 안
 * 고친다 — 이 클래스는 그 범위의 받기({@link SecFilingClient#folder} · {@link SecFilingClient#instance})와 인스턴스 고르기
 * ({@link SecFilingSupplement#pickInstance})를 <b>부르기만</b> 하고, 범위 파일은 이 클래스를 모른다.
 *
 * <p>받기 실패는 종목을 세우지 않는다 — 그 공시의 판정이 「인스턴스 없음」으로 숨김이 되고(값을 지어내지 않는다), 다음 받기가
 * 다시 받는다. 해석 실패(UNREADABLE)는 읽기 판이 같은 동안 다시 받지 않는다. 인증 실패만 던진다(배치 전체의 문제).
 *
 * <p><b>MUST: 받지 못한 공시는 행으로 남기고 종류를 결과에 싣는다</b>({@link Outcome#unresolved()}). 로그만 남기면 아무 표면에도
 * 안 닿는다 — 잡이 시장 완료에 세지 않고, 공시 소비가 그 공시를 먹여 닫고, 정기 FR이 없는 순위 후보는 다음 공시(약 한 분기)까지
 * 그 판이 숨는다(후보 손 FR 계획 v2 반증). 한도({@code RATE_LIMITED})로 멈춘 뒤의 공시도 콜 없이 같은 종류의 실패 행으로 적는다 —
 * 그래서 「미해결 = 지금 읽기 판의 {@code FAILED} 중 {@code UNREADABLE}이 아닌 것」이 표 하나로 선다.
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class ShareClassCollector {

    private static final Logger log = LoggerFactory.getLogger(ShareClassCollector.class);

    /**
     * @param targets    앵커 후보 공시 수 · {@code fetched} 이번에 받아 저장한 수 · {@code failed} 이번에 실패로 남긴 공시
     * @param unresolved 다음 받기가 다시 받을 실패의 종류(공시마다 하나) — 해석 실패는 안 든다(같은 읽기 판으로는 다시 안 받는다)
     */
    public record Outcome(int targets, int fetched, List<String> failed, List<DataSourceException.Kind> unresolved) {
        public Outcome {
            failed = List.copyOf(failed);
            unresolved = List.copyOf(unresolved);
        }
    }

    private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

    private final SecFilingClient client;
    private final ShareClassStore store;

    public ShareClassCollector(SecFilingClient client, ShareClassStore store) {
        this.client = client;
        this.store = store;
    }

    /**
     * 줄의 companyfacts 재료를 둔다 — 공시 보충 <b>전</b> 트리로(판정 엔진이 companyfacts 그대로로 검증됐다).
     *
     * @param facts companyfacts 응답의 {@code facts} 노드 — 어댑터가 쥔 트리가 이것이다(응답 루트가 아니다). 가지치기는 응답 루트
     *              꼴을 받으므로 여기서 감싼다(드라이런 3: 루트로 넘긴 줄 알고 가지치자 재료가 빈 트리로 저장됐다).
     * @return 그 재료로 읽은 companyfacts 값 — {@link #fetchInstances}에 넘긴다
     */
    public IssuerFacts saveInput(SecurityRef security, long cik, JsonNode facts) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("facts", facts);
        ObjectNode pruned = IssuerFacts.prune(root);
        store.saveInput(security.stockId(), cik, pruned.toString());
        return IssuerFacts.fromCompanyFacts(pruned);
    }

    /**
     * 앵커 후보 공시와 지금 경로 분모 후보 공시({@code IssuerTimeline.denominatorCandidates} · ADR-0189)의 인스턴스를 받는다 — 공시 보충 <b>뒤</b>에 부른다. 보충이 같은 실행에서 실은 정기 공시(companyfacts가 빠뜨린 것)도
     * 앵커 후보라서다(앞에 부르면 그 공시는 다음 받기까지 판정 없이 숨는다).
     */
    public Outcome fetchInstances(long cik, IssuerFacts companyFacts, String context) throws DataSourceException {
        IssuerFacts cf = companyFacts.withFilings(store.supplementedFilings(cik));
        Map<String, ShareClassStore.FactRow> stored = store.factRows(cik);
        int targets = 0;
        int fetched = 0;
        List<String> failed = new ArrayList<>();
        List<DataSourceException.Kind> unresolved = new ArrayList<>();
        DataSourceException limited = null;
        java.util.Set<String> targetsOf = new java.util.LinkedHashSet<>(cf.anchorCandidates());
        // 지금 경로 분모 후보(ADR-0189) — companyfacts만으로 당기 분모가 안 서는 축 없는 표지 공시. 유도가 같은 함수를 부른다.
        targetsOf.addAll(com.stockscreener.pipeline.datasource.us.shareclass.IssuerTimeline.denominatorCandidates(cf));
        for (String accession : targetsOf) {
            IssuerFacts.Filing filing = cf.filings().get(accession);
            targets++;
            ShareClassStore.FactRow row = stored.get(accession);
            if (row != null && row.readerVersion() == ClassFactReader.READER_VERSION
                    && (!"FAILED".equals(row.status()) || "UNREADABLE".equals(row.failureKind()))) {
                continue;
            }
            String where = context + " " + accession;
            if (limited != null) {
                // 한도에 걸린 뒤로는 같은 실행에서 더 두드리지 않는다 — 다만 「안 물었다」를 행으로 남긴다(다음 받기가 이어 받는다).
                store.saveFact(cik, accession, filing.form(), filing.filed(), "FAILED", null, limited.kind().name(),
                        "앞 공시가 한도에 걸려 이번 실행에서 묻지 않았다 — " + limited.getMessage());
                failed.add(accession);
                unresolved.add(limited.kind());
                continue;
            }
            try {
                fetch(cik, accession, filing, where);
                fetched++;
            } catch (SecFilingSupplement.UnreadableFiling ex) {
                store.saveFact(cik, accession, filing.form(), filing.filed(), "FAILED", null, "UNREADABLE", ex.getMessage());
                failed.add(accession);
                log.warn("클래스 축 인스턴스를 해석하지 못했다 — 읽기 판이 같은 동안 다시 받지 않는다. {} — {}", where, ex.getMessage());
            } catch (DataSourceException ex) {
                if (ex.kind() == DataSourceException.Kind.AUTH) {
                    throw ex;
                }
                store.saveFact(cik, accession, filing.form(), filing.filed(), "FAILED", null, ex.kind().name(), ex.getMessage());
                failed.add(accession);
                unresolved.add(ex.kind());
                log.warn("클래스 축 인스턴스를 받지 못했다 — 다음 받기가 다시 받는다. {} 종류={} — {}", where, ex.kind(), ex.getMessage());
                if (ex.kind() == DataSourceException.Kind.RATE_LIMITED) {
                    limited = ex;
                }
            }
        }
        return new Outcome(targets, fetched, failed, unresolved);
    }

    private void fetch(long cik, String accession, IssuerFacts.Filing filing, String context)
            throws DataSourceException, SecFilingSupplement.UnreadableFiling {
        List<SecFilingClient.FolderItem> items = client.folder(cik, accession);
        String name = SecFilingSupplement.pickInstance(items.stream().map(SecFilingClient.FolderItem::name).toList(), context);
        if (name == null) {
            store.saveFact(cik, accession, filing.form(), filing.filed(), "NO_XBRL", null, null, null);
            return;
        }
        SecFilingClient.FolderItem item = items.stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
        byte[] bytes = client.instance(cik, accession, item);
        ClassFactReader.Result read;
        try {
            read = ClassFactReader.read(new ByteArrayInputStream(bytes), cik, context + " " + name);
        } catch (DataSourceException e) {
            throw new SecFilingSupplement.UnreadableFiling(e.getMessage(), e);
        }
        if (read.unreadableValues() > 0) {
            log.info("클래스 축 인스턴스에서 수로 안 읽힌 값을 건너뛰었다. {} 건너뜀={}", context, read.unreadableValues());
        }
        store.saveFact(cik, accession, filing.form(), filing.filed(), "OK", read.facts().toJson(), null, null);
    }
}
