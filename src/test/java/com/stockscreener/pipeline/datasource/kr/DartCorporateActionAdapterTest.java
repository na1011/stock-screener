package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.StoredPriceSeries;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 낱말 하나와 우리 시계열에서 <b>세 날짜와 비율</b>이 나오는지 검증한다.
 *
 * <p>이 어댑터에서만 갈리는 것이 셋이다 — <b>비율이 소스가 아니라 우리 주식수에서 나오고</b>,
 * <b>일자 보정의 방향이 사건 종류마다 반대이며</b>, <b>무상증자에는 스냅을 걸면 안 된다.</b>
 * 셋 다 틀려도 예외가 나지 않고 값이 그럴듯하다.
 *
 * <p>응답 픽스처와 시세는 {@code docs/api-spec/dart-corporate-action.md}와
 * {@code price_daily} 실측이다. 조회 창을 <b>과거로 잡은 것은 의도적이다</b> —
 * {@link DartIrdsReports}가 제출일을 보므로, 최근 창으로 잡으면 이 테스트가 달력에 따라
 * 초록·빨강을 오간다.
 */
class DartCorporateActionAdapterTest {

    private static final SecurityRef KAKAO = new SecurityRef(1L, "KOSPI", "035720");
    private static final SecurityRef LUNIT = new SecurityRef(2L, "KOSDAQ", "328130");
    private static final SecurityRef SEEGENE = new SecurityRef(3L, "KOSDAQ", "096530");

    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("액면분할의 비율과 효력일이 소스가 아니라 상장주식수에서 나온다")
    void derivesSplitRatioAndDateFromListedShares() throws Exception {
        // 카카오 2021 — 소스 원값은 04.14인데 매매개시는 04-15다. 원값을 그대로 쓰면
        // 그 하루 봉만 잘못 조정되고 차트를 직접 보기 전까지 드러나지 않는다.
        respondWith(row("2021.04.14", "주식분할", "355,047,444", "100"));

        List<CorporateAction> actions = adapterWith(kakaoSeries())
                .fetchCorporateActions(KAKAO,
                        LocalDate.parse("2021-04-01"), LocalDate.parse("2021-04-30")).actions();

        assertThat(actions).singleElement().satisfies(action -> {
            assertThat(action.type()).isEqualTo(CorporateAction.Type.SPLIT);
            assertThat(action.ratio()).isEqualByComparingTo("5");
            assertThat(action.effectiveDate()).isEqualTo(LocalDate.parse("2021-04-15"));
            assertThat(action.listingDate())
                    .as("액면분할은 권리락과 신주상장이 같은 날이다")
                    .isEqualTo(LocalDate.parse("2021-04-15"));
            assertThat(action.sourceDate())
                    .as("원값을 남겨야 보정이 틀렸을 때 소스 탓인지 우리 탓인지 가른다")
                    .isEqualTo(LocalDate.parse("2021-04-14"));
            assertThat(action.source()).isEqualTo(CorporateAction.DetectionSource.SOURCE_DISCLOSURE);
        });
    }

    @Test
    @DisplayName("[합성 입력] 주식수가 줄면 REVERSE_SPLIT이 나온다 — 부호를 정하는 곳이 하나임을 잰다")
    void shrinkingShareCountYieldsAReverseSplit() throws Exception {
        // IMPORTANT — 이 응답은 **소스가 낼 수 없는 입력이다.** 액면병합은 자본금을 바꾸지
        // 않아 「증자(감자) 현황」에 실릴 자리가 없고, 대한전선 2023은 실제로 행이 없다
        // (DartCorporateActionWordProbeLiveTest). 그런데도 여기 두는 이유는 **분할만으로는
        // 부호가 검증되지 않기** 때문이다(ADR-0049). 실제 병합은 휴리스틱 경로가 올린다.
        respondWith(row("2023.05.15", "주식분할", "1,120,025,709", "1000"));

        List<CorporateAction> actions = adapterWith(daehanSeries())
                .fetchCorporateActions(new SecurityRef(4L, "KOSPI", "001440"),
                        LocalDate.parse("2023-05-01"), LocalDate.parse("2023-05-31")).actions();

        assertThat(actions).singleElement().satisfies(action -> {
            assertThat(action.type()).isEqualTo(CorporateAction.Type.REVERSE_SPLIT);
            assertThat(action.ratio())
                    .as("종가 배수의 역수 0.09407이 아니라 주식수 배수 0.1이다 — 6% 차이다")
                    .isEqualByComparingTo("0.1");
            assertThat(action.effectiveDate()).isEqualTo(LocalDate.parse("2023-05-16"));
        });
    }

