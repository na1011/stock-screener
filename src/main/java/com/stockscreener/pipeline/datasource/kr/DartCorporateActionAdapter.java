package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.CorporateActionDataSource;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.PriceDiscontinuity;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.StoredPriceSeries;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * DART 「증자(감자) 현황」({@code irdsSttus.json}) 어댑터. 액면분할·병합과 무상증자를 받는다.
 *
 * <p>실측과 필드 계약은 {@code docs/api-spec/dart-corporate-action.md}에 있다.
 *
 * <h2>IMPORTANT — 이 소스 하나로는 값이 나오지 않는다</h2>
 * <p><b>무엇이 가격을 바꾸는 행위인가는 DART의 낱말이 답하고, 얼마나 바꾸는가와 언제부터인가는
 * {@code price_daily.listed_shares}가 답한다</b>(ADR-0049). 둘 중 하나만으로는 되지 않는다 —
 * 주식수는 유상증자·전환권행사·주식매수선택권행사가 상시 조금씩 바꾸고, 종가 배수는 그날
 * 시장이 움직인 만큼 흐려진다(대한전선 10.63 대 정확한 10). 그래서 이 어댑터는
 * {@link StoredPriceSeries}를 함께 읽는다.
 *
 * <h2>이 소스가 답하지 못하는 것 둘</h2>
 * <ul>
 *   <li><b>액면병합이 실리지 않는다.</b> 대한전선 2023 10:1 병합은 <b>행 자체가 없다</b> —
 * 병합은 자본금을 바꾸지 않아 「증자(감자)」에 실릴 자리가 없다(dart-corporate-action.md의 「증자(감자) 현황」
 * 절 실측). 그쪽은
 *       {@code AdjustmentRecalculator.detectCandidates}가 후보로 올린다.
 *   <li><b>최대 넉 달 늦다.</b> 정기보고서에만 실리므로 {@link DartIrdsReports}가 <b>빈 집합을
 *       내는 것이 정상</b>인 창이 있다.
 * </ul>
 */
// 키가 없으면 등록하지 않는다. 다른 DART 어댑터와 같은 이유다.
@ConditionalOnApiKey("dart")
@Component
public class DartCorporateActionAdapter implements CorporateActionDataSource {

    private static final Logger log = LoggerFactory.getLogger(DartCorporateActionAdapter.class);

    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final MathContext MC = new MathContext(34);

    /** 액면분할. <b>「액면분할」이 아니다</b> — 낱말을 틀리면 0건이 되고 그것이 "분할이 없다"로 읽힌다. */
    private static final String FORM_PAR_VALUE_CHANGE = "주식분할";
    private static final String FORM_BONUS_ISSUE = "무상증자";

    /** 주식수는 바꾸지만 가격은 안 바꾼다. 계수와 무관하므로 건너뛴다. */
    private static final Set<String> FORMS_WITHOUT_PRICE_EFFECT = Set.of(
            "유상증자(일반공모)", "유상증자(제3자배정)", "유상증자(주주배정)",
            "전환권행사", "주식매수선택권행사");

    /** 액면분할 원값은 매매개시일보다 <b>1~2일 이르다</b>. 뒤로 조금만 물러 잡는다. */
    private static final int PAR_CHANGE_LOOKBEHIND_DAYS = 3;

    /** 앞으로는 넓게 본다 — 변경상장 전에 매매거래정지가 낀다(대한전선은 11거래일). */
    private static final int PAR_CHANGE_LOOKAHEAD_DAYS = 45;

    /**
     * 공시 수량으로 효력일을 찾을 때 요구하는 <b>최소 주식수 변화</b>.
     *
     * <p>이보다 작으면 기계적 가격 이동이 하루 등락에 묻혀 <b>맞물림을 잴 수 없다</b> —
     * 실측으로 {@code 신주인수권행사} 73건의 평균 주식수 배수가 1.0091이고, 그중 46건이
     * 가격 조건을 <b>거저</b> 통과한다. 근거는 {@code coupledShareChange}의 javadoc에 있다.
     */
    private static final BigDecimal MIN_SHARE_CHANGE = new BigDecimal("0.05");

