package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.indicator.GovernanceMetric;
import com.stockscreener.pipeline.datasource.GovernanceDerivation;
import com.stockscreener.pipeline.datasource.GovernanceEndpoint;
import com.stockscreener.pipeline.datasource.GovernanceFact;
import com.stockscreener.pipeline.datasource.GovernanceFactMapper;
import com.stockscreener.pipeline.datasource.GovernanceReportRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DART 응답 행을 {@link GovernanceMetric}으로 옮긴다. <b>벤더 지식이 여기서 끝난다</b>(ADR-0081).
 *
 * <p>클래스와 생성자가 <b>package-private</b>인 것이 의도다. {@code collector}는
 * {@link GovernanceFactMapper} 인터페이스만 알고, 벤더 이름이 그 패키지의 코드에 등장하지 않는다
 * (ADR-0006). 같은 패키지에 있어야 숫자 파싱의 정본인 {@link DartAccountMapper#parseAmount}를
 * 다시 짜지 않는다 — 콤마와 센티널 {@code -}의 규칙이 두 벌이 되면 한쪽만 고쳐진다.
 *
 * <p>모든 행 선택 규칙의 근거는 {@code docs/api-spec/dart-governance.md}의
 * <b>실측</b>이다. 개발가이드와 갈리는 자리가 넷이며 그때는 실측이 정본이다.
 */
@Component
class DartGovernanceFactMapper implements GovernanceFactMapper {

    private static final Logger log = LoggerFactory.getLogger(DartGovernanceFactMapper.class);

    /** {@code DartGovernanceAdapter.supportedMarketCodes}와 같은 값이다. 갈리면 그 시장이 조용히 빈다. */
    @Override
    public java.util.Set<String> supportedMarketCodes() {
        return java.util.Set.of("KOSPI", "KOSDAQ");
    }

    /** 3개년을 열로 주는 표의 열 이름과 그 열이 가리키는 회계연도의 뒤로 물린 해 수. */
    private static final Map<String, Integer> COLUMN_OFFSETS =
            Map.of("thstrm", 0, "frmtrm", 1, "lwfr", 2);

    /**
     * 기수 표기의 구분 토큰. 공백·개행 변형이 셋이라 걷어낸 뒤 견준다(dart-governance.md의 「회계감사인의 명칭 및 감사의견」 절).
     *
     * <p><b>괄호 자리가 둘이다</b> — {@code 제7기(당기)}와 {@code 제7(당)기}가 실측으로 모두 온다.
     *
     * <p><b>순서를 고정한 것은 뜻이 아니라 재현성 때문이다.</b> 실측으로 한 표기에 토큰이 둘 이상
     * 든 것은 <b>0개</b>라 어느 순서로 물어도 답이 같지만, {@code Map.of}는 순회 순서가 명세되지
     * 않아 나중에 겹치는 표기가 오면 <b>판정이 실행마다 달라진다.</b>
     */
    private static final Map<String, Integer> TERM_OFFSETS = termOffsets();

    private static Map<String, Integer> termOffsets() {
        Map<String, Integer> offsets = new LinkedHashMap<>();
        offsets.put("(전전기)", 2);
        offsets.put("(전기)", 1);
        offsets.put("(당기)", 0);
        offsets.put("(전전)기", 2);
        offsets.put("(전)기", 1);
        offsets.put("(당)기", 0);
        // MUST NOT: Map.copyOf로 감싸지 않는다 — 순회 순서를 보존하지 않아 위 근거가 무너진다.
        return Collections.unmodifiableMap(offsets);
    }

    /** 기수 표기가 회계연도를 <b>직접</b> 말하는 자리. {@code 제18기(2024년)}·{@code 2024년(제21기)}. */
    private static final Pattern EXPLICIT_YEAR = Pattern.compile("((?:19|20)\\d{2})년");

    /**
     * {@code 제53기('25.01.01~'25.12.31)} — 두 자리 연도로 <b>온전한 역년</b>을 적은 표기.
     *
     * <p><b>MUST: 시작과 끝을 둘 다 묶어 역년인 것만 받는다.</b> 범위의 시작 연도가 곧
     * 회계연도인 것은 <b>12월 결산일 때만</b> 참이고, 비12월 결산에서는 조용히 한 해 어긋난다.
     * 이 좁힘이 없으면 「보고서 안 최대 기수 = 당기」를 기각한 것과 같은 종류의 추측이 된다.
     *
     * <p>실측(2026-09-14): 이 표기는 12행 / 1종목(`009150` 삼성전기)이고 <b>12행 전부 역년</b>이다.
     * 그 종목은 이 규칙이 없으면 앵커가 하나도 없어 <b>감사의견 사실이 통째로 안 생긴다.</b>
     */
    private static final Pattern CALENDAR_YEAR_RANGE =
            Pattern.compile("'(\\d{2})\\.01\\.01~'\\d{2}\\.12\\.31");

    /** {@code 제42기}의 기수 번호. 종목의 기수↔회계연도 오프셋을 세우는 재료다. */
    private static final Pattern TERM_ORDINAL = Pattern.compile("제(\\d+)기");

    /** {@code 2025년 03월 31일}. 이 엔드포인트만 한국어 날짜다(dart-governance.md의 「최대주주」 절). */
    private static final Pattern KOREAN_DATE =
            Pattern.compile("(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일");

    /**
     * 「강조사항 없음」의 표기. 목록 밖은 <b>모르는 토큰이 아니라 강조사항의 내용</b>이다.
     *
     * <p>표본 48행에서 41이 이 목록에 들었고({@code 해당사항 없음} 35 · 빈 문자열 6) 7이 실제
     * 강조사항이었다 — 증권선물위원회 조치에 대한 행정소송 6, 재무제표 재작성 1(dart-governance.md의
     * 「회계감사인의 명칭 및 감사의견」 절).
     *
     * <p>IMPORTANT — {@code -}가 목록에 있지만 <b>{@code -}로 시작하는 것은 센티널이 아니다.</b>
     * 실측에 {@code - 증권선물위원회의 조치에 대한 행정소송}이 있고 그것은 글머리표다.
     * {@link #hasEmphasis}가 <b>완전일치</b>로 묻는 것이 그 구분을 지킨다.
     */
    private static final Set<String> NO_EMPHASIS = Set.of("", "-", "해당사항없음", "없음");

    /**
     * 보통주 축으로 세는 주식 종류.
     *
     * <p>「우선주가 아니면 보통주」로 접으면 틀린다. 오리온이 {@code 의결권 있는 주식}으로만 낸다.
     *
     * <p><b>MUST: 관측된 어휘로 채운다.</b> 소스가 같은 뜻을 여러 낱말로 낸다 — 전수 실측에서
     * {@code SHARE_TOTALS}의 {@code se}가 {@code 보통주}(308종목) 외에 {@code 보통주식}(32) ·
     * {@code 의결권 있는 주식}(13)으로도 온다. <b>목록에 없으면 그 종목의 지표가 통째로
     * 판정불가가 되는데 화면에는 「값 없음」으로만 보인다.</b>
     */
    private static final Set<String> COMMON_CLASSES =
            Set.of("보통주", "보통주식", "의결권있는주식");

    /**
     * 주식 종류를 <b>안 밝힌</b> 표기. 주식이 한 종류뿐인 회사에서 소스가 이렇게 낸다.
     *
     * <p>MUST NOT: 이것을 보통주로 <b>무조건</b> 접지 않는다 — 우선주 행이 같은 표기로 올 수
     * 있다. {@link #dividend}가 「그 보고서에서 값이 든 행이 정확히 하나일 때만」으로 좁힌다.
     */
    private static final Set<String> UNSPECIFIED_CLASSES = Set.of("", "-");

    private static final String TOTAL_ROW_NAME = "계";

    /** 배당 표에서 주당 현금배당금을 가리키는 {@code se}. 정규화 후의 표기다. */
    private static final String DPS_ITEM = "주당현금배당금(원)";

    /**
     * 현금배당금총액을 가리키는 {@code se}. {@link #DPS_ITEM}과 <b>서로의 대표 필드</b>다.
     *
     * <p>dart-governance.md의 「주식의 총수 현황」 절이 「행에 값이 있으면 {@code -}는 0」을 판정하려면 대표
     * 필드가 필요한데, 배당 행에는
     * 숫자 열이 3개년뿐이라 그 역할을 할 필드가 행 안에 없다. <b>대신 같은 보고서의 짝이 그것을
     * 한다</b> — 주당배당금과 총액이 <b>둘 다</b> {@code -}면 배당이 없었던 것이고, 한쪽만
     * {@code -}면 소스가 그 칸을 안 채운 것이다. 실측이 양방향으로 실재한다(당기 기준: DPS만
     * {@code -} 3행 · 총액만 {@code -} 3행 · 둘 다 {@code -} 88행).
     */
    private static final String TOTAL_ITEM = "현금배당금총액(백만원)";

    /**
     * 적정 의견의 <b>관측된 표기</b>. 정규화(공백 제거) 후 완전일치로 묻는다.
     *
     * <p>MUST NOT: {@code contains("적정")}으로 바꾸지 않는다. {@code 부적정의견}이 그 물음을
     * 통과해 <b>가장 위험한 종목이 「적정」으로 분류된다.</b>
     *
     * <p>목록이 서른에 가까운 것은 소스가 자유 텍스트를 받기 때문이다 — 350종목 6개년에서
     * 정규화 후에도 서른셋이 나왔다(dart-governance.md의 「350종목 전수 적재가 답한 것」 절). 새 표기는
     * <b>판정불가로 떨어져 로그에 뜬다.</b>
     */
    private static final Set<String> CLEAN_OPINIONS = Set.of(
            "적정의견", "적정", "적정함", "적정*", "적정주)",
            "적정(감사)", "적정(감사의견)", "적정(검토)", "적정(주1)",
            "적정(K-IFRS)", "적정(K-GAAP)", "적정(연결동일)",
            "적정(별도,연결)", "적정(별도/연결)", "적정(연결/별도)",
            "연결,별도:적정", "-연결:적정-별도:적정", "공정(적정)", "\"적정\"",
            "적정검토", "적정(K-IFRS)적정(K-IFRS)",
            "적정예외사항없음예외사항없음예외사항없음", "적정의견예외사항없음(반기)");

    /**
     * 비적정 의견의 <b>관측된 표기</b>. 전수 8행 / 6종목이다(2026-08-24 실측).
     *
     * <p>목록 밖이 판정불가로 떨어지므로 <b>새 비적정 표기가 「적정」으로 새지 않는다</b>는 것이
     * 이 설계의 요점이다. 그 안전은 실제로 작동했다 — 아래 일곱은 목록에 없는 동안
     * <b>「적정」이 아니라 판정불가</b>로 있었다.
     *
     * <p>MUST NOT: 각주 표기({@code (*)}·{@code (*1)}·{@code (주1)})를 여기에도
     * {@link #CLEAN_OPINIONS}에도 더하지 않는다. <b>의견이 아니라 주석 번호</b>라는 것이 사유다.
     *
     * <p><b>「기수를 못 읽어 어차피 버려진다」를 사유로 쓰지 않는다.</b> 한때 그렇게 적혀 있었는데
     * 기수 파서를 넓히자 그 전제가 무너졌다 — {@code 제20기(전기)제20기반기(전반기)}처럼
     * <b>끝이 아닌 자리</b>에 토큰이 있는 표기가 이제 읽히고, 그 행의 의견이 판정에 닿는다.
     * 316140 FY2019의 {@code (*)}는 원래부터 {@code 제1기(당기)}라 읽혔고 <b>같은 회계연도에
     * 짝 행이 없어</b> 의견을 유추할 근거가 없다.
     */
    private static final Set<String> ADVERSE_OPINIONS = Set.of(
            // 044490 태웅 FY2020
            "감사범위제한으로인한한정",
            // 251970 펌텍코리아 · 327260 RF머트리얼즈 · 052400 코나아이
            "한정", "한정(아래참조)",
            // 214370 케어젠 · 950160 코오롱티슈진(3개년)
            "의견거절", "의견거절(*1)", "의견거절(*2)", "의견거절(*3)");

    @Override
    public List<GovernanceFact> derive(GovernanceEndpoint endpoint,
                                       List<GovernanceReportRow> rows,
                                       GovernanceDerivation context) {
        // 문맥을 쓰지 않는다. 국내는 사업보고서 한 장에 회계연도·공시일·주식수가 함께 온다.
        if (endpoint == null || rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<GovernanceReportRow> real = rows.stream().filter(row -> !row.isNoData()).toList();
        return switch (endpoint) {
            case SHARE_TOTALS -> shareTotals(real);
            case DIVIDEND -> dividend(real);
            case EXEC_PAY -> execPay(real);
            case OUTSIDE_DIRECTOR -> outsideDirector(real);
            case LARGEST_HOLDER -> largestHolder(real);
            case MINORITY_HOLDER -> minorityHolder(real);
            // 「사건 없음」도 0이라는 사실이므로 원자료 행이 없어도 유도한다.
            case CONTROL_CHANGE -> controlChange(rows);
            case AUDIT_OPINION -> auditOpinion(real);
        };
    }

    // --- 주식의 총수 -------------------------------------------------------

    /**
     * {@code se}가 {@code 보통주}인 행 하나를 쓴다.
     *
     * <p>{@code 합계}를 쓰면 우선주가 섞여 {@code listed_shares} 기반 기존 지표와 분모가 갈리고,
     * {@code 비고} 행은 숫자 자리에 <b>문자열</b>({@code 자사주소각})을 싣고 온다(dart-governance.md의 「주식의
     * 총수 현황」 절).
     */
    private List<GovernanceFact> shareTotals(List<GovernanceReportRow> rows) {
        List<GovernanceFact> facts = new ArrayList<>();
        for (GovernanceReportRow row : rows) {
            if (!COMMON_CLASSES.contains(normalize(row.field("se")))) {
                continue;
            }
            BigDecimal issued = DartAccountMapper.parseAmount(row.field("istc_totqy"));
            if (issued == null || issued.signum() <= 0) {
                // 행 전체가 '-'면 그 주식 종류가 없다는 뜻이다 — 0주 발행이 아니다(dart-governance.md의 「주식의 총수 현황」 절).
                continue;
            }
            add(facts, row, GovernanceMetric.ISSUED_SHARES_COMMON, issued);
            add(facts, row, GovernanceMetric.TREASURY_SHARES_COMMON,
                    zeroIfDash(row.field("tesstk_co")));
            add(facts, row, GovernanceMetric.PROFIT_RETIRED_CUM,
                    zeroIfDash(row.field("profit_incnr")));
        }
        return facts;
    }

    // --- 배당 -------------------------------------------------------------

    /**
     * 3개년이 <b>열</b>로 오므로 열마다 회계연도를 뒤로 물린다.
     *
     * <p>단위가 {@code se} 문자열 안에만 있다 — {@code 현금배당금총액(백만원)}을 원으로 맞추지
     * 않으면 배당성향이 100만분의 1이 되고 <b>값이 작아 눈에 덜 띈다.</b>
     */
    private List<GovernanceFact> dividend(List<GovernanceReportRow> rows) {
        List<GovernanceFact> facts = new ArrayList<>();
        Set<String> unknownItems = new LinkedHashSet<>();
        Set<String> unspecifiedDps = unspecifiedDpsRows(rows);
        Set<String> zeroable = zeroableColumns(rows, unspecifiedDps);
        for (GovernanceReportRow row : rows) {
            String item = normalize(row.field("se"));
            String kind = normalize(row.field("stock_knd"));
            GovernanceMetric metric;
            BigDecimal scale;
            if (DPS_ITEM.equals(item)
                    && (COMMON_CLASSES.contains(kind) || unspecifiedDps.contains(rowKey(row)))) {
                metric = GovernanceMetric.DPS_COMMON;
                scale = BigDecimal.ONE;
            } else if (TOTAL_ITEM.equals(item)) {
                metric = GovernanceMetric.CASH_DIVIDEND_TOTAL;
                scale = new BigDecimal("1000000");
            } else {
                if (!item.isEmpty()) {
                    unknownItems.add(item);
                }
                continue;
            }
            for (Map.Entry<String, Integer> column : COLUMN_OFFSETS.entrySet()) {
                BigDecimal raw = DartAccountMapper.parseAmount(row.field(column.getKey()));
                if (raw == null) {
                    // 짝(주당배당금 ↔ 총액)도 '-'일 때만 0이다. 한쪽만 '-'면 소스가 그 칸을
                    // 안 채운 것이므로 값을 내지 않는다 — 안 그러면 배당을 한 회사에
                    // 「배당수익률 0.00%」가 붙는다(241710·450080이 실제로 그 형태다).
                    if (!zeroable.contains(columnKey(row, column.getKey()))) {
                        continue;
                    }
                    raw = BigDecimal.ZERO;
                }
                facts.add(new GovernanceFact(row.bsnsYear() - column.getValue(), metric,
                        raw.multiply(scale), row.publishedAt(), row.receiptNo()));
            }
        }
        // 쓰지 않는 항목이 대부분이라 목록 자체는 정상이다. 그럼에도 남기는 이유는 소스가 항목명을
        // 바꿨을 때 그것이 여기 말고는 드러나는 자리가 없기 때문이다.
        log.debug("배당 표에서 쓰지 않은 항목: {}", unknownItems);
        return facts;
    }

    /**
     * 주식 종류를 안 밝힌 채 온 <b>주당 현금배당금</b> 행 중 보통주로 읽어도 되는 것.
     *
     * <p><b>왜 필요한가</b> — 주식이 한 종류뿐인 회사는 소스가 {@code stock_knd}를 {@code -}로
     * 낸다. 그것을 버리면 <b>배당을 하는데 배당수익률이 없는</b> 종목이 생긴다. 실제로 그랬다:
     * 005850 에스엘이 {@code 주당 현금배당금(원) = 1,200}을 싣고도 {@code DPS_COMMON}이 0행이라
     * 화면에 <b>배당성향 39.77%와 빈 배당수익률이 나란히</b> 떴다({@code CASH_DIVIDEND_TOTAL}은
     * {@code stock_knd}를 안 보므로 살아남는다). 활성 유니버스에서 그런 종목이 22개였다.
     *
     * <p><b>왜 그냥 통과시키지 않는가</b> — 같은 표기로 우선주 행이 올 수 있다. 그래서 <b>보고서
     * 단위로</b> 셋으로 가른다(그 보고서에 주식 종류를 밝힌 행이 하나도 없을 때만):
     *
     * <ul>
     *   <li><b>값이 든 행이 정확히 하나</b> → 그 행을 쓴다.
     *   <li><b>하나도 없음</b> → {@code ordinal}이 가장 작은 행으로 <b>0</b>을 낸다. 어느 행을
     *       골라도 값이 0이라 모호하지 않다(실측 131건이 전부 2행 다 전열 {@code -}).
     *       <b>MUST NOT: 이것을 「{@code ordinal} 0이 보통주」로 읽지 않는다</b> — 값이 같아
     *       아무거나 되므로 고르는 것이지 종류를 판정한 것이 아니다.
     *   <li><b>둘 이상</b> → 어느 쪽이 보통주인지 몰라 값을 내지 않는다.
     * </ul>
     *
     * <p><b>MUST NOT: 이 분기를 「값이 든 행」 조건의 완화로 바꾸지 않는다.</b> 전열 {@code -}
     * 행까지 「값이 든 것」으로 세면 131건이 후보 2개가 되어 첫 갈래가 거짓이 되고,
     * {@code DPS_COMMON}이 <b>오히려 사라진다</b>.
     *
     * <p>애매하면 값을 안 내는 것은 ADR-0081이 총주식수 대조에 쓴 태도와 같다. 실제로 0을 낼지는
     * {@link #zeroableColumns}가 <b>총액과 대조해</b> 다시 정한다 — 이 메서드는 「어느 행이
     * 보통주인가」만 답한다(ADR-0082).
     *
     * <p>MUST NOT: {@code ordinal} 0을 보통주로 가정하지 않는다 — 순서가 그렇다는 것을
     * <b>재본 적이 없다</b>.
     */
    private static Set<String> unspecifiedDpsRows(List<GovernanceReportRow> rows) {
        // 보고서(사업연도 + 접수번호)마다 후보를 모은다. 같은 종목이라도 판이 다르면 별개다.
        Map<String, List<GovernanceReportRow>> candidates = new LinkedHashMap<>();
        Set<String> reportsWithNamedKind = new LinkedHashSet<>();
        for (GovernanceReportRow row : rows) {
            if (!DPS_ITEM.equals(normalize(row.field("se")))) {
                continue;
            }
            String report = row.bsnsYear() + "|" + row.receiptNo();
            String kind = normalize(row.field("stock_knd"));
            if (COMMON_CLASSES.contains(kind)) {
                reportsWithNamedKind.add(report);
                continue;
            }
            if (!UNSPECIFIED_CLASSES.contains(kind)) {
                continue;
            }
            candidates.computeIfAbsent(report, key -> new ArrayList<>()).add(row);
        }

        Set<String> accepted = new LinkedHashSet<>();
        candidates.forEach((report, rowsOfReport) -> {
            if (reportsWithNamedKind.contains(report)) {
                return;
            }
            List<GovernanceReportRow> valued = rowsOfReport.stream()
                    .filter(DartGovernanceFactMapper::hasAnyAmount).toList();
            if (valued.size() == 1) {
                accepted.add(rowKey(valued.get(0)));
            } else if (valued.isEmpty()) {
                // 전부 '-'면 어느 행을 골라도 값이 0이라 모호하지 않다. 재실행마다 같은 행이
                // 뽑히도록 ordinal로 고른다 — 「ordinal 0이 보통주」라는 판정이 아니다.
                rowsOfReport.stream().min(Comparator.comparingInt(GovernanceReportRow::ordinal))
                        .ifPresent(row -> accepted.add(rowKey(row)));
            }
            // 값이 든 행이 둘 이상이면 어느 쪽이 보통주인지 모른다 — 값을 내지 않는다.
        });
        return accepted;
    }

    /**
     * 0으로 읽어도 되는 {@code (보고서, 열)}.
     *
     * <p><b>보고서 단위로 판정한다</b> — 주당배당금 행과 총액 행의 <b>{@code -}인 열 집합이 정확히
     * 같을 때만</b> 그 열들을 0으로 읽는다. 하나라도 어긋나면 <b>그 보고서의 주당배당금 행을 통째로
     * 안 쓴다.</b>
     *
     * <p><b>왜 열 단위가 아니라 행 단위인가</b> — 241710 코스메카코리아 FY2025는 총액이
     * {@code 3,952 / 1,068 / -}인데 주당배당금이 {@code - / - / -}다. 열 단위로 대조하면 전전기만
     * 0이 되는데, 그 회사의 <b>최신 주당배당금 사실이 FY2023 = 0</b>이 되어 화면에
     * <b>「배당수익률 0.00%」</b>가 뜬다 — 배당을 한 회사인데도. 어긋난 행은 소스가 제대로 안 채운
     * 것이므로 <b>그 행에서는 아무것도 지어내지 않는다.</b>
     *
     * <p>비용이 거의 없다 — 실측 518보고서 중 어긋나는 것이 <b>6(5종목)</b>이다.
     */
    private static Set<String> zeroableColumns(List<GovernanceReportRow> rows,
                                               Set<String> unspecifiedDps) {
        Map<String, Set<String>> dpsDash = new LinkedHashMap<>();
        Map<String, Set<String>> totalDash = new LinkedHashMap<>();
        for (GovernanceReportRow row : rows) {
            String item = normalize(row.field("se"));
            boolean dps = DPS_ITEM.equals(item)
                    && (COMMON_CLASSES.contains(normalize(row.field("stock_knd")))
                    || unspecifiedDps.contains(rowKey(row)));
            if (!dps && !TOTAL_ITEM.equals(item)) {
                continue;
            }
            String report = row.bsnsYear() + "|" + row.receiptNo();
            Set<String> dashes = (dps ? dpsDash : totalDash)
                    .computeIfAbsent(report, key -> new LinkedHashSet<>());
            for (String column : COLUMN_OFFSETS.keySet()) {
                if (DartAccountMapper.parseAmount(row.field(column)) == null) {
                    dashes.add(column);
                }
            }
        }

        Set<String> zeroable = new LinkedHashSet<>();
        dpsDash.forEach((report, dashes) -> {
            if (!dashes.equals(totalDash.get(report))) {
                return;
            }
            dashes.forEach(column -> zeroable.add(report + "|" + column));
        });
        return zeroable;
    }

    /** {@code (보고서, 열)}을 가리키는 키. */
    private static String columnKey(GovernanceReportRow row, String column) {
        return row.bsnsYear() + "|" + row.receiptNo() + "|" + column;
    }

    /** 3개년 열 중 하나라도 숫자가 들었는가. 전부 {@code -}면 그 주식 종류는 배당이 없다. */
    private static boolean hasAnyAmount(GovernanceReportRow row) {
        return COLUMN_OFFSETS.keySet().stream()
                .anyMatch(column -> DartAccountMapper.parseAmount(row.field(column)) != null);
    }

    /** 행 하나를 가리키는 키. 레코드 동치가 아니라 <b>자리</b>로 가리킨다. */
    private static String rowKey(GovernanceReportRow row) {
        return row.bsnsYear() + "|" + row.receiptNo() + "|" + row.ordinal();
    }

    // --- 보수 -------------------------------------------------------------

    private List<GovernanceFact> execPay(List<GovernanceReportRow> rows) {
        List<GovernanceFact> facts = new ArrayList<>();
        for (GovernanceReportRow row : rows) {
            add(facts, row, GovernanceMetric.EXEC_PAY_TOTAL,
                    DartAccountMapper.parseAmount(row.field("mendng_totamt")));
        }
        return facts;
    }

    // --- 사외이사 ----------------------------------------------------------

    /**
     * 회사당 1행이다(dart-governance.md의 「여덟이 여덟 모양이다」 절).
     *
     * <p>변동 필드의 {@code -}는 <b>0명</b>이다 — 이사 수가 채워져 있는데 해임이 {@code -}인 것이
     * 8/8이었다. 결손으로 세면 이사회 축이 전 종목 판정불가가 된다.
     */
    private List<GovernanceFact> outsideDirector(List<GovernanceReportRow> rows) {
        List<GovernanceFact> facts = new ArrayList<>();
        for (GovernanceReportRow row : rows) {
            BigDecimal directors = DartAccountMapper.parseAmount(row.field("drctr_co"));
            if (directors == null || directors.signum() <= 0) {
                continue;
            }
            add(facts, row, GovernanceMetric.DIRECTOR_COUNT, directors);
            add(facts, row, GovernanceMetric.OUTSIDE_DIRECTOR_COUNT,
                    zeroIfDash(row.field("otcmp_drctr_co")));
            add(facts, row, GovernanceMetric.DIRECTOR_DISMISSED, zeroIfDash(row.field("rlsofc")));
            add(facts, row, GovernanceMetric.DIRECTOR_RESIGNED,
                    zeroIfDash(row.field("mdstrm_resig")));
        }
        return facts;
    }

    // --- 최대주주 ----------------------------------------------------------

    /**
     * {@code nm}이 {@code 계}가 <b>아닌</b> 행의 기말 주식수를 더하고, {@code 계} 행과 대조한다.
     *
     * <p>판정은 <b>완전일치</b>다. {@code contains("계")}는 {@code 계열회사}·{@code 특수관계인}을
     * 함께 무는데(실제로 물었다) 그러면 최대주주 지분이 통째로 0이 된다.
     *
     * <p>소스가 준 {@code 계}를 그대로 쓰지 않는 이유는 spec §7이다 — 대조는 하되 값은 우리가 만든다.
     * 어긋나면 로그로 남긴다. 실측에서는 여덟 다 맞았다(오리온 열 주주 합 17,321,233).
     */
    private List<GovernanceFact> largestHolder(List<GovernanceReportRow> rows) {
        // MUST: 회계연도로 먼저 가른다. 여러 해를 한 목록으로 받으므로 안 가르면 두 해의
        // 주주를 함께 더해 **지분이 두 배가 되고 값이 그럴듯해 안 드러난다.**
        Map<Integer, List<GovernanceReportRow>> byYear = new TreeMap<>();
        for (GovernanceReportRow row : rows) {
            byYear.computeIfAbsent(row.bsnsYear(), year -> new ArrayList<>()).add(row);
        }
        List<GovernanceFact> facts = new ArrayList<>();
        byYear.values().forEach(yearRows -> largestHolderOfYear(yearRows).ifPresent(facts::add));
        return facts;
    }

    private java.util.Optional<GovernanceFact> largestHolderOfYear(
            List<GovernanceReportRow> rows) {
        BigDecimal summed = null;
        BigDecimal reportedTotal = null;
        GovernanceReportRow anchor = null;
        for (GovernanceReportRow row : rows) {
            if (!COMMON_CLASSES.contains(normalize(row.field("stock_knd")))) {
                continue;
            }
            BigDecimal shares = DartAccountMapper.parseAmount(row.field("trmend_posesn_stock_co"));
            if (shares == null) {
                continue;
            }
            if (TOTAL_ROW_NAME.equals(normalize(row.field("nm")))) {
                reportedTotal = shares;
                continue;
            }
            summed = summed == null ? shares : summed.add(shares);
            anchor = anchor == null ? row : anchor;
        }
        if (summed == null || anchor == null || anchor.publishedAt() == null) {
            return java.util.Optional.empty();
        }
        if (reportedTotal != null && reportedTotal.compareTo(summed) != 0) {
            log.warn("최대주주 합계가 소스의 '계' 행과 다르다. 우리 합={} 소스={} receipt={}",
                    summed, reportedTotal, anchor.receiptNo());
        }
        return java.util.Optional.of(new GovernanceFact(anchor.bsnsYear(),
                GovernanceMetric.LARGEST_HOLDER_SHARES_COMMON, summed,
                anchor.publishedAt(), anchor.receiptNo()));
    }

    // --- 소액주주 ----------------------------------------------------------

    /** 분자와 분모가 <b>같은 행</b>에서 온다. 다른 표의 수로 나누면 기준일이 갈린다. */
    private List<GovernanceFact> minorityHolder(List<GovernanceReportRow> rows) {
        List<GovernanceFact> facts = new ArrayList<>();
        for (GovernanceReportRow row : rows) {
            BigDecimal held = DartAccountMapper.parseAmount(row.field("hold_stock_co"));
            BigDecimal total = DartAccountMapper.parseAmount(row.field("stock_tot_co"));
            if (held == null || total == null || total.signum() <= 0) {
                continue;
            }
            add(facts, row, GovernanceMetric.MINORITY_SHARES, held);
            add(facts, row, GovernanceMetric.MINORITY_TOTAL_SHARES, total);
        }
        return facts;
    }

    // --- 최대주주 변동 ------------------------------------------------------

    /**
     * 최근 3개 회계연도에 <b>최대주주 이름이 바뀐 횟수</b>를 센다.
     *
     * <p>행 수를 세지 않는 이유는 그 표가 <b>지분 변동 내역</b>이기 때문이다 — KB금융의 28행이
     * 전부 같은 {@code 국민연금공단}이고 원인이 전부 {@code 장내매매}였다(dart-governance.md의 「최대주주」 절).
     *
     * <p>행 순서가 날짜순이 아니라 {@code change_on}으로 정렬한 뒤 걷는다. 창의 시작 <b>직전</b>
     * 이름을 기준선으로 삼아야 창 첫 사건의 전이가 빠지지 않는다.
     */
    private List<GovernanceFact> controlChange(List<GovernanceReportRow> rows) {
        // MUST: 보고서(=요청 연도)마다 따로 센다. 한 덩어리로 접으면 사실이 최신 보고서 하나에만
        // 서고, **그 보고서가 나오기 전 구간이 통째로 빈다** — 실측으로 쓰기 창 255거래일 중
        // 137이 그 앞이다(2026-09-08). 같은 실수를 최대주주 지분율에서 한 번 했다.
        Map<Integer, List<GovernanceReportRow>> byYear = new TreeMap<>();
        for (GovernanceReportRow row : rows) {
            byYear.computeIfAbsent(row.bsnsYear(), year -> new ArrayList<>()).add(row);
        }
        List<GovernanceFact> facts = new ArrayList<>();
        byYear.forEach((year, yearRows) ->
                controlChangeOfReport(year, yearRows).ifPresent(facts::add));
        return facts;
    }

    /**
     * 보고서 하나가 아는 창(최근 3개 회계연도)의 최대주주 교체 횟수.
     *
     * <p>행 순서가 날짜순이 아니라 {@code change_on}으로 정렬한 뒤 걷는다. 창의 시작 <b>직전</b>
     * 이름을 기준선으로 삼아야 창 첫 사건의 전이가 빠지지 않는다.
     */
    private java.util.Optional<GovernanceFact> controlChangeOfReport(
            int bsnsYear, List<GovernanceReportRow> rows) {
        GovernanceReportRow anchor = null;
        TreeMap<LocalDate, String> holderByDate = new TreeMap<>();
        for (GovernanceReportRow row : rows) {
            // 데이터 없음 행도 닻이 된다 — 「그 해까지 변동이 없었다」는 0이라는 사실이고,
            // 잡이 같은 보고서의 접수번호를 찍어 두어 시점을 답할 수 있다.
            if (row.publishedAt() != null
                    && (anchor == null || anchor.publishedAt() == null
                        || row.publishedAt().isAfter(anchor.publishedAt()))) {
                anchor = row;
            }
            if (row.isNoData()) {
                continue;
            }
            LocalDate changedOn = parseKoreanDate(row.field("change_on"));
            String holder = normalize(row.field("mxmm_shrholdr_nm"));
            if (changedOn == null || holder.isEmpty() || "-".equals(holder)) {
                continue;
            }
            // 같은 날 여러 행이면 마지막 것이 그날의 최대주주다.
            holderByDate.put(changedOn, holder);
        }
        if (anchor == null) {
            return java.util.Optional.empty();
        }
        LocalDate windowStart = LocalDate.of(bsnsYear - 2, 1, 1);
        String previous = null;
        int transfers = 0;
        for (Map.Entry<LocalDate, String> event : holderByDate.entrySet()) {
            if (!event.getKey().isBefore(windowStart) && previous != null
                    && !previous.equals(event.getValue())) {
                transfers++;
            }
            previous = event.getValue();
        }
        return java.util.Optional.of(new GovernanceFact(bsnsYear,
                GovernanceMetric.CONTROL_TRANSFERS_3Y, BigDecimal.valueOf(transfers),
                anchor.publishedAt(), anchor.receiptNo()));
    }

    // --- 감사의견 ----------------------------------------------------------

    /**
     * 한 회계연도에 행이 둘이다(연결·별도로 보이나 <b>필드로 구분되지 않는다</b>, dart-governance.md의
     * 「회계감사인의 명칭 및 감사의견」 절).
     * 강조사항은 <b>OR</b>로 접고 감사인은 <b>집합</b>으로 접는다.
     *
     * <p><b>{@code adt_opinion}으로 판정 지표를 만든다</b>({@code AUDIT_OPINION_ADVERSE}).
     * 한때 <i>"비적정 표기를 본 적이 없고 활성 유니버스 안에서는 원리적으로 안 나온다"</i>고
     * 적혀 있었으나 <b>둘 다 거짓이다</b> — 전수로 8행 / 6종목이 나온다. 판정은 허용목록
     * <b>양쪽</b> 위에 서고, 어느 목록에도 없는 표기는 「적정」으로 접지 않고 판정불가로 남긴다.
     */
    private List<GovernanceFact> auditOpinion(List<GovernanceReportRow> rows) {
        Map<Integer, Boolean> emphasisByYear = new TreeMap<>();
        Map<Integer, Boolean> adverseByYear = new TreeMap<>();
        Map<Integer, Set<String>> auditorsByYear = new TreeMap<>();
        Map<Integer, GovernanceReportRow> anchorByYear = new LinkedHashMap<>();
        Set<String> unknownOpinions = new LinkedHashSet<>();
        Set<String> unreadableTerms = new LinkedHashSet<>();

        // 기수 오프셋은 이 종목의 행 전체에서 한 번 세운다 — 한 보고서가 아니라 종목이 단위다.
        Integer ordinalOffset = ordinalOffset(rows);

        for (GovernanceReportRow row : rows) {
            Integer fiscalYear = fiscalYearOf(row, ordinalOffset);
            if (fiscalYear == null) {
                // MUST: 삼키지 않는다. 여기서 버려지는 행은 의견 판정에 닿지도 못하므로
                // unknownOpinions 경고에 안 걸린다 — 그래서 이 자리가 유일한 목격자다.
                String term = normalize(row.field("bsns_year"));
                if (!term.isEmpty() && !"-".equals(term)) {
                    unreadableTerms.add(term);
                }
                continue;
            }
            // MUST NOT: core_adt_matter(핵심감사사항)를 여기 넣지 않는다. 감사기준의 필수
            // 기재사항이라 표본 48행이 **전부** 값을 갖는다(dart-governance.md의 「350종목 전수 적재가 답한 것」 절) — 넣으면 이 지표가 전 종목 1인
            // 상수가 되고, 그것은 아무도 못 자르는 게이트다.
            //
            // adt_reprt_spcmnt_matter는 2019-12-08까지 쓰이던 같은 자리의 필드다. 한쪽만 읽으면
            // 그 이전 구간이 통째로 「강조사항 없음」이 된다(dart-governance.md의 「회계감사인의 명칭 및 감사의견」 절).
            boolean present = hasEmphasis(row.field("emphs_matter"))
                    || hasEmphasis(row.field("adt_reprt_spcmnt_matter"));
            emphasisByYear.merge(fiscalYear, present, (a, b) -> a || b);

            String opinion = normalize(row.field("adt_opinion"));
            if (ADVERSE_OPINIONS.contains(opinion)) {
                adverseByYear.merge(fiscalYear, true, (a, b) -> a || b);
            } else if (CLEAN_OPINIONS.contains(opinion)) {
                adverseByYear.merge(fiscalYear, false, (a, b) -> a || b);
            } else if (!opinion.isEmpty()) {
                // 목록 밖이다. 「적정」으로 접지 않는다 — 새 비적정 표기가 여기로 온다.
                unknownOpinions.add(opinion);
            }

            String auditor = normalize(row.field("adtor"));
            if (!auditor.isEmpty() && !"-".equals(auditor)) {
                auditorsByYear.computeIfAbsent(fiscalYear, y -> new LinkedHashSet<>()).add(auditor);
            }
            // 같은 회계연도가 보고서 둘에서 오면 늦게 공시된 쪽이 정본이다.
            anchorByYear.merge(fiscalYear, row, DartGovernanceFactMapper::later);
        }

        if (!unreadableTerms.isEmpty()) {
            // 회계연도를 못 매기면 그 해의 감사의견 사실이 통째로 안 생기고, 화면은 그 종목에
            // 대해 **더 낡은 해의 의견**을 계속 말한다. 값이 틀리는 것이 아니라 낡는 것이라 무증상이다.
            log.warn("기수 표기를 읽지 못해 회계연도를 못 매긴 감사의견 행이 있다: {} — 관측된 "
                    + "표기면 TERM_OFFSETS나 기수 오프셋 규칙을 넓힌다(dart-governance.md의 "
                    + "「회계감사인의 명칭 및 감사의견」 절).", unreadableTerms);
        }

        if (!unknownOpinions.isEmpty()) {
            // MUST: 삼키지 않는다. 목록 밖 표기가 새로 생겼다는 것을 여기 말고는 알 자리가 없고,
            // 그 사이 그 종목은 「감사의견 판정불가」로 조건에서 탈락한다.
            log.warn("허용목록에 없는 감사의견 표기라 판정하지 않았다: {} — 관측된 표기면 "
                    + "CLEAN_OPINIONS나 ADVERSE_OPINIONS에 더한다(dart-governance.md의 「회계감사인의 명칭 및 감사의견」 절).", unknownOpinions);
        }

        List<GovernanceFact> facts = new ArrayList<>();
        adverseByYear.forEach((year, adverse) -> {
            GovernanceReportRow anchor = anchorByYear.get(year);
            facts.add(new GovernanceFact(year, GovernanceMetric.AUDIT_OPINION_ADVERSE,
                    adverse ? BigDecimal.ONE : BigDecimal.ZERO,
                    anchor.publishedAt(), anchor.receiptNo()));
        });
        emphasisByYear.forEach((year, present) -> {
            GovernanceReportRow anchor = anchorByYear.get(year);
            facts.add(new GovernanceFact(year, GovernanceMetric.AUDIT_EMPHASIS_PRESENT,
                    present ? BigDecimal.ONE : BigDecimal.ZERO,
                    anchor.publishedAt(), anchor.receiptNo()));
        });
        auditorsByYear.forEach((year, auditors) -> {
            Set<String> previous = auditorsByYear.get(year - 1);
            if (previous == null) {
                // 전년을 모르면 「안 바뀌었다」가 아니라 「모른다」다.
                return;
            }
            GovernanceReportRow anchor = later(anchorByYear.get(year), anchorByYear.get(year - 1));
            facts.add(new GovernanceFact(year, GovernanceMetric.AUDITOR_CHANGED,
                    auditors.equals(previous) ? BigDecimal.ZERO : BigDecimal.ONE,
                    anchor.publishedAt(), anchor.receiptNo()));
        });
        return facts;
    }

    // --- 공용 ------------------------------------------------------------

    private static void add(List<GovernanceFact> facts, GovernanceReportRow row,
                            GovernanceMetric metric, BigDecimal value) {
        if (value == null || row.publishedAt() == null) {
            return;
        }
        facts.add(new GovernanceFact(row.bsnsYear(), metric, value,
                row.publishedAt(), row.receiptNo()));
    }

    /** 접수번호가 큰 쪽. 보고서 둘을 견줘 만드는 값의 키를 정하는 규칙이다. */
    private static GovernanceReportRow later(GovernanceReportRow a, GovernanceReportRow b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.receiptNo().compareTo(b.receiptNo()) >= 0 ? a : b;
    }

    /**
     * 값이 있는 행의 {@code -}는 <b>0</b>이다(dart-governance.md의 「주식의 총수 현황」 절).
     *
     * <p>호출부가 「그 행이 성립하는가」를 먼저 판정한 뒤에만 쓴다 — 행 전체가 {@code -}인 것은
     * 0이 아니라 <b>해당 없음</b>이고, 그 구분은 호출부가 대표 필드로 한다.
     */
    private static BigDecimal zeroIfDash(String raw) {
        BigDecimal parsed = DartAccountMapper.parseAmount(raw);
        return parsed == null ? BigDecimal.ZERO : parsed;
    }

    /** 공백·개행을 전부 걷는다. 기수 표기의 공백 변형이 셋이고 관계·항목명에도 변형이 있다. */
    private static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", "").strip();
    }

    /** 강조사항이 「있음」인가. 허용목록은 <b>「없음」 쪽</b>이다 — 그 밖은 강조사항의 내용이다. */
    private static boolean hasEmphasis(String raw) {
        return !NO_EMPHASIS.contains(normalize(raw));
    }

    /**
     * {@code 제57기 \n(당기)}에서 회계연도를 낸다.
     *
     * <p>세 갈래를 순서대로 묻는다 — ① {@code (당기)}류 토큰이 <b>어디에 있든</b>(끝이 아니어도
     * 된다) 요청 연도에서 그만큼 물린다 ② 표기가 {@code (2024년)}처럼 연도를 직접 말하면 그것을
     * 쓴다 ③ {@code ('25.01.01~'25.12.31)}처럼 <b>온전한 역년 범위</b>면 그 연도를 쓴다.
     * 실측으로 ①과 ②가 함께 든 53행이 <b>전부 일치</b>하므로 순서는 답을 안 바꾼다.
     *
     * <p><b>기수 숫자만 남은 표기는 여기서 풀지 않는다</b> — 회사마다 설립 연도가 달라 이 함수가
     * 가진 정보로는 환산할 수 없다. 그것은 {@link #ordinalOffset}이 종목 단위로 푼다.
     */
    private static Integer fiscalYearOfTerm(String raw, int requestedYear) {
        String flat = normalize(raw);
        if (flat.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Integer> term : TERM_OFFSETS.entrySet()) {
            if (flat.contains(term.getKey())) {
                return requestedYear - term.getValue();
            }
        }
        Matcher year = EXPLICIT_YEAR.matcher(flat);
        if (year.find()) {
            return Integer.parseInt(year.group(1));
        }
        Matcher range = CALENDAR_YEAR_RANGE.matcher(flat);
        return range.find() ? 2000 + Integer.parseInt(range.group(1)) : null;
    }

    /** {@code 제42기} → 42. 없으면 null이다. */
    private static Integer termOrdinal(String raw) {
        Matcher ordinal = TERM_ORDINAL.matcher(normalize(raw));
        return ordinal.find() ? Integer.parseInt(ordinal.group(1)) : null;
    }

    /**
     * 이 종목의 <b>기수 − 회계연도</b> 오프셋. 표기가 스스로 말한 행들에서 세우고, <b>하나로
     * 일관될 때만</b> 낸다.
     *
     * <p>기수는 설립 연도에 걸려 있어 회사마다 다르지만 <b>한 회사 안에서는 상수</b>다. 그래서
     * {@code (당기)} 앵커 한 번이 그 종목의 모든 맨몸 {@code 제N기}를 푼다 — 실측으로 앵커를 가진
     * 345종목 중 <b>335(97.1%)에서 오프셋이 하나</b>이고, 그것이 감사의견 <b>163행</b>을 살린다.
     * {@code 017670}이 그 예다: 2019년 보고서의 {@code 제36기(당기)}가 오프셋 −17을 주고
     * {@code 제42기 → FY2025}가 풀린다(그 전에는 최신 사실이 <b>FY2019</b>였다).
     *
     * <p><b>MUST NOT: 「보고서 안 최대 기수 = 당기」로 대신하지 않는다.</b> 지금 해석되는 3,504행에
     * 대 보면 <b>26행이 어긋난다</b> — {@code 006730}의 한 보고서에 {@code 제45기(당기)}와
     * {@code 제46기(당기)}가 <b>둘 다</b> 있다(소스가 자기모순이다). 어긋나는 방향이 <b>전기를
     * 당기로 올려 읽는</b> 쪽이라 감사의견이 한 해 새것으로 보이고, 그것은 무증상이다.
     *
     * <p>오프셋이 모순인 종목은 <b>null을 낸다</b> — 그 종목의 맨몸 기수는 그냥 버린다.
     */
    private static Integer ordinalOffset(List<GovernanceReportRow> rows) {
        Integer offset = null;
        for (GovernanceReportRow row : rows) {
            Integer stated = fiscalYearOfTerm(row.field("bsns_year"), row.bsnsYear());
            Integer ordinal = termOrdinal(row.field("bsns_year"));
            if (stated == null || ordinal == null) {
                continue;
            }
            int candidate = stated - ordinal;
            if (offset == null) {
                offset = candidate;
            } else if (offset != candidate) {
                return null;
            }
        }
        return offset;
    }

    /** 표기가 스스로 말하면 그것을, 아니면 종목의 기수 오프셋으로 민다. 둘 다 없으면 null이다. */
    private static Integer fiscalYearOf(GovernanceReportRow row, Integer ordinalOffset) {
        Integer stated = fiscalYearOfTerm(row.field("bsns_year"), row.bsnsYear());
        if (stated != null) {
            return stated;
        }
        Integer ordinal = termOrdinal(row.field("bsns_year"));
        return ordinalOffset == null || ordinal == null ? null : ordinal + ordinalOffset;
    }

    /** {@code 2025년 03월 31일}. 실패하면 null이고 호출부가 그 행을 버린다. */
    private static LocalDate parseKoreanDate(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = KOREAN_DATE.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