    @Test
    @DisplayName("무상증자는 세 날짜가 갈리고 상장일 직전 주식수가 분모다")
    void bonusIssueHasThreeDistinctDates() throws Exception {
        // 루닛 2023 — 권리락 11-09, 원값 11-10(신주배정기준일로 보인다), 신주상장 12-01.
        respondWith(row("2023.11.10", "무상증자", "14,238,154", "500"));

        List<CorporateAction> actions = adapterWith(lunitSeries())
                .fetchCorporateActions(LUNIT,
                        LocalDate.parse("2023-11-01"), LocalDate.parse("2023-11-30")).actions();

        assertThat(actions).singleElement().satisfies(action -> {
            assertThat(action.type()).isEqualTo(CorporateAction.Type.BONUS_ISSUE);
            assertThat(action.effectiveDate())
                    .as("원값을 그대로 쓰면 권리락 당일 봉이 조정 대상에 들어가 한 번 더 나뉜다")
                    .isEqualTo(LocalDate.parse("2023-11-09"));
            assertThat(action.listingDate()).isEqualTo(LocalDate.parse("2023-12-01"));
            assertThat(action.sourceDate()).isEqualTo(LocalDate.parse("2023-11-10"));
            assertThat(action.ratio())
                    .as("분모가 권리락일 주식수(12,381,004)면 2.15가 나온다")
                    .isEqualByComparingTo("2");
        });
    }

    @Test
    @DisplayName("자사주 때문에 안 떨어지는 무상증자 비율을 반올림하지 않는다")
    void doesNotSnapTheBonusIssueRatio() throws Exception {
        // 씨젠 2021 — 52,225,994 = 2 × 26,234,020 − 242,046이고 그 242,046이 자기주식이다.
        // 2로 스냅하면 그럴듯하지만 KRX 권리락 기준가는 총주식수 기준이라 틀린 값이 된다.
        respondWith(row("2021.04.26", "무상증자", "25,991,974", "500"));

        List<CorporateAction> actions = adapterWith(seegeneSeries())
                .fetchCorporateActions(SEEGENE,
                        LocalDate.parse("2021-04-01"), LocalDate.parse("2021-05-31")).actions();

        assertThat(actions).singleElement()
                .extracting(CorporateAction::ratio, BIG_DECIMAL)
                .isEqualByComparingTo("1.9907735833");
    }

    @Test
    @DisplayName("겹치는 보고서가 같은 행을 여러 번 줘도 사건은 하나다")
    void mergesRowsRepeatedAcrossOverlappingReports() throws Exception {
        respondWith(row("2021.04.14", "주식분할", "355,047,444", "100"));

        List<CorporateAction> actions = adapterWith(kakaoSeries())
                .fetchCorporateActions(KAKAO,
                        LocalDate.parse("2020-01-01"), LocalDate.parse("2026-08-12")).actions();

        assertThat(server.getRequestCount())
                .as("사업보고서 둘의 이력은 4년이 겹친다")
                .isGreaterThan(1);
        assertThat(actions).hasSize(1);
    }

    @Test
    @DisplayName("조회 창 밖에 효력일이 떨어지면 내지 않는다 — 1콜이 5년 이력을 준다")
    void filtersByEffectiveDate() throws Exception {
        respondWith(row("2021.04.14", "주식분할", "355,047,444", "100"));

        assertThat(adapterWith(kakaoSeries()).fetchCorporateActions(KAKAO,
                LocalDate.parse("2021-01-01"), LocalDate.parse("2021-03-31")).actions()).isEmpty();
    }

    @Test
    @DisplayName("우선주 배정 행은 쓰지 않는다 — 보통주 상장주식수와 짝이 맞지 않는다")
    void skipsPreferredStockRows() throws Exception {
        respondWith("""
                {"status":"000","message":"정상","list":[
                  {"isu_dcrs_de":"2021.04.14","isu_dcrs_stle":"주식분할",
                   "isu_dcrs_stock_knd":"우선주","isu_dcrs_qy":"355,047,444",
                   "isu_dcrs_mstvdv_fval_amount":"100"}]}
                """);

        assertThat(adapterWith(kakaoSeries()).fetchCorporateActions(KAKAO,
                LocalDate.parse("2021-04-01"), LocalDate.parse("2021-04-30")).actions()).isEmpty();
    }

