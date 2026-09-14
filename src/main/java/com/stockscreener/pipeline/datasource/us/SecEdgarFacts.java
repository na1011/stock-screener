package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FinancialColumnAbsence;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code companyfacts}의 XBRL 팩트를 {@link RawFinancialReport}로 재구성한다.
 *
 * <p><b>이 클래스가 이 축의 위험 전부다.</b> DART는 (연도·보고서코드)로 기간이 정해져 오지만
 * EDGAR는 전 기간 팩트를 한 번에 쏟고 <b>기간·회계연도·정정 여부가 전부 우리 해석</b>이다.
 * 아래 넷은 전부 <b>에러 없이 그럴듯하게 틀리는</b> 자리이고, 실측이 근거다
 * ({@code docs/api-spec/sec-edgar-financial.md}).
 *
 * <p><b>① {@code fy}/{@code fp}는 그 사실의 기간이 아니라 그것을 실은 보고서를 가리킨다.</b>
 * FY2024 10-K의 FY2023·FY2022 비교표 팩트도 {@code fy=2024, fp=FY}로 온다 — 실측에서
 * <b>라벨 70개가 전부 종료일을 둘 이상 갖고 최대 12개</b>였다. 그래서 라벨을 팩트에서 직접
 * 읽지 않고, <b>보고서가 자기 기간에 붙인 것만</b> 쓴다({@link #anchorsOf}).
 *
 * <p><b>② 한 보고서가 자기 종료일에 기간 길이를 둘 이상 싣는다</b>(실측 72개 중 48개).
 * 10-Q가 누적과 분기 단독을 함께 싣기 때문이다. 그래서 <b>회계연도는 앵커에서 — 그것도
 * 연차보고서인 앵커에서만</b>(ADR-0124), <b>기간 구분은
 * 길이에서</b> 가져온다. 저장 키에 기간이 없어({@code uq_fs_latest}) 라벨이 겹치면
 * <b>제약이 걸리는 것이 아니라 앞 기간의 최신 표시가 조용히 내려간다.</b>
 *
 * <p><b>③ 같은 기간이 이후 보고서마다 다시 실리고 공시일은 매번 늦다.</b> 값 비교 없이 내면
 * 실측 <b>112개 중 107개가 가짜 정정</b>이 되고, 정정 판정은 그 종목의 지표를 보존 창 전체에서
 * 지운다. <b>그 접기는 여기서만 한다</b> — {@code FinancialWriter}는 공시일만 보고 값을
 * 비교하지 않는다({@code writeOne}의 정정 갈래).
 *
 * <p><b>④ {@code units} 키는 통화가 아니다.</b> 실측에서 3자를 넘는 키가 44종이었고
 * ({@code pure}·{@code shares}·{@code USD/shares}·{@code segment}…),
 * {@code financial_statement.currency_code}가 {@code character(3)}라 그대로 담으면
 * <b>그 종목의 적재가 통째로 롤백된다.</b>
 *
 * <p>MUST: 만들 수 없는 기간은 <b>버리고 센다</b>({@link Result#dropped()}).
 * {@link RawFinancialReport}의 생성자가 던지면 그 종목의 <b>다른 기간까지 전부</b> 못 담는다.
 */
final class SecEdgarFacts {

    /**
     * 정기보고서만 쓴다.
     *
     * <p>실측에서 {@code form}에 {@code 8-K} 9,688건과 {@code DEF 14A}가 섞여 있었다.
     * 그 팩트를 그대로 담으면 기간 계열이 오염된다 — 8-K는 회계기간을 대표하지 않는다.
     */
    private static final Set<String> PERIODIC_FORMS = Set.of(
            "10-K", "10-K/A", "10-Q", "10-Q/A", "20-F", "20-F/A", "40-F", "40-F/A");

    /** 중간보고서. {@link #ANNUAL_FORMS}를 이것의 여집합으로 유도한다. */
    private static final Set<String> INTERIM_FORMS = Set.of("10-Q", "10-Q/A");

    /**
     * 연차보고서. <b>회계연도 창은 이 form에서만 나온다.</b>
     *
     * <p>10-Q가 「Twelve Months Ended」 열을 실으면 그 팩트의 종료일이 <b>그 10-Q의 앵커
     * 종료일(분기말)과 같아져</b> 길이만 보는 판정을 통과한다. 그러면 <b>롤링 12개월 창이
     * 회계연도가 되고</b> 그 창을 기준으로 분기 번호가 매겨진다 — 실측(2026-09-04)으로
     * {@code AMZN}은 회계연도 창 69개 중 <b>52개가 10-Q에서</b> 왔고, 그중
     * {@code 2019-03-31}·{@code 2019-09-30}이 그 종목을 거절시킨 두 창이다.
     *
     * <p><b>MUST NOT: 목록을 손으로 박지 않는다.</b> {@link #PERIODIC_FORMS}에서 빼는 것이
     * 계약이다 — 박아 두면 나중에 그쪽에만 폼을 더한 사람이 그 제출자의 회계연도를 통째로
     * 지운다. 반대로 <b>중간보고 폼을 더할 때는 {@link #INTERIM_FORMS}에도 넣어야 한다</b> —
     * 안 넣으면 유도가 그것을 연간으로 분류해 이 결함을 되연다.
     */
    private static final Set<String> ANNUAL_FORMS = PERIODIC_FORMS.stream()
            .filter(form -> !INTERIM_FORMS.contains(form))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** 12개월로 보는 폭. 회사마다 52/53주라 며칠씩 다르다. */
    private static final int ANNUAL_MIN = 350;
    private static final int ANNUAL_MAX = 380;
    /** 3개월로 보는 폭. */
    private static final int QUARTER_MIN = 80;
    private static final int QUARTER_MAX = 100;

    /**
     * 회계연도 번호를 읽을 때 종료일에서 물러서는 일수. <b>회계연도는 달력 연도가 아니다.</b>
     *
     * <p>「12월 31일에 가장 가까운 요일」로 결산하는 회사는 종료일이 <b>12-25 … 01-04</b>를
     * 떠돈다. 그대로 {@code end.getYear()}를 쓰면 <b>연속한 두 회계연도가 같은 번호</b>를 받아
     * 라벨이 겹친다 — 실측으로 {@code ILMN}의 FY2022({@code ~2023-01-01})와
     * FY2023({@code ~2023-12-31})이 둘 다 {@code 2023/FY}였다.
     *
     * <p><b>4는 여유가 아니라 빈 구간이라서 고른 값이다.</b> 적재본과 거절된 145종목의
     * 종료일을 합쳐 세면 <b>01-03에서 끝나고 01-25에서 다시 시작</b>하며 그 사이가 비어 있다
     * (2026-09-04 실측 · {@code SecEdgarLabelDiffProbeLiveTest}가 소스에서도 같은 분포를
     * 냈다). 4는 관측된 최댓값 01-03과 이론적 최댓값 01-04를 덮고, 1월말 결산 무리의
     * 최솟값 01-25까지 21일 여유가 남는다.
     *
     * <p><b>MUST: 넓히기 전에 그 빈 구간이 아직 비어 있는지 다시 잰다.</b> 넓히면 1월말 결산
     * 회사(NVDA·WMT·RH)의 라벨이 <b>조용히 한 칸씩 밀린다</b> — 그 회사들은 스스로 그 해를
     * 「fiscal 2023」이라 부르고 지금 라벨이 맞다. 좁으면 그 종목이 계속 거절될 뿐이라
     * <b>방향은 좁은 쪽이 안전하다.</b>
     */
    private static final int NEW_YEAR_DRIFT_DAYS = 4;

    private SecEdgarFacts() {
    }

    /**
     * 재구성 결과와, 만들지 못해 버린 수, 그리고 <b>한 팩트도 못 얻은 컬럼</b>.
     *
     * <p>버린 수를 안 세면 결손 판정이 서지 않는다. 같은 이유로 <b>컬럼 축의 부재</b>도 낸다 —
     * 팩트 수준에서는 「없다」와 「우리가 못 만들었다」를 갈라 왔는데(P22 축①) <b>컬럼 수준에서는
     * 그 값이 쓰이고 사라지고 있었다.</b>
     */
    record Result(List<RawFinancialReport> reports, int dropped,
                  List<FinancialColumnAbsence> absences) {
    }

    /** 팩트 하나. 소스가 준 그대로이고 해석은 아래에서 한다. */
    private record Fact(String accn, LocalDate start, LocalDate end, BigDecimal value,
                        LocalDate filed, String form, int fiscalYear, String reportedPeriod,
                        String unit) {
    }

    /** 한 보고서가 자기 기간에 붙인 라벨. */
    private record Anchor(String accn, LocalDate end, LocalDate filed, String form,
                          int fiscalYear, String reportedPeriod) {
    }

    /** 저장 라벨. {@code financial_statement}의 키와 같은 축이다. */
    private record Label(int fiscalYear, String period) implements Comparable<Label> {
        @Override
        public int compareTo(Label o) {
            return fiscalYear != o.fiscalYear
                    ? Integer.compare(fiscalYear, o.fiscalYear) : period.compareTo(o.period);
        }
    }

    /** 라벨이 가리키는 실제 기간. 근사하지 않고 팩트의 값을 그대로 쓴다. */
    private record Window(LocalDate start, LocalDate end) {
    }

    private record Slot(String accn, LocalDate start, LocalDate end) {
    }

    /**
     * 재구성한다.
     *
     * @param facts          {@code companyfacts}의 {@code facts} 노드
     * @param fromFiscalYear 이 회계연도부터. 앞의 것은 만들지 않는다(버린 수에 안 든다)
     */
    static Result build(JsonNode facts, int fromFiscalYear) throws DataSourceException {
        Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn = collect(facts);
        Map<String, Anchor> anchors = anchorsOf(byColumn);
        NavigableMap<LocalDate, Window> fiscalYears = annualWindows(byColumn, anchors);
        Map<Label, Window> windows = new TreeMap<>();
        Map<Label, Anchor> labelAnchors = new LinkedHashMap<>();
        int dropped = labelPeriods(byColumn, anchors, fiscalYears, windows, labelAnchors,
                fromFiscalYear);

        List<RawFinancialReport> reports = new ArrayList<>();
        for (Map.Entry<Label, Window> e : windows.entrySet()) {
            Label label = e.getKey();
            if (label.fiscalYear() < fromFiscalYear) {
                continue;
            }
            reports.addAll(revisionsOf(label, e.getValue(), labelAnchors.get(label),
                    byColumn, anchors));
        }
        reports.addAll(deriveFourthQuarters(reports, windows, fromFiscalYear));
        reports.sort(Comparator.comparing(RawFinancialReport::periodEnd)
                .thenComparing(RawFinancialReport::fiscalPeriod)
                .thenComparing(RawFinancialReport::publishedAt));
        return new Result(reports, dropped, absencesOf(facts, byColumn));
    }

    // ---------- 1. 팩트를 컬럼별로 모은다 ----------

    /**
     * 컬럼별 팩트. 후보 태그를 <b>뒤에서 앞으로</b> 넣어 우선순위가 높은 태그가 이긴다.
     *
     * <p>후보를 하나만 보지 않는 이유는 <b>같은 회사가 연도에 따라 태그를 바꾸기 때문</b>이다 —
     * 「첫 후보가 있으면 그것만 본다」로 짜면 그 회사의 옛 연도가 통째로 빈다.
     */
    private static Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> collect(JsonNode facts) {
        Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn =
                new EnumMap<>(SecEdgarColumn.class);
        for (SecEdgarColumn column : SecEdgarColumn.values()) {
            // 후보 순서(우선순위)를 유지한다. 아래에서 기간마다 태그 하나를 고를 때 쓴다.
            Map<String, Map<Slot, Fact>> byTag = new LinkedHashMap<>();
            for (String candidate : column.candidates()) {
                boolean dei = candidate.startsWith("dei:");
                JsonNode tag = facts.path(dei ? "dei" : "us-gaap")
                        .path(dei ? candidate.substring(4) : candidate);
                Map<Slot, Fact> slots = new HashMap<>();
                readUnits(tag, column, slots);
                if (!slots.isEmpty()) {
                    byTag.put(candidate, slots);
                }
            }
            byColumn.put(column, byTag);
        }
        return byColumn;
    }

    /**
     * <b>한 팩트도 못 얻은 컬럼</b>과 그 까닭.
     *
     * <p>{@link #collect}의 {@code byTag}가 비는 원인이 둘이고 <b>사용자가 할 일이 다르다</b> —
     * 후보 태그가 소스에 이름으로도 없으면 그 회사가 그 항목을 공시하지 않는 것이고
     * ({@code ABSENT}), 태그는 있는데 {@code readUnits}의 세 필터(단위·폼·필드)가 다 걸러
     * 냈으면 <b>우리 쪽 한계</b>다({@code FILTERED}).
     *
     * <p><b>MUST: 둘을 갈라 낸다.</b> 하나로 뭉치면 화면이 <i>"소스에 그 항목이 없습니다"</i>를
     * 우리 필터 탓인 자리에도 말하게 되고, 그것이 {@code IndicatorUnavailabilityConfig}가
     * 금지한 <b>그럴듯한데 틀린 사유</b>다.
     *
     * <p><b>MUST NOT: {@code reportEvidence}가 거짓인 컬럼을 빼지 않는다.</b> 그 술어는
     * 「보고서가 그 기간을 보고했다」의 증거 자격이지 <b>값의 유무가 아니다</b> — 주식수·세율
     * 재료도 없으면 없는 것이고, 그 사실은 같은 자리에 담긴다.
     */
    private static List<FinancialColumnAbsence> absencesOf(
            JsonNode facts, Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn) {
        List<FinancialColumnAbsence> absences = new ArrayList<>();
        for (SecEdgarColumn column : SecEdgarColumn.values()) {
            if (!byColumn.getOrDefault(column, Map.of()).isEmpty()) {
                continue;
            }
            boolean tagExists = false;
            for (String candidate : column.candidates()) {
                boolean dei = candidate.startsWith("dei:");
                JsonNode tag = facts.path(dei ? "dei" : "us-gaap")
                        .path(dei ? candidate.substring(4) : candidate);
                if (!tag.isMissingNode() && tag.has("units")) {
                    tagExists = true;
                    break;
                }
            }
            absences.add(new FinancialColumnAbsence(column.columnName(),
                    tagExists ? FinancialColumnAbsence.State.FILTERED
                              : FinancialColumnAbsence.State.ABSENT));
        }
        return absences;
    }

    /** 한 컬럼의 모든 팩트. 태그를 가리지 않는 순회가 필요한 자리에서 쓴다. */
    private static Iterable<Fact> allFacts(Map<String, Map<Slot, Fact>> byTag) {
        List<Fact> all = new ArrayList<>();
        byTag.values().forEach(slots -> all.addAll(slots.values()));
        return all;
    }

    private static void readUnits(JsonNode tag, SecEdgarColumn column, Map<Slot, Fact> slots) {
        for (String unit : tag.path("units").propertyNames()) {
            if (!column.acceptsUnit(unit)) {
                continue;
            }
            for (JsonNode node : tag.path("units").path(unit)) {
                String form = node.path("form").asString("");
                if (!PERIODIC_FORMS.contains(form)) {
                    continue;
                }
                LocalDate end = date(node, "end");
                LocalDate filed = date(node, "filed");
                if (end == null || filed == null || !node.has("val")) {
                    continue;
                }
                LocalDate start = date(node, "start");
                Fact fact = new Fact(node.path("accn").asString(""), start, end,
                        new BigDecimal(node.path("val").asString("0")), filed, form,
                        node.path("fy").asInt(0), node.path("fp").asString(""), unit);
                slots.put(new Slot(fact.accn(), start, end), fact);
            }
        }
    }

    private static LocalDate date(JsonNode node, String field) {
        String raw = node.path(field).asString("");
        return raw.isBlank() ? null : LocalDate.parse(raw);
    }

    // ---------- 2. 보고서가 자기 기간에 붙인 라벨을 찾는다 ----------

    /**
     * 보고서별 앵커. 그 보고서의 <b>유량 팩트 중 종료일이 가장 늦은 것</b>이 자기 기간이다.
     *
     * <p>{@code fy}/{@code fp}를 팩트에서 직접 읽지 않고 여기를 거치는 것이 ①의 처방이다.
     *
     * <p><b>IMPORTANT — 유량만 본다.</b> 표지 항목인 주식수는 종료일이 <b>기말이 아니라 제출
     * 직전 날짜</b>라 기말보다 뒤다. 그것을 함께 세면 앵커의 종료일이 그 날짜로 밀리고,
     * 그러면 <b>어떤 유량 팩트도 앵커와 안 맞아 보고서가 한 건도 안 나온다.</b>
     * 실측에서 AAPL이 정확히 그렇게 0건이었다 — 에러 없이 「데이터가 없다」로 보였다.
     */
    private static Map<String, Anchor> anchorsOf(
            Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn) {
        Map<String, Anchor> anchors = new LinkedHashMap<>();
        for (Map.Entry<SecEdgarColumn, Map<String, Map<Slot, Fact>>> entry : byColumn.entrySet()) {
            if (entry.getKey().instant()) {
                continue;
            }
            for (Fact fact : allFacts(entry.getValue())) {
                anchors.merge(fact.accn(),
                        new Anchor(fact.accn(), fact.end(), fact.filed(), fact.form(),
                                fact.fiscalYear(), fact.reportedPeriod()),
                        (a, b) -> a.end().isAfter(b.end()) ? a : b);
            }
        }
        return anchors;
    }

    /**
     * 두 창이 <b>같은 기간을 달리 적은 것</b>인가.
     *
     * <p>종료일이 같고 시작일만 며칠 다른 쌍이 실재한다 — 52/53주 회계달력에서 「직전 기간
     * 종료일」과 「그 다음 날」 중 어느 쪽을 시작으로 적는지가 보고서마다 갈리기 때문이다.
     * 실측(2026-08-31)으로 미국 재무가 거절된 236종목 중 <b>93건</b>이 이 모양이고,
     * 시작일 차이가 <b>1일 54 · 2일 12 · 3~10일 27로 전부 10일 이내</b>다.
     *
     * <p><b>진짜로 다른 기간과는 자릿수가 다르다.</b> 분기 대 반기 대 연간은 시작일이 약 90·270일
     * 벌어지므로 이 관용에 걸릴 수 없다. 남는 143건은 <b>종료일 자체가 다르고</b>
     * ({@code JNJ 2023/Q3 → 2022-07-04~2022-10-02 vs 2023-07-03~2023-10-01}) 그것은
     * 이 관용이 아니라 {@link #labelOf}의 회계연도 배정이 답할 문제다.
     */
    private static boolean sameWindowStatedDifferently(Window a, Window b) {
        return a.end().equals(b.end())
                && Math.abs(java.time.temporal.ChronoUnit.DAYS.between(a.start(), b.start()))
                   <= SAME_PERIOD_START_TOLERANCE_DAYS;
    }

    /**
     * 앵커가 자기 종료일에 실은 <b>기간 길이별로</b> 라벨을 만든다.
     *
     * <p>회계연도는 앵커에서, 기간 구분은 길이에서 온다 — 그것이 ②의 처방이다.
     * <b>앵커가 연차보고서일 때만 회계연도 창이 된다</b>(ADR-0124).
     * 길이가 연도도 분기도 아닌 것(반기·9개월 누적)은 <b>버리고 센다.</b>
     */
    /**
     * 같은 종료일의 두 창을 같은 기간으로 볼 시작일 차이의 상한(일).
     *
     * <p>실측 분포의 최댓값이 10이고, 진짜로 다른 기간(분기/반기/연간)은 약 90일 이상
     * 벌어진다. <b>그 사이가 비어 있어서 고른 값이지 여유가 아니다.</b>
     */
    private static final int SAME_PERIOD_START_TOLERANCE_DAYS = 10;

    private static int labelPeriods(Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn,
                                    Map<String, Anchor> anchors,
                                    NavigableMap<LocalDate, Window> fiscalYears,
                                    Map<Label, Window> windows,
                                    Map<Label, Anchor> labelAnchors,
                                    int fromFiscalYear) throws DataSourceException {
        int dropped = 0;
        for (Map<String, Map<Slot, Fact>> byTag : byColumn.values()) {
            for (Fact fact : allFacts(byTag)) {
                if (fact.start() == null) {
                    continue;   // 잔액은 라벨을 만들지 않는다. 기간을 만드는 것은 유량이다.
                }
                Anchor anchor = anchors.get(fact.accn());
                if (anchor == null || !fact.end().equals(anchor.end())) {
                    continue;   // 비교표다. 그 기간은 자기 보고서가 라벨을 준다.
                }
                Label label = labelOf(fact, fiscalYears);
                if (label == null) {
                    dropped++;
                    continue;
                }
                Window window = new Window(fact.start(), fact.end());
                Window seen = windows.putIfAbsent(label, window);
                if (seen != null && sameWindowStatedDifferently(seen, window)) {
                    // 같은 기간을 시작일만 달리 적은 것이다. 좁은 쪽(늦은 시작일)으로 고정한다 —
                    // 고르는 규칙이 있어야 실행마다 같은 값이 나오고, period_start는 무효화
                    // 하한으로만 쓰여 며칠 차이가 결과를 바꾸지 않는다.
                    if (window.start().isAfter(seen.start())) {
                        windows.put(label, window);
                    }
                } else if (seen != null && !seen.equals(window)
                        && label.fiscalYear() >= fromFiscalYear) {
                    // 저장 키에 기간이 없다. 라벨이 겹치면 uq_fs_latest가 걸리는 것이 아니라
                    // 앞 기간의 최신 표시가 조용히 내려간다 — FinancialWriter가 개정 갈래에서
                    // UPDATE를 INSERT보다 먼저 돌리기 때문이다. 그래서 여기서 막는 것이
                    // 유일한 방어선이다.
                    //
                    // MUST: 판정을 적재 창 안으로 좁힌다. 지키려는 대상은 「저장되는 라벨」인데
                    // 이 순회는 파싱된 전 기간에 걸려 있어서, 아래 build의 fromFiscalYear
                    // 필터가 어차피 버릴 옛 연도의 충돌이 종목 전체를 죽였다. 실측(2026-09-04)
                    // 으로 거절된 145종목 중 116이 2023년 미만에서 죽었고 거기에 AMZN(2019)이
                    // 있다. 창 안의 충돌은 그대로 던진다 — 방어선을 낮추는 것이 아니라
                    // 지키려던 범위에 맞추는 것이다.
                    throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                            SecEdgarResponse.SOURCE_NAME,
                            "같은 라벨에 기간이 둘이다: %d/%s → %s~%s vs %s~%s".formatted(
                                    label.fiscalYear(), label.period(),
                                    seen.start(), seen.end(), window.start(), window.end()));
                }
                // 앵커가 여럿이면(원본 10-K와 10-K/A) 공시일이 가장 이른 것으로 고정한다.
                // 실행마다 같은 publishedAt이 나와야 FinancialWriter의 「공시일이 같으면
                // 아무것도 안 한다」 갈래에 안착한다.
                labelAnchors.merge(label, anchor,
                        (a, b) -> a.filed().isAfter(b.filed()) ? b : a);
            }
        }
        return dropped;
    }

    /**
     * 회계연도 창. 앵커가 자기 기간에 실은 <b>12개월 팩트</b>가 그 회사의 회계연도다.
     *
     * <p>이것을 소스의 {@code fy}에서 읽지 않는 이유는 그 필드가 <b>기간이 아니라 보고서</b>를
     * 가리키기 때문이다(실측: 라벨 70개가 전부 종료일을 둘 이상 갖는다).
     */
    private static NavigableMap<LocalDate, Window> annualWindows(
            Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn,
            Map<String, Anchor> anchors) {
        NavigableMap<LocalDate, Window> byEnd = new TreeMap<>();
        for (Map.Entry<SecEdgarColumn, Map<String, Map<Slot, Fact>>> entry : byColumn.entrySet()) {
            if (entry.getKey().instant()) {
                continue;
            }
            for (Fact fact : allFacts(entry.getValue())) {
                Anchor anchor = anchors.get(fact.accn());
                if (fact.start() == null || anchor == null || !fact.end().equals(anchor.end())) {
                    continue;
                }
                if (!ANNUAL_FORMS.contains(anchor.form())) {
                    continue;   // 10-Q의 「Twelve Months Ended」는 회계연도가 아니다.
                }
                long days = ChronoUnit.DAYS.between(fact.start(), fact.end());
                if (days >= ANNUAL_MIN && days <= ANNUAL_MAX) {
                    byEnd.putIfAbsent(fact.end(), new Window(fact.start(), fact.end()));
                }
            }
        }
        return byEnd;
    }

    /**
     * 기간에 붙일 라벨. <b>소스의 {@code fy}/{@code fp}를 쓰지 않는다.</b>
     *
     * <p>회계연도는 <b>그 기간을 품는 회계연도 창의 종료 연도</b>이고, 분기 번호는 그 창
     * 안에서의 <b>위치</b>다. 소스의 {@code fp}로 분기를 정하면 조용히 틀린다 — 실측에서
     * AT&amp;T의 같은 보고서가 7~9월 기간에 {@code Q2}를 붙였고, 1월 결산 회사(RH·NVDA)는
     * 연속한 두 회계연도에 같은 {@code fy}가 붙었다. 그 라벨로 저장하면 <b>기간이 다른 두
     * 행이 같은 키</b>를 받고, 저장 키에 기간이 없어 앞엣것의 최신 표시가 조용히 내려간다.
     *
     * <p>길이가 연도도 분기도 아닌 것(반기·9개월 누적)은 라벨을 만들지 않는다 — 버리고 센다.
     */
    private static Label labelOf(Fact fact, NavigableMap<LocalDate, Window> fiscalYears) {
        long days = ChronoUnit.DAYS.between(fact.start(), fact.end());
        boolean annual = days >= ANNUAL_MIN && days <= ANNUAL_MAX;
        if (!annual && (days < QUARTER_MIN || days > QUARTER_MAX)) {
            return null;
        }
        if (annual) {
            // MUST: 길이만 보고 FY를 만들지 않는다 — 인정된 회계연도 창이어야 한다.
            //
            // 이 갈래가 fiscalYears를 안 보던 것이 결함의 절반이다. annualWindows에만
            // form 게이트를 걸면 롤링 창이 여기서 여전히 Label(2019,"FY")를 만들어 진짜
            // FY2019와 충돌한다. 그리고 **null을 명시적으로 내야 한다** — 아래로 흘리면
            // round(364/91.31)=4가 되어 12개월 값이 Q4로 저장되고, deriveFourthQuarters가
            // 「소스가 Q4를 직접 줬다」로 읽어 유도를 포기하므로 예외도 경고도 안 난다.
            Window recognized = fiscalYears.get(fact.end());
            return recognized == null ? null : new Label(fiscalYearOf(recognized.end()), "FY");
        }
        Window year = enclosingYear(fact.end(), fiscalYears);
        if (year == null) {
            // 진행 중인 회계연도다. 사업보고서가 아직 없어 창이 없을 뿐이므로 직전 창에서
            // 투영한다 — 안 하면 **최근 분기 셋이 통째로 빠져** TTM이 최대 1년 뒤처진다.
            //
            // 투영이 안전한 이유는 라벨만 정하기 때문이다. 저장되는 기간은 언제나 팩트의
            // start/end 원본이고, 52/53주 결산으로 며칠이 달라져도 종료 연도와 분기 위치는
            // 같아서 사업보고서가 나온 뒤에도 같은 라벨이 나온다.
            year = projectYear(fact.end(), fiscalYears);
            if (year == null) {
                return null;
            }
        }
        long fromStart = ChronoUnit.DAYS.between(year.start(), fact.end());
        int quarter = (int) Math.min(4, Math.max(1, Math.round(fromStart / 91.31)));
        return new Label(fiscalYearOf(year.end()), "Q" + quarter);
    }

    /**
     * 그 종료일이 속한 회계연도의 번호. <b>{@code getYear()}를 직접 쓰지 않는다.</b>
     *
     * <p>근거와 상수의 경계는 {@link #NEW_YEAR_DRIFT_DAYS}에 있다. 이 시프트는 <b>1월 초
     * 날짜만 앞 해로 옮기고 나머지는 아무것도 안 건드린다</b> — 12-31은 그대로 그 해이고,
     * 1월말 결산(01-25 이후)도 그대로다.
     */
    private static int fiscalYearOf(LocalDate end) {
        return end.minusDays(NEW_YEAR_DRIFT_DAYS).getYear();
    }

    /**
     * 직전 회계연도 창에서 한 해씩 밀어 그 종료일을 품는 창을 만든다.
     *
     * <p>회계연도 창이 하나도 없으면 투영할 근거가 없어 {@code null}이다 — 그 회사는
     * 사업보고서를 낸 적이 없다.
     */
    private static Window projectYear(LocalDate end, NavigableMap<LocalDate, Window> fiscalYears) {
        if (fiscalYears.isEmpty()) {
            return null;
        }
        Window last = fiscalYears.lastEntry().getValue();
        if (end.isBefore(last.end())) {
            return null;   // 과거인데 창에 안 들어온다. 지어내지 않는다.
        }
        LocalDate start = last.end().plusDays(1);
        LocalDate close = last.end().plusYears(1);
        while (end.isAfter(close)) {
            start = close.plusDays(1);
            close = close.plusYears(1);
        }
        return new Window(start, close);
    }

    /** 그 종료일을 품는 회계연도 창. 없으면 null이다. */
    private static Window enclosingYear(LocalDate end, NavigableMap<LocalDate, Window> fiscalYears) {
        for (Window year : fiscalYears.values()) {
            if (!end.isBefore(year.start()) && !end.isAfter(year.end())) {
                return year;
            }
        }
        return null;
    }

    // ---------- 3. 정정을 접는다 ----------

    /**
     * 한 기간의 보고서들. <b>값이 실제로 달라진 것만</b> 낸다.
     *
     * <p>MUST: {@code publishedAt} 오름차순으로 낸다. {@code FinancialWriter}는 받은 순서대로
     * 처리하고 더 이른 판이 나중에 오면 <b>버리므로</b>, 뒤집으면 정정본이
     * {@code revision_no = 0}이 되고 원본이 사라진다.
     */
    private static List<RawFinancialReport> revisionsOf(Label label, Window window,
                                                        Anchor labelAnchor,
                                                        Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn,
                                                        Map<String, Anchor> anchors) {
        // MUST: 컬럼마다 태그를 **기간 단위로 하나** 고정한다.
        //
        // 같은 기간을 보고서마다 다른 후보 태그로 내는 일이 실재한다 — 현금이
        // CashAndCashEquivalentsAtCarryingValue와 CashCashEquivalentsRestrictedCash…로 갈리고
        // 그 둘은 값이 다르다. 보고서별로 태그를 고르면 **값이 아니라 태그가 바뀐 것**을
        // 정정으로 읽어, 값이 그대로인데도 판이 쌓인다(실측에서 AAPL 34건 중 절반이 그랬다).
        Map<SecEdgarColumn, String> tags = chooseTags(labelAnchor, window, byColumn);

        List<Anchor> reporters = new ArrayList<>();
        for (Anchor anchor : anchors.values()) {
            if (hasAnyValue(anchor, window, tags, byColumn)) {
                reporters.add(anchor);
            }
        }
        reporters.sort(Comparator.comparing(Anchor::filed).thenComparing(Anchor::accn));

        List<RawFinancialReport> out = new ArrayList<>();
        Map<SecEdgarColumn, BigDecimal> baseline = null;
        // MUST: 같은 날 제출된 판들을 **하나로 접는다.** 저장의 판 단위는 accn이 아니라
        // 공시일이다 — FinancialWriter는 publishedAt이 같으면 정정이 아니라 fillMissing으로
        // 보내 덮고, 덮을 때마다 지표를 무효화한다. 그래서 같은 날 제출된 accn 둘이 같은 창을
        // 다르게 보고하면 두 보고서가 한 키에 번갈아 써서 값이 A→B→A로 제자리를 돌고,
        // **매 실행 무효화만 남는다**(실측: 재적재 두 번째 실행에서 교정 340 · (종목,기간,공시일)
        // 41개가 정확히 2번씩 · 저장값 md5는 3회 동일 · 그 12종목의 최신일 지표가 통째로 빈다).
        // financial-refill은 매주 목요일 자동으로 돈다(ADR-0116).
        for (int i = 0; i < reporters.size(); ) {
            LocalDate filed = reporters.get(i).filed();
            int end = i;
            while (end < reporters.size() && reporters.get(end).filed().equals(filed)) {
                end++;
            }
            // 묶음 안에서는 accn 오름차순으로 이어받는다 — 뒤 accn이 이기고, 앞 accn만 실은
            // 컬럼은 살아남는다. 지금은 앞 accn이 conflicts 거짓으로 버려질 때 그 컬럼이
            // 통째로 사라지므로, **접기는 값을 줄이지 않고 늘릴 수 있다.**
            Map<SecEdgarColumn, BigDecimal> values = null;
            Anchor reporter = null;
            String currency = null;
            for (int k = i; k < end; k++) {
                Anchor candidate = reporters.get(k);
                String candidateCurrency = currencyOf(candidate, window, tags, byColumn);
                if (candidateCurrency == null) {
                    // 통화를 못 읽는 판은 지금도 통째로 건너뛴다. 묶음에 섞으면 그 판의 값이
                    // 통화 판정 없이 저장되므로 같은 술어를 유지한다(ADR-0094).
                    continue;
                }
                values = carryForward(values, valuesOf(candidate, labelAnchor, label, window, tags, byColumn));
                reporter = candidate;
                currency = candidateCurrency;
            }
            i = end;
            if (reporter == null) {
                continue;
            }
            // 값이 하나도 없는 판은 보고서가 아니다. 담으면 공시일만 늦은 빈 정정본이 되어
            // 그 종목의 지표를 지운다. 주식수는 라벨 앵커에서 오므로 판정에서 뺀다 —
            // 그것만 있는 판은 「그 기간을 보고한 것」이 아니다.
            boolean empty = true;
            for (SecEdgarColumn column : SecEdgarColumn.values()) {
                // MUST: hasAnyValue와 같은 술어를 쓴다. 갈리면 세금만 든 판이 reporter는 아닌데
                // empty도 아니게 되어, baseline을 세우고 뒤 10-K를 버리는 그 자리가 다시 열린다.
                if (column.reportEvidence() && values.get(column) != null) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
                continue;
            }
            // MUST: 「달라졌는가」를 둘 다 값이 있는 컬럼으로만 판정한다.
            //
            // 뒤 보고서는 비교표라 컬럼이 대개 더 적다. null이 된 것을 「달라졌다」로 읽으면
            // 값이 그대로인데도 정정본이 쌓이고, 그 판정은 그 종목의 지표를 보존 창 전체에서
            // 지운다. 실측에서 한 기간을 보고서 112개가 다시 실었고 값이 실제로 달라진 것은
            // 5개뿐이었다 — 나머지 107개가 전부 가짜 정정이 될 뻔한 자리다.
            //
            // 그리고 **앞 판의 값을 이어받는다**(carryForward). 그러지 않으면 뒤 판이 안 실은
            // 컬럼이 null로 저장되어, 정정 하나가 그 기간의 손익을 통째로 지운다.
            Map<SecEdgarColumn, BigDecimal> carried = carryForward(baseline, values);
            if (baseline != null && !conflicts(baseline, carried)) {
                continue;
            }
            baseline = carried;
            out.add(toReport(label, window, reporter, currency, carried));
        }
        return out;
    }

    /**
     * 이 기간에 쓸 태그. 후보 순서대로 보고 <b>그 기간에 값이 있는 첫 태그</b>를 고른다.
     *
     * <p>어느 보고서가 냈는지를 보지 않는 것이 핵심이다 — 보고서별로 고르면 같은 기간의
     * 판마다 태그가 갈려 <b>값이 아니라 태그가 바뀐 것</b>이 정정이 된다.
     */
    private static Map<SecEdgarColumn, String> chooseTags(
            Anchor labelAnchor, Window window,
            Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn) {
        Map<SecEdgarColumn, String> chosen = new EnumMap<>(SecEdgarColumn.class);
        for (SecEdgarColumn column : SecEdgarColumn.values()) {
            // 1순위 — 그 기간을 자기 기간으로 낸 보고서가 실제로 쓴 태그.
            //
            // 아무 보고서나 가진 태그를 고르면, 앵커가 다른 태그를 썼을 때 그 값이 **null이
            // 된다.** 실측에서 AT&T의 FY2023·2024 영업활동현금흐름이 그렇게 사라졌다 —
            // 뒤 보고서가 다른 후보로 실은 것을 골랐고 앵커에는 그 태그가 없었다.
            String tag = coveringTag(column, window, byColumn, labelAnchor);
            if (tag == null) {
                // 2순위 — 앵커가 그 계정을 아예 안 냈다. 그때만 보고서를 가리지 않는다.
                tag = coveringTag(column, window, byColumn, null);
            }
            if (tag != null) {
                chosen.put(column, tag);
            }
        }
        return chosen;
    }

    /**
     * 그 기간을 덮는 팩트가 있는 첫 후보 태그. {@code accn}을 주면 그 보고서의 팩트만 본다.
     *
     * <p>후보 순서가 우선순위다 — <b>어느 보고서가 냈는지로 순서를 바꾸지 않는다.</b>
     * 보고서별로 우선순위가 갈리면 같은 기간의 판마다 태그가 달라져 값이 아니라 태그가
     * 바뀐 것이 정정이 된다.
     */
    /**
     * 이 기간을 덮는 태그 하나. <b>선택 규칙은 컬럼이 정한다</b>({@code SecEdgarColumn.Selection}).
     *
     * <p><b>MUST: 최댓값 판정을 이 메서드 안에 둔다.</b> 여기는 {@code chooseTags}가 앵커로
     * 한정해 부르는 <b>1순위 층</b>이고, 그래야 고른 태그가 앵커에 반드시 존재한다. 2순위
     * (앵커 무시)에서 최댓값을 고르면 <b>앵커가 안 쓴 태그가 뽑혀 {@code factFor}가 null을
     * 낸다</b> — AT&amp;T의 FY2023·2024 영업활동현금흐름이 그렇게 사라진 전례가
     * {@code chooseTags}의 주석에 있다.
     */
    private static String coveringTag(SecEdgarColumn column, Window window,
                                      Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn,
                                      Anchor only) {
        String first = null;
        String largest = null;
        BigDecimal largestValue = null;
        for (Map.Entry<String, Map<Slot, Fact>> entry : byColumn.get(column).entrySet()) {
            for (Fact fact : entry.getValue().values()) {
                if (only != null && !fact.accn().equals(only.accn())) {
                    continue;
                }
                boolean covers = column.anchoredInstant()
                        || (column.instant()
                        ? fact.end().equals(window.end())
                        : fact.end().equals(window.end()) && window.start().equals(fact.start()));
                if (!covers) {
                    continue;
                }
                if (!column.picksLargest()) {
                    return entry.getKey();
                }
                // 후보 순서가 곧 우선순위이므로 첫 매칭을 마지막 수단으로 들고 있는다.
                if (first == null) {
                    first = entry.getKey();
                }
                if (column.competesForLargest(entry.getKey()) && fact.value() != null
                        && (largestValue == null || fact.value().compareTo(largestValue) > 0)) {
                    largestValue = fact.value();
                    largest = entry.getKey();
                }
                break;
            }
        }
        // 경쟁 후보가 하나도 안 덮으면 마지막 수단 태그가 남는다.
        return largest != null ? largest : first;
    }

    /**
     * 앞 판이 낸 값을 이어받는다 — <b>뒤 판이 안 실은 컬럼을 null로 덮지 않는다.</b>
     *
     * <p><b>실측(AAON FY2022)</b>: 10-K(2023-02-27)가 매출 888.8M · 영업이익 126.8M을 냈는데,
     * 8개월 뒤 10-Q(2023-11-06)가 <b>비교 재무상태표만</b> 싣고 {@code retained_earnings}를
     * 461,765,000 → 461,657,000으로 정정했다. 그 <b>한 컬럼</b> 때문에 판이 새로 나갔고
     * 손익이 통째로 NULL인 행이 {@code is_latest}가 됐다. {@code US_TOP1000}의 FY 6,568행에서
     * 그렇게 사라진 것이 <b>영업이익 373 · 매출 505 · 총자산 543</b>이다.
     *
     * <p><b>MUST: 라벨 하나 안에서만 겹친다.</b> 이 메서드는 {@code revisionsOf}가 한 기간의
     * 판들을 훑는 루프 안에서만 불린다 — 기간을 넘어 겹치면 낡은 해의 값이 번진다.
     *
     * <p><b>MUST: {@code empty} 판정은 겹치기 전 값으로 한다.</b> 겹친 뒤에 보면 그 기간을
     * 아무것도 보고하지 않은 판이 <b>상속만으로 「보고자」</b>가 된다.
     *
     * <p><b>정정 판정이 헐거워지지 않는다 — 오히려 촘촘해진다.</b> {@link #conflicts}는 양쪽 다
     * 값이 있는 컬럼만 보므로, 이어받은 컬럼은 {@code baseline}과 같아져 <b>차이를 만들지
     * 않는다.</b> 다만 {@code baseline}의 non-null이 누적되므로 <b>비교 대상이 늘고</b>, 그만큼
     * 정정으로 판정되는 경우가 <b>늘거나 같다.</b> 가짜 정정 접기(실측 112 중 107)가 그대로인지는
     * <b>논증이 아니라 측정</b>이며 {@code SecEdgarLiveTest}의 멱등 검사가 그것을 본다.
     */
    private static Map<SecEdgarColumn, BigDecimal> carryForward(
            Map<SecEdgarColumn, BigDecimal> baseline, Map<SecEdgarColumn, BigDecimal> values) {
        if (baseline == null) {
            return values;
        }
        Map<SecEdgarColumn, BigDecimal> merged = new EnumMap<>(SecEdgarColumn.class);
        merged.putAll(baseline);
        for (SecEdgarColumn column : SecEdgarColumn.values()) {
            BigDecimal value = values.get(column);
            if (value != null) {
                merged.put(column, value);
            }
        }
        return merged;
    }

    /** 둘 다 값이 있는 컬럼 중 하나라도 다른가. 한쪽이 null인 것은 다름이 아니다. */
    private static boolean conflicts(Map<SecEdgarColumn, BigDecimal> a,
                                     Map<SecEdgarColumn, BigDecimal> b) {
        for (SecEdgarColumn column : SecEdgarColumn.values()) {
            BigDecimal x = a.get(column);
            BigDecimal y = b.get(column);
            // MUST: BigDecimal.compareTo로 본다. equals는 scale이 달라 같은 수를 다르다고 읽고,
            // 그 오판 하나가 그 종목의 지표를 보존 창 전체에서 지운다.
            if (x != null && y != null && x.compareTo(y) != 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyValue(Anchor anchor, Window window,
                                       Map<SecEdgarColumn, String> tags,
                                       Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn) {
        for (SecEdgarColumn column : SecEdgarColumn.values()) {
            // MUST NOT: 주식수로 「그 기간을 보고했다」를 판정하지 않는다. 그 팩트는 기간을
            // 묻지 않고 아무 보고서에서나 받으므로, 세면 **모든 보고서가 모든 기간의
            // 보고자**가 되어 값 하나 없는 판이 라벨마다 쌓인다(실측에서 FY 하나에 10판).
            if (column.reportEvidence()
                    && factFor(anchor, column, window, tags, byColumn) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 그 보고서가 그 기간에 대해 낸 값.
     *
     * <p>유량은 (시작, 종료)가 정확히 맞는 것만, 잔액은 종료일이 맞는 것만 쓴다 —
     * 「종료일만 같으면 된다」로 두면 <b>3개월 매출에 9개월 현금흐름</b>이 한 행에 앉는다.
     */
    private static Fact factFor(Anchor anchor, SecEdgarColumn column, Window window,
                                Map<SecEdgarColumn, String> tags,
                                Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn) {
        String tag = tags.get(column);
        if (tag == null) {
            return null;
        }
        Map<Slot, Fact> slots = byColumn.get(column).get(tag);
        if (column.anchoredInstant()) {
            // 표지 항목이라 종료일이 기말이 아니다. 같은 보고서에서 왔으면 받는다 —
            // 이것을 종료일로 거르면 미국 PER·PBR의 분모가 영영 빈다(ADR-0013).
            return slots.values().stream()
                    .filter(f -> f.accn().equals(anchor.accn()))
                    .max(Comparator.comparing(Fact::end))
                    .orElse(null);
        }
        return column.instant()
                ? slots.get(new Slot(anchor.accn(), null, window.end()))
                : slots.get(new Slot(anchor.accn(), window.start(), window.end()));
    }

    /**
     * 그 판의 값들.
     *
     * <p>주식수만 <b>라벨의 앵커</b>에서 가져온다 — 표지 항목이라 보고서마다 값이 다르고,
     * 판마다 다르게 담으면 <b>값이 그대로인데도 매번 정정</b>이 된다. 그 기간을 자기 기간으로
     * 낸 보고서가 붙인 수가 그 기간의 주식수다.
     */
    private static Map<SecEdgarColumn, BigDecimal> valuesOf(
            Anchor anchor, Anchor labelAnchor, Label label, Window window,
            Map<SecEdgarColumn, String> tags,
            Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn) {
        Map<SecEdgarColumn, BigDecimal> values = new EnumMap<>(SecEdgarColumn.class);
        for (SecEdgarColumn column : SecEdgarColumn.values()) {
            if (column.annualOnly() && !"FY".equals(label.period())) {
                // 현금흐름·자본적지출은 연간만 담는다. 분기 커버리지가 고르지 않고
                // (실측 손익 208 대 현금흐름 28), 잉여현금흐름이 둘의 차이라
                // 한쪽만 분기에 값이 있으면 기간이 다른 두 수를 빼게 된다.
                values.put(column, null);
                continue;
            }
            Fact fact = factFor(
                    column.anchoredInstant() && labelAnchor != null ? labelAnchor : anchor,
                    column, window, tags, byColumn);
            values.put(column, fact == null ? null : fact.value());
        }
        return values;
    }

    /**
     * 행의 통화. <b>3자 ISO 키에서만 뽑는다.</b>
     *
     * <p>{@code units} 키에는 {@code pure}·{@code shares}·{@code segment}가 섞여 있고
     * {@code currency_code}가 {@code character(3)}다. 그대로 담으면 그 종목의 적재가
     * 통째로 롤백된다. 뽑을 것이 없으면 그 판을 내지 않는다 — 지어내지 않는다.
     */
    private static String currencyOf(Anchor anchor, Window window,
                                     Map<SecEdgarColumn, String> tags,
                                     Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn) {
        for (SecEdgarColumn column : SecEdgarColumn.values()) {
            if (!column.monetary()) {
                continue;
            }
            Fact fact = factFor(anchor, column, window, tags, byColumn);
            if (fact != null && fact.unit().length() == 3) {
                return fact.unit();
            }
        }
        return null;
    }

    private static RawFinancialReport toReport(Label label, Window window, Anchor anchor,
                                               String currency,
                                               Map<SecEdgarColumn, BigDecimal> v) {
        return new RawFinancialReport(
                label.fiscalYear(), label.period(), window.start(), window.end(), anchor.filed(),
                // 정정 추적의 근거. DART 쪽이 rcept_no 컬럼이 없어 못 하던 것이다.
                "%s:%s".formatted(anchor.form(), anchor.accn()), currency,
                v.get(SecEdgarColumn.REVENUE), v.get(SecEdgarColumn.OPERATING_INCOME),
                v.get(SecEdgarColumn.NET_INCOME), v.get(SecEdgarColumn.TOTAL_ASSETS),
                v.get(SecEdgarColumn.TOTAL_EQUITY), v.get(SecEdgarColumn.TOTAL_LIABILITIES),
                v.get(SecEdgarColumn.CURRENT_ASSETS), v.get(SecEdgarColumn.CURRENT_LIABILITIES),
                v.get(SecEdgarColumn.RETAINED_EARNINGS), v.get(SecEdgarColumn.OPERATING_CASH_FLOW),
                v.get(SecEdgarColumn.SHARES_OUTSTANDING), v.get(SecEdgarColumn.EPS),
                // bps는 소스가 주지 않는다. 자본총계/주식수로 지표 계층이 계산한다(spec §7).
                null,
                v.get(SecEdgarColumn.COST_OF_SALES), v.get(SecEdgarColumn.CASH_AND_EQUIVALENTS),
                v.get(SecEdgarColumn.CAPEX), v.get(SecEdgarColumn.INVENTORIES),
                // 지배주주 귀속 순이익을 따로 담지 않는다. 미국은 net_income이 이미
                // `NetIncomeLoss`(us-gaap 정의상 지배주주 귀속)를 첫 후보로 쓰고 있어
                // (SecEdgarColumn.NET_INCOME) 폴백이 곧 옳은 값을 쓴다. 여기서 갈라 담으면
                // 이미 적재된 행은 옛 정의, 새 행은 새 정의인 혼합물이 된다 —
                // fillMissing이 stored == null일 때만 채우므로(ADR-0056) 되돌릴 길이 없다.
                null,
                // 영업이익 유도의 재료 여덟(V40). 여기서 계산하지 않는다 — 원자료만 담고
                // 판단은 지표 계층이 한다(FinancialMath.operatingIncomeOf).
                v.get(SecEdgarColumn.INTEREST_AND_DIVIDEND_INCOME),
                v.get(SecEdgarColumn.NONINTEREST_INCOME),
                v.get(SecEdgarColumn.INTEREST_EXPENSE),
                v.get(SecEdgarColumn.NONINTEREST_EXPENSE),
                v.get(SecEdgarColumn.BENEFITS_LOSSES_AND_EXPENSES),
                v.get(SecEdgarColumn.GROSS_PROFIT),
                v.get(SecEdgarColumn.OPERATING_EXPENSES),
                v.get(SecEdgarColumn.COSTS_AND_EXPENSES),
                v.get(SecEdgarColumn.INCOME_TAX_EXPENSE),
                v.get(SecEdgarColumn.PROFIT_BEFORE_TAX));
    }

    // ---------- 4. Q4를 유도한다 ----------

    /**
     * Q4 = 사업보고서 − (Q1+Q2+Q3). <b>선택이 아니라 TTM이 요구한다.</b>
     *
     * <p>{@code IndicatorDataReader}가 분기 TTM을
     * {@code fiscal_period <> 'FY' ORDER BY period_end DESC LIMIT 4}로 뽑는다. 4분기 보고서가
     * 없으므로 Q4 행이 없으면 그 넷이 <b>Q3·Q2·Q1(N) + Q3(N−1)</b>이 되어 다섯 분기에 걸친
     * 구멍 난 합이 된다 — 값이 그럴듯하고 에러가 없다.
     *
     * <p>IMPORTANT — <b>손익만 차감한다.</b> 재무상태표는 시점 잔액이라 차감하면 자산총계가
     * 음수가 된다. 잔액은 FY 값을 그대로 쓴다({@code DartFinancialAdapter}와 같은 형태다).
     *
     * <p>소스가 Q4를 직접 준 회계연도는 건너뛴다 — 사업보고서가 3개월을 함께 싣는 경우가 있다.
     *
     * <p>IMPORTANT — <b>통화가 갈리면 손익을 내지 않는다</b>(ADR-0110). 회사가 그 해에 보고
     * 통화를 갈아타면 이 뺄셈이 <b>통화를 건너뛴다</b>. 실측으로 MLR의 2025 Q4 매출이
     * FY 790,271,000 USD에서 Q1 225,651,000 <b>GBP</b>를 뺀 {@code 171,918,000}으로 저장돼
     * 있었고, <b>그 수는 결손처럼 보이지도 않는다.</b> 오염 11행 중 아홉이 이 자리에서 났다.
     * <b>잔액은 그대로 낸다</b> — FY의 연말 잔액이라 뺄셈을 거치지 않는다.
     */
    private static List<RawFinancialReport> deriveFourthQuarters(
            List<RawFinancialReport> existing, Map<Label, Window> windows, int fromFiscalYear) {
        Map<Label, RawFinancialReport> latest = new LinkedHashMap<>();
        for (RawFinancialReport report : existing) {
            latest.merge(new Label(report.fiscalYear(), report.fiscalPeriod()), report,
                    (a, b) -> a.publishedAt().isAfter(b.publishedAt()) ? a : b);
        }

        List<RawFinancialReport> derived = new ArrayList<>();
        for (Map.Entry<Label, RawFinancialReport> e : latest.entrySet()) {
            Label label = e.getKey();
            if (!"FY".equals(label.period()) || label.fiscalYear() < fromFiscalYear) {
                continue;
            }
            RawFinancialReport fy = e.getValue();
            Label q4 = new Label(label.fiscalYear(), "Q4");
            if (latest.containsKey(q4)) {
                continue;   // 사업보고서가 3개월을 함께 실었다. 유도하지 않는다.
            }

            // MUST: 라벨이 아니라 **기간 포함관계**로 분기를 고른다.
            //
            // 1월 결산 회사는 소스가 연간과 그 다음 회계연도의 분기에 **같은 회계연도**를
            // 붙인다(실측: RH의 FY가 2023-01-28에 끝나는데 같은 라벨의 Q3가 2023-10-29에
            // 끝난다). 라벨로 묶으면 시작이 끝보다 뒤인 행을 만들어 그 종목의 적재가
            // 통째로 죽는다 — 실제로 그렇게 났다.
            List<RawFinancialReport> inside = new ArrayList<>();
            for (RawFinancialReport candidate : latest.values()) {
                if (!"FY".equals(candidate.fiscalPeriod())
                        && !candidate.periodStart().isBefore(fy.periodStart())
                        && !candidate.periodEnd().isAfter(fy.periodEnd())) {
                    inside.add(candidate);
                }
            }
            inside.sort(Comparator.comparing(RawFinancialReport::periodEnd));
            if (inside.size() != 3 || !inside.get(0).periodStart().equals(fy.periodStart())) {
                continue;
            }
            RawFinancialReport q3 = inside.get(2);
            LocalDate start = q3.periodEnd().plusDays(1);
            if (!start.isBefore(fy.periodEnd())) {
                continue;
            }
            RawFinancialReport q1 = inside.get(0);
            RawFinancialReport q2 = inside.get(1);
            boolean subtractable = sameCurrency(fy, q1, q2, q3);
            derived.add(new RawFinancialReport(
                    label.fiscalYear(), "Q4", start, fy.periodEnd(), fy.publishedAt(),
                    fy.reportCode(), fy.currencyCode(),
                    subtractable ? minus(fy.revenue(), q1.revenue(), q2.revenue(), q3.revenue()) : null,
                    subtractable ? minus(fy.operatingIncome(), q1.operatingIncome(), q2.operatingIncome(),
                            q3.operatingIncome()) : null,
                    subtractable ? minus(fy.netIncome(), q1.netIncome(), q2.netIncome(), q3.netIncome()) : null,
                    // 잔액은 차감하지 않는다 — 연말 잔액이 곧 Q4 말 잔액이다.
                    fy.totalAssets(), fy.totalEquity(), fy.totalLiabilities(),
                    fy.currentAssets(), fy.currentLiabilities(), fy.retainedEarnings(),
                    // 현금흐름·capex는 연간만 담으므로 Q4에도 담지 않는다.
                    null, fy.sharesOutstanding(), null, null,
                    subtractable ? minus(fy.costOfSales(), q1.costOfSales(), q2.costOfSales(), q3.costOfSales()) : null,
                    fy.cashAndEquivalents(), null, fy.inventories(),
                    // 위 toReport와 같은 이유로 담지 않는다.
                    null,
                    // 유도 재료 여덟은 전부 유량이라 차감한다. 하나라도 없으면 minus가
                    // null을 내므로 Q4 영업이익이 지어지지 않는다 — 그것이 옳다.
                    subtractable ? minus(fy.interestAndDividendIncome(), q1.interestAndDividendIncome(),
                            q2.interestAndDividendIncome(), q3.interestAndDividendIncome()) : null,
                    subtractable ? minus(fy.noninterestIncome(), q1.noninterestIncome(),
                            q2.noninterestIncome(), q3.noninterestIncome()) : null,
                    subtractable ? minus(fy.interestExpense(), q1.interestExpense(),
                            q2.interestExpense(), q3.interestExpense()) : null,
                    subtractable ? minus(fy.noninterestExpense(), q1.noninterestExpense(),
                            q2.noninterestExpense(), q3.noninterestExpense()) : null,
                    subtractable ? minus(fy.benefitsLossesAndExpenses(), q1.benefitsLossesAndExpenses(),
                            q2.benefitsLossesAndExpenses(), q3.benefitsLossesAndExpenses()) : null,
                    subtractable ? minus(fy.grossProfit(), q1.grossProfit(), q2.grossProfit(), q3.grossProfit()) : null,
                    subtractable ? minus(fy.operatingExpenses(), q1.operatingExpenses(),
                            q2.operatingExpenses(), q3.operatingExpenses()) : null,
                    subtractable ? minus(fy.costsAndExpenses(), q1.costsAndExpenses(),
                            q2.costsAndExpenses(), q3.costsAndExpenses()) : null,
                    // 세율 재료는 annualOnly라 분기 판에 값이 없다 — 차감할 것이 없다.
                    null, null));
        }
        return derived;
    }

    /**
     * 넷이 <b>한 통화로 일치하는가.</b> 아니면 뺄셈이 통화를 건너뛴다(ADR-0110).
     *
     * <p>{@code currencyCode}가 {@code NOT NULL}이라 「모른다」가 원리적으로 없다
     * (ADR-0094 §규칙) — NULL 방어를 적으면 없는 방어선을 보증하는 문장이 된다.
     */
    private static boolean sameCurrency(RawFinancialReport fy, RawFinancialReport... quarters) {
        for (RawFinancialReport quarter : quarters) {
            if (!fy.currencyCode().equals(quarter.currencyCode())) {
                return false;
            }
        }
        return true;
    }

    /** 하나라도 없으면 null이다. 0으로 대체하면 조용히 틀린 값이 저장된다. */
    private static BigDecimal minus(BigDecimal total, BigDecimal... parts) {
        if (total == null) {
            return null;
        }
        BigDecimal result = total;
        for (BigDecimal part : parts) {
            if (part == null) {
                return null;
            }
            result = result.subtract(part);
        }
        return result;
    }
}
