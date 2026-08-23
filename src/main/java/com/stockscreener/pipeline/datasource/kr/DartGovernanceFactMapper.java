package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.indicator.GovernanceMetric;
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
 * <p>모든 행 선택 규칙의 근거는 {@code docs/api-spec/dart-open-api.md} §16-A·§16-B·§16-C의
 * <b>실측</b>이다. 개발가이드(§11-K)와 갈리는 자리가 넷이며 그때는 §16이 정본이다.
 */
@Component
class DartGovernanceFactMapper implements GovernanceFactMapper {

    private static final Logger log = LoggerFactory.getLogger(DartGovernanceFactMapper.class);

    /** 3개년을 열로 주는 표의 열 이름과 그 열이 가리키는 회계연도의 뒤로 물린 해 수. */
    private static final Map<String, Integer> COLUMN_OFFSETS =
            Map.of("thstrm", 0, "frmtrm", 1, "lwfr", 2);

    /** 기수 표기의 구분 토큰. 공백·개행 변형이 셋이라 걷어낸 뒤 견준다(§16-B). */
    private static final Map<String, Integer> TERM_OFFSETS =
            Map.of("(당기)", 0, "(전기)", 1, "(전전기)", 2);

    /** {@code 2025년 03월 31일}. 이 엔드포인트만 한국어 날짜다(§16-C). */
    private static final Pattern KOREAN_DATE =
            Pattern.compile("(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일");

    /**
     * 「강조사항 없음」의 표기. 목록 밖은 <b>모르는 토큰이 아니라 강조사항의 내용</b>이다.
     *
     * <p>표본 48행에서 41이 이 목록에 들었고({@code 해당사항 없음} 35 · 빈 문자열 6) 7이 실제
     * 강조사항이었다 — 증권선물위원회 조치에 대한 행정소송 6, 재무제표 재작성 1(§16-C).
     *
     * <p>IMPORTANT — {@code -}가 목록에 있지만 <b>{@code -}로 시작하는 것은 센티널이 아니다.</b>
     * 실측에 {@code - 증권선물위원회의 조치에 대한 행정소송}이 있고 그것은 글머리표다.
     * {@link #hasEmphasis}가 <b>완전일치</b>로 묻는 것이 그 구분을 지킨다.
     */
    private static final Set<String> NO_EMPHASIS = Set.of("", "-", "해당사항없음", "없음");

    /**
     * 보통주 축으로 세는 주식 종류.
     *
     * <p>어휘가 넷이고({@code 보통주}·{@code 우선주}·{@code 종류주}·{@code 의결권 있는 주식})
     * 「우선주가 아니면 보통주」로 접으면 틀린다. 오리온이 {@code 의결권 있는 주식}으로만 낸다.
     */
    private static final Set<String> COMMON_CLASSES = Set.of("보통주", "의결권있는주식");

    private static final String TOTAL_ROW_NAME = "계";

    /**
     * 적정 의견의 <b>관측된 표기</b>. 정규화(공백 제거) 후 완전일치로 묻는다.
     *
     * <p>MUST NOT: {@code contains("적정")}으로 바꾸지 않는다. {@code 부적정의견}이 그 물음을
     * 통과해 <b>가장 위험한 종목이 「적정」으로 분류된다.</b>
     *
     * <p>목록이 서른에 가까운 것은 소스가 자유 텍스트를 받기 때문이다 — 350종목 6개년에서
     * 정규화 후에도 서른셋이 나왔다(§16-C). 새 표기는 <b>판정불가로 떨어져 로그에 뜬다.</b>
     */
    private static final Set<String> CLEAN_OPINIONS = Set.of(
            "적정의견", "적정", "적정함", "적정*", "적정주)",
            "적정(감사)", "적정(감사의견)", "적정(검토)", "적정(주1)",
            "적정(K-IFRS)", "적정(연결동일)",
            "적정(별도,연결)", "적정(별도/연결)", "적정(연결/별도)",
            "연결,별도:적정", "-연결:적정-별도:적정",
            "적정예외사항없음예외사항없음예외사항없음", "적정의견예외사항없음(반기)");

    /**
     * 비적정 의견의 <b>관측된 표기</b>.
     *
     * <p>지금 하나뿐이다 — 044490 태웅 FY2020의 {@code 감사범위 제한으로 인한 한정}.
     * <b>「하나뿐이니 없는 것과 같다」로 읽지 않는다</b>: 목록 밖이 판정불가로 떨어지므로
     * 새 비적정 표기가 「적정」으로 새지 않는다는 것이 이 설계의 요점이다.
     */
    private static final Set<String> ADVERSE_OPINIONS = Set.of("감사범위제한으로인한한정");