    @Test
    @DisplayName("전 필드가 '-'인 응답은 사건 없음과 같은 빈 결과다 — 로그만이 둘을 가른다")
    void treatsBlankTableAsNoUsableRow() throws Exception {
        // 삼성전자 2018이 그 모양이다. status 000이라 오류로도 안 보이고 행 수만 세면
        // "1건 있음"으로 읽힌다(phase 기준: 커버리지를 성립으로 읽지 않는다).
        respondWith("""
                {"status":"000","message":"정상","list":[
                  {"rcept_no":"-","isu_dcrs_de":"-","isu_dcrs_stle":"-",
                   "isu_dcrs_stock_knd":"-","isu_dcrs_qy":"-",
                   "isu_dcrs_mstvdv_fval_amount":"-","isu_dcrs_mstvdv_amount":"-"}]}
                """);

        assertThat(adapterWith(kakaoSeries()).fetchCorporateActions(KAKAO,
                LocalDate.parse("2021-04-01"), LocalDate.parse("2021-04-30")).actions()).isEmpty();
    }

    @Test
    @DisplayName("IMPORTANT — 범위 밖 낱말과 모르는 낱말도 이름은 남긴다. 로그로만 두면 판정하는 사람이 못 본다")
    void outOfScopeAndUnknownFormsKeepTheirName() throws Exception {
        // 아시아나항공 2021-01-15이 실측 표본이다. 낱말이 원장에 없으면 휴리스틱이 올린
        // REVERSE_SPLIT 1/3을 사람이 깨끗한 병합으로 읽고 승인한다 — ADR-0049가 범위 밖으로
        // 둔 사건에 계수가 들어간다.
        for (String form : List.of("무상감자", "처음보는형태")) {
            respondWith(row("2021.04.14", form, "355,047,444", "100"));

            assertThat(adapterWith(kakaoSeries()).fetchCorporateActions(KAKAO,
                    LocalDate.parse("2021-04-01"), LocalDate.parse("2021-04-30")).actions())
                    .singleElement()
                    .satisfies(action -> {
                        assertThat(action.sourceForm()).isEqualTo(form);
                        // 종류도 비율도 모른다. UNKNOWN이라 findApplicable이 제외한다.
                        assertThat(action.type()).isEqualTo(CorporateAction.Type.UNKNOWN);
                        // 효력일은 가격이 뛴 날이다 — 그것이 효력일의 정의다.
                        assertThat(action.effectiveDate()).isEqualTo(LocalDate.parse("2021-04-15"));
                        // 관측값은 계수와 같은 방향이다(558,000 / 120,500).
                        assertThat(action.ratio()).isEqualByComparingTo("4.6307053942");
                    });
        }
    }

    @Test
    @DisplayName("이름만 아는 사건도 불연속이 없으면 지어내지 않는다 — 그 사각지대는 로그로 남는다")
    void nameOnlyEventsStillNeedAPriceGap() throws Exception {
        respondWith(row("2021.04.14", "무상감자", "355,047,444", "100"));

        StoredPriceSeries flat = (stockId, from, to) -> List.of(
                bar("2021-04-13", "550000", 88_746_712L),
                bar("2021-04-14", "548000", 88_746_712L),
                bar("2021-04-15", "552000", 88_746_712L));

        assertThat(adapterWith(flat).fetchCorporateActions(KAKAO,
                LocalDate.parse("2021-04-01"), LocalDate.parse("2021-04-30")).actions()).isEmpty();
    }

    @Test
    @DisplayName("유도에 성공한 사건도 소스의 낱말을 함께 싣는다")
    void derivedActionsCarryTheSourceWord() throws Exception {
        respondWith(row("2021.04.14", "주식분할", "355,047,444", "100"));

        assertThat(adapterWith(kakaoSeries()).fetchCorporateActions(KAKAO,
                LocalDate.parse("2021-04-01"), LocalDate.parse("2021-04-30")).actions())
                .singleElement()
                .satisfies(action -> assertThat(action.sourceForm()).isEqualTo("주식분할"));
    }

