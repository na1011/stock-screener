package com.stockscreener.pipeline.datasource.us.shareclass;

import com.stockscreener.pipeline.datasource.DataSourceException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 공시 인스턴스에서 클래스 축 판정의 팩트를 읽는다(탐색 `SHARES-CLASS-PLAN-V5-1015.md` §0-5·6 · 시뮬레이터 {@code load5.py}).
 *
 * <p><b>기존 보충 리더({@code XbrlInstanceReader})와 따로 둔다.</b> 그 리더는 판 고정 범위 안이고 차원 맥락을 전부 버린다 —
 * 그것을 고치면 판이 올라 저장 보충 행 전부를 다시 받는다(v4 반증 15). 이 리더는 그 범위를 참조하지 않는다.
 *
 * <p><b>맥락은 축의 뜻으로 받는다 — 종목 갈래가 아니다.</b>
 * <ul>
 *   <li>클래스 축({@code StatementClassOfStockAxis})만 단 맥락과 축 없는 맥락을 받는다.</li>
 *   <li>클래스 축 + {@code StatementEquityComponentsAxis=CommonStockMember}는 같은 사실을 한 번 더 이름 단 것이라 <b>클래스만
 *       단 맥락이 없을 때</b> 그 클래스 사실로 받는다({@code NET}·{@code DASH}는 클래스 사실을 이 꼴로만 싣는다). 클래스 축 없이
 *       자본 구성요소 축만 단 맥락은 안 받는다 — {@code UHAL}에서 그것은 의결권 보통주 하나다.</li>
 *   <li>표지 주식수의 {@code SubsequentEventTypeAxis}는 받는다 — 표지는 원래 기말 뒤 시점 값이다({@code F}·{@code BIO}). 그 축
 *       없는 값이 있으면 그것이 먼저다.</li>
 *   <li>표지 기호 · 증권 이름은 거래소 축과 클래스 축 없는 보통주 구성요소 축도 받는다 — 같은 줄을 거래소나 증권 종류로 한 번 더 이름
 *       단 것이다. 그 축 없는 값이 있으면 그것이 먼저다.</li>
 *   <li>재작성·연결실체·법인 축은 다른 판·다른 실체라 버린다.</li>
 * </ul>
 *
 * <p>남의 CIK 맥락(공동 등록)은 기존 리더처럼 건너뛴다. 수로 안 읽히는 값은 건너뛰고 {@link Result#unreadableValues()}로 센다.
 */
public final class ClassFactReader {

    /**
     * 저장 팩트({@code sec_filing_class_fact.facts})를 정하는 읽기 규칙의 판 — 이 파일과 {@link ClassFacts}의 저장 형태를 바꾸면
     * 올린다. 판이 다른 행은 없는 것으로 치고 다음 받기가 다시 받는다. 2 — 표지 기호 · 증권 이름이 거래소 축 · 보통주 구성요소 축 꼴도
     * 받는다(2026-10-21). 3 — 표지 글의 줄바꿈 없는 공백 · 너비 0 구분자를 공백으로, 방향 표시를 지운다({@link #plainText} · 2026-10-21).
     * 4 — IFRS 공시(ADR-0182 · 0183): 클래스 축 {@code ifrs-full:ClassesOfShareCapitalAxis}를 클래스 축으로 받고, IFRS의 EPS · 가중평균 ·
     * 분자를 같은 뜻의 us-gaap 이름으로 싣는다(AZN · VALE 표지가 그 축의 구성원에만 주식수를 싣는다 · 2026-10-22 인스턴스).
     * 5 — LP 단위(ADR-0187): LP 단위 가중평균을 LP 클래스 축에서도 받고(표지 클래스 구성원만), LP+GP 합계 단위를 다른 가중평균이 없는
     * 자리의 폴백으로 싣는다(SUN · GEL · 2026-10-23). 6 — 지금 경로 분모(ADR-0189): 축 하나만 단 가중평균을 {@link #DIMENSIONED} 칸에
     * 싣는다(재작성 · 연결 · 법인 축과 형 붙은 차원 제외 · BKR · CBSH · LYB · SJM · PAA는 축 맥락에만 싣는다 · 2026-10-24).
     * 7 — 보통주 귀속 이익 재료({@link #NUMERATOR_MATERIAL} · 우선주 배당 · 참가증권 · GP · LP 배분 · LP 단위 EPS · 비지배)를 싣고, 총 EPS도
     * {@link #DIMENSIONED} 칸에 싣는다 — 보충 · 앵커 공시는 companyfacts에 없어 인스턴스가 유일한 재료이고, EPS를 LP 클래스 축 보통 단위에만
     * 싣는 발행사(PAA)가 있다(탐색 {@code PERSHARE-NUM-PLAN-1024.md} v2.1 · 2026-10-25). 판정은 그 칸의 가중평균만 읽는다.
     */
    public static final int READER_VERSION = 7;

    public static final String SHARES_OUTSTANDING = "EntityCommonStockSharesOutstanding";
    public static final String TRADING_SYMBOL = "TradingSymbol";
    public static final String SECURITY_TITLE = "Security12bTitle";
    /** 표지 거래소 코드({@code NYSE} · {@code NASDAQ} · {@code NYSEAMER} …) — 상장 판정({@code listing} 패키지)만 읽는다. 저장 팩트에는 안 든다. */
    public static final String SECURITY_EXCHANGE = "SecurityExchangeName";
    /** 표지의 예탁증권 비율(1 예탁증권당 주식 수) — 상장 판정 표지만 읽는다(관문 밖 순위의 예탁증권 판정이 증권 이름과 맞대는 보조). */
    public static final String DEPOSITORY_RATIO = "EntityListingDepositoryReceiptRatio";
    public static final List<String> WEIGHTED_TAGS = List.of("WeightedAverageNumberOfSharesOutstandingBasic",
            "WeightedAverageNumberOfShareOutstandingBasicAndDiluted", "WeightedAverageNumberOfSharesIssuedBasic",
            "WeightedAverageLimitedPartnershipUnitsOutstanding",
            "WeightedAverageNumberOfLimitedPartnershipAndGeneralPartnershipUnitOutstandingBasicAndDiluted",
            "WeightedAverageLimitedPartnershipAndGeneralPartnershipUnitsOutstandingBasic");
    /**
     * LP와 GP를 합친 단위 가중평균 — 같은 (구성원, 기간)에 다른 가중평균 태그가 없을 때만 싣는다. 함께 실린 공시에서 뜻이 갈린다
     * (CAPL 2016 Q1: LP 단위 28,475,363 대 합계 4,630,769 · JSPR 2022 9개월: 기본 36,425,000 대 합계 3,028,277 · 2026-10-23
     * 탐색 {@code LP-UNIT-PLAN-1023.md}). 둘을 함께 두면 판정기의 앞 태그가 이기는 자리({@code firstPerMember})와 뒤 태그가
     * 이기는 자리({@code periodValues})가 다른 값을 고른다 — 그래서 순서가 아니라 배타로 막는다.
     */
    public static final List<String> WEIGHTED_FALLBACK_TAGS = List.of(
            "WeightedAverageNumberOfLimitedPartnershipAndGeneralPartnershipUnitOutstandingBasicAndDiluted",
            "WeightedAverageLimitedPartnershipAndGeneralPartnershipUnitsOutstandingBasic");
    public static final List<String> EPS_TAGS = List.of("EarningsPerShareBasic", "EarningsPerShareBasicAndDiluted",
            "IncomeLossFromContinuingOperationsPerBasicShare");
    public static final String DECLARED_NUMERATOR = "NetIncomeLossAvailableToCommonStockholdersBasic";
    /** 총 EPS — 기본 · 기본겸희석 · LP 단위 기본(LP 단위 EPS는 분모 판정의 EPS 목록 밖 · ADR-0187 §버린 대안). */
    public static final List<String> TOTAL_EPS = List.of("EarningsPerShareBasic", "EarningsPerShareBasicAndDiluted",
            "NetIncomeLossPerOutstandingLimitedPartnershipUnitBasicNetOfTax",
            "NetIncomeLossPerOutstandingLimitedPartnershipUnitBasicAndDilutedNetOfTax",
            "NetIncomeLossPerOutstandingLimitedPartnershipAndGeneralPartnershipUnitBasicAndDiluted");
    public static final List<String> CONTINUING_EPS = List.of("IncomeLossFromContinuingOperationsPerBasicShare");
    public static final List<String> PREFERRED_DIVIDENDS = List.of("PreferredStockDividendsAndOtherAdjustments",
            "PreferredStockDividendsIncomeStatementImpact", "DividendsPreferredStock");
    public static final List<String> PARTICIPATING = List.of("ParticipatingSecuritiesDistributedAndUndistributedEarningsLossBasic",
            "UndistributedEarningsLossAllocatedToParticipatingSecuritiesBasic");
    public static final String GENERAL_PARTNER = "NetIncomeLossAllocatedToGeneralPartners";
    public static final String LIMITED_PARTNERS = "NetIncomeLossAllocatedToLimitedPartners";
    public static final String NONCONTROLLING = "NetIncomeLossAttributableToNoncontrollingInterest";
    /** 희석 EPS · 희석 가중평균 — 기본 EPS 없이 희석만 실은 공시의 배분 0 증인(POR 2025 Q1 10-Q). */
    public static final String DILUTED_EPS = "EarningsPerShareDiluted";
    public static final String DILUTED_WEIGHTED = "WeightedAverageNumberOfDilutedSharesOutstanding";
    /** 보통주 귀속 이익 재료 태그 전부 — 가지치기 · 인스턴스 판독기가 같은 목록을 본다. */
    public static final List<String> NUMERATOR_MATERIAL = java.util.stream.Stream.of(TOTAL_EPS, CONTINUING_EPS, PREFERRED_DIVIDENDS, PARTICIPATING,
            List.of(GENERAL_PARTNER, LIMITED_PARTNERS, NONCONTROLLING, "NetIncomeLoss", "ProfitLoss", DILUTED_EPS, DILUTED_WEIGHTED))
            .flatMap(List::stream).toList();
    public static final List<String> NUMERATOR_TAGS = List.of(DECLARED_NUMERATOR, "NetIncomeLoss", "ProfitLoss");

    private static final Set<String> COVER = Set.of(SHARES_OUTSTANDING, TRADING_SYMBOL, SECURITY_TITLE);
    /**
     * 상장 판정의 표지 — 기호 · 거래소 · 증권 이름 · 예탁증권 비율 · 표지 주식수(예탁증권 판정이 그 주식수가 클래스 구성원에만 실렸는지
     * 본다). 저장 팩트({@link #COVER})와 따로 둬 그 판을 안 바꾼다.
     */
    private static final Set<String> LISTING_COVER = Set.of(TRADING_SYMBOL, SECURITY_EXCHANGE, SECURITY_TITLE, DEPOSITORY_RATIO,
            SHARES_OUTSTANDING);
    private static final Set<String> DURATION = Set.copyOf(concat(WEIGHTED_TAGS, EPS_TAGS, NUMERATOR_TAGS, NUMERATOR_MATERIAL));
    /** LP 축을 클래스 축으로 받는 개념 — LP 단위 가중평균과 그 합계 폴백뿐이다(법인 태그는 안 넓힌다 — REIT 운영 파트너십 단위). */
    private static final Set<String> LP_UNIT_TAGS = Set.copyOf(concat(List.of("WeightedAverageLimitedPartnershipUnitsOutstanding"),
            WEIGHTED_FALLBACK_TAGS));
    /** 낱말을 가르는 비ASCII 공백 — 공백 분리자 전부와 너비 0 구분자(지우면 너비 0 공백으로 가른 두 낱말이 하나로 붙는다). */
    private static final Pattern WIDE_SPACE = Pattern.compile("[\\p{Zs}\\u200B\\u2060\\uFEFF]");
    /** 글자 사이에 끼어도 뜻이 없는 표시 — 방향 표시와 soft hyphen. */
    private static final Pattern INVISIBLE = Pattern.compile("[\\u00AD\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]");
    private static final String CLASS_AXIS = "StatementClassOfStockAxis";
    /** IFRS의 클래스 축 — 같은 뜻(주식 종류). 구성원 열쇠는 축 이름과 무관하게 구성원 그대로다. */
    private static final String IFRS_CLASS_AXIS = "ClassesOfShareCapitalAxis";
    private static final String IFRS_PREFIX = "http://xbrl.ifrs.org/taxonomy/";
    /** 2023년 판부터의 IFRS 택소노미는 https다(TRI · AZN 2026 인스턴스 실측) — 둘 다 받는다. */
    private static final String IFRS_PREFIX_HTTPS = "https://xbrl.ifrs.org/taxonomy/";
    /** IFRS 개념 → 판정이 읽는 us-gaap 이름(뜻이 같은 것만 · ADR-0182 별칭과 같다). */
    private static final Map<String, String> IFRS_DURATION = Map.of(
            "BasicEarningsLossPerShare", "EarningsPerShareBasic",
            "WeightedAverageShares", "WeightedAverageNumberOfSharesOutstandingBasic",
            "ProfitLossAttributableToOrdinaryEquityHoldersOfParentEntity", "NetIncomeLossAvailableToCommonStockholdersBasic",
            "ProfitLossAttributableToOwnersOfParent", "NetIncomeLoss",
            "ProfitLoss", "ProfitLoss");
    private static final String EQUITY_AXIS = "StatementEquityComponentsAxis";
    /**
     * LP 단위의 클래스 축 — LP 단위 가중평균에만 클래스 축으로 받는다. SUN은 2023-08 뒤 가중평균을 이 축의 {@code CommonUnitsMember}에만
     * 싣고, 표지 주식수는 클래스 축의 같은 구성원에 싣는다(2026-10-23 인스턴스). 구성원이 그 공시 표지의 클래스 구성원일 때만 받는다 —
     * 이 축에는 거래 구성원(KNOP {@code PartnersCapitalAccountSaleOfUnitsMember})과 0인 종속 단위(BSM)도 실린다.
     */
    private static final String LP_CLASS_AXIS = "LimitedPartnersCapitalAccountByClassAxis";
    private static final String SUBSEQUENT_AXIS = "SubsequentEventTypeAxis";
    /**
     * 지금 경로 분모 칸의 접두어 — 축 하나만 단 가중평균을 {@code DIMENSIONED + 개념}에 {@code 축=구성원} 열쇠로 싣는다. 앵커 판정은 개념
     * 이름으로 읽으므로 이 칸을 못 본다(ADR-0189).
     */
    public static final String DIMENSIONED = "d:";
    /** 같은 분모를 다른 이름으로 단 축 — 주식 종류 · 조합원 종류 · EPS 표시 · 계속/중단영업(ADR-0189). */
    static final Set<String> SAME_DENOMINATOR_AXES = Set.of(CLASS_AXIS, IFRS_CLASS_AXIS, "LimitedPartnersCapitalAccountByClassAxis",
            "PartnerTypeOfPartnersCapitalAccountAxis", "IncomePerCommonShareAxis", "EarningsPerShareMethodAxis",
            "StatementOperatingActivitiesSegmentAxis");
    /** 지금 경로 칸에 안 싣는 축 — 다른 판(재작성)이나 다른 실체(연결 · 법인)다(이 판독기 머리말의 축 규칙과 같다). */
    private static final Set<String> OTHER_VERSION_AXES = Set.of("RestatementAxis", "StatementScenarioAxis",
            "ConsolidatedEntitiesAxis", "LegalEntityAxis", "ConsolidationItemsAxis",
            "ErrorCorrectionsAndPriorPeriodAdjustmentsRestatementByRestatementPeriodAndAmountAxis",
            "AdjustmentsForNewAccountingPronouncementsAxis");
    /** 표지의 거래소 축 — 상장 판정 표지만 받는다(Two Harbors 10-Q는 표지 기호마다 클래스 축 + 이 축을 단다 · R-D6-0 2026-10-18). */
    private static final String LISTINGS_EXCHANGE_AXIS = "EntityListingsExchangeAxis";
    private static final String XBRLI = "http://www.xbrl.org/2003/instance";
    private static final String XBRLDI = "http://xbrl.org/2006/xbrldi";
    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String US_GAAP_PREFIX = "http://fasb.org/us-gaap/";
    private static final String DEI_PREFIX = "http://xbrl.sec.gov/dei/";
    private static final String SOURCE = "sec:filing-instance";

    private ClassFactReader() {
    }

    /** @param unreadableValues 수로 안 읽혀 건너뛴 기간 팩트의 수 */
    public record Result(ClassFacts facts, int unreadableValues) {
    }

    /** 축 로컬 이름 → 구성원 QName 글자. */
    private record Context(long cik, Map<String, String> dims, LocalDate start, LocalDate end) {
    }

    /** 맥락을 풀기 전의 사실 — 사실이 맥락보다 앞에 나오는 인스턴스가 있어 파일 끝에서 푼다(JOBY 2021-03). */
    private record Raw(String concept, String contextRef, String decimals, String text) {
    }

    private record Late(boolean cover, String concept, String member, String text, ClassFacts.Period period,
                        ClassFacts.Measured measured) {
    }

    private record Pending(String concept, String member, ClassFacts.Period period, ClassFacts.Measured measured) {
    }

    public static Result read(InputStream xml, long cik, String context) throws DataSourceException {
        return read(xml, cik, context, COVER);
    }

    /**
     * 상장 판정의 표지 — 클래스 축 구성원(없으면 {@link ClassFacts#AXISLESS})마다 기호 · 거래소 코드 · 증권 이름. 맥락 해석은
     * {@link #read}와 같다(같은 축 규칙 · 남의 CIK 맥락은 건너뜀). 기간 팩트도 함께 읽히지만 상장 판정은 표지만 쓴다.
     */
    public static ClassFacts readListingCover(InputStream xml, long cik, String context) throws DataSourceException {
        return read(xml, cik, context, LISTING_COVER).facts();
    }

    private static Result read(InputStream xml, long cik, String context, Set<String> coverConcepts)
            throws DataSourceException {
        Map<String, Context> contexts = new HashMap<>();
        Map<String, LinkedHashMap<ClassFacts.MemberPeriod, ClassFacts.Measured>> durations = new LinkedHashMap<>();
        Map<String, LinkedHashMap<String, String>> cover = new LinkedHashMap<>();
        // 저장 표지의 넓힌 꼴(거래소 축 · 보통주 구성요소 축)로만 온 기호 · 증권 이름 — 구성원마다 문서 순서로 모은다
        Map<String, LinkedHashMap<String, LinkedHashSet<String>>> widened = new LinkedHashMap<>();
        List<Late> late = new ArrayList<>();
        List<Pending> lpAxis = new ArrayList<>();
        List<Pending> fallback = new ArrayList<>();
        List<Pending> dimensioned = new ArrayList<>();
        List<Raw> raws = new ArrayList<>();
        String documentType = null;
        int unreadable = 0;
        try {
            XMLStreamReader r = factory().createXMLStreamReader(xml);
            try {
                while (r.hasNext()) {
                    if (r.next() != XMLStreamConstants.START_ELEMENT) {
                        continue;
                    }
                    String ns = r.getNamespaceURI();
                    String local = r.getLocalName();
                    if (XBRLI.equals(ns) && "context".equals(local)) {
                        contexts.put(r.getAttributeValue(null, "id"), readContext(r, context));
                        continue;
                    }
                    boolean gaap = ns != null && ns.startsWith(US_GAAP_PREFIX);
                    boolean dei = ns != null && ns.startsWith(DEI_PREFIX);
                    boolean ifrs = ns != null && (ns.startsWith(IFRS_PREFIX) || ns.startsWith(IFRS_PREFIX_HTTPS));
                    if (!gaap && !dei && !ifrs) {
                        continue;
                    }
                    if (ifrs) {
                        local = IFRS_DURATION.get(local);
                        if (local == null) {
                            continue;
                        }
                    }
                    if (dei && "DocumentType".equals(local)) {
                        documentType = r.getElementText().trim();
                        continue;
                    }
                    if (!coverConcepts.contains(local) && !DURATION.contains(local)
                            || coverConcepts != COVER && WEIGHTED_FALLBACK_TAGS.contains(local)) {
                        continue;
                    }
                    String contextRef = r.getAttributeValue(null, "contextRef");
                    String decimals = r.getAttributeValue(null, "decimals");
                    boolean nil = "true".equals(r.getAttributeValue(XSI, "nil"));
                    String text = nil ? null : r.getElementText();
                    if (text != null && !text.isBlank()) {
                        raws.add(new Raw(local, contextRef, decimals, coverConcepts.contains(local) ? plainText(text) : text.trim()));
                    }
                }
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE, "인스턴스를 읽지 못했다. " + context, e);
        }
        for (Raw raw : raws) {
            Context c = contexts.get(raw.contextRef());
            if (c == null || c.cik() != cik) {
                continue;
            }
            String local = raw.concept();
            String text = raw.text();
            String decimals = raw.decimals();
            if (coverConcepts == COVER && (WEIGHTED_TAGS.contains(local) || TOTAL_EPS.contains(local))
                    && c.start() != null && c.end() != null && c.dims().size() == 1
                    && !OTHER_VERSION_AXES.contains(c.dims().keySet().iterator().next())
                    && !"typed".equals(c.dims().values().iterator().next())) {
                String axis = c.dims().keySet().iterator().next();
                try {
                    dimensioned.add(new Pending(DIMENSIONED + local, axis + "=" + c.dims().get(axis),
                            new ClassFacts.Period(c.start(), c.end()),
                            new ClassFacts.Measured(Double.parseDouble(text), halfWidth(decimals))));
                } catch (NumberFormatException e) {
                    if (!CLASS_AXIS.equals(axis) && !IFRS_CLASS_AXIS.equals(axis)) {
                        unreadable++;    // 클래스 축 사실은 아래 본 경로가 센다 — 다른 축은 본 경로가 파싱 전에 건너뛴다
                    }
                }
            }
            Set<String> extra = new java.util.HashSet<>(c.dims().keySet());
            extra.remove(CLASS_AXIS);
            extra.remove(IFRS_CLASS_AXIS);
            String member = c.dims().getOrDefault(CLASS_AXIS, c.dims().getOrDefault(IFRS_CLASS_AXIS, ClassFacts.AXISLESS));
            boolean lpMember = false;
            if (coverConcepts == COVER && LP_UNIT_TAGS.contains(local) && !c.dims().containsKey(CLASS_AXIS)
                    && c.dims().containsKey(LP_CLASS_AXIS)) {
                member = c.dims().get(LP_CLASS_AXIS);
                extra.remove(LP_CLASS_AXIS);
                lpMember = true;
            }
            if (coverConcepts == LISTING_COVER) {
                // 상장 판정은 기호 · 거래소 · 증권 이름(과 예탁증권 판정의 비율 · 표지 주식수)만 본다(저장 팩트 COVER는 그 판을 안 바꾸려고 그대로 둔다).
                //  - 거래소 축은 그 줄이 어느 거래소의 것인지 말한다 — 이중 상장은 줄이 거래소마다 따로라 구성원 열쇠에 거래소를 붙인다
                //    (SunOpta 10-K: STKL · NASDAQ과 SOY · TSX가 둘 다 클래스 축 없이 온다 · R-D6-0).
                //  - 클래스 축 없이 자본 구성요소 축 = 보통주만 단 표지 줄은 그 보통주 줄이다(같은 SunOpta).
                String exchange = c.dims().get(LISTINGS_EXCHANGE_AXIS);
                if (exchange != null) {
                    extra.remove(LISTINGS_EXCHANGE_AXIS);
                    member = member + "@" + exchange;
                }
                if (extra.equals(Set.of(EQUITY_AXIS)) && c.dims().get(EQUITY_AXIS).endsWith(":CommonStockMember")) {
                    extra.remove(EQUITY_AXIS);
                }
            }
            if (coverConcepts == COVER && (TRADING_SYMBOL.equals(local) || SECURITY_TITLE.equals(local)) && !extra.isEmpty()) {
                // 저장 표지의 기호 · 증권 이름도 상장 판정 표지와 같은 두 꼴을 받는다 — 버리면 그 등록인의 상장 클래스 매핑이 「unmapped」로
                // 떨어진다(Two Harbors · CenterPoint · Molson Coors 10-Q는 표지 줄마다 거래소 축을, SunOpta · Algonquin은 클래스 축 없이 보통주
                // 구성요소 축을 단다 · 클래스 스윕 2026-10-21). 구성원 열쇠는 클래스 축 그대로다(거래소를 안 붙인다) — 저장 팩트를 읽는 쪽은
                // 클래스 구성원으로 EPS와 잇는다. 표지 주식수는 넓히지 않는다(그 뜻을 읽는 소비자가 넷이다).
                Set<String> rest = new java.util.HashSet<>(extra);
                rest.remove(LISTINGS_EXCHANGE_AXIS);
                if (rest.equals(Set.of(EQUITY_AXIS)) && c.dims().get(EQUITY_AXIS).endsWith(":CommonStockMember")) {
                    rest.remove(EQUITY_AXIS);
                }
                if (rest.isEmpty()) {
                    widened.computeIfAbsent(local, k -> new LinkedHashMap<>()).computeIfAbsent(member, k -> new LinkedHashSet<>())
                            .add(text);
                }
                continue;
            }
            if (coverConcepts.contains(local)) {
                if (!extra.isEmpty() && !(SHARES_OUTSTANDING.equals(local) && extra.equals(Set.of(SUBSEQUENT_AXIS)))) {
                    continue;
                }
                if (extra.isEmpty()) {
                    cover.computeIfAbsent(local, k -> new LinkedHashMap<>()).put(member, text);
                } else {
                    late.add(new Late(true, local, member, text, null, null));
                }
                continue;
            }
            if (c.start() == null || c.end() == null) {
                continue;
            }
            if (!extra.isEmpty() && !(extra.equals(Set.of(EQUITY_AXIS)) && c.dims().containsKey(CLASS_AXIS)
                    && c.dims().get(EQUITY_AXIS).endsWith(":CommonStockMember"))) {
                continue;
            }
            ClassFacts.Measured measured;
            try {
                measured = new ClassFacts.Measured(Double.parseDouble(text), halfWidth(decimals));
            } catch (NumberFormatException e) {
                unreadable++;
                continue;
            }
            ClassFacts.Period period = new ClassFacts.Period(c.start(), c.end());
            if (lpMember || WEIGHTED_FALLBACK_TAGS.contains(local)) {
                if (extra.isEmpty()) {
                    (WEIGHTED_FALLBACK_TAGS.contains(local) ? fallback : lpAxis).add(new Pending(local, member, period, measured));
                }
                continue;
            }
            if (extra.isEmpty()) {
                durations.computeIfAbsent(local, k -> new LinkedHashMap<>()).put(new ClassFacts.MemberPeriod(member, period), measured);
            } else {
                late.add(new Late(false, local, member, null, period, measured));
            }
        }
        // 두 축 사실은 클래스만 단 사실이 없는 자리만 채운다(문서 순서와 무관하게 클래스만 단 쪽이 먼저다).
        for (Late l : late) {
            if (l.cover()) {
                cover.computeIfAbsent(l.concept(), k -> new LinkedHashMap<>()).putIfAbsent(l.member(), l.text());
            } else {
                durations.computeIfAbsent(l.concept(), k -> new LinkedHashMap<>())
                        .putIfAbsent(new ClassFacts.MemberPeriod(l.member(), l.period()), l.measured());
            }
        }
        // LP 축 사실은 그 구성원이 표지의 클래스 구성원일 때만, 클래스 축 사실이 없는 자리만 채운다.
        Set<String> coverMembers = cover.getOrDefault(SHARES_OUTSTANDING, new LinkedHashMap<>()).keySet();
        for (Pending p : lpAxis) {
            if (coverMembers.contains(p.member())) {
                durations.computeIfAbsent(p.concept(), k -> new LinkedHashMap<>())
                        .putIfAbsent(new ClassFacts.MemberPeriod(p.member(), p.period()), p.measured());
            }
        }
        // 합계 단위는 같은 (구성원, 기간)에 다른 가중평균이 없을 때만 — 위 LP 축 사실까지 다 실은 뒤에 본다.
        for (Pending p : fallback) {
            ClassFacts.MemberPeriod key = new ClassFacts.MemberPeriod(p.member(), p.period());
            boolean lp = !ClassFacts.AXISLESS.equals(p.member());
            boolean taken = WEIGHTED_TAGS.stream().filter(t -> !WEIGHTED_FALLBACK_TAGS.contains(t))
                    .anyMatch(t -> durations.getOrDefault(t, new LinkedHashMap<>()).containsKey(key));
            if (!taken && (!lp || coverMembers.contains(p.member()))) {
                durations.computeIfAbsent(p.concept(), k -> new LinkedHashMap<>()).putIfAbsent(key, p.measured());
            }
        }
        for (Pending p : dimensioned) {
            durations.computeIfAbsent(p.concept(), k -> new LinkedHashMap<>())
                    .putIfAbsent(new ClassFacts.MemberPeriod(p.member(), p.period()), p.measured());
        }
        // 넓힌 꼴은 축 없는(클래스 축만 단) 사실이 없는 구성원만 채운다. 이중 상장(SunOpta STKL · NASDAQ과 SOY · TSX)은 한 증권의 기호가
        // 거래소마다 따로 오므로 기호는 「GOOGL, GOOG」 꼴로 잇고(DenominatorJudge.splitSymbols가 쪼갠다), 증권 이름은 문서 순서 첫 값이다.
        widened.forEach((concept, byMember) -> byMember.forEach((member, texts) ->
                cover.computeIfAbsent(concept, k -> new LinkedHashMap<>()).putIfAbsent(member,
                        TRADING_SYMBOL.equals(concept) ? String.join(", ", texts) : texts.iterator().next())));
        return new Result(new ClassFacts(durations, cover, documentType), unreadable);
    }

    /**
     * 표지 글(기호 · 거래소 · 증권 이름 · 표지 주식수)을 읽는 쪽이 ASCII 공백으로 가르게 한다. 공시 표지 증권 이름이 「Class A」 사이에
     * 줄바꿈 없는 공백(U+00A0)을 싣곤 하는데(클래스 스윕 재료 공시 12,529 중 1,042 · 2026-10-21) 자바 정규식의 {@code \s}는 그것을 안 받아, 판정기 · 상장
     * 판정({@code ClassLines})이 클래스 글자를 못 읽었다(ERIE 표지 → 상장 클래스 unmapped). 공시 HTML 경로({@code ListingFormReader})는
     * 이미 읽는 자리에서 바꾼다 — 여기도 읽는 자리에서 한 번 바꿔, 글을 읽는 정규식마다 따로 막지 않는다.
     */
    static String plainText(String text) {
        return INVISIBLE.matcher(WIDE_SPACE.matcher(text).replaceAll(" ")).replaceAll("").strip();
    }

    /** 선언 정밀도의 반올림 반폭. 정밀도가 수로 안 읽히면 그 팩트를 못 읽은 것으로 센다(호출자). */
    private static double halfWidth(String decimals) {
        if (decimals == null || "INF".equals(decimals)) {
            return 0.0;
        }
        return 0.5 * Math.pow(10, -Integer.parseInt(decimals.trim()));
    }

    private static Context readContext(XMLStreamReader r, String context) throws XMLStreamException, DataSourceException {
        long cik = -1;
        LocalDate start = null;
        LocalDate end = null;
        Map<String, String> dims = new HashMap<>();
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                String ns = r.getNamespaceURI();
                String local = r.getLocalName();
                if (XBRLDI.equals(ns) && "explicitMember".equals(local)) {
                    String dimension = r.getAttributeValue(null, "dimension");
                    String member = r.getElementText().trim();
                    depth--;
                    dims.put(ClassFacts.local(dimension.trim()), member);
                } else if (XBRLDI.equals(ns) && "typedMember".equals(local)) {
                    // 형 붙은 차원은 클래스 축이 아니다 — 축으로 세어 그 맥락을 「다른 축이 붙은 것」으로 만든다.
                    dims.put(ClassFacts.local(r.getAttributeValue(null, "dimension").trim()), "typed");
                } else if (XBRLI.equals(ns) && "identifier".equals(local)) {
                    String text = r.getElementText().trim();
                    depth--;
                    try {
                        cik = Long.parseLong(text);
                    } catch (NumberFormatException e) {
                        cik = -1;
                    }
                } else if (XBRLI.equals(ns) && ("instant".equals(local) || "endDate".equals(local))) {
                    end = date(r.getElementText(), context);
                    depth--;
                } else if (XBRLI.equals(ns) && "startDate".equals(local)) {
                    start = date(r.getElementText(), context);
                    depth--;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return new Context(cik, dims, start, end);
    }

    private static LocalDate date(String raw, String context) throws DataSourceException {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE, "날짜가 아니다: " + raw + " " + context, e);
        }
    }

    private static XMLInputFactory factory() {
        XMLInputFactory f = XMLInputFactory.newDefaultFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        return f;
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> out = new ArrayList<>();
        for (List<String> l : lists) {
            out.addAll(l);
        }
        return out;
    }
}