    @Override
    public List<GovernanceFact> derive(GovernanceEndpoint endpoint,
                                       List<GovernanceReportRow> rows) {
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
     * {@code 비고} 행은 숫자 자리에 <b>문자열</b>({@code 자사주소각})을 싣고 온다(§16-A).
     */
    private List<GovernanceFact> shareTotals(List<GovernanceReportRow> rows) {
        List<GovernanceFact> facts = new ArrayList<>();
        for (GovernanceReportRow row : rows) {
            if (!"보통주".equals(normalize(row.field("se")))) {
                continue;
            }
            BigDecimal issued = DartAccountMapper.parseAmount(row.field("istc_totqy"));
            if (issued == null || issued.signum() <= 0) {
                // 행 전체가 '-'면 그 주식 종류가 없다는 뜻이다 — 0주 발행이 아니다(§16-A).
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
        for (GovernanceReportRow row : rows) {
            String item = normalize(row.field("se"));
            String kind = normalize(row.field("stock_knd"));
            GovernanceMetric metric;
            BigDecimal scale;
            if ("주당현금배당금(원)".equals(item) && COMMON_CLASSES.contains(kind)) {
                metric = GovernanceMetric.DPS_COMMON;
                scale = BigDecimal.ONE;
            } else if ("현금배당금총액(백만원)".equals(item)) {
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
                    continue;
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
     * 회사당 1행이다(§16-C).
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
     * 전부 같은 {@code 국민연금공단}이고 원인이 전부 {@code 장내매매}였다(§16-C).
     *
     * <p>행 순서가 날짜순이 아니라 {@code change_on}으로 정렬한 뒤 걷는다. 창의 시작 <b>직전</b>
     * 이름을 기준선으로 삼아야 창 첫 사건의 전이가 빠지지 않는다.
     */
    private List<GovernanceFact> controlChange(List<GovernanceReportRow> rows) {
        // MUST: 보고서(=요청 연도)마다 따로 센다. 한 덩어리로 접으면 사실이 최신 보고서 하나에만
        // 서고, **그 보고서가 나오기 전 구간이 통째로 빈다** — 실측으로 쓰기 창 180거래일 중
        // 74가 그 앞이다. 같은 실수를 최대주주 지분율에서 한 번 했다.
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
     * 한 회계연도에 행이 둘이다(연결·별도로 보이나 <b>필드로 구분되지 않는다</b>, §16-B).
     * 강조사항은 <b>OR</b>로 접고 감사인은 <b>집합</b>으로 접는다.
     *
     * <p>MUST NOT: {@code adt_opinion}으로 판정 지표를 만들지 않는다. 비적정 표기를 본 적이 없고
     * 활성 유니버스 안에서는 원리적으로 안 나온다 — 여기서 만드는 것은 강조사항 유무와 감사인
     * 교체뿐이고 둘 다 <b>관측된 값 위에</b> 선다.
     */
    private List<GovernanceFact> auditOpinion(List<GovernanceReportRow> rows) {
        Map<Integer, Boolean> emphasisByYear = new TreeMap<>();
        Map<Integer, Boolean> adverseByYear = new TreeMap<>();
        Map<Integer, Set<String>> auditorsByYear = new TreeMap<>();
        Map<Integer, GovernanceReportRow> anchorByYear = new LinkedHashMap<>();
        Set<String> unknownOpinions = new LinkedHashSet<>();

        for (GovernanceReportRow row : rows) {
            Integer fiscalYear = fiscalYearOfTerm(row.field("bsns_year"), row.bsnsYear());
            if (fiscalYear == null) {
                continue;
            }
            // MUST NOT: core_adt_matter(핵심감사사항)를 여기 넣지 않는다. 감사기준의 필수
            // 기재사항이라 표본 48행이 **전부** 값을 갖는다(§16-C) — 넣으면 이 지표가 전 종목 1인
            // 상수가 되고, 그것은 아무도 못 자르는 게이트다.
            //
            // adt_reprt_spcmnt_matter는 2019-12-08까지 쓰이던 같은 자리의 필드다. 한쪽만 읽으면
            // 그 이전 구간이 통째로 「강조사항 없음」이 된다(§11-I).
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

        if (!unknownOpinions.isEmpty()) {
            // MUST: 삼키지 않는다. 목록 밖 표기가 새로 생겼다는 것을 여기 말고는 알 자리가 없고,
            // 그 사이 그 종목은 「감사의견 판정불가」로 조건에서 탈락한다.
            log.warn("허용목록에 없는 감사의견 표기라 판정하지 않았다: {} — 관측된 표기면 "
                    + "CLEAN_OPINIONS나 ADVERSE_OPINIONS에 더한다(§16-C).", unknownOpinions);
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
     * 값이 있는 행의 {@code -}는 <b>0</b>이다(§16-A).
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
     * <p>MUST NOT: 기수 숫자를 쓰지 않는다. 회사마다 다르고 설립 연도에 걸려 있어 회계연도로
     * 환산할 수 없다 — 요청한 {@code bsns_year}에서 구분 토큰만큼 뒤로 물리는 것이 유일한 길이다.
     */
    private static Integer fiscalYearOfTerm(String raw, int requestedYear) {
        String flat = normalize(raw);
        for (Map.Entry<String, Integer> term : TERM_OFFSETS.entrySet()) {
            if (flat.endsWith(term.getKey())) {
                return requestedYear - term.getValue();
            }
        }
        return null;
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
