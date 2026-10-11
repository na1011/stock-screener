package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FinancialColumnAbsence;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    private static final Logger log = LoggerFactory.getLogger(SecEdgarFacts.class);

    /**
     * 정기보고서만 쓴다.
     *
     * <p>실측에서 {@code form}에 {@code 8-K} 9,688건과 {@code DEF 14A}가 섞여 있었다.
     * 그 팩트를 그대로 담으면 기간 계열이 오염된다 — 8-K는 회계기간을 대표하지 않는다.
     */
    static final Set<String> PERIODIC_FORMS = Set.of(
            "10-K", "10-K/A", "10-Q", "10-Q/A", "20-F", "20-F/A", "40-F", "40-F/A",
            "6-K", "6-K/A");

    /**
     * 중간보고서. {@link #ANNUAL_FORMS}를 이것의 여집합으로 유도한다.
     *
     * <p><b>{@code 6-K}가 여기 있는 것이 이 목록의 요점이다.</b> 외국 발행인은 분기 의무가
     * 없어 중간보고를 {@code 6-K}로 내는데, {@link #PERIODIC_FORMS}에만 더하면 여집합 유도가
     * 그것을 <b>연차로 분류한다</b> — 실측으로 {@code TSEM}의 6-K 앵커 종료일에 연차 길이
     * span이 14개 있어 <b>거짓 회계연도 창 14개</b>가 생긴다(ADR-0164).
     */
    private static final Set<String> INTERIM_FORMS =
            Set.of("10-Q", "10-Q/A", "6-K", "6-K/A");

    /**
     * 이 어댑터가 담는 유일한 통화. <b>미국 시장의 통화다.</b>
     *
     * <p>다른 통화로 보고한 판을 담지 않는 이유는 <b>담아도 읽히지 않고 종목을 끄기 때문</b>이다.
     * {@code IndicatorDataReader}의 {@code CURRENCY_GATE}는 <b>종목 단위이고 기간 제한이 없어</b>
     * 한 행만 달라도 그 종목의 재무를 한 행도 읽지 않는다(ADR-0094 — 환율 시계열이 없어
     * 환산하지 않는 것이 그 결정이다). 실측으로 {@code SMMT}의 6-K 팩트가 <b>{@code GBP}
     * 1,545건 · USD 0건</b>(2018~2020)이라, 폼만 더하면 <b>현 편입 종목의 93코드가 통째로
     * 꺼진다</b> — 그리고 ADR-0080의 「입력을 못 읽었다」로 접혀 <b>옛 값이 얼어붙는다.</b>
     *
     * <p><b>이 필터는 예방적이다 — 이미 저장된 비-USD 행을 지우지 않는다.</b> 적재는
     * {@code FILL_MISSING}이라 행을 삭제하지 않으므로, 통화가 섞인 기존 203종목의 게이트는
     * 그대로 닫혀 있다. 그것을 여는 것은 환율 소스를 들이는 별개의 축이다.
     */
    private static final String MARKET_CURRENCY = "USD";

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
    /**
     * 3개월로 보는 폭.
     *
     * <p><b>상한이 100이던 동안 4-5-4 소매 회계력의 16주 분기가 통째로 버려졌다.</b>
     * {@code KR}(Kroger)은 1분기가 <b>16주</b>(우리 셈으로 111일)인데 그 길이가 상한 밖이라
     * {@link #labelOf}가 {@code null}을 냈고, 그래서 <b>12개 회계연도 전부 Q1이 없다.</b>
     * Q1이 없으니 {@link #deriveFourthQuarters}의 {@code inside.size() != 3}에 걸려
     * <b>FY2022부터는 Q4도 없다</b> — 분기가 연 둘뿐이다. 그 결과 TTM이 <b>17개월 창에
     * 5개월 구멍 둘</b>이 되어 저장된 지표가 그대로 틀렸다(2026-09-14 기준
     * {@code KR} PER <b>103.34</b> · NET_MARGIN <b>0.276%</b> · 실제 FY 순이익률 0.69%).
     * <b>원장은 정직하게 준다</b> — 10-Q가 {@code 2025-02-02~2025-05-24}를 <b>67태그</b>로 낸다.
     *
     * <p><b>MUST NOT: 상한을 늘려서 열지 않는다 — 구간을 따로 둔다.</b> 101~110일이
     * <b>전부 재편·IPO stub</b>이라 상한을 112로 올리면 그것들이 분기가 되고, 같은 종료일의
     * 진짜 분기 창과 <b>같은 라벨에 기간을 둘</b> 만들어 {@link #labelPeriods}가 예외를 던진다.
     * 드라이런에서 {@code AM}({@code 2019-03-13~2019-06-30}, 109일 · Antero Midstream 재편)이
     * 정확히 그렇게 <b>종목을 죽였다.</b>
     *
     * <p><b>구간의 근거는 실측 분포다</b>(팩트 캐시 1,003종목 · accn 54,785 ·
     * 「자기 기간」 전수). 101~132일 구간을 열어 보면:
     * <ul>
     *   <li>104 {@code RPRX} · 105 {@code BROS} · 108 {@code IONQ SYM} · 109 {@code AM SYM} ·
     *       110 {@code LGN TKO} — <b>전부 재편·IPO stub</b>이다.
     *   <li><b>111일(16주) 62건 · 9종목</b> — {@code KR ACI CAVA}는 <b>1분기</b>,
     *       {@code AZO COST DPZ YUM PEP}는 <b>4분기</b>. 둘 다 진짜 분기다.
     *   <li>114 {@code SCI} · 121 {@code YUMC MOS SHW TLN} · 125 {@code ASTS SAIC} —
     *       <b>4개월 stub·결산월 변경 전환기</b>. 126~132일은 <b>0건</b>이다.
     * </ul>
     *
     * <p><b>MUST NOT: 17주(118일)까지 넓히지 않는다.</b> 그 사이의 114일({@code SCI}
     * {@code 2018-06-08~09-30}, 태그 2개)이 같은 종료일의 91일 창(태그 12개)과 충돌해 위와 같이
     * 죽는다. 그리고 넓혀서 얻을 것이 없다 — 118일 라벨은 {@code AZO} 2019 · {@code COST} 2017 ·
     * {@code DPZ} 2015 전부 <b>이미 유도로 채워져 있다</b>(스윕에서 {@code derived=true}).
     */
    private static final int QUARTER_MIN = 80;
    private static final int QUARTER_MAX = 100;
    /** 4-5-4 회계력의 16주 분기. 위 주석대로 <b>101~110을 비워 두려고</b> 구간을 따로 둔다. */
    private static final int LONG_QUARTER_MIN = 111;
    private static final int LONG_QUARTER_MAX = 112;

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
        return build(facts, fromFiscalYear, false);
    }

    /**
     * @param usGate <b>미국 재적재 관문 설정</b>({@code stockscreener.gate.us}). 켜지면 관문에서 함께 나갈
     *               규칙 변경이 적용된다 — 지금은 {@link #controllingIncomeQ4}의 마지막 갈래 하나다.
     *               <b>꺼진 상태의 출력은 이 설정이 생기기 전과 바이트까지 같아야 한다</b>(`docs/reload-gate.md`).
     *               설정을 읽는 곳은 {@link SecEdgarFinancialAdapter} 하나다.
     */
    static Result build(JsonNode facts, int fromFiscalYear, boolean usGate)
            throws DataSourceException {
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
            Map<SecEdgarColumn, String> tags =
                    chooseTags(labelAnchors.get(label), e.getValue(), byColumn);
            reports.addAll(revisionsOf(label, e.getValue(), labelAnchors.get(label),
                    byColumn, anchors, tags));
        }
        List<RawFinancialReport> fourthQuarters =
                deriveFourthQuarters(reports, windows, fromFiscalYear, byColumn, usGate);
        // MUST: 같은 (회계연도, 기간, 공시일)이면 **병합본이 소스 판을 대체한다.**
        // mergeQ4가 소스 값을 그대로 얹으므로 병합본은 소스 판의 상위집합이고, 둘을 함께 두면
        // 저장이 같은 키에 두 번 써서 **무효화만 남는다.**
        Set<String> replaced = new HashSet<>();
        for (RawFinancialReport q4 : fourthQuarters) {
            replaced.add(q4.fiscalYear() + "/" + q4.fiscalPeriod() + "@" + q4.publishedAt());
        }
        reports.removeIf(r -> replaced.contains(
                r.fiscalYear() + "/" + r.fiscalPeriod() + "@" + r.publishedAt()));
        reports.addAll(fourthQuarters);
        reports.sort(Comparator.comparing(RawFinancialReport::periodEnd)
                .thenComparing(RawFinancialReport::fiscalPeriod)
                .thenComparing(RawFinancialReport::publishedAt));
        return new Result(reports, dropped, withoutDerived(absencesOf(facts, byColumn), reports));
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
    /**
     * <b>값이 난 컬럼의 부재 행을 뺀다.</b>
     *
     * <p>{@link #absencesOf}는 <b>태그의 유무</b>를 말하는데, 유도가 들어온 뒤로 그것이
     * <b>저장된 값의 유무와 갈린다.</b> 그대로 두면 화면이 <i>"소스가 이 항목을 안 냅니다"</i>라고
     * 말하면서 <b>그 수를 함께 보여 준다</b> — ADR-0147이 막으려던 「그럴듯한데 틀린 사유」다.
     *
     * <p><b>실측(2026-09-15)</b>: 이 필터가 없을 때 값과 모순되는 {@code ABSENT} 행이
     * <b>226종목</b>이었다 — {@code total_liabilities} 199({@code ABBV}·{@code AMZN}·{@code KO}·
     * {@code WMT}…) · {@code revenue} 19(은행) · {@code profit_before_tax} 8.
     *
     * <p><b>목록이 낡으면 ADR-0147의 전제가 다시 잡는다</b> — 그 펜스가 이 결함을 찾아낸
     * 바로 그것이다. 그래서 여기 손으로 적는 대가가 작다.
     */
    private static List<FinancialColumnAbsence> withoutDerived(
            List<FinancialColumnAbsence> absences, List<RawFinancialReport> reports) {
        Set<String> filled = new HashSet<>();
        for (RawFinancialReport report : reports) {
            addIfPresent(filled, SecEdgarColumn.REVENUE, report.revenue());
            addIfPresent(filled, SecEdgarColumn.NET_INCOME, report.netIncome());
            // net_income이 연결 총액이 되면서 NET_INCOME_TOTAL·NET_INCOME_CONTROLLING으로도
            // 유도가 돈다(2026-09-21). 목록에 없으면 「태그는 없다는데 값은 있다」가 다시 생긴다.
            addIfPresent(filled, SecEdgarColumn.NET_INCOME_TOTAL, report.netIncome());
            addIfPresent(filled, SecEdgarColumn.NET_INCOME_CONTROLLING,
                    report.netIncomeControlling());
            addIfPresent(filled, SecEdgarColumn.TOTAL_EQUITY, report.totalEquity());
            addIfPresent(filled, SecEdgarColumn.TOTAL_EQUITY_CONTROLLING, report.totalEquityControlling());
            addIfPresent(filled, SecEdgarColumn.TOTAL_LIABILITIES, report.totalLiabilities());
            addIfPresent(filled, SecEdgarColumn.OPERATING_CASH_FLOW, report.operatingCashFlow());
            addIfPresent(filled, SecEdgarColumn.PROFIT_BEFORE_TAX, report.profitBeforeTax());
        }
        if (filled.isEmpty()) {
            return absences;
        }
        List<FinancialColumnAbsence> kept = new ArrayList<>();
        for (FinancialColumnAbsence absence : absences) {
            if (!filled.contains(absence.columnName())) {
                kept.add(absence);
            }
        }
        return kept;
    }

    private static void addIfPresent(Set<String> filled, SecEdgarColumn column, BigDecimal value) {
        if (value != null) {
            filled.add(column.columnName());
        }
    }

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
            FinancialColumnAbsence.State state;
            if (tagExists) {
                state = FinancialColumnAbsence.State.FILTERED;
            } else if (hasAny(facts, column.partialCandidates())) {
                // 후보는 하나도 없는데 부분 개념 태그는 있다. 「안 낸다」가 아니라 「뜻이 다른
                // 것만 낸다」이고, 둘을 뭉치면 화면이 거짓을 말한다(실측 20종목).
                state = FinancialColumnAbsence.State.PARTIAL_ONLY;
            } else {
                state = FinancialColumnAbsence.State.ABSENT;
            }
            absences.add(new FinancialColumnAbsence(column.columnName(), state));
        }
        return absences;
    }

    /** 이 태그들 중 하나라도 소스의 팩트 목록에 있는가. 값이 아니라 <b>이름</b>만 본다. */
    private static boolean hasAny(JsonNode facts, List<String> tags) {
        for (String candidate : tags) {
            JsonNode tag = facts.path("us-gaap").path(candidate);
            if (!tag.isMissingNode() && tag.has("units")) {
                return true;
            }
        }
        return false;
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
                // **제출일보다 뒤에 끝나는 기간은 그 제출물이 보고할 수 없다.** 담으면
                // 「그 시점에 없던 값」이 그 시점 조회에 보인다 — `IndicatorDataReader`의
                // KNOWN_ON이 `published_at <= asOf`만 보므로 원리적으로 안 걸린다.
                // 실측(2026-09-16): 저장된 `published_at < period_end` 행이
                // **US 45(is_latest 20 · 31종목)**이고, `CREX`는 **2022-11-14 기준 조회가
                // 2023년 FY 전체**를 본다. 스윕에서도 `MORN 2020/Q4` · `PEN 2021/Q3` ·
                // `ROP 2015/FY`·`2015/Q4` 넷이 그 모양이었다.
                //
                // **MUST: 앵커 층이 아니라 여기서 막는다.** `anchorsOf`에 걸면 그 넷 중
                // **하나만** 잡힌다 — 나머지 셋의 불가능 팩트가 전부 잔액(INSTANT)인데
                // 앵커는 유량만 보기 때문이다. 여기 한 자리가 앵커·라벨·보고자·값을 함께 고친다.
                //
                // **MUST NOT: `ANCHORED_INSTANT`를 여기 걸지 않는다.** 표지 주식수의 `end`는
                // 기간이 아니라 **「as of」**라 뜻이 다르고 제출일보다 뒤인 것이 정상이다
                // (`AAL` end 2027-07-17 / filed 2026-07-23). 걸면 그 47칸의 주식수가 바뀐다.
                // 반경: 전 컬럼 팩트 5,017,371 중 122팩트이고 주식수 47을 빼면 75 미만이다.
                if (end.isAfter(filed) && !column.anchoredInstant()) {
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
        // 기간이 둘인 라벨. 순회가 끝난 뒤 통째로 뺀다 — 뒤에 온 팩트가 그 라벨을 되살리면
        // 「둘 중 무엇을 골랐는지」가 순서에 달리고, 그것이 막으려던 조용한 덮어쓰기다.
        Set<Label> conflicted = new LinkedHashSet<>();
        for (Map<String, Map<Slot, Fact>> byTag : byColumn.values()) {
            for (Fact fact : allFacts(byTag)) {
                if (fact.start() == null) {
                    continue;   // 잔액은 라벨을 만들지 않는다. 기간을 만드는 것은 유량이다.
                }
                Anchor anchor = anchors.get(fact.accn());
                if (anchor == null || !fact.end().equals(anchor.end())) {
                    // 비교표다. 그 기간은 자기 보고서가 라벨을 준다.
                    //
                    // **MUST: 그 전제가 깨지는 자리를 알고 쓴다 — 자기 보고서가 원자료에
                    // 없으면 아무도 그 라벨을 안 준다.** 실측(2026-09-20 · 편입 미국 1,000 ·
                    // 전 구간)으로 **16종목 32회계연도**가 그렇게 사라진다: SFD·TLN 각 8 ·
                    // DSGX 3 · AOS CHRD CIFR DY ELF EXE JEF KRYS ORKA SMCI SPGI VAL WULF 각 1
                    // (KOSPI 1종목 2년 · KOSDAQ 1종목 1년도 같은 모양이다).
                    //
                    // 갈래가 둘이고 둘 다 실측이다. companyfacts에 그 보고서가 **아예 없거나**
                    // (AOS·SPGI는 10-K가 2024-02와 2026-02 둘뿐이라 FY2024 보고서가 없다),
                    // 실려 있는데 **연간 기간을 한 건도 안 싣는다**(ORKA의 2025-03-06 10-K ·
                    // DSGX의 40-F 둘 2024-04-22·2025-04-22).
                    //
                    // **원자료에는 있다 — 가짜 한계다.** AOS의 2024-01-01~2024-12-31이 뒤
                    // 공시 0000091142-26-000008의 비교표로 실려 있다. 0콜로 재현한다:
                    // REPLAY_FACTS=build/factcache/AOS.json REPLAY_FROM=2022
                    // ./gradlew test --tests "*SecEdgarFactsReplayTest*" → 2023/FY·2025/FY는
                    // 나오고 2024/FY가 없다(출력은 build/test-results/test/*.xml의 system-out).
                    //
                    // **MUST: 처방을 올리기 전에 controllingIncomeQ4의 §버린 대안을 읽는다** —
                    // 「비교표로 라벨을 만든다」가 거기서 실측으로 기각돼 있고, 그 기각 근거가
                    // 이 좁힌 조건에도 서는지가 처방의 관문이다.
                    continue;
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
                } else if (seen != null && shorterQuarterWins(seen, window)) {
                    // 같은 종료일에 분기 길이 창이 둘이다. 짧은 쪽이 그 분기이고 긴 쪽은
                    // **stub·누적**이다 — 16주 분기(LONG_QUARTER_*)를 연 뒤에 실재하는 형태다.
                    //
                    // 실측(2026-09-16 드라이런): PRMB 2024/Q3에 정규 분기 2024-07-01~09-30(91일)과
                    // 합병 stub 2024-06-11~09-30(**111일**)이 함께 있어 아래 예외가 터졌고
                    // **그 종목 11판·값칸 173이 통째로 사라졌다.**
                    //
                    // MUST: 종료일이 같을 때만이다. 종료일이 다른 두 창이 같은 라벨을 받는 것은
                    // 여전히 이상 신호이고 아래에서 던진다. 그리고 고르는 규칙이 결정론이라
                    // 아래 주석이 막으려는 「앞 기간의 최신 표시가 조용히 내려가는」 일은 안 생긴다.
                    if (window.start().isAfter(seen.start())) {
                        windows.put(label, window);
                    }
                } else if (seen != null && !seen.equals(window)
                        && label.fiscalYear() >= fromFiscalYear) {
                    // 저장 키에 기간이 없다. 라벨이 겹치면 uq_fs_latest가 걸리는 것이 아니라
                    // 앞 기간의 최신 표시가 조용히 내려간다 — FinancialWriter가 개정 갈래에서
                    // UPDATE를 INSERT보다 먼저 돌리기 때문이다. **그 라벨을 저장하지 않는 것**이
                    // 유일한 방어선이다.
                    //
                    // **MUST: 그 라벨만 버리고 종목은 살린다.** 예전에는 여기서 던져
                    // **그 종목의 재무가 통째로 안 바뀌었다** — 실측(2026-09-16)으로 `SENS`가
                    // 결산월을 **6월 → 12월**로 바꿔 `2014-07-01~2015-06-30`과
                    // `2015-01-01~2015-12-31`이 **둘 다 `2015/FY`**가 되는데, 그 한 라벨 때문에
                    // 나머지 전 연도가 옛 값에 얼어붙었다(그 종목은 지표 253일을 들고
                    // 백분위 모집단에 있다). **한 라벨의 모호함이 한 종목의 전 기간을 죽일
                    // 이유가 없다.**
                    //
                    // **저장 키가 `(회계연도, 기간)`이라 한 해에 끝나는 회계연도 둘을 담을 수
                    // 없다** — 그것은 모델의 한계이고, 담으려면 키에 기간이 들어가야 한다.
                    // 여기서는 **버리고 센다**(`dropped`). 버린 라벨은 `windows`에서 빠지므로
                    // `FinancialWriter`의 회수 안전장치가 **그 라벨의 저장 행을 안 건드린다.**
                    //
                    // MUST: 판정을 적재 창 안으로 좁힌다. 지키려는 대상은 「저장되는 라벨」인데
                    // 이 순회는 파싱된 전 기간에 걸려 있어서, 창 밖 옛 연도의 충돌까지 셌다.
                    // 실측(2026-09-04)으로 거절된 145종목 중 116이 2023년 미만에서 죽었다.
                    log.warn("같은 라벨에 기간이 둘이라 그 라벨을 버린다: {}/{} → {}~{} vs {}~{}",
                            label.fiscalYear(), label.period(),
                            seen.start(), seen.end(), window.start(), window.end());
                    conflicted.add(label);
                    dropped++;
                }
                // 앵커가 여럿이면(원본 10-K와 10-K/A) 공시일이 가장 이른 것으로 고정한다.
                // 실행마다 같은 publishedAt이 나와야 FinancialWriter의 「공시일이 같으면
                // 아무것도 안 한다」 갈래에 안착한다.
                labelAnchors.merge(label, anchor,
                        (a, b) -> a.filed().isAfter(b.filed()) ? b : a);
            }
        }
        for (Label label : conflicted) {
            windows.remove(label);
            labelAnchors.remove(label);
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

    /** 분기로 볼 길이인가. 두 구간이고 <b>그 사이는 일부러 비어 있다</b>({@link #QUARTER_MAX}). */
    private static boolean isQuarterSpan(long days) {
        return (days >= QUARTER_MIN && days <= QUARTER_MAX)
                || (days >= LONG_QUARTER_MIN && days <= LONG_QUARTER_MAX);
    }

    /**
     * 같은 종료일에 분기 길이 창이 둘인가. 그때는 <b>짧은 쪽</b>이 그 분기다.
     *
     * <p>{@link #sameWindowStatedDifferently}와 가르는 자리는 <b>시작일 차의 크기</b>다 —
     * 저쪽은 「같은 기간을 며칠 달리 적었다」(≤10일)이고 이쪽은 「기간이 실제로 다르다」다.
     * 긴 쪽은 합병·재편 stub이거나 누적이다.
     */
    private static boolean shorterQuarterWins(Window seen, Window window) {
        if (!seen.end().equals(window.end()) || seen.equals(window)) {
            return false;
        }
        return isQuarterSpan(ChronoUnit.DAYS.between(seen.start(), seen.end()))
                && isQuarterSpan(ChronoUnit.DAYS.between(window.start(), window.end()));
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
        if (!annual && !isQuarterSpan(days)) {
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
                                                        Map<String, Anchor> anchors,
                                                        Map<SecEdgarColumn, String> tags) {
        // 손익 비지배의 마지막 관측. 이 기간보다 앞이면 「그 뒤로 비지배를 안 낸다」이고
        // 연결 총액을 지배주주 값으로 채울 수 있다(noNoncontrollingIncomeBy).
        LocalDate lastNoncontrolling = lastNoncontrollingIncome(byColumn);

        // MUST: 컬럼마다 태그를 **기간 단위로 하나** 고정한다.
        //
        // 같은 기간을 보고서마다 다른 후보 태그로 내는 일이 실재한다 — 현금이
        // CashAndCashEquivalentsAtCarryingValue와 CashCashEquivalentsRestrictedCash…로 갈리고
        // 그 둘은 값이 다르다. 보고서별로 태그를 고르면 **값이 아니라 태그가 바뀐 것**을
        // 정정으로 읽어, 값이 그대로인데도 판이 쌓인다(실측에서 AAPL 34건 중 절반이 그랬다).

        List<Anchor> reporters = new ArrayList<>();
        for (Anchor anchor : anchors.values()) {
            if (hasAnyValue(anchor, window, tags, byColumn)) {
                reporters.add(anchor);
            }
        }
        reporters.sort(Comparator.comparing(Anchor::filed).thenComparing(Anchor::accn));

        List<RawFinancialReport> out = new ArrayList<>();
        Map<SecEdgarColumn, BigDecimal> baseline = null;
        RawFinancialReport last = null;
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
                if (!MARKET_CURRENCY.equals(candidateCurrency)) {
                    // 시장 통화가 아닌 판은 담지 않는다 — 근거는 MARKET_CURRENCY의 javadoc이다.
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
            RawFinancialReport candidate = toReport(label, window, reporter, currency, carried,
                    byColumn.getOrDefault(SecEdgarColumn.MINORITY_INTEREST, Map.of()).isEmpty(),
                    noNoncontrollingIncomeBy(lastNoncontrolling, window),
                    byColumn.getOrDefault(SecEdgarColumn.DISCONTINUED_OPERATIONS, Map.of()).isEmpty());
            // MUST: 「저장될 행이 달라지는가」로 판정한다. 원시 컬럼 맵으로 판정하면 안 된다 —
            // 다리 컬럼(유도 재료)이 달라져도 유도 결과가 같으면 저장은 한 칸도 안 바뀌고,
            // 반대로 reportEvidence()로 좁히면 income_tax_expense·profit_before_tax만 더하는
            // 판이 버려진다(그 둘은 저장 컬럼인데 reportEvidence()가 제외한다).
            // MUST: 옛 규칙이 내던 판을 하나도 버리지 않는다 — 원시 컬럼이 충돌하면 그대로 낸다.
            // 저장 값이 같아도 그 판의 accn이 **4분기 유도에서 다른 슬롯을 연다**:
            // CPNG FY2024의 두 판은 저장 값이 같은데 2026-02-26 판만 Q4 매출총이익
            // 2,491,000,000을 연다. 그것까지 접으면 그 칸을 잃는다(실측 1칸).
            if (baseline != null && last != null
                    && !conflicts(baseline, carried) && !storedDiffers(last, candidate)) {
                continue;
            }
            baseline = carried;
            last = candidate;
            out.add(candidate);
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
                // MUST: `factFor`와 **같은 술어**를 쓴다. 여기서 정확 일치만 보면 52/53주 회계력의
                // 태그가 아예 안 골라져 `factFor`의 허용오차까지 못 간다 — 실측으로 `ONTO`
                // FY2021의 매출만 통과하고 원가·총이익·순이익·영업현금흐름·판관비·capex는
                // 태그 선택에서 끊겼다.
                boolean covers = column.anchoredInstant()
                        || (column.instant()
                        ? fact.end().equals(window.end())
                        : fact.end().equals(window.end())
                                && startMatches(window, fact.start(), fact.end()));
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

    /**
     * <b>저장될 행이 달라지는가.</b> 판을 새로 낼지는 이것 하나로 정한다.
     *
     * <p><b>왜 원시 컬럼 맵으로 판정하면 안 되는가</b> — 세 번 재고 세 번 틀렸다.
     * <ol>
     *   <li>{@code conflicts}만 보면 <b>뒤 판이 앞에 없던 컬럼만 더할 때</b> 충돌이 아니라
     *       {@code continue}가 {@code baseline} 갱신까지 건너뛰어 <b>그 값이 통째로 버려진다.</b>
     *       실측: {@code TGT} 2019/Q1의 {@code OperatingIncomeLoss} <b>1,041,000,000</b>이
     *       10-K(2019-03-13)에만 있는데 스윕은 그 라벨을 판 하나만 내고 영업이익이 비어 있었다.</li>
     *   <li>모든 컬럼의 추가를 세면 <b>주식수</b>가 켠다 — 표지 항목이라 기간을 안 묻고 아무
     *       보고서에서나 오므로 <b>모든 보고서가 모든 기간의 보고자</b>가 된다
     *       ({@code hasAnyValue}의 javadoc이 이미 이름 붙인 함정이다). 실측: 새 판 10,262 중
     *       <b>5,196(50%)</b>이 금액 26컬럼이 직전 판과 완전히 같았고,
     *       {@code Amount.SHARES_OUTSTANDING}은 {@code overwritable()}이 거짓이라 <b>저장도 안 된다.</b></li>
     *   <li>{@code reportEvidence()}로 좁히면 <b>{@code income_tax_expense}·
     *       {@code profit_before_tax}만 더하는 판이 버려진다</b> — 그 둘은 저장 컬럼인데
     *       그 술어가 제외한다. 다리 컬럼(유도 재료)도 <b>유도 결과를 바꿀 수 있어</b> 원시 맵으로는
     *       못 가른다. 실측: 그렇게 좁혀도 새 판 8,372 중 <b>3,528</b>이 여전히 저장값 동일이었다.</li>
     * </ol>
     *
     * <p><b>MUST: 주식수는 보지 않는다.</b> {@code FinancialWriter}가 그 컬럼을 덮지 않으므로
     * (커밋 {@code 06585a2}) 그것만 달라진 판은 <b>쓰기가 0인데 무효화만 부른다.</b>
     *
     * <p><b>MUST NOT: 「앞 판으로 되돌려 채운다」로 고치지 않는다.</b> 그 값은 뒤 공시일에
     * 처음 공개된 것이라, 앞 판의 행에 넣으면 <b>그 시점에 없던 값을 그 시점 조회가 본다</b>
     * (ADR-0128).
     */
    private static boolean storedDiffers(RawFinancialReport a, RawFinancialReport b) {
        return differs(a.revenue(), b.revenue())
                || differs(a.operatingIncome(), b.operatingIncome())
                || differs(a.netIncome(), b.netIncome())
                || differs(a.netIncomeControlling(), b.netIncomeControlling())
                || differs(a.totalAssets(), b.totalAssets())
                || differs(a.totalEquity(), b.totalEquity())
                || differs(a.totalEquityControlling(), b.totalEquityControlling())
                || differs(a.totalLiabilities(), b.totalLiabilities())
                || differs(a.currentAssets(), b.currentAssets())
                || differs(a.currentLiabilities(), b.currentLiabilities())
                || differs(a.retainedEarnings(), b.retainedEarnings())
                || differs(a.operatingCashFlow(), b.operatingCashFlow())
                || differs(a.eps(), b.eps())
                || differs(a.bps(), b.bps())
                || differs(a.costOfSales(), b.costOfSales())
                || differs(a.cashAndEquivalents(), b.cashAndEquivalents())
                || differs(a.capex(), b.capex())
                || differs(a.inventories(), b.inventories())
                || differs(a.interestAndDividendIncome(), b.interestAndDividendIncome())
                || differs(a.noninterestIncome(), b.noninterestIncome())
                || differs(a.interestExpense(), b.interestExpense())
                || differs(a.noninterestExpense(), b.noninterestExpense())
                || differs(a.benefitsLossesAndExpenses(), b.benefitsLossesAndExpenses())
                || differs(a.grossProfit(), b.grossProfit())
                || differs(a.operatingExpenses(), b.operatingExpenses())
                || differs(a.costsAndExpenses(), b.costsAndExpenses())
                || differs(a.incomeTaxExpense(), b.incomeTaxExpense())
                || differs(a.profitBeforeTax(), b.profitBeforeTax());
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
        if (column.instant()) {
            return slots.get(new Slot(anchor.accn(), null, window.end()));
        }
        Fact exact = slots.get(new Slot(anchor.accn(), window.start(), window.end()));
        return exact != null ? exact : nearStart(slots, anchor, window);
    }

    /**
     * <b>시작일이 창과 맞는가 — 52/53주 회계력의 며칠 어긋남을 받는다.</b>
     * {@link #nearStart}와 같은 술어여야 한다(태그 선택과 값 조회가 갈리면 태그는 골라지는데
     * 값이 null인 자리가 생긴다).
     */
    private static boolean startMatches(Window window, LocalDate start, LocalDate end) {
        if (start == null) {
            return false;
        }
        if (window.start().equals(start)) {
            return true;
        }
        long shift = Math.abs(ChronoUnit.DAYS.between(window.start(), start));
        if (shift > START_TOLERANCE_DAYS) {
            return false;
        }
        long span = ChronoUnit.DAYS.between(window.start(), window.end());
        long candidateSpan = ChronoUnit.DAYS.between(start, end);
        return Math.abs(candidateSpan - span) <= START_TOLERANCE_DAYS + 3;
    }

    /** 52/53주 회계력이 창 시작일을 며칠 밀 수 있는 폭. */
    private static final int START_TOLERANCE_DAYS = 7;

    /**
     * <b>기말이 같고 시작일만 며칠 어긋난 팩트.</b> 정확 일치가 없을 때만 본다.
     *
     * <p><b>왜</b>: 52/53주 회계력을 쓰는 회사는 <b>같은 회계연도를 두 창으로</b> 낸다.
     * {@code ONTO} FY2021을 원장이 <b>{@code 2020-12-27~2022-01-01}(370일)</b>로 내는데 라벨 창은
     * {@code 2021-01-02~2022-01-01}(364일)이라, {@code (start, end)} 정확 일치가 <b>매출·원가·
     * 총이익·순이익·영업현금흐름·판관비·capex를 통째로 놓친다</b>(전부 −6일에 있다).
     *
     * <p><b>MUST: 값이 정확히 하나일 때만 쓴다.</b> 같은 태그가 창 하나에 값 둘을 내면
     * <b>어느 것도 증거가 아니다</b> — {@code ONTO}의 {@code OperatingIncomeLoss}가
     * <b>{@code +156,407,000}과 {@code −156,407,000}을 함께 낸다</b>(2024년 10-K가 부호를 뒤집었다).
     * 그 자리는 비워 두는 것이 옳다.
     *
     * <p><b>MUST: 같은 공시(accn) 안에서만 찾는다.</b> 다른 판의 팩트를 끌어오면 한 행이 두 공시의
     * 혼합이 된다 — {@code carryForward}가 판을 가로질러 하는 일과 뜻이 다르다.
     *
     * <p><b>MUST: 기간 길이도 함께 본다.</b> 시작일만 보면 분기 팩트가 연간 창에 걸릴 수 있다.
     */
    private static Fact nearStart(Map<Slot, Fact> slots, Anchor anchor, Window window) {
        long span = ChronoUnit.DAYS.between(window.start(), window.end());
        Fact found = null;
        for (Map.Entry<Slot, Fact> entry : slots.entrySet()) {
            Slot slot = entry.getKey();
            if (!slot.accn().equals(anchor.accn()) || slot.start() == null
                    || !slot.end().equals(window.end())) {
                continue;
            }
            long shift = Math.abs(ChronoUnit.DAYS.between(window.start(), slot.start()));
            if (shift == 0 || shift > START_TOLERANCE_DAYS) {
                continue;
            }
            long candidateSpan = ChronoUnit.DAYS.between(slot.start(), slot.end());
            if (Math.abs(candidateSpan - span) > START_TOLERANCE_DAYS + 3) {
                continue;
            }
            Fact fact = entry.getValue();
            if (found != null && (fact.value() == null || found.value() == null
                    || fact.value().compareTo(found.value()) != 0)) {
                return null;
            }
            found = fact;
        }
        return found;
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
                                               Map<SecEdgarColumn, BigDecimal> v,
                                               boolean noNoncontrolling,
                                               boolean noNoncontrollingIncome,
                                               boolean noDiscontinued) {
        return new RawFinancialReport(
                label.fiscalYear(), label.period(), window.start(), window.end(), anchor.filed(),
                // 정정 추적의 근거. DART 쪽이 rcept_no 컬럼이 없어 못 하던 것이다.
                "%s:%s".formatted(anchor.form(), anchor.accn()), currency,
                revenue(v), operatingIncome(v),
                consolidatedIncome(v, noNoncontrollingIncome), v.get(SecEdgarColumn.TOTAL_ASSETS),
                totalEquity(v),
                controllingEquity(v, noNoncontrolling),
                totalLiabilities(v, noNoncontrolling),
                v.get(SecEdgarColumn.CURRENT_ASSETS), v.get(SecEdgarColumn.CURRENT_LIABILITIES),
                v.get(SecEdgarColumn.RETAINED_EARNINGS), operatingCashFlow(v),
                v.get(SecEdgarColumn.SHARES_OUTSTANDING), v.get(SecEdgarColumn.EPS),
                // bps는 소스가 주지 않는다. 자본총계/주식수로 지표 계층이 계산한다(spec §7).
                null,
                v.get(SecEdgarColumn.COST_OF_SALES), v.get(SecEdgarColumn.CASH_AND_EQUIVALENTS),
                v.get(SecEdgarColumn.CAPEX), v.get(SecEdgarColumn.INVENTORIES),
                // 지배주주 귀속 순이익. **net_income은 위에서 연결 총액이 됐으므로 둘이 갈라졌다**
                // (ADR-0159 §결정 — 두 컬럼의 뜻은 두 시장 공통이다). 2026-09-21 이전에는
                // net_income에 이 값이 들어가고 여기가 상수 null이었는데, 그 때문에
                // ROA·NET_MARGIN·ACCRUAL_RATIO가 연결 분모에 지배주주 분자를 얹고 있었다.
                controllingIncome(v),
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
                profitBeforeTax(v, noDiscontinued),
                // 소스가 준 행이다. 유도 행만 derived=true이고, 저장이 그것으로 판을 회수한다.
                false,
                // MUST: net_income이 여기서 null이면 그것은 **판정**이다 — 위 consolidatedIncome이
                // 네 갈래를 다 태운 결과이고, 들어온 v는 carryForward가 **판을 가로질러** 이어받은
                // 뒤의 맵이다. 그래서 「이 판이 안 실었다」가 아니라 「모든 판을 합쳐도 재료가
                // 없다」이고, 저장이 옛 값을 들고 있으면 지워야 한다(RawFinancialReport.assertedAbsent).
                assertedAbsentOf(consolidatedIncome(v, noNoncontrollingIncome)));
    }

    /**
     * <b>어댑터가 「없다」고 판정한 컬럼.</b> 지금은 {@code net_income} 하나다.
     *
     * <p><b>MUST: 컬럼을 더하기 전에 그 컬럼만 따로 잰다.</b> 2026-09-22에 스윕과 DB를 컬럼
     * 열한 개로 갈라 대조했더니 {@code net_income}이 <b>5,366칸</b>이고 나머지는
     * {@code revenue} 2 · {@code operating_income} 1 · {@code cost_of_sales} 1 · 나머지 전부 0이었다.
     * 묶어서 넣으면 근거 하나로 여럿을 판정하는 것이 된다.
     */
    private static java.util.Set<String> assertedAbsentOf(BigDecimal consolidatedIncome) {
        return consolidatedIncome == null
                ? java.util.Set.of(RawFinancialReport.NET_INCOME)
                : java.util.Set.of();
    }

    /**
     * <b>지배주주 자본 — 직접 낸 값이 없으면 비지배지분을 빼서 만든다.</b>
     *
     * <p>비지배지분이 0에 가까운 회사는 두 자본 태그 중 <b>하나만 낸다.</b> 실측(2026-09-15):
     * {@code CAT}·{@code ADM}은 {@code StockholdersEquity}를 아예 안 내고
     * {@code MinorityInterest}만 내며, {@code AVGO}·{@code CPRT}·{@code CAG}는 어느 해부터
     * 그만 냈다. <b>그 62종목은 원장의 한계가 아니라 우리가 유도를 안 한 자리였다</b> —
     * 유도하면 {@code ROE}의 분모가 분자와 같은 기저가 된다(ADR-0153).
     *
     * <p><b>MUST: 직접 낸 값이 언제나 이긴다</b>(ADR-0104). 유도는 없을 때만이다.
     */
    /**
     * <b>지배주주 귀속 순이익.</b> 직접 낸 값 → 유도 → 마지막 수단 순이고, <b>어느 갈래에서도
     * 값을 잃지 않는다</b> — 유도가 안 되면 예전과 똑같이 {@code NET_INCOME}의 첫값이 나간다.
     *
     * <p><b>마지막 수단이 연결을 집는 것은 재고 고른 것이다.</b> 그 판 공시일 이하에 0 아닌 비지배가 보이는 폴백 약
     * 1,460행을 뒤 공시의 {@code NetIncomeLoss}(평가 전용)로 채점하면 연결이 맞음 87%(351/402) · 보통주 귀속(+우선주배당)
     * 73%(151/208)였고, 좁힌 술어 둘(직전 FY의 {@code NetIncomeLoss} = 보통주 귀속 · 우선주 잔액 0)도 판정 가능 행에서
     * 4 대 6 · 15 대 12로 서지 않았다 — {@code OXY}가 {@code NetIncomeLoss}에 우선주배당 뒤 값을 싣는다(2026-10-04).
     * null로 두면 {@code sumControllingNetIncome}이 창 단위로 연결 합에 떨어져 더 틀린다(ADR-0154 §버린 대안).
     *
     * <p><b>왜 지금 필요한가</b>: ADR-0153이 {@code ROE}의 분모를 지배주주 자본으로 옮기면서
     * {@code NetIncomeLoss}가 없는 종목은 <b>분자만 비지배 포함</b>으로 남았다. 그 전에는
     * 분자·분모가 둘 다 비지배 포함이라 정합했으므로, <b>이것은 그 변경이 만든 어긋남</b>이다.
     *
     * <p><b>MUST: 직접 낸 값이 언제나 이긴다</b>(ADR-0104).
     */
    /**
     * <b>영업이익 — 크기가 같은데 부호가 반대면 원장 자신의 항등식을 쓴다.</b>
     *
     * <p><b>왜</b>: 제출본이 {@code OperatingIncomeLoss}의 <b>부호를 뒤집어 내는</b> 일이 있다.
     * {@code ONTO} FY2021은 같은 공시가 {@code 총이익 429,086,000 − 판관비 272,679,000 =
     * <b>+156,407,000</b>}을 내면서 영업이익을 <b>−156,407,000</b>으로 낸다(2020 FY도 같다).
     *
     * <p><b>MUST: 크기가 같을 때만 뒤집는다.</b> 「항등식과 다르면 항등식을 쓴다」로 넓히면
     * 실측 <b>523칸</b>이 걸리는데 그것은 대부분 <b>영업이익의 정의가 회사마다 다른</b> 자리다
     * (총이익·판관비의 범위가 갈린다). <b>크기가 같고 부호만 반대</b>인 것은 정의 차이로
     * 설명되지 않는다 — 실측 <b>2칸 · 1종목</b>뿐이고 항등식 일치는 8,152칸이다.
     *
     * <p><b>MUST NOT: 부호만 보고 뒤집지 않는다.</b> 영업이익은 <b>정당하게 음수</b>다.
     */
    private static BigDecimal operatingIncome(Map<SecEdgarColumn, BigDecimal> v) {
        BigDecimal direct = v.get(SecEdgarColumn.OPERATING_INCOME);
        BigDecimal grossProfit = v.get(SecEdgarColumn.GROSS_PROFIT);
        BigDecimal operatingExpenses = v.get(SecEdgarColumn.OPERATING_EXPENSES);
        if (direct == null || grossProfit == null || operatingExpenses == null) {
            return direct;
        }
        BigDecimal identity = grossProfit.subtract(operatingExpenses);
        if (identity.signum() == 0 || identity.signum() == direct.signum()) {
            return direct;
        }
        return direct.abs().compareTo(identity.abs()) == 0 ? identity : direct;
    }

    /**
     * <b>그 종목이 손익의 비지배를 한 번도 안 내는가.</b> 티커 단위 증거이고, 연결 총액을
     * 지배주주 값으로 채우는 폴백의 <b>유일한 근거</b>다(ADR-0159 §결정).
     *
     * <p><b>MUST NOT: {@code MINORITY_INTEREST}로 이 판정을 하지 않는다 — 축이 다르다.</b>
     * 그 컬럼은 {@code MinorityInterest} 하나이고 <b>재무상태표 잔액</b>({@code Kind.INSTANT})인데,
     * 상환가능 비지배지분은 중간지분에 실려 거기 안 나온다. 실측(2026-09-21 · 팩트 캐시 1,214):
     * {@code MinorityInterest}가 없는 <b>536티커 중 54</b>가 손익 비지배 태그를 실제로 낸다
     * (편입 998 중 <b>38</b> — {@code BX} FY2025 {@code ProfitLoss} 6,046M 대
     * {@code NetIncomeLoss} 3,019M으로 <b>2.00배</b> · {@code ARES} 834M 대 527M).
     * 그 게이트를 손익에 쓰면 <b>지배주주 값이 연결총액 이름으로 저장된다.</b>
     *
     * <p><b>이 교정은 커버리지로도 이긴다</b> — 손익 게이트가 편입 998 중 415를 덮어,
     * 자본 게이트의 <b>정당한</b> 387(=425−38)보다 넓다. 잃는 칸이 0이다.
     */
    private static LocalDate lastNoncontrollingIncome(
            Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn) {
        LocalDate last = null;
        for (SecEdgarColumn column : List.of(
                SecEdgarColumn.NET_INCOME_NONCONTROLLING,
                SecEdgarColumn.NET_INCOME_NONCONTROLLING_REDEEMABLE,
                SecEdgarColumn.NET_INCOME_NONCONTROLLING_NONREDEEMABLE)) {
            for (Map<Slot, Fact> bySlot : byColumn.getOrDefault(column, Map.of()).values()) {
                for (Slot slot : bySlot.keySet()) {
                    if (last == null || slot.end().isAfter(last)) {
                        last = slot.end();
                    }
                }
            }
        }
        return last;
    }

    /**
     * <b>이 기간에 비지배가 없다고 볼 수 있는가.</b> 연결 총액을 지배주주 값으로 채우는
     * 폴백의 유일한 근거다(ADR-0159 §결정).
     *
     * <p><b>축은 손익이다 — {@code MINORITY_INTEREST}로 판정하지 않는다.</b> 그 컬럼은
     * 재무상태표 잔액({@code Kind.INSTANT})이라 상환가능 비지배지분이 안 잡힌다. 실측
     * (2026-09-21 · 팩트 캐시 1,214): 그 태그가 없는 <b>536티커 중 54</b>가 손익 비지배를
     * 실제로 낸다({@code BX}는 {@code ProfitLoss}가 {@code NetIncomeLoss}의 <b>2.00배</b>).
     *
     * <p><b>그리고 「한 번도 안 냈는가」가 아니라 「이 기간 뒤로 안 냈는가」다.</b> 전자로
     * 재면 <b>10~17년 전 이력에 발목이 잡힌다</b> — 실측으로 최신 TTM에서 세 지표를 잃는
     * 종목이 <b>89</b>였고, 열어 보니 갈래가 둘이었다:
     * <ul>
     *   <li>{@code ACN} — 비지배 손익을 <b>2026-05-31까지</b> 내고 최근 두 자본이 1.0352배로
     *       갈린다. <b>지금도 비지배가 있다</b> → NULL이 정당하다.</li>
     *   <li>{@code AMD}(2009) · {@code ADI}(2009) · {@code AOS}(2010) · {@code BBY}(2011) —
     *       십 년 넘게 안 낸다. {@code BBY}는 최근 두 자본이 <b>같아</b> 원장이 비지배 0을
     *       확인해 준다. <b>값을 만들 수 있는데 안 만드는 자리였다.</b></li>
     * </ul>
     *
     * <p><b>MUST: 「창 단위 부재를 회사 단위 부재로 읽지 않는다」는 그대로다</b>(ADR-0159).
     * 이 술어는 그 기간 하나가 비었는지를 묻지 않는다 — <b>그 뒤로 한 번도 안 냈는지</b>를 묻고,
     * 마지막 관측이 이 기간보다 뒤면(또는 같으면) 폴백을 <b>안</b> 건다.
     */
    private static boolean noNoncontrollingIncomeBy(LocalDate lastNoncontrolling, Window window) {
        return lastNoncontrolling == null || lastNoncontrolling.isBefore(window.start());
    }

    /**
     * <b>연결 총액 순이익 — 비지배 포함.</b> {@code ROA}·{@code NET_MARGIN}·
     * {@code ACCRUAL_RATIO}가 이것을 읽고, 그 분모는 전부 연결이다(ADR-0159 §지표표).
     *
     * <p>갈래가 넷이고 순서가 §결정 표 그대로다 — 직접 → {@code 지배주주 + 비지배} →
     * <b>그 종목이 비지배를 한 번도 안 내면</b> 지배주주 → <b>NULL</b>.
     *
     * <p><b>MUST: 못 만드는 칸은 NULL로 둔다 — 지어내지 않는다.</b> 게이트 없이 폴백하면
     * 미국 창의 42.84%가 추정값이 되고 그중 <b>32.5%가 비지배를 실제로 신고하는 회사</b>이며,
     * 대조군에서 <b>그런 회사는 81.50%에서 두 값이 다르다</b>(ADR-0159 §커버리지).
     */
    private static BigDecimal consolidatedIncome(Map<SecEdgarColumn, BigDecimal> v,
                                                 boolean noNoncontrollingIncome) {
        BigDecimal total = v.get(SecEdgarColumn.NET_INCOME_TOTAL);
        if (total != null) {
            return total;
        }
        BigDecimal controlling = controllingIncome(v);
        BigDecimal nonControlling = nonControllingIncome(v);
        if (controlling != null && nonControlling != null) {
            return controlling.add(nonControlling);
        }
        return noNoncontrollingIncome ? controlling : null;
    }

    private static BigDecimal controllingIncome(Map<SecEdgarColumn, BigDecimal> v) {
        BigDecimal direct = v.get(SecEdgarColumn.NET_INCOME_CONTROLLING);
        BigDecimal total = v.get(SecEdgarColumn.NET_INCOME_TOTAL);
        BigDecimal nonControlling = nonControllingIncome(v);
        BigDecimal identity = total != null && nonControlling != null
                ? total.subtract(nonControlling) : null;
        if (direct != null) {
            return mistaggedControlling(direct, total, nonControlling, identity,
                    v.get(SecEdgarColumn.NET_INCOME_AVAILABLE_TO_COMMON)) ? identity : direct;
        }
        if (identity != null) {
            return identity;
        }
        return v.get(SecEdgarColumn.NET_INCOME);
    }

    /**
     * <b>직접 태그가 「지배주주 귀속」일 수 없는가.</b> 추측이 아니라 <b>원장 자신의 모순</b>만 본다.
     *
     * <p><b>왜 필요한가</b>: {@code NET_INCOME_CONTROLLING}({@code NetIncomeLoss})을 무조건
     * 1순위로 쓰는데 <b>그 태그에 총액을 싣는 제출본이 실재한다.</b>
     * {@code QSR} 2019 Q1은 원장이 {@code ProfitLoss} <b>246M</b> · 비지배 <b>111M</b> ·
     * 보통주 귀속 <b>135M</b>을 내는데 2020년 10-K의 {@code NetIncomeLoss}가 <b>246M</b>이다 —
     * 총액을 그대로 실었다. {@code ESI} 2019 Q2는 1.4M − (−0.1M) = 1.5M인데 <b>14.7M</b>이다.
     *
     * <p><b>규칙 둘 다 논리적으로 빈틈이 없다.</b>
     * <ol>
     *   <li><b>직접 == 총액이고 비지배 ≠ 0</b> — 지배주주는 정의상 {@code 총액 − 비지배}라
     *       총액과 같을 수 없다. 실측 <b>163칸 · 45종목</b>.</li>
     *   <li><b>보통주 귀속 == {@code 총액 − 비지배}가 정확히 일치</b> — 우선주배당이 0이라는
     *       뜻이고, 그때 지배주주는 그 값이어야 한다. 실측 <b>114칸 · 34종목</b>(합집합 217).</li>
     * </ol>
     *
     * <p><b>MUST NOT: 「직접 태그가 항등식과 다르면 항등식을 쓴다」로 넓히지 않는다.</b>
     * 그 조건은 <b>1,147칸(4.9%)</b>에서 참인데 대부분은 <b>우선주배당이 있는 정상</b>이다 —
     * {@code ACGL} 2017 Q2는 직접 185.2M · 항등식 171.2M · 보통주 귀속 173.8M이고
     * <b>직접 태그가 옳다.</b> 넓히면 그런 회사의 지배주주 순이익을 우선주배당만큼 깎는다.
     *
     * <p><b>MUST NOT: {@code NetIncomeLossAvailableToCommonStockholdersBasic}을 「제3의 증인」으로
     * 크기 비교에 쓰지 않는다.</b> 그것은 <b>지배주주 − 우선주배당</b>이라 비지배가 양수일 때
     * <b>구조적으로 항등식 쪽에 가깝다</b> — 실측으로 「더 가까운 쪽」을 세면 456 대 198로
     * 직접 태그가 이기는데 그 수는 뜻이 없다. <b>정확히 같을 때만</b> 증거가 된다.
     */
    private static boolean mistaggedControlling(BigDecimal direct, BigDecimal total,
                                                BigDecimal nonControlling, BigDecimal identity,
                                                BigDecimal availableToCommon) {
        if (identity == null || direct.compareTo(identity) == 0) {
            return false;
        }
        if (total != null && nonControlling != null
                && direct.compareTo(total) == 0 && nonControlling.signum() != 0) {
            return true;
        }
        return availableToCommon != null && availableToCommon.compareTo(identity) == 0;
    }

    /**
     * 비지배 귀속분. <b>합계 태그가 이기고, 없을 때만 상환가능·상환불능 둘을 더한다.</b>
     * 합계와 구성요소를 함께 더하면 두 번 센다.
     *
     * <p>구성요소 갈래는 <b>둘 다 있을 때만</b> 성립한다 — 한쪽만 있는 것을 전체로 쓰면
     * 과소 차감이 되어 지배주주 순이익이 실제보다 커진다.
     */
    private static BigDecimal nonControllingIncome(Map<SecEdgarColumn, BigDecimal> v) {
        BigDecimal total = v.get(SecEdgarColumn.NET_INCOME_NONCONTROLLING);
        if (total != null) {
            return total;
        }
        BigDecimal redeemable = v.get(SecEdgarColumn.NET_INCOME_NONCONTROLLING_REDEEMABLE);
        BigDecimal nonRedeemable = v.get(SecEdgarColumn.NET_INCOME_NONCONTROLLING_NONREDEEMABLE);
        return redeemable != null && nonRedeemable != null ? redeemable.add(nonRedeemable) : null;
    }

    /**
     * <b>비지배 포함 자본.</b> {@code TOTAL_ASSETS}·{@code TOTAL_LIABILITIES}와 같은 기저라야
     * {@code 자산 = 부채 + 자본}이 선다(spec §7 · 국내 「자본총계」와 같은 것).
     *
     * <p>소스가 그 태그를 안 내면 {@code TOTAL_EQUITY}가 지배주주분으로 떨어지므로
     * <b>지배주주 자본 + 비지배지분</b>으로 되돌린다. {@link #controllingEquity}의 정확한 역이다.
     */
    /**
     * <b>영업활동현금흐름.</b> 소스가 총액 태그를 안 내면 <b>계속영업분 + 중단영업분</b>으로
     * 유도한다. 둘 다 안 되면 예전과 같은 첫값이 나간다 — <b>값을 잃지 않는다.</b>
     *
     * <p>실측은 {@code OPERATING_CASH_FLOW_DISCONTINUED}의 javadoc에 있다(항등식 98.8% ·
     * 절대오차 총합 −94.2%).
     */
    private static BigDecimal operatingCashFlow(Map<SecEdgarColumn, BigDecimal> v) {
        BigDecimal total = v.get(SecEdgarColumn.OPERATING_CASH_FLOW_TOTAL);
        if (total != null) {
            return total;
        }
        BigDecimal continuing = v.get(SecEdgarColumn.OPERATING_CASH_FLOW_CONTINUING);
        BigDecimal discontinued = v.get(SecEdgarColumn.OPERATING_CASH_FLOW_DISCONTINUED);
        if (continuing != null && discontinued != null) {
            return continuing.add(discontinued);
        }
        return v.get(SecEdgarColumn.OPERATING_CASH_FLOW);
    }

    /**
     * <b>세전이익.</b> 소스가 지분법 포함 태그를 안 내면 <b>지분법 제외분 + 지분법손익</b>으로
     * 유도한다. 둘 다 안 되면 예전과 같은 첫값이 나간다.
     *
     * <p>실측은 {@code EQUITY_METHOD_INCOME}의 javadoc에 있다(항등식 82.7% ·
     * 절대오차 총합 −78.0% · 악화 13 중 11이 「제외 태그가 실제로 제외하지 않는」 자리).
     */
    private static BigDecimal profitBeforeTax(Map<SecEdgarColumn, BigDecimal> v,
                                              boolean noDiscontinued) {
        BigDecimal including = v.get(SecEdgarColumn.PROFIT_BEFORE_TAX_INCLUDING_EQUITY);
        if (including != null) {
            return including;
        }
        BigDecimal excluding = v.get(SecEdgarColumn.PROFIT_BEFORE_TAX_EXCLUDING_EQUITY);
        BigDecimal equityMethod = v.get(SecEdgarColumn.EQUITY_METHOD_INCOME);
        if (excluding != null && equityMethod != null) {
            return excluding.add(equityMethod);
        }
        BigDecimal tax = v.get(SecEdgarColumn.INCOME_TAX_EXPENSE);
        // 세전이익 = 계속영업손익 + 법인세. 항등식 147/151(97.4%)이고 중단영업과 무관하다 —
        // 피가산수가 이미 「계속영업」이기 때문이다.
        BigDecimal continuing = v.get(SecEdgarColumn.INCOME_FROM_CONTINUING_OPERATIONS);
        if (continuing != null && tax != null) {
            return continuing.add(tax);
        }
        // 세전이익 = 총액순이익 + 법인세. **중단영업이 없는 회사에서만** 선다
        // (실측 95.9% 정확 · 97.6%가 1% 이내 · 최대 137%. 있는 회사는 55.9% · 최대 7,974%).
        BigDecimal total = v.get(SecEdgarColumn.NET_INCOME_TOTAL);
        if (noDiscontinued && total != null && tax != null) {
            return total.add(tax);
        }
        return v.get(SecEdgarColumn.PROFIT_BEFORE_TAX);
    }

    /**
     * <b>매출.</b> 은행은 <b>순이자손익 + 비이자수익</b>으로 유도하고, 나머지는 후보 태그를 쓴다.
     *
     * <p><b>왜 은행만 다른가</b>: 은행은 {@code Revenues}를 안 내거나 <b>좁은 하위 항목에 쓴다</b>
     * ({@code ZION} 662M인데 실제 3,385M · {@code RF} 58M인데 5,409M). 실측
     * (2026-09-15 · {@code US_TOP1000} 최신 FY): 은행형 69 중 <b>NULL 27 · 명백한 과소 11</b>.
     *
     * <p><b>MUST: 두 재료가 다 있을 때만 유도한다.</b> {@code NoninterestIncome}은 금융 전용
     * 태그가 아니어서({@code MPLX}가 매출의 80%를 그것으로 낸다) 하나만 보면 비금융이 걸린다.
     * 둘을 요구하면 전 유니버스에서 대상이 <b>정확히 53종목이고 전부 금융</b>이다(DB 전수).
     *
     * <p><b>MUST NOT: 소스가 준 값과 큰 쪽을 고르지 않는다.</b> {@code max}는 두 가지를 깨뜨린다 —
     * {@code STT} FY2023은 같은 10-K 안에서 {@code Revenues} 11,945M인데 유도값이 12,239M이라
     * <b>과대계상</b>이 되고({@code NII}·비이자 어디에도 안 드는 별도 라인 −294M),
     * {@code IBKR}은 해마다 승자가 갈려 <b>매출성장률 부호가 −14.0% ↔ +14.5%로 뒤집힌다.</b>
     * <b>스크리너는 성장률·5년 중앙값을 읽으므로 시계열 기저가 하나인 것이 셀 단위 충실도보다
     * 앞선다</b> — 그래서 재료가 있으면 언제나 유도다. 소스가 제대로 신고하는 자리에서는
     * 둘이 같다(같은 공시 안에서 <b>89.5%</b>가 1% 이내).
     */
    private static BigDecimal revenue(Map<SecEdgarColumn, BigDecimal> v) {
        BigDecimal netInterest = v.get(SecEdgarColumn.NET_INTEREST_INCOME);
        BigDecimal noninterest = v.get(SecEdgarColumn.NONINTEREST_INCOME);
        if (netInterest != null && noninterest != null) {
            return netInterest.add(noninterest);
        }
        return v.get(SecEdgarColumn.REVENUE);
    }

    private static BigDecimal totalEquity(Map<SecEdgarColumn, BigDecimal> v) {
        BigDecimal founded = foundedTotalEquity(v);
        return founded != null ? founded : v.get(SecEdgarColumn.TOTAL_EQUITY);
    }

    /** {@code |a − b| ≤ 1% × |b|}. */
    private static boolean within(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(b.abs().multiply(BALANCE_TOLERANCE)) <= 0;
    }

    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.01");

    /**
     * <b>자본 후보를 재무상태표 증거로 고른다.</b>
     *
     * <p><b>왜</b>: {@code TOTAL_EQUITY}의 1순위
     * ({@code …IncludingPortionAttributableToNoncontrollingInterest})를 <b>오태깅하는 회사가 있고</b>
     * {@code Selection.FIRST}가 그것을 무조건 집는다. {@code A}(Agilent) 2026 Q3은 원장이
     * {@code StockholdersEquity} <b>7,363M</b>을 정직하게 주고 같은 공시의 {@code 자산 − 부채}가
     * 그것을 확증하는데, 1순위가 <b>−233M</b>이라 오늘 {@code DEBT_RATIO}·{@code PBR}이 안 난다
     * ({@code DEBT_RATIO}는 활성 프리셋 다섯이 읽고 하나는 필수 게이트다).
     *
     * <p><b>증거는 {@code 자산 − 부채 − 중간지분}이다.</b> 중간지분을 빼지 않으면 뒤집힌다 —
     * {@code WEX} 2014-12-31은 원장이 {@code 3,024.0 + 16.6 + 1,077.7 = 4,118.3}으로 정확히 서는데,
     * 중간지분(16.6)과 {@code MinorityInterest}(17.4)가 우연히 거의 같아
     * <b>{@code 자산 − 부채}만 쓰면 정답 1,077.7을 1.52%로 탈락시키고 틀린 1,095.1을 통과시킨다.</b>
     * 이 보정으로 발화가 <b>319 → 336칸</b>이 되고 반대 방향 손해는 <b>0칸</b>이다(실측 436종목).
     *
     * <p><b>MUST: 정확히 하나만 맞을 때만 고른다.</b> 둘 다 맞으면 손댈 이유가 없고, 둘 다 안 맞으면
     * 중간지분을 우리가 못 본 자리라 <b>증거가 아니다</b>(캐시 1,240칸).
     *
     * <p><b>MUST: 부채는 소스가 준 값만 쓴다.</b> {@link #totalLiabilities}가 자본으로 부채를
     * 유도하므로, 유도된 부채를 증거로 쓰면 <b>순환</b>이 된다. 부채가 없으면 증거가 없으니
     * 현행 규칙으로 떨어진다.
     *
     * <p><b>MUST NOT: 후보 순서를 뒤집어 해결하지 않는다.</b> 1순위가 옳은 칸이 <b>138칸 · 28종목</b>
     * 이고({@code MS} 40 · {@code BNY} 25 · {@code ARES} 18), {@code ARES} 2016은 2순위가
     * <b>4.7배</b> 틀리다. 대칭으로 고르는 것이 요점이다.
     */
    private static BigDecimal equityByBalanceEvidence(Map<SecEdgarColumn, BigDecimal> v) {
        BigDecimal assets = v.get(SecEdgarColumn.TOTAL_ASSETS);
        BigDecimal liabilities = v.get(SecEdgarColumn.TOTAL_LIABILITIES);
        if (assets == null || liabilities == null) {
            return null;
        }
        BigDecimal target = assets.subtract(liabilities);
        BigDecimal mezzanine = v.get(SecEdgarColumn.MEZZANINE_EQUITY);
        if (mezzanine != null) {
            target = target.subtract(mezzanine);
        }
        if (target.signum() == 0) {
            return null;
        }
        BigDecimal including = v.get(SecEdgarColumn.TOTAL_EQUITY_INCLUDING_NCI);
        BigDecimal controlling = v.get(SecEdgarColumn.TOTAL_EQUITY_CONTROLLING);
        BigDecimal minority = v.get(SecEdgarColumn.MINORITY_INTEREST);
        BigDecimal summed = controlling == null ? null
                : (minority == null ? controlling : controlling.add(minority));
        boolean okIncluding = including != null && within(including, target);
        boolean okSummed = summed != null && within(summed, target);
        if (okIncluding == okSummed) {
            return null;
        }
        return okIncluding ? including : summed;
    }

    /**
     * <b>근거가 확실한 총자본만</b> — 소스가 직접 낸 값이거나 지배주주 자본 + 비지배지분이다.
     *
     * <p>{@link #totalEquity}의 마지막 폴백({@code TOTAL_EQUITY}의 첫값)과 갈라 두는 이유는
     * 그 폴백이 <b>지배주주분일 수 있기 때문</b>이다. 그것으로 부채를 유도하면 비지배지분만큼
     * 부채가 부풀고, <b>그 값은 그럴듯해서 화면에서 안 드러난다.</b>
     */
    private static BigDecimal foundedTotalEquity(Map<SecEdgarColumn, BigDecimal> v) {
        // 증거를 먼저 본다 — 「총자본」의 정의를 이 파일 안에서 하나로 유지한다.
        // 부채가 없을 때만 이 함수가 totalLiabilities에 쓰이므로 그 경로에서는 증거가
        // 원리적으로 안 켜진다(no-op). 정의가 갈리는 것을 막는 것이 목적이다.
        BigDecimal evidence = equityByBalanceEvidence(v);
        if (evidence != null) {
            return evidence;
        }
        BigDecimal direct = v.get(SecEdgarColumn.TOTAL_EQUITY_INCLUDING_NCI);
        if (direct != null) {
            return direct;
        }
        BigDecimal controlling = v.get(SecEdgarColumn.TOTAL_EQUITY_CONTROLLING);
        BigDecimal minority = v.get(SecEdgarColumn.MINORITY_INTEREST);
        return controlling != null && minority != null ? controlling.add(minority) : null;
    }

    /**
     * <b>총부채.</b> 소스가 {@code Liabilities}를 안 내면 <b>자산 − 총자본 − 중간지분</b>으로
     * 유도한다.
     *
     * <p><b>왜</b>: 그 태그가 없는 것이 드물지 않다 — 재적재 뒤 {@code US_TOP1000} 최신 FY
     * <b>1,000종목 중 225</b>가 비어 있었고, 그동안 {@code DEBT_RATIO}·{@code ALTMAN_Z}·
     * {@code ALTMAN_Z_DOUBLE_PRIME}·{@code NCAV_TO_MARKET}이 <b>그 종목에서 통째로 꺼져
     * 있었다.</b> 실측(표본 47): 부재 813칸 중 <b>611(75.2%)</b>이 닫히고, 답을 아는 1,845칸에서
     * 이 산수가 <b>1,729(93.7%)</b>를 0.1% 이내로 맞힌다 — 벗어난 116 중 <b>76이 1% 이내</b>이고
     * 5%를 넘는 것은 <b>7뿐</b>(6이 {@code YUM} — 자본잠식이라 분모가 작다).
     *
     * <p><b>MUST: 근거가 확실한 총자본으로만 유도한다</b>({@link #foundedTotalEquity}).
     * 폴백 총자본은 지배주주분일 수 있어 부채를 부풀린다.
     */
    private static BigDecimal totalLiabilities(Map<SecEdgarColumn, BigDecimal> v,
                                               boolean noNoncontrolling) {
        BigDecimal direct = v.get(SecEdgarColumn.TOTAL_LIABILITIES);
        if (direct != null) {
            return direct;
        }
        BigDecimal assets = v.get(SecEdgarColumn.TOTAL_ASSETS);
        BigDecimal equity = foundedTotalEquity(v);
        if (equity == null && noNoncontrolling) {
            // 그 회사가 **비지배지분 태그를 한 번도 안 낸다** — 비지배지분이 0이라는 뜻이고,
            // 그러면 지배주주 자본이 곧 총자본이다(대조군: 그런 회사 93개에서 두 자본 태그가
            // 같은 칸이 173/198 · 87.4%).
            //
            // MUST: 자본이 음수면 쓰지 않는다. 그 자리는 **상장 전 바이오의 전환우선주가
            // 중간지분에 있는** 경우이고 태그가 우리 후보에 안 걸린다 — 실측으로 자본 ≥ 0에서는
            // 97.2%가 정확한데(1% 이내 97.6%) 자본 < 0에서는 58.5%이고 최대 오차가 3,373%다.
            BigDecimal controlling = v.get(SecEdgarColumn.TOTAL_EQUITY_CONTROLLING);
            if (controlling != null && controlling.signum() >= 0) {
                equity = controlling;
            }
        }
        if (assets == null || equity == null) {
            return null;
        }
        BigDecimal mezzanine = v.get(SecEdgarColumn.MEZZANINE_EQUITY);
        BigDecimal liabilities = assets.subtract(equity);
        return mezzanine != null ? liabilities.subtract(mezzanine) : liabilities;
    }

    private static BigDecimal controllingEquity(Map<SecEdgarColumn, BigDecimal> v,
                                                boolean noNoncontrolling) {
        BigDecimal direct = v.get(SecEdgarColumn.TOTAL_EQUITY_CONTROLLING);
        BigDecimal total = v.get(SecEdgarColumn.TOTAL_EQUITY);
        if (direct != null) {
            // **MUST: 비지배를 한 번도 안 내는 회사인데 두 자본이 갈리면 직접 태그를 버린다**
            // (ADR-0159 §규칙). 비지배가 없으면 두 값은 정의상 같아야 하므로, 갈리는 것은
            // 회사가 `StockholdersEquity`에 다른 것을 실었다는 뜻이다. 실측 43행·6티커 —
            // NKE 2017 Q1 총자본 85,000,000 대 직접 12,165,000,000 · UNM 2021 FY −5,164.6M 대
            // 6,033.9M · NFG 2025 Q1 2,752.8M 대 −76.2M. 지금 순서는 직접이 언제나 이겨
            // **그 쓰레기 값을 ROE·PBR 분모로 승격시킨다.**
            if (noNoncontrolling && total != null && differsMaterially(direct, total)) {
                return total;
            }
            return direct;
        }
        BigDecimal minority = v.get(SecEdgarColumn.MINORITY_INTEREST);
        return total != null && minority != null ? total.subtract(minority) : null;
    }

    /** 두 자본이 1% 넘게 갈리는가. 임계는 ADR-0159 §규칙의 실측(43행·6티커)이 쓴 것과 같다. */
    private static boolean differsMaterially(BigDecimal a, BigDecimal b) {
        if (a.signum() != b.signum()) {
            return true;   // 부호가 다르면 크기와 무관하게 다른 개념이다
        }
        BigDecimal scale = b.abs().max(a.abs());
        if (scale.signum() == 0) {
            return false;
        }
        return a.subtract(b).abs()
                .divide(scale, 6, java.math.RoundingMode.HALF_UP)
                .compareTo(new BigDecimal("0.01")) > 0;
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
            List<RawFinancialReport> existing, Map<Label, Window> windows, int fromFiscalYear,
            Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn, boolean usGate) {
        Map<Label, RawFinancialReport> latest = new LinkedHashMap<>();
        Map<Label, List<RawFinancialReport>> byLabel = new LinkedHashMap<>();
        for (RawFinancialReport report : existing) {
            Label key = new Label(report.fiscalYear(), report.fiscalPeriod());
            latest.merge(key, report, (a, b) -> a.publishedAt().isAfter(b.publishedAt()) ? a : b);
            byLabel.computeIfAbsent(key, k -> new ArrayList<>()).add(report);
        }
        byLabel.values().forEach(판 -> 판.sort(Comparator.comparing(RawFinancialReport::publishedAt)));
        // 공시(accn)마다 **분기를 낸 회계연도**. 재제출 판정의 재료다 — 아래 §.
        Map<String, Set<Integer>> quarterYears = new HashMap<>();
        for (RawFinancialReport report : existing) {
            if (!"FY".equals(report.fiscalPeriod())) {
                quarterYears.computeIfAbsent(report.reportCode(), key -> new HashSet<>())
                        .add(report.fiscalYear());
            }
        }

        List<RawFinancialReport> derived = new ArrayList<>();
        for (Map.Entry<Label, RawFinancialReport> e : latest.entrySet()) {
            Label label = e.getKey();
            if (!"FY".equals(label.period()) || label.fiscalYear() < fromFiscalYear) {
                continue;
            }
            RawFinancialReport fy = e.getValue();
            Label q4 = new Label(label.fiscalYear(), "Q4");
            // MUST NOT: 소스가 4분기를 냈다고 유도를 통째로 건너뛰지 않는다.
            //
            // 사업보고서가 3개월을 함께 싣는 일은 실재하지만 **전 컬럼을 싣지는 않는다.**
            // 잔액만으로 4분기 라벨이 생기거나 유량 일부만 실린 자리가 많고, 건너뛰면
            // **나머지 컬럼이 영영 빈다.** 실측(캐시 1,003종목 · 2026-09-16):
            // 소스 4분기가 유도를 막는 라벨 **3,430 · 633종목**이고 FY·Q1~Q3에는 다 있는데
            // 그 라벨에서만 빈 유량 칸이 **7,503**이다(이자비용 2,202 · 매출원가 1,902 ·
            // 영업이익 1,283 · 총비용 961 · 판관비 768 …).
            //
            // 대신 **소스가 낸 값이 언제나 이긴다**({@link #mergeQ4}). 잔차는 빈칸만 채운다.

            // MUST: 라벨이 아니라 **기간 포함관계**로 분기를 고른다.
            //
            // 1월 결산 회사는 소스가 연간과 그 다음 회계연도의 분기에 **같은 회계연도**를
            // 붙인다(실측: RH의 FY가 2023-01-28에 끝나는데 같은 라벨의 Q3가 2023-10-29에
            // 끝난다). 라벨로 묶으면 시작이 끝보다 뒤인 행을 만들어 그 종목의 적재가
            // 통째로 죽는다 — 실제로 그렇게 났다.
            List<RawFinancialReport> inside = new ArrayList<>();
            for (RawFinancialReport candidate : latest.values()) {
                // MUST: **우리가 만들 라벨 자신은 빼고 센다.** 소스가 4분기를 냈으면 이 목록이
                // 넷이 되어 아래 `size() != 3`에 걸리고, 그것이 유도를 막는 진짜 문턱이다.
                // 위 §의 3,430 라벨이 여기서 끊겼다.
                if (candidate.fiscalYear() == label.fiscalYear()
                        && q4.period().equals(candidate.fiscalPeriod())) {
                    continue;
                }
                // **시작일에 허용오차를 준다.** 52/53주 회계력에서는 같은 회계연도의 창을
                // 보고서마다 며칠 다르게 적어, FY가 `2024-01-01~`인데 그 해 1분기가
                // `2023-12-31~`로 온다. 정확 일치를 요구하면 그 분기가 `inside`에서 빠져
                // **`size() != 3`으로 4분기 유도가 통째로 막힌다** — 실측(2026-09-16)으로
                // `FBIN` 2024/Q4가 그 자리다. A21-c가 값 조회 층에 건 것과 **같은 술어·같은 상수**다.
                if (!"FY".equals(candidate.fiscalPeriod())
                        && !candidate.periodStart().isBefore(
                                fy.periodStart().minusDays(START_TOLERANCE_DAYS))
                        && !candidate.periodEnd().isAfter(fy.periodEnd())) {
                    inside.add(candidate);
                }
            }
            inside.sort(Comparator.comparing(RawFinancialReport::periodEnd));
            if (inside.size() != 3
                    || Math.abs(ChronoUnit.DAYS.between(
                            inside.get(0).periodStart(), fy.periodStart())) > START_TOLERANCE_DAYS) {
                continue;
            }
            RawFinancialReport q3 = inside.get(2);
            LocalDate start = q3.periodEnd().plusDays(1);
            if (!start.isBefore(fy.periodEnd())) {
                continue;
            }
            RawFinancialReport q1 = inside.get(0);
            RawFinancialReport q2 = inside.get(1);
            // MUST: **FY 판과 분기 판의 세대를 맞춘다.**
            //
            // 지금까지는 라벨마다 최신 판을 골라 뺐다. 그런데 회사가 **FY만 다시 내고 분기는
            // 안 내는** 일이 실재한다 — 사업을 팔면 FY를 계속영업 기준으로 다시 쓰는데 그해
            // 분기는 이미 지나가서 다시 안 낸다. 그러면 최신 FY와 최신 분기가 **서로 다른
            // 세대**가 되고 뺄셈이 뜻을 잃는다.
            //
            // 실측(2026-09-14): 유도 4분기 매출이 음수인 25건 중 **22건이 이 모양**이고
            // (FY 판이 여럿 · FY가 분기보다 늦게 재작성) **22건 전부 더 이른 FY 판을 쓰면
            // 양수가 된다.** AIG 2022가 그것이다 — FY가 56,437 → 54,450 → **29,996**으로
            // 세 번 바뀌는 동안 분기는 2023-11 이후 한 번도 안 바뀌었다. 최신끼리 빼면
            // 29,996 − 42,605 = **−12,609**이고, 2024-02-14 판으로 빼면 **11,845**다.
            //
            // MUST NOT: 음수를 걸러 내는 것으로 대신하지 않는다 — 그것은 증상이다. 같은
            // 어긋남이 **그럴듯한 양수**로 앉으면 못 잡고, 잡더라도 값이 사라질 뿐이다.
            List<Label> quarterLabels = List.of(
                    new Label(q1.fiscalYear(), q1.fiscalPeriod()),
                    new Label(q2.fiscalYear(), q2.fiscalPeriod()),
                    new Label(q3.fiscalYear(), q3.fiscalPeriod()));
            boolean emitted = false;
            RawFinancialReport lastUsed = null;
            RawFinancialReport lastQ4 = null;
            RawFinancialReport fallback = null;
            LocalDate fallbackStart = null;
            RawFinancialReport f1 = null;
            RawFinancialReport f2 = null;
            RawFinancialReport f3 = null;
            for (RawFinancialReport vintage : byLabel.getOrDefault(label, List.of())) {
                RawFinancialReport v1 = vintageOf(byLabel, quarterLabels.get(0), vintage.publishedAt());
                RawFinancialReport v2 = vintageOf(byLabel, quarterLabels.get(1), vintage.publishedAt());
                RawFinancialReport v3 = vintageOf(byLabel, quarterLabels.get(2), vintage.publishedAt());
                if (v1 == null || v2 == null || v3 == null) {
                    continue;
                }
                LocalDate vintageStart = v3.periodEnd().plusDays(1);
                if (!vintageStart.isBefore(vintage.periodEnd())) {
                    continue;
                }
                fallback = vintage;
                fallbackStart = vintageStart;
                f1 = v1;
                f2 = v2;
                f3 = v3;
                if (lastUsed != null && restated(lastUsed, vintage)
                        && representedOtherYears(quarterYears, vintage, label.fiscalYear())) {
                    // **이 공시는 분기 계열을 다시 냈는데 이 해의 분기는 안 냈다.**
                    //
                    // 사업을 팔면 FY를 계속영업 기준으로 다시 쓰고, 그때 **아직 비교분기로 실을
                    // 수 있는 해**는 분기까지 다시 낸다. 실측(AIG 2025-02-13 10-K): FY를
                    // 2022·2023·2024로 내면서 **분기는 2023·2024만** 냈다. 그러니 2022의 FY는
                    // 그해 분기가 못 받은 기준이고, 그 둘을 빼면 뜻이 없다
                    // (29,996 − 42,605 = −12,609. 2024-02-14 세대로 빼면 11,845다).
                    //
                    // MUST NOT: 부호로 판정하지 않는다 — **한 분기 매출은 정당하게 음수일 수
                    // 있다.** KKR의 10-K가 2018 Q4 매출을 −178.1M으로 **직접 낸다**(자산운용사라
                    // 투자손익이 매출에 섞인다). 부호는 원인이 아니라 증상이고, 증상으로 자르면
                    // 정당한 값을 버린다.
                    //
                    // 분기를 하나도 안 낸 공시는 이 판정에 안 걸린다 — 재제출했다는 증거가
                    // 없으므로 보수적으로 쓴다(10-K/A가 연간만 정정하는 경우가 그렇다).
                    continue;
                }
                RawFinancialReport q4Report = mergeQ4(
                        vintageOf(byLabel, q4, vintage.publishedAt()),
                        buildQ4(label.fiscalYear(), vintageStart, vintage, v1, v2, v3, byColumn, usGate));
                // MUST: 값이 같은 4분기 판을 세대마다 다시 내지 않는다. FY 판이 늘면 잔차가
                // 같아도 Q4 판이 그만큼 늘고, 저장은 그것을 **정정으로 읽어 지표를 지운다** —
                // 쓰기는 0인데 무효화만 남는다. 실측: 접기를 고쳐 FY 판이 늘자 이 자리에서
                // **값이 같은 Q4 판 349개**가 덤으로 났다.
                // 남기는 것은 **가장 이른 판**이다 — 그 값은 그때 이미 알 수 있었다(ADR-0128).
                if (lastQ4 == null || storedDiffers(lastQ4, q4Report)) {
                    derived.add(q4Report);
                    lastQ4 = q4Report;
                }
                emitted = true;
                lastUsed = vintage;
            }
            if (!emitted && fallback != null) {
                // 어느 세대에서도 잔차가 성립하지 않는다(FY 판이 하나뿐인 경우가 그렇다).
                // **그래도 마지막 세대로 한 판을 낸다** — 불가능한 컬럼은 비운 채로.
                // 안 내면 이 라벨이 이번 세트에 없어 **회수가 안 돌고**(ADR-0152), 옛 규칙이
                // 만든 값이 is_latest인 채 영영 남는다. 실측으로 KKR 2018 −178 · MSGS 2020 −7 ·
                // CYTK 2017이 그 자리였다.
                derived.add(mergeQ4(
                        vintageOf(byLabel, q4, fallback.publishedAt()),
                        buildQ4(label.fiscalYear(), fallbackStart, fallback, f1, f2, f3, byColumn, usGate)));
            }
        }
        return derived;
    }

    /**
     * <b>소스가 낸 4분기 값을 잔차 위에 얹는다 — 소스가 이긴다.</b>
     *
     * <p><b>왜</b>: 사업보고서가 3개월을 함께 싣는 일은 실재하지만 <b>전 컬럼을 싣지는 않는다.</b>
     * 그래서 「소스가 4분기를 냈으면 유도하지 않는다」로 두면 <b>나머지 컬럼이 영영 빈다</b> —
     * 실측 <b>3,430 라벨 · 633종목 · 빈칸 7,503</b>.
     *
     * <p><b>MUST: 소스 값을 잔차로 덮지 않는다.</b> 회사가 직접 낸 3개월 값이 우리 뺄셈보다 앞선다.
     * 잔차는 <b>소스가 안 낸 칸만</b> 채운다.
     *
     * <p><b>MUST: 세대를 맞춘다.</b> 얹는 소스 4분기는 {@code vintageOf}로 고른 <b>그 FY 세대
     * 이하의 최신 판</b>이다 — 뒤 세대의 소스 값을 앞 세대 잔차에 얹으면 한 행이 두 공시의
     * 혼합이 된다.
     *
     * <p><b>못 닫는 자리 — 전수로 다시 쟀다.</b> 이 변경 뒤에도 「latest Q4의 칸이 비었는데
     * FY와 분기 셋에는 있다」가 <b>460칸 / 347라벨</b> 남는다(변경 전 7,942칸 / 3,608라벨).
     * 그중 <b>85칸 / 44라벨</b>만이 「소스 4분기 판이 FY 판보다 늦다」로 설명되고,
     * <b>나머지 375칸 / 303라벨은 아직 사유를 안 댔다</b>
     * (매출원가 146 · 이자비용 106 · 총비용 59 · 매출 42).
     */
    private static RawFinancialReport mergeQ4(RawFinancialReport source,
                                              RawFinancialReport residual) {
        if (source == null) {
            return residual;
        }
        return new RawFinancialReport(
                residual.fiscalYear(), residual.fiscalPeriod(),
                residual.periodStart(), residual.periodEnd(), residual.publishedAt(),
                residual.reportCode(), residual.currencyCode(),
                pick(source.revenue(), residual.revenue()),
                pick(source.operatingIncome(), residual.operatingIncome()),
                pick(source.netIncome(), residual.netIncome()),
                pick(source.totalAssets(), residual.totalAssets()),
                pick(source.totalEquity(), residual.totalEquity()),
                pick(source.totalEquityControlling(), residual.totalEquityControlling()),
                pick(source.totalLiabilities(), residual.totalLiabilities()),
                pick(source.currentAssets(), residual.currentAssets()),
                pick(source.currentLiabilities(), residual.currentLiabilities()),
                pick(source.retainedEarnings(), residual.retainedEarnings()),
                pick(source.operatingCashFlow(), residual.operatingCashFlow()),
                pick(source.sharesOutstanding(), residual.sharesOutstanding()),
                pick(source.eps(), residual.eps()),
                pick(source.bps(), residual.bps()),
                pick(source.costOfSales(), residual.costOfSales()),
                pick(source.cashAndEquivalents(), residual.cashAndEquivalents()),
                pick(source.capex(), residual.capex()),
                pick(source.inventories(), residual.inventories()),
                pick(source.netIncomeControlling(), residual.netIncomeControlling()),
                pick(source.interestAndDividendIncome(), residual.interestAndDividendIncome()),
                pick(source.noninterestIncome(), residual.noninterestIncome()),
                pick(source.interestExpense(), residual.interestExpense()),
                pick(source.noninterestExpense(), residual.noninterestExpense()),
                pick(source.benefitsLossesAndExpenses(), residual.benefitsLossesAndExpenses()),
                pick(source.grossProfit(), residual.grossProfit()),
                pick(source.operatingExpenses(), residual.operatingExpenses()),
                pick(source.costsAndExpenses(), residual.costsAndExpenses()),
                pick(source.incomeTaxExpense(), residual.incomeTaxExpense()),
                pick(source.profitBeforeTax(), residual.profitBeforeTax()),
                // MUST: 소스가 그 분기를 낸 라벨은 **유도가 아니다.**
                //
                // `derived`는 「우리가 만든 행」이라는 표시이고, 참이면
                // `FinancialWriter.fillMissing`의 `toClear`가 **그 행의 값을 지울 수 있다.**
                // 소스가 낸 3개월 값을 그 경로에 넣으면 안 된다.
                //
                // **MUST NOT: 여기에 `V46` 마이그레이션을 근거로 대지 않는다 — 거짓이다.**
                // 처음 그렇게 적었는데 재보니 **`financial_statement`에 `derived` 컬럼이 없고**
                // (`information_schema` 실측 0) **V46은 그 값을 읽지 않는다**(술어는
                // `fiscal_period = 'Q4'`와 통화가 갈린 FY뿐이다). 통화 방어는 `buildQ4`의
                // `sameCurrency` 게이트가 한다. **근거는 `toClear` 하나다.**
                //
                // **[2026-09-22] 위 마지막 문단의 「지금 회수 대상이 아니다」는 닫혔다.**
                // 컬럼 단위 출처(`RawFinancialReport.assertedAbsent`)가 생겨, 행 전체를 유도로
                // 돌리지 않고 **그 컬럼만** 회수할 수 있다. `derived`는 여전히 false다 —
                // 소스가 낸 3개월 값을 유도 취급하지 않는다는 위 근거는 그대로 선다.
                false,
                // 합친 값이 null인데 **두 판 중 하나라도 「없다」고 판정했으면** 그것은 판정이다.
                // 둘 다 그냥 안 실었을 뿐이면 빈 집합이 되어 지금까지와 똑같이 동작한다.
                mergedAbsence(source, residual));
    }

    private static BigDecimal pick(BigDecimal source, BigDecimal residual) {
        return source != null ? source : residual;
    }

    /**
     * 병합본의 <b>컬럼 단위 부재 판정.</b> 합친 값이 {@code null}이고 두 판 중 하나라도 그
     * 컬럼을 「없다」고 판정했으면 판정을 잇는다.
     *
     * <p><b>MUST: 합친 값을 다시 본다.</b> 한쪽이 「없다」고 해도 다른 쪽이 값을 냈으면
     * 병합본에는 값이 있고, 그때 부재를 이으면 <b>저장이 멀쩡한 값을 지운다.</b>
     */
    private static java.util.Set<String> mergedAbsence(RawFinancialReport source,
                                                       RawFinancialReport residual) {
        java.util.Set<String> merged = new java.util.LinkedHashSet<>(source.assertedAbsent());
        merged.addAll(residual.assertedAbsent());
        if (merged.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Set<String> kept = new java.util.LinkedHashSet<>();
        if (merged.contains(RawFinancialReport.NET_INCOME)
                && pick(source.netIncome(), residual.netIncome()) == null) {
            kept.add(RawFinancialReport.NET_INCOME);
        }
        return kept;
    }

    /**
     * 한 세대의 4분기 한 판. <b>FY와 분기 셋이 같은 세대여야 한다</b> — 그 판정은 호출부에 있다.
     */
    private static RawFinancialReport buildQ4(int fiscalYear, LocalDate start,
                                              RawFinancialReport fy, RawFinancialReport q1,
                                              RawFinancialReport q2, RawFinancialReport q3,
                                              Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn,
                                              boolean usGate) {
        boolean subtractable = sameCurrency(fy, q1, q2, q3);
        Slot slot = new Slot(accnOf(fy.reportCode()), start, fy.periodEnd());
        return new RawFinancialReport(
                fiscalYear, "Q4", start, fy.periodEnd(), fy.publishedAt(),
                fy.reportCode(), fy.currencyCode(),
                sourceOrMinus(byColumn, SecEdgarColumn.REVENUE, slot, subtractable, fy.revenue(),
                            q1.revenue(), q2.revenue(), q3.revenue()),
                sourceOrMinus(byColumn, SecEdgarColumn.OPERATING_INCOME, slot, subtractable, fy.operatingIncome(),
                            q1.operatingIncome(), q2.operatingIncome(), q3.operatingIncome()),
                // **MUST: Q4도 두 컬럼을 다 만든다.** 여기가 한쪽만 채우면
                // IndicatorDataReader의 TTM 창(분기 넷)에 Q4가 들어가는 순간
                // sumControllingNetIncome이 **창 단위 전부-아니면-전무**라 통째로 netIncome
                // 합으로 떨어진다 — 실측(2026-09-21) 편입 미국 983종목 중 **960(97.7%)**의
                // 최신 TTM 창에 Q4 행이 든다. 그러면 PER·EPS_TTM의 분자가 연결이 되고
                // roeBasis의 지배주주 짝이 **원리적으로 안 켜진다.**
                consolidatedIncomeQ4(byColumn, slot, subtractable, fy, q1, q2, q3),
                // 잔액은 차감하지 않는다 — 연말 잔액이 곧 Q4 말 잔액이다.
                fy.totalAssets(), fy.totalEquity(), fy.totalEquityControlling(),
                fy.totalLiabilities(),
                fy.currentAssets(), fy.currentLiabilities(), fy.retainedEarnings(),
                // 현금흐름·capex는 연간만 담으므로 Q4에도 담지 않는다.
                null, fy.sharesOutstanding(), null, null,
                sourceOrMinus(byColumn, SecEdgarColumn.COST_OF_SALES, slot, subtractable, fy.costOfSales(),
                            q1.costOfSales(), q2.costOfSales(), q3.costOfSales()),
                fy.cashAndEquivalents(), null, fy.inventories(),
                controllingIncomeQ4(byColumn, slot, subtractable, fy, q1, q2, q3, usGate),
                // 유도 재료 여덟은 전부 유량이라 차감한다. 하나라도 없으면 minus가
                // null을 내므로 Q4 영업이익이 지어지지 않는다 — 그것이 옳다.
                guardedMinus(SecEdgarColumn.INTEREST_AND_DIVIDEND_INCOME, subtractable, fy.interestAndDividendIncome(),
                        q1.interestAndDividendIncome(), q2.interestAndDividendIncome(), q3.interestAndDividendIncome()),
                guardedMinus(SecEdgarColumn.NONINTEREST_INCOME, subtractable, fy.noninterestIncome(),
                        q1.noninterestIncome(), q2.noninterestIncome(), q3.noninterestIncome()),
                guardedMinus(SecEdgarColumn.INTEREST_EXPENSE, subtractable, fy.interestExpense(),
                        q1.interestExpense(), q2.interestExpense(), q3.interestExpense()),
                guardedMinus(SecEdgarColumn.NONINTEREST_EXPENSE, subtractable, fy.noninterestExpense(),
                        q1.noninterestExpense(), q2.noninterestExpense(), q3.noninterestExpense()),
                guardedMinus(SecEdgarColumn.BENEFITS_LOSSES_AND_EXPENSES, subtractable, fy.benefitsLossesAndExpenses(),
                        q1.benefitsLossesAndExpenses(), q2.benefitsLossesAndExpenses(), q3.benefitsLossesAndExpenses()),
                sourceOrMinus(byColumn, SecEdgarColumn.GROSS_PROFIT, slot, subtractable, fy.grossProfit(),
                            q1.grossProfit(), q2.grossProfit(), q3.grossProfit()),
                guardedMinus(SecEdgarColumn.OPERATING_EXPENSES, subtractable, fy.operatingExpenses(),
                        q1.operatingExpenses(), q2.operatingExpenses(), q3.operatingExpenses()),
                guardedMinus(SecEdgarColumn.COSTS_AND_EXPENSES, subtractable, fy.costsAndExpenses(),
                        q1.costsAndExpenses(), q2.costsAndExpenses(), q3.costsAndExpenses()),
                // 세율 재료는 annualOnly라 분기 판에 값이 없다 — 차감할 것이 없다.
                null, null,
                // 우리가 만든 행이다. 저장이 이것을 알아야 유도 규칙을 고쳤을 때 옛 값을
                // 지운다 — 재수집 경로는 NULL만 채우고 값을 NULL로 덮지 않기 때문이다.
                true,
                // derived=true라 지우기는 이미 열려 있다. 그래도 같은 사실을 같은 자리에 싣는
                // 이유는 mergeQ4가 이 판과 소스 판을 합치면서 **derived를 잃을 수 있기** 때문이다.
                assertedAbsentOf(consolidatedIncomeQ4(byColumn, slot, subtractable, fy, q1, q2, q3)));
    }

    /**
     * <b>이 세대에서 잔차가 성립하는가.</b>
     *
     * <p>4분기는 정의상 <b>잔차</b>({@code FY − Q1 − Q2 − Q3})다. 그 산수가 뜻을 가지려면 넷이
     * <b>같은 것을 재고 있어야</b> 하는데, 회사가 <b>FY만 다시 내고 분기는 안 내는</b> 일이
     * 실재한다 — 사업을 팔면 FY를 계속영업 기준으로 다시 쓰고 그해 분기는 이미 지나가서 다시
     * 안 낸다.
     *
     * <p><b>매출·매출원가처럼 음수일 수 없는 유량에서 잔차가 음수면 그것은 「4분기가 음수다」가
     * 아니라 「FY와 분기가 같은 것을 재고 있지 않다」는 뜻이다</b> — 잔차의 정의역 밖이다.
     * 그때는 <b>앞 세대로 물러난다</b>. 실측(2026-09-14)으로 음수 25건 중 <b>22건이 이 모양</b>이고
     * <b>22건 전부 더 이른 FY 판을 쓰면 잔차가 성립한다</b>({@code AIG} 2022: 최신끼리 빼면
     * 29,996 − 42,605 = −12,609이고 2024-02-14 세대로 빼면 <b>11,845</b>다).
     *
     * <p><b>MUST NOT: 이것을 「음수를 걸러 낸다」로 읽지 않는다.</b> 값을 버리는 것이 아니라
     * <b>산수가 성립하는 세대를 고르는 것</b>이고, 그래서 결과는 결측이 아니라 <b>옳은 값</b>이다.
     *
     * <p><b>한계</b> — 같은 어긋남이 <b>그럴듯한 양수</b>로 앉으면 이 판정이 못 잡는다. 재작성이
     * 어느 분기에 귀속되는지는 소스가 말하지 않으므로 그것을 가를 재료가 지금 없다.
     */
    /**
     * <b>이 공시가 분기 계열을 다시 냈는데 이 해의 분기는 안 냈는가.</b>
     *
     * <p>「기준이 바뀌었다」의 <b>직접 증거</b>다. 재제출 공시는 <b>아직 비교분기로 실을 수 있는
     * 해</b>의 분기까지 다시 내고, 너무 오래된 해는 못 낸다 — 10-Q가 직전 연도 비교분기만 싣기
     * 때문이다. 그래서 <b>「다른 해 분기는 냈는데 이 해는 안 냈다」</b>가 그 해의 FY와 분기가
     * 서로 다른 기준이라는 뜻이 된다.
     *
     * <p><b>분기를 하나도 안 낸 공시는 걸리지 않는다.</b> 재제출했다는 증거가 없으므로 보수적으로
     * 쓴다 — 10-K/A가 연간 총액만 정정하는 경우가 그렇고, 그때 그 차이를 4분기 잔차가 흡수하는
     * 것이 내부적으로 정합적이다.
     *
     * <p><b>MUST NOT: 부호로 판정하지 않는다.</b> 한 분기 매출은 <b>정당하게 음수일 수 있다</b> —
     * {@code KKR}의 10-K가 2018 Q4 매출을 <b>−178.1M으로 직접 낸다</b>(자산운용사라 투자손익이
     * 매출에 섞인다). 부호는 원인이 아니라 증상이고, 증상으로 자르면 정당한 값을 버린다.
     */
    private static boolean representedOtherYears(Map<String, Set<Integer>> quarterYears,
                                                 RawFinancialReport vintage, int fiscalYear) {
        Set<Integer> years = quarterYears.getOrDefault(vintage.reportCode(), Set.of());
        if (years.isEmpty() || years.contains(fiscalYear)) {
            return false;
        }
        // MUST NOT: **「자기 해가 아닌 분기」로 조이지 않는다 — 조여 보고 되돌렸다.**
        //
        // 「그 공시의 최신 FY 연도가 아닌 해의 분기를 냈을 때만」으로 좁히면 **분사·매각
        // 재작성을 놓친다.** 재작성 10-K가 자기 해의 4분기만 싣는 경우가 많기 때문이다.
        // 실측(2026-09-15): 조인 뒤 유도 4분기 음수 매출이 **4 → 34**로 늘었고 그 안에
        // {@code DLTR} 2024(−5,183M · Family Dollar 매각) · {@code AWI} 2015~17(분사) ·
        // {@code BGC} 2016~17(Newmark 분사) · {@code APP} 2023이 들어 있었다.
        //
        // 조인 근거였던 「세대가 뒤진 991 종목연도 중 744」는 **분기 합과 FY의 차이가 1%
        // 미만**이라 무해하다. **작은 불일치 744와 TTM을 오염시키는 음수 30을 맞바꾸는 것은
        // 잘못된 거래다** — 음수 한 분기는 OPERATING_MARGIN·PSR을 부풀린 채 그럴듯하게 나간다.
        return true;
    }

    /**
     * <b>같은 공시가 그 4분기를 직접 냈으면 그 값을 쓴다.</b> 없을 때만 뺀다.
     *
     * <p>회사가 자기 보고서에 4분기를 안 태그해도 <b>뒤 공시의 비교표로 그것을 내는 일이 있다.</b>
     * 실측(2026-09-15 · {@code DLTR}): FY2024 10-K는 4분기를 안 태그했고, 1년 뒤 10-K가
     * Family Dollar를 중단영업으로 옮기며 <b>재작성 FY(16,781M)와 그 4분기(4,961M)를 함께</b> 냈다.
     * 그런데 그 4분기는 <b>라벨이 안 생긴다</b> — {@code labelPeriods}가 자기 기간 팩트만 라벨을
     * 만들기 때문이다. 그래서 유도가 대신 돌아 <b>−5,183M</b>이 저장됐다(FY는 재작성본, 분기는
     * 원본이라 세대가 어긋난 뺄셈이다).
     *
     * <p><b>MUST: 같은 accn의 팩트만 쓴다.</b> 그러면 FY와 4분기가 <b>같은 공시에서 왔음이
     * 보장되어</b> 세대가 원리적으로 맞는다. 다른 공시의 것을 끌어오면 지금 고친 세대 불일치를
     * 다른 문으로 다시 들인다.
     *
     * <p><b>MUST NOT: 비교표로 라벨을 만들어 해결하지 않는다 — 만들어 보고 걷었다.</b>
     * 라벨이 생기면 그 창을 비교표로 실은 <b>모든 공시가 보고자</b>가 되어 판이 폭증한다
     * (실측: {@code BNY}가 보고서 91 → 158, 새 판 88 중 <b>79가 매출 null</b>). 그리고 소스
     * 4분기가 일부 컬럼만 주면 <b>유도가 통째로 막혀 나머지 컬럼을 잃는다</b>
     * ({@code DLTR}이 매출원가를 잃었다).
     *
     * <p><b>그 기각은 「전면 라벨 생성」에 붙는다 — 조건부 폴백까지 덮는지는 재지 않았다.</b>
     * 폭증의 기제는 <b>한 창을 비교표로 실은 모든 공시가 보고자가 되는 것</b>이라,
     * 「자기 보고서 앵커가 그 기간을 <b>끝내</b> 안 줄 때만」으로 좁히면 보고자가 원리적으로
     * 하나다. 그 좁힌 조건에 걸리는 것이 <b>16종목 32회계연도</b>이고({@link #labelPeriods}의
     * 비교표 갈래 주석 · 2026-09-20 실측), 지금 그 회계연도는 <b>통째로 없다.</b>
     * <b>MUST: 그 폴백을 올리기 전에 {@code BNY} 실측(91 → 158)을 좁힌 조건으로 다시 낸다</b> —
     * 안 내고 올리면 이 기각을 근거 없이 두 번째로 무시하는 것이고, 안 내고 접으면
     * 「기각돼 있다」를 근거로 가짜 한계를 사는 것이다.
     */
    /**
     * Q4의 <b>연결 총액</b> 순이익. {@link #consolidatedIncome}과 같은 네 갈래이고
     * 마지막 갈래만 다르다 — 소스가 그 분기를 안 내면 <b>FY − (Q1+Q2+Q3)</b>로 뺀다.
     *
     * <p><b>피감수와 감수가 같은 기저다</b> — 연간·분기 행이 모두 이 함수를 거쳤으므로
     * 뺄셈이 기저를 섞지 않는다. {@code subtractable}이 거짓이면(통화가 갈리면) 빼지 않는다.
     */
    private static BigDecimal consolidatedIncomeQ4(
            Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn, Slot slot,
            boolean subtractable, RawFinancialReport fy, RawFinancialReport q1,
            RawFinancialReport q2, RawFinancialReport q3) {
        BigDecimal direct = sourceAt(byColumn, SecEdgarColumn.NET_INCOME_TOTAL, slot);
        if (direct != null) {
            return direct;
        }
        BigDecimal controlling = sourceAt(byColumn, SecEdgarColumn.NET_INCOME_CONTROLLING, slot);
        BigDecimal nonControlling =
                sourceAt(byColumn, SecEdgarColumn.NET_INCOME_NONCONTROLLING, slot);
        if (nonControlling == null) {
            BigDecimal redeemable = sourceAt(
                    byColumn, SecEdgarColumn.NET_INCOME_NONCONTROLLING_REDEEMABLE, slot);
            BigDecimal nonRedeemable = sourceAt(
                    byColumn, SecEdgarColumn.NET_INCOME_NONCONTROLLING_NONREDEEMABLE, slot);
            nonControlling = redeemable != null && nonRedeemable != null
                    ? redeemable.add(nonRedeemable) : null;
        }
        if (controlling != null && nonControlling != null) {
            return controlling.add(nonControlling);
        }
        // 소스가 이 분기를 안 냈다. FY와 분기 셋이 이미 연결 기저이므로 뺄셈이 성립한다.
        return sourceOrMinus(byColumn, SecEdgarColumn.NET_INCOME_TOTAL, slot, subtractable,
                fy.netIncome(), q1.netIncome(), q2.netIncome(), q3.netIncome());
    }

    /**
     * Q4 <b>지배주주</b> 순이익. <b>연간 행과 같은 기저라야 한다</b>(ADR-0154 §규칙) — 그렇지 않으면
     * 한 종목의 시계열 안에서 분기만 비지배 포함이 되고, {@code EPS_TTM}처럼 네 분기를 더하는 지표가
     * 서로 다른 기저를 더한다.
     *
     * <p>순서: 소스가 낸 지배주주 값 → 소스가 낸 연결에서 비지배를 뺀 값 → 소스가 낸 연결 그대로 → 우리 뺄셈.
     *
     * <p><b>마지막 갈래는 미국 관문 설정({@code usGate})이 켜지면 지배주주끼리 뺀다.</b> 꺼진 상태(관문 전 운영)는
     * {@code fy.netIncome() − Σq.netIncome()}인데 ADR-0159 뒤로 {@code net_income}이 연결이라 <b>Q4 지배주주 =
     * Q4 연결</b>이 되고, 연결이 게이트에서 null이면 null이 된다 — 미국 편입 · Q4 끝 ≥ 2020-07에서 틀린 라벨
     * 1,325·421종목 · 빈 칸 197·95(2026-10-04). 켜면 같은 해 네 분기의 지배주주 합이 FY 지배주주와 같아진다.
     *
     * <p><b>MUST NOT: 「FY는 비지배가 있는데 어떤 분기는 지배 = 연결」일 때 연결 뺄셈으로 되돌리는 가드를 두지 않는다.</b>
     * 그 가드는 칸 하나의 음수(ARES 2024 Q4 −60.5)를 막지만 소비자 TTM에서는 더 틀린다 — 사후 진실 기준
     * 틀림 셀-일이 창 5,282 대 4,133 · 1년 전 TTM 4,934 대 3,146 · 5년 전 TTM 7,818 대 6,929(2026-10-04).
     * 그 분기들의 폴백이 뿌리이고 고칠 자리는 분기다.
     *
     * <p><b>소스 Q4가 연결만 실은 셋째 갈래는 이 설정이 안 바꾼다.</b> 그것을 잔차로 바꾸는 것은 소스 대 잔차를 고르는
     * Q4 정책 결정이고, 바꿔 본 변형은 맞던 소스값 14칸을 잃었다(ACGL·CNX·STRL·WTM·JLL·MTCH) —
     * 재적재 관문의 Q4 정책 설계가 다시 판정한다(`docs/reload-gate.md`).
     */
    private static BigDecimal controllingIncomeQ4(
            Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn, Slot slot,
            boolean subtractable, RawFinancialReport fy, RawFinancialReport q1,
            RawFinancialReport q2, RawFinancialReport q3, boolean usGate) {
        BigDecimal direct = sourceAt(byColumn, SecEdgarColumn.NET_INCOME_CONTROLLING, slot);
        if (direct != null) {
            return direct;
        }
        BigDecimal total = sourceAt(byColumn, SecEdgarColumn.NET_INCOME_TOTAL, slot);
        if (total != null) {
            BigDecimal nonControlling =
                    sourceAt(byColumn, SecEdgarColumn.NET_INCOME_NONCONTROLLING, slot);
            if (nonControlling == null) {
                BigDecimal redeemable = sourceAt(
                        byColumn, SecEdgarColumn.NET_INCOME_NONCONTROLLING_REDEEMABLE, slot);
                BigDecimal nonRedeemable = sourceAt(
                        byColumn, SecEdgarColumn.NET_INCOME_NONCONTROLLING_NONREDEEMABLE, slot);
                nonControlling = redeemable != null && nonRedeemable != null
                        ? redeemable.add(nonRedeemable) : null;
            }
            return nonControlling != null ? total.subtract(nonControlling) : total;
        }
        if (usGate) {
            return sourceOrMinus(byColumn, SecEdgarColumn.NET_INCOME_CONTROLLING, slot, subtractable,
                    fy.netIncomeControlling(), q1.netIncomeControlling(), q2.netIncomeControlling(),
                    q3.netIncomeControlling());
        }
        return sourceOrMinus(byColumn, SecEdgarColumn.NET_INCOME, slot, subtractable,
                fy.netIncome(), q1.netIncome(), q2.netIncome(), q3.netIncome());
    }

    /** 그 칸에 소스가 낸 팩트. 없으면 null — 여기서는 유도하지 않는다. */
    private static BigDecimal sourceAt(Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn,
                                       SecEdgarColumn column, Slot slot) {
        for (Map<Slot, Fact> slots : byColumn.getOrDefault(column, Map.of()).values()) {
            Fact fact = slots.get(slot);
            if (fact != null) {
                return fact.value();
            }
        }
        return null;
    }

    /**
     * <b>잔차 하나 — 불가능한 답은 안 낸다.</b> {@link #sourceOrMinus}의 가드와 같은 술어를 쓴다.
     *
     * <p><b>왜 따로 있는가</b>: {@code buildQ4}의 일곱 유량은 소스 슬롯을 안 보고 {@code minus}를
     * 직접 불렀다. 그래서 <b>가드를 한 번도 안 거쳤고</b>, 4분기 유도를 연 순간 그 구멍으로
     * 불가능한 값이 들어왔다({@link SecEdgarColumn#nonNegativeFlow}의 §에 수가 있다).
     *
     * <p><b>소스 값은 이 함수를 안 거친다</b> — {@code mergeQ4}가 그 위에 얹으므로,
     * 회사가 직접 낸 음수 비용은 그대로 남는다.
     */
    private static BigDecimal guardedMinus(SecEdgarColumn column, boolean subtractable,
                                           BigDecimal annual, BigDecimal... quarters) {
        if (!subtractable) {
            return null;
        }
        BigDecimal residual = minus(annual, quarters);
        if (residual != null && residual.signum() < 0 && column.nonNegativeFlow()) {
            return null;
        }
        return residual;
    }

    private static BigDecimal sourceOrMinus(Map<SecEdgarColumn, Map<String, Map<Slot, Fact>>> byColumn,
                                            SecEdgarColumn column, Slot slot,
                                            boolean subtractable, BigDecimal annual,
                                            BigDecimal... quarters) {
        BigDecimal source = sourceAt(byColumn, column, slot);
        if (source != null) {
            return source;
        }
        if (!subtractable) {
            return null;
        }
        BigDecimal residual = minus(annual, quarters);
        if (residual != null && residual.signum() < 0 && column.nonNegativeFlow()) {
            // **우리 뺄셈이 불가능한 답을 냈다.** 매출·매출원가는 한 분기에 음수일 수 없는데,
            // FY는 재작성본이고 그 컬럼의 분기는 원본이면 음수가 나온다 — 실측(DLTR 2024):
            // 같은 공시가 **매출은 다시 냈고 매출원가는 안 다시 냈다.** 세대 판정은 판 단위라
            // 이 컬럼별 어긋남을 못 잡는다.
            //
            // **MUST: 소스가 준 값에는 이 가드를 걸지 않는다.** 위 갈래가 먼저 돌아 소스 팩트를
            // 그대로 돌려주므로 여기 오는 것은 **우리 산수뿐**이다. 한 분기 매출은 정당하게
            // 음수일 수 있고({@code KKR}의 10-K가 2018 Q4를 −178.1M으로 직접 낸다) 그것은
            // 애초에 유도를 거치지 않는다.
            return null;
        }
        return residual;
    }

    /** {@code form:accn}에서 accn만. {@link #toReport}가 그 형태로 만든다. */
    private static String accnOf(String reportCode) {
        int colon = reportCode.indexOf(':');
        return colon < 0 ? reportCode : reportCode.substring(colon + 1);
    }

    /** 두 판의 <b>유량</b>이 하나라도 다른가. 잔액은 보지 않는다 — 뺄셈에 안 들어간다. */
    private static boolean restated(RawFinancialReport a, RawFinancialReport b) {
        return differs(a.revenue(), b.revenue())
                || differs(a.operatingIncome(), b.operatingIncome())
                || differs(a.netIncome(), b.netIncome())
                || differs(a.costOfSales(), b.costOfSales())
                || differs(a.grossProfit(), b.grossProfit())
                || differs(a.operatingExpenses(), b.operatingExpenses())
                || differs(a.costsAndExpenses(), b.costsAndExpenses())
                || differs(a.benefitsLossesAndExpenses(), b.benefitsLossesAndExpenses());
    }

    private static boolean differs(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a != b;
        }
        return a.compareTo(b) != 0;
    }

    /** 그 라벨의 판 중 {@code at} 이하에서 가장 늦은 것. 없으면 {@code null}. */
    private static RawFinancialReport vintageOf(Map<Label, List<RawFinancialReport>> byLabel,
                                                Label label, LocalDate at) {
        RawFinancialReport found = null;
        for (RawFinancialReport candidate : byLabel.getOrDefault(label, List.of())) {
            if (!candidate.publishedAt().isAfter(at)) {
                found = candidate;
            }
        }
        return found;
    }

    /** 분기 셋 중 하나라도 {@code (after, until]} 사이에 다시 나왔는가. */
    private static boolean quarterRefiledBetween(Map<Label, List<RawFinancialReport>> byLabel,
                                                 List<Label> quarters, LocalDate after,
                                                 LocalDate until) {
        for (Label quarter : quarters) {
            for (RawFinancialReport candidate : byLabel.getOrDefault(quarter, List.of())) {
                if (candidate.publishedAt().isAfter(after)
                        && !candidate.publishedAt().isAfter(until)) {
                    return true;
                }
            }
        }
        return false;
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
