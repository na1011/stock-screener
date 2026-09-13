package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>{@code companyconcept}가 {@code companyfacts}가 못 주는 것을 주는가</b>를 잰다.
 *
 * <p>ADR-0099 §버린 대안이 주식수 결손 165종목의 폴백을 <b>사용자 결정으로 미뤘고</b>, §미룬 문이
 * <i>"되살릴 이유가 생겨도 그 결정부터 다시 연다"</i>로 못박았다. 그 선택지를 올리려면 둘을 먼저
 * 재야 한다 — <b>재보지 않은 것을 선택지 설명에 적지 않는다</b>(CLAUDE.md).
 *
 * <ol>
 *   <li><b>측정 A — 복수 클래스를 가르는가.</b> {@link SecEdgarSharesProbeLiveTest}가 결손의 원인을
 *       <i>"{@code readUnits}가 {@code Slot(accn, start, end)}를 키로 쓰는데 복수 클래스는 같은
 *       {@code accn}·같은 종료일에 클래스별 팩트를 내므로 서로 덮는다"</i>로 가설 세웠다.
 *       그 가설이 맞고 {@code companyconcept}도 <b>축이 붙은 팩트를 빼고 준다면</b>(ADR-0125 §규칙이
 *       {@code companyfacts}에 대해 그렇게 적었다) 그 문으로 복수 클래스는 안 열린다.</li>
 *   <li><b>측정 B — ADS 비율을 말하는 개념이 있는가.</b> ADR-0126 §{@code AKTX}가
 *       {@code companyfacts}에는 없다고 이미 쟀다. 같은 데이터셋이면 여기에도 없다.</li>
 * </ol>
 *
 * <h2>왜 새 탐침인가 — 기존 셋이 원리적으로 못 잰다</h2>
 * <ul>
 *   <li>{@link SecEdgarSharesProbeLiveTest} — {@code companyfacts}만 부른다.</li>
 *   <li>{@code SecEdgarGovernanceProbeLiveTest} — <b>CIK가 한 종목으로 박혀 있다.</b></li>
 *   <li>{@code SecEdgarIfrsAndBulkProbeLiveTest} — CIK는 변수인데 <b>태그가 {@code Assets} 고정</b>이다.</li>
 * </ul>
 * <p>그래서 이 탐침은 <b>종목과 개념을 둘 다 밖에서 받는다.</b>
 *
 * <h2>함정</h2>
 * <ul>
 *   <li><b>MUST NOT: {@code SEC_EDGAR_USER_AGENT}를 출력에 찍지 않는다</b> — 이메일이 들어 있다.</li>
 *   <li><b>MUST: 상태 코드를 먼저 본다.</b> 오류 본문이 JSON이 아니라 XML이라
 *       ({@code sec-edgar-financial.md} §실패 계약) 그대로 파싱하면 404가 예외로 바뀐다.</li>
 *   <li><b>MUST: 받은 파라미터를 출력한다.</b> {@code liveTest}가 {@code probe.} 접두를 통째로
 *       넘기므로 오타는 <b>조용히 기본값으로 도는 것</b>으로 나타난다 — 찍어야 드러난다.</li>
 *   <li><b>MUST: 후보 태그를 {@link SecEdgarColumn}에서 가져온다.</b> 손으로 박으면 적재와 다른
 *       것을 재게 된다({@code SecEdgarSharesProbeLiveTest} 승계).</li>
 *   <li><b>MUST: CIK를 매핑 파일에서 푼다.</b> 기억에서 만든 CIK의 404는 「없다」와 구분되지 않는다.
 *       승자 규칙은 {@code SecTickerCikCache.parse}와 같게 <b>마지막 행이 이긴다.</b></li>
 * </ul>
 *
 * <h2>실측 (2026-09-14) — 아래가 이 탐침의 출력 그대로다</h2>
 *
 * <p><b>측정 A — {@code companyconcept}는 {@code companyfacts}와 <u>같은 팩트</u>를 준다.</b>
 * 여섯 종목 × 네 태그에서 두 엔드포인트의 팩트 수가 <b>한 칸도 안 갈렸고</b>
 * (AMZN 69·138·313·313 · BRK.B 7·82 · AKTX 29·72·90 · V 2), <b>같은
 * {@code (start, end, accn)}에 값이 둘인 자리가 0</b>이다. 즉 <b>축이 붙은 팩트를 주지 않는다</b> —
 * 복수 클래스는 이 문으로 안 열린다.
 *
 * <p><b>그리고 결손의 원인이 가설과 달랐다.</b> {@code GEF}/{@code GEF.B}(같은 CIK 43920)는
 * 네 태그가 <b>전부 404이고 {@code companyfacts}에도 없다</b>. 「클래스별 팩트가 서로 덮는다」가
 * 아니라 <b>소스가 그 개념을 아예 안 낸다</b>. {@code V}는 팩트가 2개뿐이라 낡음이 원인이다 —
 * <b>결손 165종목의 원인이 한 갈래가 아니다.</b>
 *
 * <p><b>측정 B — ADS 비율 개념은 실재하나 거의 아무도 안 낸다.</b>
 * {@code dei:EntityListingDepositoryReceiptRatio}(단위 {@code pure})가 있고 {@code BABA}가
 * <b>32팩트</b>를 낸다. 그러나 ADR·ADS 대표 16종목에 물으면 <b>내는 것은 BABA 하나뿐</b>
 * (AKTX·TSM·HSBC·NVS·SAP·UL·BP·TM·SONY·PDD·NIO·ASML·BIDU·GHG·FENG 전부 404).
 * <b>ADR-0126 §{@code AKTX}의 결론(비율을 얻을 경로가 없다)은 그대로 서지만 이유가 다르다</b> —
 * 「개념이 없다」가 아니라 <b>「그 발행사가 안 낸다」</b>이고, 커버리지가 1/16이라 일반 해법이 못 된다.
 *
 * <p>표본은 {@code -Dprobe.tickers=}·{@code -Dprobe.concepts=}·{@code -Dprobe.conceptNeedles=}로 준다.
 * 위 커버리지도 {@code -Dprobe.concepts=dei:EntityListingDepositoryReceiptRatio}로 잰 것이다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SEC_EDGAR_USER_AGENT", matches = ".+",
        disabledReason = "SEC_EDGAR_USER_AGENT가 없어 건너뛴다")
class SecEdgarConceptProbeLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 기본 표본 여섯. <b>복수 클래스 쌍 + 단일 클래스 결손 + 정상 대조군을 섞은 것이 계약이다.</b>
     *
     * <p>{@code GEF}/{@code GEF.B}는 <b>같은 CIK의 두 클래스</b>라 클래스를 가르지 못하면 두 줄이
     * 똑같이 나온다 — 그것이 측정 A의 답이다. {@code BRK.B}도 같은 형태이고, {@code V}는 주식수가
     * 전량 NULL이지만 <b>단일 클래스</b>라 원인이 다른지 가른다. {@code AMZN}은 주식수가 정상으로
     * 저장된 <b>대조군</b>이고, {@code AKTX}는 측정 B의 대상이다.
     */
    private static final String DEFAULT_TICKERS = "GEF,GEF.B,BRK.B,V,AMZN,AKTX";

    /** 측정 B의 낱말. 개념 <b>이름</b>에서 찾는다 — 값이 아니라 그 개념의 존재가 질문이다. */
    private static final String DEFAULT_NEEDLES =
            "ADS,ADR,Depositary,Depository,Ratio,Conversion,OrdinaryShare";

    private static RestClient client(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                // MUST NOT: 이 값을 출력에 찍지 않는다 — 이메일이 들어 있다.
                .defaultHeader("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT"))
                // 상태 코드를 우리가 직접 본다. 기본 핸들러는 4xx/5xx에 예외를 던지는데
                // 이 탐침은 "404인가 200인가"가 곧 답이라 던지면 답이 사라진다.
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    private static void pause() {
        try {
            Thread.sleep(220);   // 공표 초당 10회의 절반 아래로 둔다(api-sources skill)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 시세 소스 표기 → SEC 매핑 파일 표기.
     *
     * <p><b>MUST: 적재와 같은 규칙을 쓴다</b> — {@code SecTickerCikCache.cikOf}가
     * {@code ticker.replace('.', '-')} 하나로 정규화한다(우리 유니버스에서 24종목).
     * 안 하면 {@code BRK.B}·{@code GEF.B}가 <b>「CIK 매핑 없음」으로 빠지는데 그것은
     * 「개념이 없다」와 생김새가 같다</b> — 2026-09-14에 이 탐침이 실제로 그렇게 신고했다.
     */
    private static String normalize(String ticker) {
        return ticker.replace('.', '-');
    }

    private static List<String> parameter(String key, String fallback) {
        String raw = System.getProperty(key, fallback);
        List<String> out = new ArrayList<>();
        for (String piece : raw.split(",")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    // ---------- 측정 A ----------

    @Test
    @DisplayName("[live][probe] companyconcept가 복수 클래스를 가르는가 — companyfacts와 대조")
    void probeConceptVersusFacts() {
        List<String> tickers = parameter("probe.tickers", DEFAULT_TICKERS);
        List<String> concepts = parameter("probe.concepts",
                String.join(",", SecEdgarColumn.SHARES_OUTSTANDING.candidates()));

        // companyfacts는 종목당 평균 3.4MB라(api-spec) 큰 표본에서는 대역폭이 소요를 정한다.
        // 대조가 목적이 아니라 「그 개념이 오는가」만 물을 때는 끈다.
        boolean compareFacts = Boolean.parseBoolean(
                System.getProperty("probe.compareFacts", "true"));

        // MUST: 받은 값을 찍는다 — 오타는 「조용히 기본값으로 도는 것」으로만 나타난다.
        System.out.println("\n표본(probe.tickers) = " + tickers.size() + "종목 " + tickers);
        System.out.println("개념(probe.concepts) = " + concepts);
        System.out.println("facts 대조(probe.compareFacts) = " + compareFacts);

        RestClient www = client("https://www.sec.gov");
        RestClient data = client("https://data.sec.gov");
        Map<String, Long> cikOf = tickerToCik(www);

        int measured = 0;
        List<String> verdicts = new ArrayList<>();

        for (String ticker : tickers) {
            Long cik = cikOf.get(normalize(ticker));
            if (cik == null) {
                System.out.printf("%n===== %-7s CIK 매핑 없음 — 「개념이 없다」와 다른 사실이다%n", ticker);
                continue;
            }

            JsonNode facts = MAPPER.nullNode();
            String factsNote = "건너뜀";
            if (compareFacts) {
                pause();
                Fetched factsBody = fetch(data, "/api/xbrl/companyfacts/CIK%010d.json".formatted(cik));
                facts = factsBody.json() == null ? MAPPER.nullNode() : factsBody.json().path("facts");
                factsNote = factsBody.describe();
            }

            System.out.printf("%n===== %s (CIK=%d) · companyfacts %s =====%n",
                    ticker, cik, factsNote);

            for (String concept : concepts) {
                boolean dei = concept.startsWith("dei:");
                String taxonomy = dei ? "dei" : "us-gaap";
                String tag = dei ? concept.substring(4) : concept;

                pause();
                Fetched conceptBody = fetch(data,
                        "/api/xbrl/companyconcept/CIK%010d/%s/%s.json".formatted(cik, taxonomy, tag));

                Shape fromConcept = shapeOf(conceptBody.json());
                Shape fromFacts = shapeOf(facts.path(taxonomy).path(tag));

                System.out.printf("  %-48s concept %-22s facts %s%n",
                        concept, conceptBody.status() + " " + fromConcept, fromFacts);

                if (fromConcept.facts > 0) {
                    measured++;
                    if (fromConcept.duplicatedSlots > 0) {
                        verdicts.add("%s %s — 같은 (시작일, 종료일, accn)에 값이 둘 이상인 칸 %d개"
                                .formatted(ticker, concept, fromConcept.duplicatedSlots));
                    }
                }
                if (compareFacts && fromConcept.facts != fromFacts.facts) {
                    verdicts.add("%s %s — 팩트 수가 갈렸다: concept %d 대 facts %d"
                            .formatted(ticker, concept, fromConcept.facts, fromFacts.facts));
                }
            }
        }

        System.out.println("\n---------- 판정 ----------");
        if (verdicts.isEmpty()) {
            System.out.println("  두 엔드포인트가 같은 팩트를 주고, 같은 칸에 값이 둘인 자리가 없다.");
            System.out.println("  → companyconcept는 축이 붙은 팩트를 주지 않는다(= 복수 클래스를 못 가른다).");
        } else {
            verdicts.forEach(line -> System.out.println("  " + line));
        }

        assertThat(measured)
                .as("한 개념도 못 쟀으면 위 판정은 「잴 것이 없었다」이지 「없다」가 아니다")
                .isPositive();
    }

    // ---------- 측정 B ----------

    @Test
    @DisplayName("[live][probe] ADS 비율을 말하는 개념이 소스에 있는가")
    void probeAdsRatioConcepts() {
        List<String> tickers = parameter("probe.adsTickers", "AKTX,BABA,TSM");
        List<String> needles = parameter("probe.conceptNeedles", DEFAULT_NEEDLES);

        System.out.println("\n표본(probe.adsTickers) = " + tickers);
        System.out.println("낱말(probe.conceptNeedles) = " + needles);

        RestClient www = client("https://www.sec.gov");
        RestClient data = client("https://data.sec.gov");
        Map<String, Long> cikOf = tickerToCik(www);

        int scanned = 0;
        for (String ticker : tickers) {
            Long cik = cikOf.get(normalize(ticker));
            if (cik == null) {
                System.out.printf("%n===== %-7s CIK 매핑 없음%n", ticker);
                continue;
            }
            pause();
            Fetched fetched = fetch(data, "/api/xbrl/companyfacts/CIK%010d.json".formatted(cik));
            if (fetched.json() == null) {
                System.out.printf("%n===== %-7s companyfacts %s%n", ticker, fetched.describe());
                continue;
            }
            scanned++;

            JsonNode facts = fetched.json().path("facts");
            Map<String, List<String>> hits = new TreeMap<>();
            int total = 0;
            for (String taxonomy : facts.propertyNames()) {
                for (String tag : facts.path(taxonomy).propertyNames()) {
                    total++;
                    for (String needle : needles) {
                        if (tag.toLowerCase().contains(needle.toLowerCase())) {
                            hits.computeIfAbsent(needle, k -> new ArrayList<>()).add(taxonomy + ":" + tag);
                        }
                    }
                }
            }

            System.out.printf("%n===== %s (CIK=%d) · 개념 %d개 =====%n", ticker, cik, total);
            if (hits.isEmpty()) {
                System.out.println("  낱말에 걸리는 개념이 하나도 없다.");
            } else {
                hits.forEach((needle, tags) ->
                        System.out.printf("  %-16s %s%n", needle, tags));
            }
        }

        assertThat(scanned)
                .as("한 종목도 못 훑었으면 「개념이 없다」가 아니라 「잴 것이 없었다」다")
                .isPositive();
    }

    // ---------- 헬퍼 ----------

    /** 한 번의 GET 결과. <b>상태 코드와 본문을 갈라 들고 있는 것이 요점이다.</b> */
    private record Fetched(int status, JsonNode json, String note) {
        String describe() {
            return note == null ? String.valueOf(status) : status + " " + note;
        }
    }

    private static Fetched fetch(RestClient client, String uri) {
        ResponseEntity<String> response = client.get().uri(uri).retrieve().toEntity(String.class);
        int status = response.getStatusCode().value();
        String body = response.getBody();
        if (status != 200) {
            // 오류 본문은 XML이다. 파싱하지 않고 상태만 돌려준다.
            return new Fetched(status, null, "(본문 " + (body == null ? 0 : body.length()) + "바이트)");
        }
        if (body == null || body.isBlank() || body.startsWith("<")) {
            return new Fetched(status, null, "(JSON이 아니다)");
        }
        return new Fetched(status, MAPPER.readTree(body), null);
    }

    /**
     * 한 개념의 팩트가 어떤 모양인가.
     *
     * <p>{@code duplicatedSlots}가 이 탐침의 답이다 — 복수 클래스 발행사가 클래스별 팩트를
     * 낸다면 <b>같은 {@code (start, end, accn)}에 값이 둘 이상</b>으로 보여야 한다. 0이면 소스가
     * 축이 붙은 팩트를 빼고 준다는 뜻이고, 그러면 이 경로로 클래스를 가를 수 없다.
     */
    private record Shape(int facts, int slots, int duplicatedSlots, Set<String> units, String latestEnd) {
        @Override
        public String toString() {
            if (facts == 0) {
                return "없음";
            }
            // 최신 종료일을 함께 낸다 — 폴백의 조건이 「값이 있는가」가 아니라 「2년 이내인가」다
            // (api-spec §companyconcept: V가 2010-01-27, BRK가 2011-04-29로 멈춰 있다).
            return "팩트 %d · 칸 %d · 겹친 칸 %d · 최신 %s · 단위 %s"
                    .formatted(facts, slots, duplicatedSlots, latestEnd, units);
        }
    }

    private static Shape shapeOf(JsonNode conceptNode) {
        if (conceptNode == null || conceptNode.isMissingNode() || conceptNode.isNull()) {
            return new Shape(0, 0, 0, Set.of(), "-");
        }
        JsonNode units = conceptNode.path("units");
        if (units.isMissingNode()) {
            return new Shape(0, 0, 0, Set.of(), "-");
        }
        String latestEnd = "";
        int facts = 0;
        Map<String, Set<String>> valuesBySlot = new LinkedHashMap<>();
        Set<String> unitNames = new LinkedHashSet<>();
        for (String unit : units.propertyNames()) {
            unitNames.add(unit);
            for (JsonNode fact : units.path(unit)) {
                facts++;
                // 적재의 키와 같은 축으로 센다(SecEdgarFacts의 Slot: accn·start·end).
                // MUST: start를 넣는다. 빼고 세면 **한 보고서가 같은 종료일에 싣는 3개월·6개월·
                // 9개월 누적**이 전부 「겹친 칸」이 되어(api-spec: 72개 중 48개) 클래스 축과
                // 구분되지 않는다 — 2026-09-14에 그렇게 재서 AMZN이 83칸으로 나왔다.
                String end = fact.path("end").asString("");
                if (end.compareTo(latestEnd) > 0) {
                    latestEnd = end;
                }
                String slot = fact.path("start").asString("") + "|"
                        + end + "|" + fact.path("accn").asString("");
                valuesBySlot.computeIfAbsent(slot, k -> new LinkedHashSet<>())
                        .add(fact.path("val").asString(""));
            }
        }
        int duplicated = 0;
        for (Set<String> values : valuesBySlot.values()) {
            if (values.size() > 1) {
                duplicated++;
            }
        }
        return new Shape(facts, valuesBySlot.size(), duplicated, unitNames,
                latestEnd.isEmpty() ? "-" : latestEnd);
    }

    /**
     * 티커 → CIK. <b>MUST: 적재와 같은 승자 규칙을 쓴다</b> — {@code SecTickerCikCache.parse}가
     * {@code map.put}이라 <b>마지막 행이 이긴다.</b> 첫 행 승으로 두면 중복 티커에서 탐침이
     * 적재와 <b>다른 회사</b>를 재고, 그 차이는 출력 어디에도 드러나지 않는다.
     */
    private static Map<String, Long> tickerToCik(RestClient www) {
        JsonNode map = MAPPER.readTree(
                www.get().uri("/files/company_tickers_exchange.json").retrieve().body(String.class));
        JsonNode fields = map.path("fields");
        int iTicker = -1;
        int iCik = -1;
        for (int i = 0; i < fields.size(); i++) {
            String name = fields.get(i).asString("");
            if ("ticker".equals(name)) {
                iTicker = i;
            }
            if ("cik".equals(name)) {
                iCik = i;
            }
        }
        Map<String, Long> cikOf = new LinkedHashMap<>();
        for (JsonNode row : map.path("data")) {
            cikOf.put(row.get(iTicker).asString(""), row.get(iCik).asLong());
        }
        return cikOf;
    }
}