    /** 주식수 배수 × 가격 배수가 1에서 벗어나도 되는 폭. 순수 희석은 이 폭을 크게 넘는다. */
    private static final BigDecimal PRICE_MATCH_TOLERANCE = new BigDecimal("0.03");

    /** 권리락과 신주상장이 14~16거래일 벌어진다. 달력으로 넉넉히 덮는다. */
    private static final int BONUS_ISSUE_LOOKAHEAD_DAYS = 75;

    /** 소스 원값 기준으로 시계열을 읽어 둘 여유. 앞쪽은 직전 봉과 거래정지를 함께 덮는다. */
    private static final int SERIES_MARGIN_BEFORE_DAYS = 30;
    private static final int SERIES_MARGIN_AFTER_DAYS = 90;

    /**
     * 소스 원값이 이만큼 창 밖이면 아예 유도하지 않는다. 1콜이 5년 이력을 주므로 없으면
     * 창과 무관한 옛 사건까지 매번 시계열을 뒤진다. 일자 보정 폭보다 넉넉하다.
     */
    private static final int SOURCE_DATE_MARGIN_DAYS = 90;

    /** 액면가 교차확인용. <b>게이트가 아니라 두 번째 증거다</b> — 아래 주석 참조. */
    private static final List<BigDecimal> STANDARD_PAR_VALUES = List.of(
            new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("500"),
            new BigDecimal("1000"), new BigDecimal("2500"), new BigDecimal("5000"));

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final DartCorpCodeCache corpCodes;
    private final StoredPriceSeries series;
    private final String apiKey;

    public DartCorporateActionAdapter(ExternalApiClientFactory clientFactory,
                                      DataSourceProperties properties,
                                      @Qualifier("dartRateLimiter") RateLimiter rateLimiter,
                                      DartCorpCodeCache corpCodes,
                                      StoredPriceSeries series) {
        DataSourceProperties.Source source = properties.getDart();
        this.client = clientFactory.create(source);
        this.rateLimiter = rateLimiter;
        this.corpCodes = corpCodes;
        this.series = series;
        this.apiKey = source.getApiKey();
    }

    @Override
    public Set<String> supportedMarketCodes() {
        return Set.of("KOSPI", "KOSDAQ");
    }

    @Override
    public String sourceName() {
        return DartResponse.SOURCE_NAME;
    }