    @Test
    @DisplayName("효력일을 시계열에서 못 찾으면 지어내지 않는다 — 원값을 효력일로 쓰지 않는다")
    void neverInventsAnEffectiveDate() throws Exception {
        respondWith(row("2021.04.14", "주식분할", "355,047,444", "100"));

        StoredPriceSeries flat = (stockId, from, to) -> List.of(
                bar("2021-04-13", "550000", 88_746_712L),
                bar("2021-04-14", "548000", 88_746_712L),
                bar("2021-04-15", "552000", 88_746_712L));

        assertThat(adapterWith(flat).fetchCorporateActions(KAKAO,
                LocalDate.parse("2021-04-01"), LocalDate.parse("2021-04-30")).actions()).isEmpty();
    }

    @Test
    @DisplayName("추정 권리락일에 가격 불연속이 없으면 무상증자를 적지 않는다")
    void refusesBonusIssueWhenThePriceGapIsMissing() throws Exception {
        respondWith(row("2023.11.10", "무상증자", "14,238,154", "500"));

        // 상장일은 찾히지만 권리락 추정일(11-09)의 종가가 직전과 거의 같다 — 일자 보정이
        // 어긋났다는 뜻이고, 그대로 내면 엉뚱한 날짜가 APPROVED로 들어간다.
        StoredPriceSeries noGap = (stockId, from, to) -> List.of(
                bar("2023-11-08", "170500", 12_381_004L),
                bar("2023-11-09", "169000", 12_381_004L),
                bar("2023-11-30", "99800", 14_238_154L),
                bar("2023-12-01", "96300", 28_476_308L));

        assertThat(adapterWith(noGap).fetchCorporateActions(LUNIT,
                LocalDate.parse("2023-11-01"), LocalDate.parse("2023-11-30")).actions()).isEmpty();
    }

    @Test
    @DisplayName("데이터 없음(013)은 빈 결과다 — 그 보고서에 이 표가 없는 것이 정상이다")
    void treatsNoDataAsEmpty() throws Exception {
        respondWith("{\"status\":\"013\",\"message\":\"조회된 데이터가 없습니다.\"}");

        assertThat(adapterWith(kakaoSeries()).fetchCorporateActions(KAKAO,
                LocalDate.parse("2021-04-01"), LocalDate.parse("2021-04-30")).actions()).isEmpty();
    }

    @Test
    @DisplayName("잘못된 키는 AUTH다 — DART는 인증 실패도 HTTP 200으로 준다")
    void mapsInvalidKeyToAuth() {
        respondWith("{\"status\":\"010\",\"message\":\"등록되지 않은 키입니다.\"}");
        DartCorporateActionAdapter adapter = adapterWith(kakaoSeries());

        assertThatThrownBy(() -> adapter.fetchCorporateActions(KAKAO,
                LocalDate.parse("2021-04-01"), LocalDate.parse("2021-04-30")).actions())
                .isInstanceOf(DataSourceException.class)
                .satisfies(e -> assertThat(((DataSourceException) e).kind())
                        .isEqualTo(DataSourceException.Kind.AUTH));
    }

