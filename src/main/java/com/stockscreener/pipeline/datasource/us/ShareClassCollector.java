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
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class ShareClassCollector {

    private static final Logger log = LoggerFactory.getLogger(ShareClassCollector.class);

    /** @param targets 앵커 후보 공시 수 · {@code fetched} 이번에 받아 저장한 수 · {@code failed} 이번에 실패로 남긴 공시 */
    public record Outcome(int targets, int fetched, List<String> failed) {
    }

    private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

    private final SecFilingClient client;
    private final ShareClassStore store;

    public ShareClassCollector(SecFilingClient client, ShareClassStore store) {
        this.client = client;
        this.store = store;
    }

    /**
     * @param facts companyfacts 응답의 {@code facts} 노드 — 어댑터가 쥔 트리가 이것이다(응답 루트가 아니다). 가지치기는 응답 루트
     *              꼴을 받으므로 여기서 감싼다(드라이런 3: 루트로 넘긴 줄 알고 가지치자 재료가 빈 트리로 저장됐다).
     */
    public Outcome collect(SecurityRef security, long cik, JsonNode facts, String context) throws DataSourceException {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("facts", facts);
        ObjectNode pruned = IssuerFacts.prune(root);
        store.saveInput(security.stockId(), cik, pruned.toString());
        IssuerFacts cf = IssuerFacts.fromCompanyFacts(pruned);
        Map<String, ShareClassStore.FactRow> stored = store.factRows(cik);
        int targets = 0;
        int fetched = 0;
        List<String> failed = new ArrayList<>();
        for (String accession : cf.anchorCandidates()) {
            IssuerFacts.Filing filing = cf.filings().get(accession);
            targets++;
            ShareClassStore.FactRow row = stored.get(accession);
            if (row != null && row.readerVersion() == ClassFactReader.READER_VERSION
                    && (!"FAILED".equals(row.status()) || "UNREADABLE".equals(row.failureKind()))) {
                continue;
            }
            String where = context + " " + accession;
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
                log.warn("클래스 축 인스턴스를 받지 못했다 — 다음 받기가 다시 받는다. {} 종류={} — {}", where, ex.kind(), ex.getMessage());
                if (ex.kind() == DataSourceException.Kind.RATE_LIMITED) {
                    // 남은 공시를 같은 실행에서 더 두드리지 않는다 — 다음 받기가 이어 받는다.
                    break;
                }
            }
        }
        return new Outcome(targets, fetched, failed);
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
