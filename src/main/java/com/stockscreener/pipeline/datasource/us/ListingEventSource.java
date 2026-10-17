package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.us.SecDailyIndexClient.IndexLine;
import com.stockscreener.pipeline.datasource.us.listing.ListingEvent;
import com.stockscreener.pipeline.datasource.us.listing.ListingFormReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 등록인 집합의 창 안 거래소 사건 — 일일 색인에서 세 양식({@code 25-NSE} · 발행사 {@code 25} · {@code 8-A12B})을 골라 본문을 읽는다
 * (탐색 {@code US-PAST-LISTING-IMPL-1018.md} U2 · 원자료 {@code docs/api-spec/sec-edgar-listing-forms.md}).
 *
 * <p><b>받기 실패는 던진다</b> — 한 양식을 빼고 이으면 그 등록인의 이력이 사건 하나 없이 「계속 안」으로 읽힌다. <b>읽기 실패는 센다</b>
 * — 주 문서가 없거나 라벨·거래소를 못 읽은 양식은 사건을 안 내고 {@link Result#unreadable()}에 남는다(지어내지 않는다).
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class ListingEventSource {

    static final Set<String> FORMS = Set.of("25-NSE", "25", "8-A12B");

    private static final Logger log = LoggerFactory.getLogger(ListingEventSource.class);

    private final SecListingFormClient client;

    public ListingEventSource(SecListingFormClient client) {
        this.client = client;
    }

    /**
     * @param byCik      등록인마다 보통주 줄의 사건(효력일 순서는 아니다 — {@code ListedTimeline}이 정렬한다)
     * @param forms      그 등록인 집합에서 받은 양식 수
     * @param unreadable 사건을 못 낸 양식(양식 · CIK · 접수번호 · 사유)
     */
    public record Result(Map<Long, List<ListingEvent>> byCik, int forms, List<String> unreadable) {
    }

    public Result events(Set<Long> ciks, LocalDate from, LocalDate to) throws DataSourceException {
        Map<Long, List<ListingEvent>> byCik = new LinkedHashMap<>();
        List<String> unreadable = new ArrayList<>();
        int forms = 0;
        for (IndexLine line : client.lines(from, to, FORMS)) {
            if (!ciks.contains(line.cik())) {
                continue;
            }
            forms++;
            String where = "%s %d %s %s".formatted(line.form(), line.cik(), line.accession(), line.filed());
            Optional<byte[]> doc = client.document(line.cik(), line.accession(), line.form());
            if (doc.isEmpty()) {
                unreadable.add(where + " · 주 문서를 못 골랐다");
                continue;
            }
            List<ListingEvent> events;
            String why;
            if ("8-A12B".equals(line.form())) {
                ListingFormReader.Registration registration =
                        ListingFormReader.readRegistration(new String(doc.get(), StandardCharsets.ISO_8859_1));
                why = registration.unreadable();
                events = ListingEvent.ofRegistration(line.form(), line.filed(), line.accession(), registration);
            } else {
                ListingFormReader.Removal removal = "25-NSE".equals(line.form())
                        ? ListingFormReader.readExchangeRemoval(doc.get(), where)
                        : ListingFormReader.readIssuerRemoval(new String(doc.get(), StandardCharsets.ISO_8859_1));
                why = removal.unreadable() != null ? removal.unreadable()
                        : removal.venue().isEmpty() ? "거래소 이름을 모른다: " + removal.rawVenue() : null;
                events = ListingEvent.ofRemoval(line.form(), line.filed(), line.accession(), removal);
            }
            if (why != null) {
                unreadable.add(where + " · " + why);
                continue;
            }
            if (!events.isEmpty()) {
                byCik.computeIfAbsent(line.cik(), k -> new ArrayList<>()).addAll(events);
            }
        }
        log.info("거래소 사건. 등록인={} 창={}~{} 양식={} 사건 등록인={} 못 읽음={}{}", ciks.size(), from, to, forms,
                byCik.size(), unreadable.size(), unreadable.stream().limit(20).toList());
        return new Result(byCik, forms, List.copyOf(unreadable));
    }
}