    @Test
    @DisplayName("DART가 모르는 종목코드는 호출조차 하지 않는다")
    void skipsUnknownTickerWithoutSpendingQuota() throws Exception {
        DartCorporateActionAdapter adapter =
                adapterWith(kakaoSeries(), new StubCorpCodeCache(Optional.empty()));

        assertThat(adapter.fetchCorporateActions(new SecurityRef(9L, "KOSPI", "999999"),
                LocalDate.parse("2021-04-01"), LocalDate.parse("2021-04-30")).actions()).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("소스가 아직 안 담은 최근 창은 호출조차 하지 않는다 — 실패가 아니라 지연이다")
    void doesNotCallWhenNoReportCoversTheWindow() throws Exception {
        respondWith(row("2021.04.14", "주식분할", "355,047,444", "100"));
        LocalDate today = LocalDate.now();

        assertThat(adapterWith(kakaoSeries()).fetchCorporateActions(KAKAO,
                today.minusDays(10), today).actions()).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    // --- 픽스처 ------------------------------------------------------------

    private static final org.assertj.core.api.InstanceOfAssertFactory<BigDecimal,
            org.assertj.core.api.AbstractBigDecimalAssert<?>> BIG_DECIMAL =
            org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL;

    private static String row(String date, String form, String quantity, String parValue) {
        return """
                {"status":"000","message":"정상","list":[
                  {"rcept_no":"20220000000000","corp_code":"00258801","corp_name":"테스트",
                   "isu_dcrs_de":"%s","isu_dcrs_stle":"%s","isu_dcrs_stock_knd":"보통주",
                   "isu_dcrs_qy":"%s","isu_dcrs_mstvdv_fval_amount":"%s",
                   "isu_dcrs_mstvdv_amount":"-","stlm_dt":"2021-12-31"}]}
                """.formatted(date, form, quantity, parValue);
    }

    /** 카카오 2021 — 분할 엿새 전 04-09에 ×1.00017 잔변동이 있다. */
    private static StoredPriceSeries kakaoSeries() {
        return window(List.of(
                bar("2021-04-08", "548000", 88_746_712L),
                bar("2021-04-09", "558000", 88_761_861L),
                bar("2021-04-15", "120500", 443_809_305L),
                bar("2021-04-16", "119000", 443_809_305L)));
    }

    /** 대한전선 2023 — 병합 전 11거래일(달력 19일)이 비어 있다. */
    private static StoredPriceSeries daehanSeries() {
        return window(List.of(
                bar("2023-04-27", "1492", 1_244_473_009L),
                bar("2023-05-16", "15860", 124_447_300L),
                bar("2023-05-17", "15180", 124_447_300L)));
    }

    /** 루닛 2023 — 권리락과 신주상장 사이 11-24에 별개 증가가 낀다. */
    private static StoredPriceSeries lunitSeries() {
        return window(List.of(
                bar("2023-11-08", "170500", 12_381_004L),
                bar("2023-11-09", "90200", 12_381_004L),
                bar("2023-11-10", "96600", 12_381_004L),
                bar("2023-11-24", "93100", 14_238_154L),
                bar("2023-11-30", "99800", 14_238_154L),
                bar("2023-12-01", "96300", 28_476_308L)));
    }

    /** 씨젠 2021 — 상장 배수가 2가 아니라 1.9907735833이다. */
    private static StoredPriceSeries seegeneSeries() {
        return window(List.of(
                bar("2021-04-22", "195000", 26_234_020L),
                bar("2021-04-23", "107500", 26_234_020L),
                bar("2021-05-18", "80100", 26_234_020L),
                bar("2021-05-20", "75300", 52_225_994L)));
    }

    /**
     * 창을 <b>실제로 거르는</b> 스텁. 전량을 돌려주면 어댑터가 읽기 창을 좁게 잡아도
     * 테스트가 통과해, 실환경에서만 유도가 조용히 실패한다.
     */
    private static StoredPriceSeries window(List<StoredPriceSeries.Bar> bars) {
        return (stockId, from, to) -> bars.stream()
                .filter(bar -> !bar.tradeDate().isBefore(from) && !bar.tradeDate().isAfter(to))
                .toList();
    }

    private static StoredPriceSeries.Bar bar(String date, String close, long listedShares) {
        return new StoredPriceSeries.Bar(LocalDate.parse(date), new BigDecimal(close),
                BigDecimal.valueOf(listedShares));
    }

    private void respondWith(String body) {
        server.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json;charset=UTF-8")
                        .setBody(body);
            }
        });
    }

    private DartCorporateActionAdapter adapterWith(StoredPriceSeries series) {
        return adapterWith(series, new StubCorpCodeCache(Optional.of("00258801")));
    }

    private DartCorporateActionAdapter adapterWith(StoredPriceSeries series,
                                                   DartCorpCodeCache corpCodes) {
        DataSourceProperties properties = new DataSourceProperties();
        DataSourceProperties.Source source = properties.getDart();
        source.setBaseUrl(server.url("/").toString());
        source.setApiKey("test-key");
        return new DartCorporateActionAdapter(
                new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("dart", 0, 0), corpCodes, series);
    }

    /** corp_code 조회를 고정한다. 이 테스트의 관심사는 유도다. */
    private static final class StubCorpCodeCache extends DartCorpCodeCache {
        private final Optional<String> corpCode;

        StubCorpCodeCache(Optional<String> corpCode) {
            super(new ExternalApiClientFactory(), new DataSourceProperties(),
                    new TokenBucketRateLimiter("dart", 0, 0));
            this.corpCode = corpCode;
        }

        @Override
        public Optional<String> findCorpCode(String stockCode) {
            return corpCode;
        }
    }
}