    @Override
    public List<CorporateAction> fetchCorporateActions(SecurityRef security,
                                                       LocalDate from, LocalDate to)
            throws DataSourceException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, DartResponse.SOURCE_NAME,
                    "DART_API_KEY가 설정되지 않았다.");
        }
        Optional<String> corpCode = corpCodes.findCorpCode(security.ticker());
        if (corpCode.isEmpty()) {
            // DART가 모르는 종목코드다. 신규 상장 직후에 정상적으로 생긴다.
            log.info("DART에 등록되지 않은 종목코드라 기업 행위를 건너뛴다. ticker={}", security.ticker());
            return List.of();
        }

        List<DartIrdsReports.Report> reports = DartIrdsReports.covering(from, to, LocalDate.now());
        if (reports.isEmpty()) {
            // MUST NOT: 이것을 실패로 올리지 않는다. 소스가 정기보고서에만 실려 최대 넉 달
            // 늦는 것이고, 그 사이의 사건은 휴리스틱 탐지가 후보로 올린다.
            log.debug("조회 창을 덮는 정기보고서가 아직 없다. ticker={} {} ~ {}", security.ticker(), from, to);
            return List.of();
        }
        Set<IrdsRow> rows = new LinkedHashSet<>();
        for (DartIrdsReports.Report report : reports) {
            for (IrdsRow row : rowsOf(corpCode.get(), report, security)) {
                // 창과 무관한 옛 사건까지 시계열을 뒤지지 않는다. 여유는 일자 보정 폭보다 넓다.
                if (row.sourceDate().isBefore(from.minusDays(SOURCE_DATE_MARGIN_DAYS))
                        || row.sourceDate().isAfter(to.plusDays(SOURCE_DATE_MARGIN_DAYS))) {
                    continue;
                }
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            return List.of();
        }

        CorporateActionEvidence evidence = evidenceFor(security, rows);
        Map<LocalDate, CorporateAction> byEffectiveDate = new LinkedHashMap<>();
        for (IrdsRow row : rows) {
            derive(row, evidence, security)
                    .filter(action -> !action.effectiveDate().isBefore(from)
                            && !action.effectiveDate().isAfter(to))
                    .ifPresent(action -> byEffectiveDate.putIfAbsent(action.effectiveDate(), action));
        }
        return byEffectiveDate.values().stream()
                .sorted(Comparator.comparing(CorporateAction::effectiveDate))
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>DART는 정기보고서에만 실려 창의 뒤쪽 최대 넉 달이 비는 일이 있다.
     * {@link DartIrdsReports}의 탐욕 알고리즘은 못 덮는 구간을 <b>조용히 건너뛰므로</b>,
     * 이 답이 없으면 호출자가 창 전체를 조회했다고 읽는다 — <b>커버리지를 성립으로
     * 읽지 않는다</b>(phase 기준 §P9).
     *
     * <p>실패가 아니다 — 그 구간의 불연속은 {@code AdjustmentRecalculator.detectCandidates}가
     * 후보로 올린다. 여기서는 <b>무엇을 못 봤는지 말할 수 있게</b> 하는 것이 전부다.
     *
     * <p>MUST: 종목을 안 본다. {@code covering}이 {@code (from, to, today)}만 받으므로
     * 이 답은 한 판 안에서 모든 종목에 같다 — 종목 루프 안에서 찍던 때는 한 판에 350줄이
     * 티커만 다른 채 똑같이 나왔다(2026-09-11 실측 · 운영 로그의 12.7%).
     */
    @Override
    public Optional<String> windowCoverage(LocalDate from, LocalDate to) {
        List<DartIrdsReports.Report> reports = DartIrdsReports.covering(from, to, LocalDate.now());
        if (reports.isEmpty()) {
            return Optional.of("창을 덮는 정기보고서가 하나도 없다. 창=%s~%s".formatted(from, to));
        }
        LocalDate coveredThrough = reports.stream()
                .map(DartIrdsReports.Report::coversTo)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        if (!coveredThrough.isBefore(to)) {
            return Optional.empty();
        }
        return Optional.of(("조회 창의 뒤쪽을 덮는 정기보고서가 아직 없다. 창=%s~%s 덮은 데까지=%s"
                + " — 그 뒤 구간의 사건은 소스가 원리적으로 답하지 못하며 휴리스틱이 후보로 올린다.")
                .formatted(reports.get(0).coversFrom(), to, coveredThrough));
    }

    /**
     * 보고서 하나의 행. {@code raw}는 낱말 원문이며 로그에만 쓴다.
     *
     * <p>겹치는 보고서가 같은 사건을 두 번 주므로 이 record의 동등성이 곧 중복 제거다.
     */
    private record IrdsRow(LocalDate sourceDate, String form, BigDecimal quantity,
                           BigDecimal parValueAfter, String stockKind) {
    }

    /** 한 종목의 유도에 필요한 시계열을 <b>한 번만</b> 읽는다. */
    private CorporateActionEvidence evidenceFor(SecurityRef security, Set<IrdsRow> rows) {
        LocalDate earliest = rows.stream().map(IrdsRow::sourceDate).min(Comparator.naturalOrder())
                .orElseThrow();
        LocalDate latest = rows.stream().map(IrdsRow::sourceDate).max(Comparator.naturalOrder())
                .orElseThrow();
        return new CorporateActionEvidence(series.between(security.stockId(),
                earliest.minusDays(SERIES_MARGIN_BEFORE_DAYS),
                latest.plusDays(SERIES_MARGIN_AFTER_DAYS)));
    }

    /**
     * 낱말로 갈래를 나누고 우리 시계열에서 값을 유도한다.
     *
     * <p>MUST NOT: 유도가 안 되면 지어내지 않는다. 원장 행은 {@code effective_date}가 정본인데
     * 그것을 모른 채 적으면 거짓이 남고, 비율을 근사로 채우면 <b>틀린 계수가 아무 에러 없이</b>
     * 들어간다.
     */
    private Optional<CorporateAction> derive(IrdsRow row, CorporateActionEvidence evidence,
                                             SecurityRef security) {
        return switch (row.form()) {
            case FORM_PAR_VALUE_CHANGE -> deriveParValueChange(row, evidence, security);
            case FORM_BONUS_ISSUE -> deriveBonusIssue(row, evidence, security);
            default -> {
                if (FORMS_WITHOUT_PRICE_EFFECT.contains(row.form())) {
                    yield Optional.empty();
                }
                // MUST NOT: 조용히 버리지 않는다. 버리면 "그런 사건이 없다"와 "우리가 그 이름을
                // 모른다"가 구분되지 않는다(dart-financial.md의 「계정 집합이 회사마다 다르다」 절의 이익잉여금 전례). 로그로만 두는 것도 같다 —
                // 판정하는 사람은 로그를 보지 않는다(ADR-0050).
                yield nameOnly(row, evidence, security);
            }
        };
    }

    /**
     * 종류도 비율도 못 내지만 <b>이름은 아는</b> 사건. 범위 밖 형태와 처음 보는 형태가 여기다.
     *
     * <p>「무상감자」가 실측 표본이다(아시아나항공 2021-01-15). ADR-0049가 다루는 것은
     * 액면분할·병합·무상증자 셋뿐이라 그 계수를 우리가 내지 않는데, <b>휴리스틱은 주식수 배수가
     * 1/3로 스냅돼 {@code REVERSE_SPLIT}으로 올린다.</b> 낱말이 원장에 없으면 사람은 그 행을
     * 깨끗한 병합으로 읽고 승인한다.
     *
     * <p>효력일은 가격이 뛴 날이 정본이다 — 그것이 효력일의 정의다. 못 찾으면 로그로만 남는다.
     * 지어내지 않는 이유는 {@code effective_date}가 원장의 축이라 틀린 날짜가 들어가면
     * <b>그 봉만 조용히 틀리기</b> 때문이다.
     *
     * <p>이 값은 {@link CorporateActionCollector}가 {@code record}가 아니라 <b>주석</b>으로
     * 보낸다 — 종류를 모르는 공시가 휴리스틱의 확정된 종류·비율을 밀어내면 안 되기 때문이다.
     */
    private Optional<CorporateAction> nameOnly(IrdsRow row, CorporateActionEvidence evidence,
                                               SecurityRef security) {
        Optional<LocalDate> gap = evidence.firstPriceGap(
                row.sourceDate().minusDays(PAR_CHANGE_LOOKBEHIND_DAYS),
                row.sourceDate().plusDays(PAR_CHANGE_LOOKAHEAD_DAYS))
                .or(() -> coupledShareChange(row, evidence, security));
        if (gap.isEmpty()) {
            log.warn("가격을 바꿨을 수 있는 형태인데 시계열에 불연속이 없다."
                            + " ticker={} {} 형태='{}' 수량={} — 상장 이전 사건이거나 창 밖이거나,"
                            + " 주식수는 그만큼 움직였는데 가격이 함께 움직이지 않은 것이다"
                            + "(유상증자·전환권행사가 그렇다).",
                    security.ticker(), row.sourceDate(), row.form(), row.quantity());
            return Optional.empty();
        }
        Optional<BigDecimal> observed = evidence.observedRatioOn(gap.get());
        if (observed.isEmpty()) {
            return Optional.empty();
        }
        log.warn("종류를 확정하지 못한 기업 행위를 이름만 적는다. ticker={} {} 형태='{}' 효력일={}"
                        + " — 수정계수는 사람이 종류와 비율을 정할 때까지 그 구간에서 틀린 채로 남는다.",
                security.ticker(), row.sourceDate(), row.form(), gap.get());
        return Optional.of(
                CorporateAction.named(gap.get(), row.sourceDate(), observed.get(), row.form()));
    }

    /**
     * 공시가 준 <b>수량</b>으로 효력일을 찾는다. 가격 불연속이 임계 안이라 안 잡힐 때의 둘째 그물이다.
     *
     * <h2>왜 필요한가</h2>
     * <p>{@code firstPriceGap}은 {@link PriceDiscontinuity}의 가격제한폭(0.70~1.30)에 걸린 날만
     * 찾는다. 그런데 <b>보정이 필요한 사건이 그 폭 안에서도 일어난다</b> — 현대지에프홀딩스
     * 2026-07-20이 주식수 ×1.176345 · 종가 ×0.8659(−13.4%)이라 폭 안이었고, 그래서 DART가
     * {@code 수량=27,492,898}로 정확히 알려준 사건이 <b>통째로 버려졌다.</b> 그 수량은 우리
     * {@code listed_shares} 증분과 자릿수까지 같다.
     *
     * <h2>MUST: 두 조건을 함께 건다</h2>
     * <p>수량 일치만으로 열면 <b>가격 보정이 없는 사건까지 전부 후보가 된다.</b> 실측:
     * 전량 실행이 버린 공시 <b>382건·93종목</b> 중 {@code 신주인수권행사}가 207건인데 그것은
     * 주식수만 늘고 가격은 안 움직인다. 그리고 그 부류는 주식수 변화가 평균 0.9%라
     * <b>가격 조건만 걸면 거저 통과한다</b>(73건 중 46건).
     *
     * <ul>
     *   <li>{@link #MIN_SHARE_CHANGE} — 기계적 가격 이동이 <b>하루 등락과 구별될 만큼</b>
     *       커야 한다. 5%면 기대 이동이 약 4.8%다.</li>
     *   <li>{@link #PRICE_MATCH_TOLERANCE} — 주식수 배수 × 가격 배수가 1에 가까워야 한다.
     *       순수 희석이면 이 곱이 주식수 배수만큼 벌어진다(실측 유상증자 9건 평균 1.3349).</li>
     * </ul>
     *
     * <p><b>둘을 함께 건 결과가 382건 → 3건이다</b>(2026-09-01 전수 실측):
     * {@code 005440} 2026-07-20(곱 1.0187) · {@code 012450} 2024-09-27(곱 0.9841) ·
     * {@code 066970} 2025-11-07(곱 1.0271). <b>임계는 이 3건 위에서 정해졌으므로 과적합을
     * 가정하고 다룬다</b> — 늘려 잡을 근거가 생기기 전에는 좁히지도 넓히지도 않는다.
     *
     * <p>MUST NOT: 여기서 종류나 비율을 정하지 않는다. 찾는 것은 <b>효력일 하나</b>이고,
     * 그 행은 호출부가 {@code UNKNOWN} + {@code OBSERVED}로 올려 사람의 판정을 기다린다.
     * 수량이 맞는다는 것은 "그날 그 일이 있었다"까지이지 "그것이 분할이다"가 아니다.
     */
    private Optional<LocalDate> coupledShareChange(IrdsRow row, CorporateActionEvidence evidence,
                                                   SecurityRef security) {
        Optional<CorporateActionEvidence.ShareIssue> change = evidence.shareChangeOf(
                row.quantity(),
                row.sourceDate().minusDays(PAR_CHANGE_LOOKBEHIND_DAYS),
                row.sourceDate().plusDays(PAR_CHANGE_LOOKAHEAD_DAYS));
        if (change.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal shareRatio = change.get().ratio();
        if (shareRatio.subtract(BigDecimal.ONE).abs().compareTo(MIN_SHARE_CHANGE) < 0) {
            return Optional.empty();
        }
        Optional<BigDecimal> priceMultiple = evidence.closeMultipleOn(change.get().listingDate());
        if (priceMultiple.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal product = shareRatio.multiply(priceMultiple.get());
        if (product.subtract(BigDecimal.ONE).abs().compareTo(PRICE_MATCH_TOLERANCE) > 0) {
            return Optional.empty();
        }
        log.warn("공시 수량으로 효력일을 찾았다. ticker={} {} 형태='{}' 수량={} 효력일={}"
                        + " 주식수배수={} 가격배수={} — 가격 불연속이 임계 안이라 첫 그물이 놓친 것이다.",
                security.ticker(), row.sourceDate(), row.form(), row.quantity(),
                change.get().listingDate(), shareRatio, priceMultiple.get());
        return Optional.of(change.get().listingDate());
    }

    /**
     * 액면분할·병합. <b>비율과 효력일을 {@code listed_shares}에서 낸다</b>(ADR-0049).
     *
     * <p><b>종류도 그 비율의 부호가 정한다.</b> DART의 낱말은 "액면이 바뀌는 사건이다"까지만
     * 말한다. 부호를 정하는 곳을 하나로 유지해야 두 번 뒤집혀 원래대로 돌아오는 일이 없다.
     */
    private Optional<CorporateAction> deriveParValueChange(IrdsRow row,
                                                           CorporateActionEvidence evidence,
                                                           SecurityRef security) {
        Optional<CorporateActionEvidence.ParValueChange> change = evidence.firstSnappedShareChange(
                row.sourceDate().minusDays(PAR_CHANGE_LOOKBEHIND_DAYS),
                row.sourceDate().plusDays(PAR_CHANGE_LOOKAHEAD_DAYS));
        if (change.isEmpty()) {
            log.warn("공시된 주식분할의 효력일을 시계열에서 찾지 못했다. ticker={} 원값={}"
                            + " — 상장 이전 사건이거나 주식수 배수가 단순 유리수가 아니다(자사주 소각이 겹치면 그렇다).",
                    security.ticker(), row.sourceDate());
            return Optional.empty();
        }
        BigDecimal ratio = change.get().ratio();
        CorporateAction.Type type = ratio.compareTo(BigDecimal.ONE) > 0
                ? CorporateAction.Type.SPLIT
                : CorporateAction.Type.REVERSE_SPLIT;

        // 액면가 교차확인. **게이트가 아니다** — 액면가가 상법상 표준 여섯 개로 제한되지
        // 않으므로 어긋난다고 버리면 멀쩡한 사건을 떨어뜨린다. 어긋남 자체는 볼 값이다.
        if (row.parValueAfter() != null && !isStandardParValue(row.parValueAfter().multiply(ratio))) {
            log.warn("주식수에서 낸 비율과 액면가가 어긋난다. ticker={} {} 비율={} 분할후액면가={}"
                            + " — 효력일을 잘못 집었거나 액면가가 표준값이 아니다.",
                    security.ticker(), change.get().effectiveDate(), ratio, row.parValueAfter());
        }

        // 액면분할은 권리락과 신주상장이 같은 날이다. 벌어지는 것은 무상증자다.
        return Optional.of(new CorporateAction(
                change.get().effectiveDate(), change.get().effectiveDate(), row.sourceDate(),
                type, ratio, CorporateAction.DetectionSource.SOURCE_DISCLOSURE, row.form()));
    }

    /**
     * 무상증자. <b>비율을 스냅하지 않는다.</b>
     *
     * <p>배수가 정확히 떨어지지 않지만 그 편차는 단수주가 아니라 <b>자사주 미배정</b>이라
     * 실체가 있다 — 씨젠 2021은 {@code 52,225,994 = 2 × 26,234,020 − 242,046}이고 그
     * 242,046이 자기주식이다. KRX 권리락 기준가가 총주식수 기준이므로 {@code 1.9907735833}이
     * 정본이고 {@code 2}가 아니다. 스냅을 걸면 실측의 상당수가 거부되어 계수가 영영 1로 남는다.
     */
    private Optional<CorporateAction> deriveBonusIssue(IrdsRow row,
                                                       CorporateActionEvidence evidence,
                                                       SecurityRef security) {
        Optional<CorporateActionEvidence.ShareIssue> issue = evidence.shareIncreaseOf(
                row.quantity(),
                row.sourceDate().minusDays(PAR_CHANGE_LOOKBEHIND_DAYS),
                row.sourceDate().plusDays(BONUS_ISSUE_LOOKAHEAD_DAYS));
        if (issue.isEmpty()) {
            log.warn("공시된 무상증자의 신주상장일을 시계열에서 찾지 못했다. ticker={} 원값={} 수량={}"
                            + " — 우선주 배정이거나 상장이 아직 안 됐거나 창 밖이다.",
                    security.ticker(), row.sourceDate(), row.quantity());
            return Optional.empty();
        }
        BigDecimal ratio = issue.get().ratio();
        if (ratio.compareTo(BigDecimal.ONE) <= 0) {
            log.warn("무상증자인데 주식수가 늘지 않았다. ticker={} 원값={} 비율={}",
                    security.ticker(), row.sourceDate(), ratio);
            return Optional.empty();
        }

        // 권리락은 소스 원값(신주배정기준일로 보인다) 직전 거래일이다 — 루닛 2023.11.10 →
        // 권리락 11-09. 표본이 하나뿐이라 가격으로 반드시 교차확인한다.
        Optional<LocalDate> exRightsDate = evidence.lastTradingDayBefore(row.sourceDate());
        if (exRightsDate.isEmpty()) {
            log.warn("무상증자의 권리락일을 시계열에서 찾지 못했다. ticker={} 원값={}",
                    security.ticker(), row.sourceDate());
            return Optional.empty();
        }
        Optional<BigDecimal> observed = evidence.closeMultipleOn(exRightsDate.get());
        if (observed.isEmpty() || !PriceDiscontinuity.explainsPriceGap(observed.get(), ratio)) {
            // MUST NOT: 교차확인이 실패했는데 적지 않는다. 일자 보정이 어긋났다는 뜻이고,
            // 그대로 내면 엉뚱한 날짜가 APPROVED로 들어가 그 봉만 조용히 틀린다.
            log.warn("무상증자의 권리락 추정일에 가격 불연속이 없다. ticker={} 원값={} 추정 권리락={}"
                            + " 비율={} 관측 종가배수={} — 소스 원값의 뜻이 표본(신주배정기준일)과 다르다.",
                    security.ticker(), row.sourceDate(), exRightsDate.get(), ratio,
                    observed.orElse(null));
            return Optional.empty();
        }

        return Optional.of(new CorporateAction(
                exRightsDate.get(), issue.get().listingDate(), row.sourceDate(),
                CorporateAction.Type.BONUS_ISSUE, ratio,
                CorporateAction.DetectionSource.SOURCE_DISCLOSURE, row.form()));
    }

    /** {@code BigDecimal.equals}는 스케일까지 보므로 {@code compareTo}로 견준다. */
    private static boolean isStandardParValue(BigDecimal value) {
        return STANDARD_PAR_VALUES.stream().anyMatch(standard -> standard.compareTo(value) == 0);
    }

    /** 보고서 하나를 받아 쓸 만한 행으로 바꾼다. 데이터가 없으면 빈 목록이다. */
    private List<IrdsRow> rowsOf(String corpCode, DartIrdsReports.Report report, SecurityRef security)
            throws DataSourceException {
        String context = "ticker=" + security.ticker() + " " + report.businessYear()
                + "/" + report.reportCode();
        if (!rateLimiter.hasDailyQuota()) {
            throw new DataSourceException(DataSourceException.Kind.RATE_LIMITED,
                    DartResponse.SOURCE_NAME,
                    "일일 호출 한도를 소진했다(사용 " + rateLimiter.usedToday() + "회). " + context);
        }
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DartResponse.SOURCE_NAME, "호출 대기 중 인터럽트됐다. " + context, e);
        }

        ResponseEntity<JsonNode> response;
        try {
            response = client.get().uri(uri -> uri
                            .path("/irdsSttus.json")
                            .queryParam("crtfc_key", apiKey)
                            .queryParam("corp_code", corpCode)
                            .queryParam("bsns_year", report.businessYear())
                            .queryParam("reprt_code", report.reportCode())
                            .build())
                    .retrieve()
                    .toEntity(JsonNode.class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DartResponse.SOURCE_NAME, "호출에 실패했다. " + context, e);
        }

        JsonNode list;
        try {
            list = DartResponse.requireList(response.getBody(), context);
        } catch (DataSourceException e) {
            if (e.kind() == DataSourceException.Kind.NOT_FOUND) {
                // 그 보고서에 이 표가 없다. 정상이므로 조용히 건너뛴다.
                return List.of();
            }
            throw e;
        }

        List<IrdsRow> rows = new ArrayList<>();
        int received = 0;
        for (JsonNode node : list) {
            received++;
            IrdsRow row = toRow(node, security);
            if (row != null) {
                rows.add(row);
            }
        }
        if (received > 0 && rows.isEmpty()) {
            // IMPORTANT — 회사가 표를 비워서 낼 수 있다. 삼성전자 2018은 status 000 + 모든
            // 필드가 '-'인 1행이라 오류로도 안 보이고, 행 수만 세면 "1건 있음"으로 읽힌다.
            // MUST NOT: 커버리지를 성립으로 읽지 않는다(phase 기준 §P9).
            log.warn("증자(감자) 현황에 쓸 수 있는 행이 하나도 없다. {} 받은행={}"
                            + " — 회사가 표를 비워서 냈을 수 있고, 그것은 '사건이 없다'와 다르다.",
                    context, received);
        }
        return rows;
    }

    /**
     * 행 하나를 읽는다. 우선주 배정이거나 일자를 못 읽으면 null이다.
     *
     * <p>{@code isu_dcrs_stock_knd}의 실제 값은 <b>미실측</b>이라 "우선"이 들어간 것만
     * 뺀다. 무상증자에서는 {@code shareIncreaseOf}의 정수 완전 일치가 2차 방어를 겸한다 —
     * 우선주 배정 수량은 보통주 상장주식수 증가분과 맞지 않는다.
     */
    private IrdsRow toRow(JsonNode node, SecurityRef security) {
        String kind = node.path("isu_dcrs_stock_knd").asString("").strip();
        if (kind.contains("우선")) {
            log.debug("우선주 행이라 건너뛴다. ticker={} 종류='{}'", security.ticker(), kind);
            return null;
        }
        LocalDate sourceDate = parseSourceDate(node.path("isu_dcrs_de").asString(""));
        if (sourceDate == null) {
            return null;
        }
        return new IrdsRow(sourceDate,
                node.path("isu_dcrs_stle").asString("").strip(),
                DartAccountMapper.parseAmount(node.path("isu_dcrs_qy").asString("")),
                DartAccountMapper.parseAmount(node.path("isu_dcrs_mstvdv_fval_amount").asString("")),
                kind);
    }

    /** {@code isu_dcrs_de}는 실측상 {@code 2021.04.14} 꼴이다. 8자리 숫자 표기도 함께 받는다. */
    private static LocalDate parseSourceDate(String raw) {
        String cleaned = raw == null ? "" : raw.strip().replace('.', '-');
        if (cleaned.isEmpty() || "-".equals(cleaned)) {
            return null;
        }
        try {
            if (cleaned.length() == 8 && cleaned.chars().allMatch(Character::isDigit)) {
                return LocalDate.parse(cleaned, COMPACT_DATE);
            }
            return LocalDate.parse(cleaned);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
