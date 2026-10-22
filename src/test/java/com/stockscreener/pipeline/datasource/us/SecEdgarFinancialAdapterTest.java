package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FinancialColumnAbsence;
import com.stockscreener.pipeline.datasource.FinancialFetch;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link SecEdgarFinancialAdapter}의 재구성 규칙을 잠근다.
 *
 * <p>MockWebServer를 쓰는 이유는 실제 소켓을 열어 {@code RestClient}를 통과시키기 위해서다.
 * {@code RestClient}를 목킹하면 <b>봉투 판별과 상태코드 처리가 전혀 검증되지 않는다.</b>
 *
 * <p>픽스처는 손으로 만든 것이고 <b>{@link SecEdgarProbeLiveTest}가 실측한 모양</b>을 담았다 —
 * 같은 종료일의 여러 기간 길이 · 값 그대로의 재보고 · 보고서마다 갈리는 후보 태그 ·
 * 3자가 아닌 단위 키 · 기말보다 뒤인 표지 주식수 · 정기보고서가 아닌 {@code form}.
 * 그 하나하나가 <b>없으면 에러 없이 틀리는</b> 자리다.
 */
class SecEdgarFinancialAdapterTest {

    private static final SecurityRef FIX = new SecurityRef(1L, "US", "FIX");
    private static final int FROM = 2020;

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
    @DisplayName("회계연도는 보고서에서, 기간 구분은 길이에서 온다 — 같은 종료일의 누적은 라벨이 안 된다")
    void labelsPeriodsByDurationLength() throws Exception {
        List<RawFinancialReport> reports = fetch();

        assertThat(reports).extracting(RawFinancialReport::fiscalPeriod)
                .as("9개월 누적이 Q3와 같은 종료일이지만 라벨이 되면 안 된다")
                // 4분기는 **FY 판마다** 하나씩 나온다(ADR-0151). 이 픽스처의 FY는 500과 505
                // 두 판이라 4분기도 둘이다 — 2024-05 시점 봉에서 505판은 아직 없으므로,
                // 판을 하나로 접으면 그때 알 수 있던 170이 화면에서 사라진다(ADR-0128).
                .containsExactlyInAnyOrder("Q1", "Q2", "Q3", "Q4", "Q4", "FY", "FY");
        assertThat(reports).allSatisfy(r ->
                assertThat(r.fiscalYear()).as("라벨의 회계연도는 보고서가 준다").isEqualTo(2023));

        RawFinancialReport q3 = of(reports, "Q3");
        assertThat(q3.periodStart()).isEqualTo(LocalDate.of(2023, 7, 1));
        assertThat(q3.periodEnd()).isEqualTo(LocalDate.of(2023, 9, 30));
        assertThat(q3.revenue()).as("3개월 값이어야 한다 — 9개월 누적 330이 아니다")
                .isEqualByComparingTo(new BigDecimal("120"));
    }

    @Test
    @DisplayName("끊겨 JSON으로 안 읽히는 companyfacts는 같은 실행 안에서 다시 받는다 — 한 번 끊겼다고 다음 FR까지 비우지 않는다")
    void truncatedCompanyFactsIsRetriedWithinTheRun() throws Exception {
        String full = fixture("companyfacts.json");
        enqueue(200, fixture("tickers.json"));
        enqueue(200, full.substring(0, full.length() / 2));
        enqueue(200, full);

        List<RawFinancialReport> reports = adapter().fetchReports(FIX, FROM).reports();

        assertThat(reports).isNotEmpty();
        assertThat(server.getRequestCount()).as("매핑 1 + 끊긴 companyfacts 1 + 다시 받은 1").isEqualTo(3);
    }

    @Test
    @DisplayName("companyfacts 5xx는 같은 실행 안에서 다시 받고, 404는 다시 묻지 않는다")
    void companyFactsRetriesServerErrorsButNotMissing() throws Exception {
        enqueue(200, fixture("tickers.json"));
        enqueue(503, "{}");
        enqueue(200, fixture("companyfacts.json"));
        assertThat(adapter().fetchReports(FIX, FROM).reports()).isNotEmpty();

        server.shutdown();
        server = new MockWebServer();
        server.start();
        enqueue(200, fixture("tickers.json"));
        enqueue(404, "<Error><Code>NoSuchKey</Code></Error>");
        enqueue(200, fixture("companyfacts.json"));
        assertThatThrownBy(() -> adapter().fetchReports(FIX, FROM))
                .isInstanceOfSatisfying(DataSourceException.class,
                        e -> assertThat(e.kind()).isEqualTo(DataSourceException.Kind.NOT_FOUND));
        assertThat(server.getRequestCount()).as("매핑 1 + 404 1 — 다시 안 묻는다").isEqualTo(2);
    }

    @Test
    @DisplayName("끝까지 JSON으로 안 읽히는 companyfacts는 받기 실패(TRANSIENT)다 — 소스가 답한 것이 아니다(ADR-0174)")
    void companyFactsStillUnreadableAfterRetriesIsTransient() throws Exception {
        String full = fixture("companyfacts.json");
        enqueue(200, fixture("tickers.json"));
        for (int i = 0; i <= SecFilingClient.TRANSIENT_RETRIES; i++) {
            enqueue(200, full.substring(0, full.length() / 2));
        }

        assertThatThrownBy(() -> adapter().fetchReports(FIX, FROM))
                .isInstanceOfSatisfying(DataSourceException.class,
                        e -> assertThat(e.kind()).isEqualTo(DataSourceException.Kind.TRANSIENT));
        assertThat(server.getRequestCount()).isEqualTo(2 + SecFilingClient.TRANSIENT_RETRIES);
    }

    @Test
    @DisplayName("companyfacts 429는 다시 묻지 않는다 — 한 콜로 RATE_LIMITED")
    void companyFactsDoesNotRetryRateLimit() throws Exception {
        enqueue(200, fixture("tickers.json"));
        enqueue(429, "<Error>slow down</Error>");
        enqueue(200, fixture("companyfacts.json"));

        assertThatThrownBy(() -> adapter().fetchReports(FIX, FROM))
                .isInstanceOfSatisfying(DataSourceException.class,
                        e -> assertThat(e.kind()).isEqualTo(DataSourceException.Kind.RATE_LIMITED));
        assertThat(server.getRequestCount()).as("매핑 1 + 429 1 — 다시 안 묻는다(v11 반증: 다시 묻게 바꾼 변이에 시험이 다 초록이었다)")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("공백만 든 companyfacts 2xx도 본문이 없는 것이다 — 같은 실행 안에서 다시 받는다")
    void blankCompanyFactsIsRetriedLikeAnEmptyOne() throws Exception {
        enqueue(200, fixture("tickers.json"));
        enqueue(200, "   ");
        enqueue(200, fixture("companyfacts.json"));

        assertThat(adapter().fetchReports(FIX, FROM).reports()).isNotEmpty();
        assertThat(server.getRequestCount()).as("매핑 1 + 공백 1 + 다시 1").isEqualTo(3);
    }

    @Test
    @DisplayName("값이 그대로인 재보고는 정정이 아니다 — 접지 않으면 지표가 보존 창째 지워진다")
    void foldsUnchangedRestatements() throws Exception {
        List<RawFinancialReport> reports = fetch();

        assertThat(reports).filteredOn(r -> "Q1".equals(r.fiscalPeriod()))
                .as("Q1을 뒤 보고서가 값 그대로 다시 실었다. 판은 하나여야 한다")
                .hasSize(1);
        assertThat(of(reports, "Q1").publishedAt())
                .as("접을 때 남는 것은 그 기간을 자기 기간으로 낸 보고서다")
                .isEqualTo(LocalDate.of(2023, 4, 20));
    }

    @Test
    @DisplayName("값이 달라진 재보고는 정정이다 — 공시일 오름차순으로 낸다")
    void keepsRealRestatements() throws Exception {
        List<RawFinancialReport> annual = fetch().stream()
                .filter(r -> "FY".equals(r.fiscalPeriod())).toList();

        assertThat(annual)
                .as("정정 둘 중 값이 달라진 하나만 판이 된다 — 값이 같은 재보고는 접힌다")
                .hasSize(2);
        assertThat(annual).extracting(RawFinancialReport::publishedAt)
                .as("MUST: 오름차순이다. 뒤집으면 FinancialWriter가 더 이른 판을 버려 원본이 사라진다")
                .isSorted();
        assertThat(annual.get(0).revenue()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(annual.get(1).revenue()).isEqualByComparingTo(new BigDecimal("505"));
        assertThat(annual.get(0).reportCode()).as("정정 추적의 근거").startsWith("10-K:");
        assertThat(annual.get(1).reportCode()).startsWith("10-K/A:");
    }

    @Test
    @DisplayName("보고서마다 후보 태그가 갈려도 정정이 아니다 — 값이 아니라 태그가 바뀐 것이다")
    void doesNotTreatTagSwitchAsRestatement() throws Exception {
        List<RawFinancialReport> annual = fetch().stream()
                .filter(r -> "FY".equals(r.fiscalPeriod())).toList();

        // 픽스처의 첫 정정(10-K/A, 2024-06-01)은 매출이 그대로이고 현금만 다른 후보 태그로
        // 낸다(300 → 355). 기간마다 태그를 고정하지 않으면 그 차이가 정정으로 읽혀 판이
        // 셋이 된다. 고정하면 그 보고서는 접히고, 값이 실제로 바뀐 정정만 남는다.
        assertThat(annual).extracting(RawFinancialReport::publishedAt)
                .as("2024-06-01 판이 남으면 태그 교체를 정정으로 읽은 것이다")
                .containsExactly(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 9, 1));
        assertThat(annual).extracting(RawFinancialReport::cashAndEquivalents)
                .as("두 판 모두 우선순위가 높은 태그의 값이다")
                .containsOnly(new BigDecimal("300"));
    }

    @Test
    @DisplayName("표지 주식수는 기말보다 뒤여도 받고, 그것이 앵커를 밀지 않는다")
    void takesCoverPageSharesWithoutMovingTheAnchor() throws Exception {
        List<RawFinancialReport> reports = fetch();

        assertThat(reports).as("주식수가 앵커를 밀면 보고서가 한 건도 안 나온다").isNotEmpty();
        assertThat(of(reports, "Q1").sharesOutstanding())
                .as("PER·PBR의 분모다 — 종료일로 거르면 미국에서 영영 빈다")
                .isEqualByComparingTo(new BigDecimal("900"));
        assertThat(of(reports, "Q1").periodEnd())
                .as("기간은 손익이 정한다. 표지 날짜가 아니다")
                .isEqualTo(LocalDate.of(2023, 3, 31));
    }

    @Test
    @DisplayName("통화 때문에 안 담은 판의 제출일은 회수에서 지킨다 — BABA 꼴(원판 CNY+USD 편의환산 · 이듬해 비교열 CNY만)")
    void currencySkippedVintageIsProtectedFromRetraction() throws Exception {
        enqueue(200, fixture("tickers.json"));
        enqueue(200, fixture("companyfacts-convenience-translation.json"));

        // 실측(2026-10-19 후보 소비 FR): 이듬해 20-F 비교열의 CNY 정정판이 어댑터 출력에 없어 회수가 지웠다 —
        // 48 발행사 219행. 그 판은 원장이 주는데 우리가 통화로 거른 것이다.
        FinancialFetch fetch = adapter().fetchReports(FIX, FROM);

        assertThat(fetch.reports()).extracting(RawFinancialReport::fiscalPeriod, RawFinancialReport::publishedAt,
                        RawFinancialReport::currencyCode)
                .as("담는 판은 그대로다 — 이 고침은 출력을 안 바꾼다")
                .containsExactly(
                        tuple("FY", LocalDate.of(2024, 5, 23), "USD"),
                        tuple("FY", LocalDate.of(2025, 6, 26), "USD"));
        assertThat(fetch.completeVintageHistory()).isTrue();
        assertThat(fetch.withheldVintages())
                .as("FY2024를 CNY로만 다시 실은 2025-06-26 판과 그 판에서 유도된 Q4 — 없으면 저장된 CNY 정정판이 회수된다")
                .containsExactlyInAnyOrder(
                        new FinancialFetch.WithheldVintage(2024, "FY", LocalDate.of(2025, 6, 26)),
                        new FinancialFetch.WithheldVintage(2024, "Q4", LocalDate.of(2025, 6, 26)));
        assertThat(fetch.unconfirmedVintages())
                .as("날짜로 지키지 않는다 — 같은 날짜의 FY2025 판은 담겼고 다른 라벨의 회수를 막으면 안 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("3자가 아닌 단위 키는 금액도 통화도 되지 않는다")
    void ignoresNonCurrencyUnits() throws Exception {
        List<RawFinancialReport> reports = fetch();

        assertThat(reports).allSatisfy(r -> assertThat(r.currencyCode())
                .as("character(3) 컬럼이다. 넘치면 그 종목의 적재가 통째로 롤백된다")
                .isEqualTo("USD"));
        assertThat(fetchAnnualLatest(reports).revenue())
                .as("segment 단위의 5가 매출에 더해지면 안 된다")
                .isEqualByComparingTo(new BigDecimal("505"));
    }

    @Test
    @DisplayName("Q4를 유도한다 — 손익만 차감하고 잔액은 기말 값을 그대로 쓴다")
    void derivesFourthQuarter() throws Exception {
        RawFinancialReport q4 = latestOf(fetch(), "Q4");

        assertThat(q4.revenue()).as("정정된 연간에서 뺀다: 505 - (100+110+120)")
                .isEqualByComparingTo(new BigDecimal("175"));
        assertThat(q4.netIncome()).as("50 - (10+11+12)")
                .isEqualByComparingTo(new BigDecimal("17"));
        assertThat(q4.totalAssets()).as("잔액은 차감하지 않는다 — 차감하면 자산총계가 음수가 된다")
                .isEqualByComparingTo(new BigDecimal("1100"));
        assertThat(q4.operatingCashFlow())
                .as("현금흐름은 연간만 담는다 — Q4에도 담지 않는다").isNull();
    }

    @Test
    @DisplayName("현금흐름은 연간 행에만 담는다 — 분기에 담으면 기간이 다른 두 수를 뺀다")
    void keepsCashFlowAnnualOnly() throws Exception {
        List<RawFinancialReport> reports = fetch();

        assertThat(of(reports, "Q1").operatingCashFlow())
                .as("픽스처에 분기 값이 있는데도 담지 않는다").isNull();
        assertThat(reports).filteredOn(r -> "FY".equals(r.fiscalPeriod()))
                .allSatisfy(r -> assertThat(r.operatingCashFlow())
                        .isEqualByComparingTo(new BigDecimal("60")));
    }

    @Test
    @DisplayName("정기보고서가 아닌 form은 통째로 무시한다")
    void ignoresNonPeriodicForms() throws Exception {
        assertThat(of(fetch(), "Q1").revenue())
                .as("8-K의 999가 섞이면 안 된다")
                .isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    @DisplayName("사업보고서가 아직 없는 해의 분기도 담는다 — 안 담으면 TTM이 최대 1년 뒤처진다")
    void labelsQuartersOfTheYearInProgress() throws Exception {
        enqueue(200, fixture("tickers.json"));
        enqueue(200, fixture("companyfacts-in-progress-year.json"));
        List<RawFinancialReport> reports = adapter().fetchReports(FIX, FROM).reports();

        RawFinancialReport q1 = reports.stream()
                .filter(r -> r.fiscalYear() == 2024 && "Q1".equals(r.fiscalPeriod()))
                .findFirst().orElseThrow(() -> new AssertionError("2024/Q1이 없다"));
        assertThat(q1.periodStart()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(q1.periodEnd()).isEqualTo(LocalDate.of(2024, 3, 31));
        assertThat(q1.revenue()).isEqualByComparingTo(new BigDecimal("130"));
        assertThat(q1.totalAssets())
                .as("잔액도 그 분기 말 것이어야 한다").isEqualByComparingTo(new BigDecimal("1150"));
    }

    @Test
    @DisplayName("한 해에 회계연도가 둘 끝나면 그 라벨만 버린다 — 종목을 죽이지는 않는다")
    void dropsTheLabelWhenTwoPeriodsClaimIt() throws Exception {
        enqueue(200, fixture("tickers.json"));
        // 파일 이름에 conflict를 쓰지 않는다 — 전역 .gitignore의 `*CONFLICT*`가 삼켜
        // 저장소에 안 들어가고, 그러면 여기서는 통과하고 새로 클론한 곳에서만 깨진다.
        enqueue(200, fixture("companyfacts-two-years-one-label.json"));

        List<RawFinancialReport> reports = adapter().fetchReports(FIX, FROM).reports();

        // **보호는 그대로다.** uq_fs_latest에 기간이 없어 라벨이 겹쳐도 제약이 안 걸리고,
        // FinancialWriter가 개정 갈래에서 UPDATE를 INSERT보다 먼저 돌리므로 앞 기간의 최신
        // 표시가 조용히 내려간다 — 그것을 막는 것은 **그 라벨을 한 판도 안 내는 것**이다.
        assertThat(reports)
                .as("기간이 둘인 라벨은 한 판도 안 나온다 — 그것이 조용한 덮어쓰기를 막는다")
                .noneMatch(r -> r.fiscalYear() == 2023 && "FY".equals(r.fiscalPeriod()));

        // **예전에는 여기서 예외를 던져 그 종목의 재무가 통째로 안 바뀌었다.** 실측(2026-09-16)
        // 으로 `SENS`가 결산월을 6월 → 12월로 바꿔 한 라벨에 창이 둘이 되는데, 그 하나 때문에
        // 나머지 전 연도가 옛 값에 얼어붙었다(그 종목은 지표 253일을 들고 백분위 모집단에 있다).
        // 스윕에서 그 종목이 판 115개·값칸 1,973을 되찾았고 다른 종목은 한 톨도 안 움직였다.
        // **위 호출이 예외 없이 돌아온 것 자체가 그 증거다** — 목 서버의 응답을 하나만 큐에
        // 넣으므로 여기서 다시 부르면 매핑 파일 호출이 실패해 다른 이유로 던진다.
    }

    @Test
    @DisplayName("10-Q의 12개월 열은 회계연도가 아니다 — 롤링 창이 회계연도가 되면 분기가 밀린다")
    void doesNotTreatTrailingTwelveMonthsAsFiscalYear() throws Exception {
        enqueue(200, fixture("tickers.json"));
        // 10-K가 FY2023을, 세 10-Q가 각자 분기와 「Twelve Months Ended」를 함께 싣는다.
        // 그 12개월 열은 그 10-Q의 앵커 종료일(분기말)과 같아 길이만 보는 판정을 통과한다.
        enqueue(200, fixture("companyfacts-trailing-twelve-months-in-10q.json"));

        List<RawFinancialReport> reports = adapter().fetchReports(FIX, FROM).reports();

        assertThat(reports).extracting(RawFinancialReport::fiscalPeriod)
                .as("롤링 창이 회계연도가 되면 분기 번호가 밀리고 라벨이 겹친다")
                .containsExactlyInAnyOrder("Q1", "Q2", "Q3", "Q4", "FY");
        assertThat(reports).extracting(RawFinancialReport::fiscalYear).containsOnly(2023);

        // MUST: 12개월 값이 Q4로 앉지 않았는지 본다. labelOf가 인정 안 된 창을 null로
        // 끝내지 않고 아래로 흘리면 round(364/91.31)=4가 되어 그 값이 Q4가 되고,
        // deriveFourthQuarters가 「소스가 Q4를 직접 줬다」로 읽어 유도를 포기한다 —
        // 예외도 경고도 안 나므로 값으로만 잡힌다.
        assertThat(of(reports, "Q4").revenue())
                .as("유도값 1000-(100+110+120)이어야 한다. 910/920/930이면 롤링 열이 앉은 것이다")
                .isEqualByComparingTo(new BigDecimal("670"));
    }

    @Test
    @DisplayName("신년을 넘는 결산의 두 해는 다른 라벨이다 — 달력 연도로 지으면 겹친다")
    void labelsFiscalYearsThatCrossNewYearSeparately() throws Exception {
        enqueue(200, fixture("tickers.json"));
        // 「12월 31일에 가장 가까운 요일」로 결산하는 회사. 두 회계연도가 2023-01-01과
        // 2023-12-31에 끝나 end.getYear()로는 둘 다 2023/FY가 된다(실측: ILMN).
        enqueue(200, fixture("companyfacts-fiscal-year-crosses-new-year.json"));

        List<RawFinancialReport> reports = adapter().fetchReports(FIX, FROM).reports();

        assertThat(reports).extracting(RawFinancialReport::fiscalYear)
                .as("회계연도는 달력 연도가 아니다 — 앞 해는 2022여야 한다")
                .containsExactlyInAnyOrder(2022, 2023);
    }

    @Test
    @DisplayName("1월 말 결산 회사의 라벨은 안 바뀐다 — 시프트가 그 무리를 삼키면 안 된다")
    void keepsLateJanuaryFiscalYearLabel() throws Exception {
        enqueue(200, fixture("tickers.json"));
        // 01-25는 실측으로 **관측된 가장 이른 1월 결산**이다(그 아래는 01-03에서 끊긴다).
        // 즉 이 대조군의 민감도가 NEW_YEAR_DRIFT_DAYS = 25이고, 그보다 좁은 값에서는
        // 라벨이 안 움직인다. 넓히려는 세션이 여기서 붉는 것을 보고 멈춘다.
        enqueue(200, fixture("companyfacts-late-january-fiscal-year.json"));

        List<RawFinancialReport> reports = adapter().fetchReports(FIX, FROM).reports();

        assertThat(reports).extracting(RawFinancialReport::fiscalYear)
                .as("NVDA·WMT·RH는 스스로 그 해를 fiscal 2023이라 부른다 — 지금 라벨이 맞다")
                .containsExactly(2023);
    }

    @Test
    @DisplayName("적재 창 밖의 라벨 충돌은 종목을 거절하지 않는다 — 저장되지 않을 연도가 종목을 죽였다")
    void ignoresLabelClashBeforeLoadWindow() throws Exception {
        enqueue(200, fixture("tickers.json"));
        // 2019/FY에 기간이 둘(2018-02-01~2019-01-31 · 2019-01-01~2019-12-31)이고
        // 2021·2022는 깨끗하다. FROM이 2020이므로 그 충돌은 어차피 저장되지 않는다.
        //
        // 파일 이름에 conflict를 쓰지 않는다 — 전역 .gitignore의 `*CONFLICT*`가 삼킨다
        // (refusesTwoPeriodsUnderOneLabel의 같은 주석이 그 함정을 적어 뒀고, 실제로
        // 이 픽스처를 그 이름으로 만들었다가 git이 추적하지 않는 것을 보고 고쳤다).
        enqueue(200, fixture("companyfacts-two-labels-before-load-window.json"));

        List<RawFinancialReport> reports = adapter().fetchReports(FIX, FROM).reports();

        assertThat(reports).extracting(RawFinancialReport::fiscalYear)
                .as("창 안 두 해만 나온다 — 창 밖(2019)의 충돌은 종목을 죽이지 않는다")
                .containsExactlyInAnyOrder(2021, 2022);
    }

    @Test
    @DisplayName("종료일이 같고 시작일만 며칠 다르면 같은 기간이다 — 52/53주 달력의 표기 차이다")
    void foldsSamePeriodStatedWithDifferentStart() throws Exception {
        enqueue(200, fixture("tickers.json"));
        enqueue(200, fixture("companyfacts-same-end-different-start.json"));

        // 실측(2026-08-31): 미국 재무가 거절된 236종목 중 93건이 이 모양이고 시작일 차이가
        // 전부 10일 이내다. 던지면 그 종목의 재무가 통째로 안 들어온다.
        List<RawFinancialReport> reports = adapter().fetchReports(FIX, FROM).reports();

        assertThat(reports).extracting(RawFinancialReport::fiscalPeriod).contains("Q1");
        RawFinancialReport q1 = of(reports, "Q1");
        assertThat(q1.periodEnd()).isEqualTo(LocalDate.of(2023, 4, 1));
        assertThat(q1.periodStart())
                .as("좁은 쪽으로 고정한다 — 규칙이 있어야 실행마다 같은 값이 나온다")
                .isEqualTo(LocalDate.of(2023, 1, 2));
    }

    @Test
    @DisplayName("뒤 판이 한 컬럼만 정정하고 나머지를 안 실으면 앞 판의 값이 살아남는다")
    void laterFilingDoesNotEraseWhatEarlierFilingReported() throws Exception {
        enqueue(200, fixture("tickers.json"));
        enqueue(200, fixture("companyfacts-revision-drops-columns.json"));

        // 실측(AAON FY2022)이 이 모양이다. 10-K가 손익을 냈고, 8개월 뒤 10-Q가 비교
        // 재무상태표만 실으면서 retained_earnings를 정정했다. 그 한 컬럼 때문에 판이
        // 새로 나갔고 손익이 통째로 NULL인 행이 is_latest가 됐다.
        List<RawFinancialReport> reports = adapter().fetchReports(FIX, FROM).reports();

        List<RawFinancialReport> fy = reports.stream()
                .filter(r -> "FY".equals(r.fiscalPeriod())).toList();
        assertThat(fy)
                .as("정정이 실제로 났다 — retained_earnings가 달라졌으므로 판이 둘이어야 한다")
                .hasSize(2);

        RawFinancialReport later = fy.get(fy.size() - 1);
        assertThat(later.publishedAt()).isEqualTo(LocalDate.of(2024, 11, 6));
        assertThat(later.retainedEarnings())
                .as("정정된 값이 이긴다 — 이어받기가 진짜 정정을 덮으면 안 된다")
                .isEqualByComparingTo("461657000");
        assertThat(later.revenue())
                .as("뒤 판이 안 실은 컬럼은 앞 판에서 이어받는다. 이 단언이 이 시험의 전부다")
                .isEqualByComparingTo("888788000");
        assertThat(later.operatingIncome())
                .as("영업이익도 같다 — US_TOP1000에서 373행이 이렇게 사라졌다")
                .isEqualByComparingTo("126761000");
    }

    @Test
    @DisplayName("같은 날 제출된 accn 둘은 한 판으로 접힌다 — 판 단위는 accn이 아니라 공시일이다")
    void foldsTwoAccessionsFiledOnTheSameDay() throws Exception {
        enqueue(200, fixture("tickers.json"));
        enqueue(200, fixture("companyfacts-same-day-two-accns.json"));

        // 실측(AROC 2015/Q1)이 이 모양이다. 같은 날 제출된 accn 둘이 같은 창을 다르게 보고하면
        // FinancialWriter는 publishedAt이 같다는 이유로 정정이 아니라 fillMissing으로 보내
        // 덮고, 덮을 때마다 지표를 무효화한다. 값은 A→B→A로 제자리를 돌고 무효화만 남아
        // 매주 목요일 재적재가 그 종목의 지표를 지웠다.
        List<RawFinancialReport> reports = adapter().fetchReports(FIX, FROM).reports();

        List<RawFinancialReport> fy = reports.stream()
                .filter(r -> "FY".equals(r.fiscalPeriod())).toList();
        assertThat(fy)
                .as("공시일이 같으면 판도 하나다 — 둘이면 저장이 한 키에 번갈아 쓴다")
                .hasSize(1);

        RawFinancialReport only = fy.get(0);
        assertThat(only.publishedAt()).isEqualTo(LocalDate.of(2024, 2, 20));
        assertThat(only.cashAndEquivalents())
                .as("묶음 안에서는 뒤 accn이 이긴다")
                .isEqualByComparingTo("52011000");
        assertThat(only.operatingIncome())
                .as("앞 accn만 실은 컬럼은 살아남는다 — 접기는 값을 줄이지 않는다")
                .isEqualByComparingTo("200000");
    }

    @Test
    @DisplayName("후보는 없고 부분 개념 태그만 있으면 「안 낸다」가 아니라 PARTIAL_ONLY다")
    void reportsPartialOnlyWhenSourceGivesASubsetTag() throws Exception {
        enqueue(200, fixture("tickers.json"));
        enqueue(200, fixture("companyfacts-partial-cost-tag.json"));

        // EDGAR가 감가상각을 뺀 원가를 내는데 우리 후보 넷에는 없다. 갈라 담지 않으면 화면이
        // "이 회사는 매출원가·매출총이익을 공시에 내지 않습니다"라는 거짓을 말한다 —
        // 전수 탐침으로 그 문장을 받는 종목이 20이다.
        FinancialFetch fetch = adapter().fetchReports(FIX, FROM);

        assertThat(fetch.absences())
                .filteredOn(absence -> "cost_of_sales".equals(absence.columnName()))
                .singleElement()
                .extracting(FinancialColumnAbsence::state)
                .isEqualTo(FinancialColumnAbsence.State.PARTIAL_ONLY);
        assertThat(fetch.absences())
                .as("부분 개념도 없는 컬럼은 그대로 ABSENT다 — 셋을 가르는 것이 요점이다")
                .filteredOn(absence -> "gross_profit".equals(absence.columnName()))
                .singleElement()
                .extracting(FinancialColumnAbsence::state)
                .isEqualTo(FinancialColumnAbsence.State.ABSENT);
        assertThat(fetch.reports())
                .as("부분 개념 태그는 컬럼을 채우지 않는다 — 후보가 아니다")
                .allSatisfy(report -> assertThat(report.costOfSales()).isNull());
    }

    @Test
    @DisplayName("분기 계열을 다시 낸 공시가 이 해의 분기는 안 냈으면 그 세대로 안 뺀다")
    void skipsVintageThatRepresentedOtherYearsQuartersButNotThisOne() throws Exception {
        enqueue(200, fixture("tickers.json"));
        enqueue(200, fixture("companyfacts-q4-vintage-mismatch.json"));

        // 실측(AIG 2025-02-13 10-K)이 이 모양이다. 사업을 팔아 FY를 계속영업 기준으로 다시
        // 쓰면서 **아직 비교분기로 실을 수 있는 해**(2023·2024)는 분기까지 다시 냈고, 너무
        // 오래된 2022는 못 냈다 — 10-Q가 직전 연도 비교분기만 싣기 때문이다. 그래서 그 공시의
        // FY2022는 그해 분기가 못 받은 기준이고, 둘을 빼면 -12,609가 나온다.
        //
        // 픽스처도 같은 모양이되 **잔차가 양수로 앉는다**: 2026 공시가 FY2022를 900,000으로
        // 다시 쓰면 900,000 - 750,000 = 150,000이라 **부호로는 아무것도 못 잡는다.** 그런데 그
        // 공시는 자기 해가 2024인데 **지나간 해(2023)의 분기를 다시 냈고 2022 분기는 안 냈다**
        // — 기준이 갈렸다는 직접 증거다. 그래서 FY2022는 2023-02-20 세대로 빼 250,000이 된다.
        //
        // 2023은 그 공시가 분기를 함께 냈으므로 **건너뛰지 않는다** — 아래 둘째 단언.
        //
        // **이것이 부호 규칙이 원리적으로 못 잡던 자리다**(ADR-0151 §결과의 「그럴듯한 양수」).
        List<RawFinancialReport> reports = adapter().fetchReports(FIX, FROM).reports();

        RawFinancialReport q4 = latestOf(reports.stream()
                .filter(r -> r.fiscalYear() == 2022).toList(), "Q4");

        assertThat(q4.publishedAt())
                .as("분기가 따라온 마지막 세대를 쓴다")
                .isEqualTo(LocalDate.of(2023, 2, 20));
        assertThat(q4.revenue()).isEqualByComparingTo("250000");
        assertThat(q4.derived()).isTrue();
        assertThat(reports.stream()
                .filter(r -> r.fiscalYear() == 2022 && "Q4".equals(r.fiscalPeriod())).count())
                .as("건너뛴 세대는 판을 안 낸다")
                .isEqualTo(1);
        assertThat(reports.stream()
                .filter(r -> r.fiscalYear() == 2023 && "Q4".equals(r.fiscalPeriod())).count())
                .as("분기를 함께 낸 해는 세대마다 낸다 — 판정은 연도별이다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("소스가 직접 낸 음수 4분기는 그대로 둔다 — 한 분기 매출은 정당하게 음수일 수 있다")
    void keepsSourceReportedNegativeQuarter() throws Exception {
        enqueue(200, fixture("tickers.json"));
        enqueue(200, fixture("companyfacts-source-negative-quarter.json"));

        // KKR의 10-K가 2018 Q4 매출을 -178.1M으로 직접 낸다 — 자산운용사라 투자손익이 매출에
        // 섞인다. 소스가 준 값에 부호 가드를 걸면 정당한 값을 버린다.
        List<RawFinancialReport> reports = adapter().fetchReports(FIX, FROM).reports();

        RawFinancialReport q4 = latestOf(reports, "Q4");
        assertThat(q4.derived()).as("소스가 줬으므로 유도가 아니다").isFalse();
        assertThat(q4.revenue()).isEqualByComparingTo("-100000");
    }

    @Test
    @DisplayName("우리 뺄셈이 낸 음수 매출·매출원가는 안 낸다 — 뜻이 다른 두 수를 뺀 것이다")
    void dropsImpossibleSubtraction() throws Exception {
        enqueue(200, fixture("tickers.json"));
        enqueue(200, fixture("companyfacts-q4-tag-switch.json"));

        // FY 600,000에 분기 합 900,000이라 뺄셈이 -300,000을 낸다. 「한 분기 매출이 음수일 수
        // 없다」가 아니라 — 그 경우는 위 시험이 지킨다 — **우리가 뜻이 다른 두 수를 뺐다**는
        // 뜻이다. 실측(DLTR 2024): 같은 공시가 매출은 다시 냈고 매출원가는 안 다시 내서,
        // 재작성 FY 원가에서 원본 분기 원가를 빼 -4,649M이 나왔다.
        List<RawFinancialReport> reports = adapter().fetchReports(FIX, FROM).reports();

        RawFinancialReport q4 = latestOf(reports, "Q4");
        assertThat(q4.revenue()).as("불가능한 뺄셈은 안 낸다").isNull();
        assertThat(q4.costOfSales())
                .as("성립하는 컬럼은 그대로 낸다 — 판정은 컬럼별이다")
                .isEqualByComparingTo("800000");
        assertThat(q4.netIncome())
                .as("순이익은 한 분기에 정당하게 음수다 — 이 가드를 안 건다")
                .isEqualByComparingTo("-50000");
    }

    @Test
    @DisplayName("매핑 파일에 없는 티커는 NOT_FOUND다 — 조용한 0건이 아니다")
    void unknownTickerIsNotFound() {
        enqueue(200, fixture("tickers.json"));
        assertThatThrownBy(() -> adapter()
                .fetchReports(new SecurityRef(2L, "US", "NOPE"), FROM))
                .isInstanceOf(DataSourceException.class)
                .hasFieldOrPropertyWithValue("kind", DataSourceException.Kind.NOT_FOUND);
    }

    @Test
    @DisplayName("404는 NOT_FOUND이고, 그 본문이 XML이어도 판정이 안 바뀐다")
    void mapsNotFound() {
        enqueue(200, fixture("tickers.json"));
        enqueue(404, "<?xml version=\"1.0\"?><Error><Code>NoSuchKey</Code></Error>");
        assertThatThrownBy(() -> adapter().fetchReports(FIX, FROM))
                .isInstanceOf(DataSourceException.class)
                .hasFieldOrPropertyWithValue("kind", DataSourceException.Kind.NOT_FOUND);
    }

    @Test
    @DisplayName("403을 AUTH로 접지 않는다 — 접으면 스로틀 한 번이 배치 전체를 죽인다")
    void doesNotEscalateForbiddenToAuth() {
        enqueue(200, fixture("tickers.json"));
        enqueue(403, "{}");
        assertThatThrownBy(() -> adapter().fetchReports(FIX, FROM))
                .isInstanceOf(DataSourceException.class)
                .hasFieldOrPropertyWithValue("kind", DataSourceException.Kind.RATE_LIMITED)
                .satisfies(e -> assertThat(((DataSourceException) e).fatal())
                        .as("fatal이면 국내 수집까지 멈춘다").isFalse());
    }

    @Test
    @DisplayName("200인데 facts가 없으면 MALFORMED다 — 스펙 변경 신호다")
    void mapsMissingFactsToMalformed() {
        enqueue(200, fixture("tickers.json"));
        enqueue(200, "{\"cik\":1}");
        assertThatThrownBy(() -> adapter().fetchReports(FIX, FROM))
                .isInstanceOf(DataSourceException.class)
                .hasFieldOrPropertyWithValue("kind", DataSourceException.Kind.MALFORMED);
    }

    @Test
    @DisplayName("신원 표시가 비면 호출 전에 AUTH로 멈춘다")
    void requiresUserAgentBeforeCalling() {
        DataSourceProperties properties = new DataSourceProperties();
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("sec-edgar", 0, 0);
        SecEdgarFinancialAdapter adapter = new SecEdgarFinancialAdapter(factory, properties, limiter,
                new SecTickerCikCache(factory, properties, limiter, baseUrl(), 1), baseUrl());

        assertThatThrownBy(() -> adapter.fetchReports(FIX, FROM))
                .isInstanceOf(DataSourceException.class)
                .hasFieldOrPropertyWithValue("kind", DataSourceException.Kind.AUTH);
        assertThat(server.getRequestCount()).as("호출 전에 멈춘다").isZero();
    }

    @Test
    @DisplayName("담당 시장은 US 하나다")
    void declaresUsOnly() {
        assertThat(adapter().supportedMarketCodes()).containsExactly(UsMarket.CODE);
        assertThat(adapter().sourceName()).isEqualTo("sec-edgar");
    }

    /** 최신 연간 판. 정정이 있으면 뒤엣것이다. */
    private static RawFinancialReport fetchAnnualLatest(List<RawFinancialReport> reports) {
        return reports.stream().filter(r -> "FY".equals(r.fiscalPeriod()))
                .reduce((a, b) -> b).orElseThrow();
    }

    @Test
    @DisplayName("매출은 후보 중 가장 큰 값이다 — ASC606 태그는 총매출의 부분집합이다")
    void picksTheLargestRevenueCandidate() throws Exception {
        enqueue(200, fixture("tickers.json"));
        enqueue(200, fixture("companyfacts-revenue-tag-priority.json"));

        // 픽스처는 실제 결함의 모양이다: ASC606(제외) 200이 총계 Revenues 500의 40%이고,
        // 후보 순서로 첫값을 고르면 200이 총매출 자리에 앉는다. 실측에서 US_TOP1000
        // 970종목 중 92종목이 이 모양이었고 92 전부가 과소계상이었다.
        assertThat(of(adapter().fetchReports(FIX, FROM).reports(), "FY").revenue())
                .as("ASC606 부분값 200이 아니라 총계 500이어야 한다")
                .isEqualByComparingTo(new BigDecimal("500"));
    }

    @Test
    @DisplayName("Revenues가 부분값인 회사에서는 ASC606 쪽이 이긴다 — 순서가 아니라 크기다")
    void picksLargestEvenWhenRevenuesTagIsThePartialOne() throws Exception {
        enqueue(200, fixture("tickers.json"));
        enqueue(200, fixture("companyfacts-revenues-tag-is-partial.json"));

        // 이 시험이 없으면 「후보 순서만 바꾸면 된다」와 구분되지 않는다. 실측에서 OWL이
        // 이 모양이었다 — 저장값(ASC606) 2,870M이 Revenues 568M보다 크고 그쪽이 맞다.
        // 그래서 「Revenues를 앞세운다」는 처방은 기각됐고 최댓값이 남았다.
        assertThat(of(adapter().fetchReports(FIX, FROM).reports(), "FY").revenue())
                .as("Revenues 부분값 100이 아니라 총계 500이어야 한다")
                .isEqualByComparingTo(new BigDecimal("500"));
    }

    @Test
    @DisplayName("주세 포함 매출은 최댓값 경쟁에 끼지 않는다")
    void doesNotLetAssessedTaxRevenueWinTheLargest() throws Exception {
        enqueue(200, fixture("tickers.json"));
        enqueue(200, fixture("companyfacts-assessed-tax-revenue.json"));

        // Revenues가 없고 세 포함(650)과 제외(500)만 있는 회사다. 세 포함은 정의상 제외보다
        // 크므로 최댓값 경쟁에 넣으면 언제나 이겨 주세가 매출로 들어온다 — 실측에서
        // 브라운포먼(BF.A)이 순매출 3,928M 대신 총매출 5,082M을 받을 자리였다.
        assertThat(of(adapter().fetchReports(FIX, FROM).reports(), "FY").revenue())
                .as("주세 포함 650이 아니라 순매출 500이어야 한다")
                .isEqualByComparingTo(new BigDecimal("500"));
    }

    @Test
    @DisplayName("주세 포함 매출이 유일한 출처면 그것을 쓴다 — 빼버리면 42종목이 빈다")
    void stillUsesAssessedTaxRevenueWhenItIsTheOnlySource() throws Exception {
        enqueue(200, fixture("tickers.json"));
        enqueue(200, fixture("companyfacts-only-assessed-tax-revenue.json"));

        // 최댓값 경쟁에서 뺀 것이지 후보에서 뺀 것이 아니다. 실측으로 US_TOP1000의
        // 42종목(AKAM·BALL·CPRT·CRWD…)이 이 태그만 낸다.
        assertThat(of(adapter().fetchReports(FIX, FROM).reports(), "FY").revenue())
                .as("경쟁 후보가 하나도 없으면 마지막 수단이 남아야 한다")
                .isEqualByComparingTo(new BigDecimal("500"));
    }

    private List<RawFinancialReport> fetch() throws DataSourceException {
        enqueue(200, fixture("tickers.json"));
        enqueue(200, fixture("companyfacts.json"));
        return adapter().fetchReports(FIX, FROM).reports();
    }

    /** 그 기간의 <b>가장 늦은 판</b>. 4분기는 FY 판마다 하나씩 나온다(ADR-0151). */
    private static RawFinancialReport latestOf(List<RawFinancialReport> reports, String period) {
        return reports.stream().filter(r -> period.equals(r.fiscalPeriod()))
                .max(java.util.Comparator.comparing(RawFinancialReport::publishedAt))
                .orElseThrow(() -> new AssertionError(period + " 보고서가 없다"));
    }

    private static RawFinancialReport of(List<RawFinancialReport> reports, String period) {
        return reports.stream().filter(r -> period.equals(r.fiscalPeriod())).findFirst()
                .orElseThrow(() -> new AssertionError(period + " 보고서가 없다"));
    }

    private SecEdgarFinancialAdapter adapter() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getSecEdgar().setApiKey("Test Runner test@example.com");
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("sec-edgar", 0, 0);
        return new SecEdgarFinancialAdapter(factory, properties, limiter,
                new SecTickerCikCache(factory, properties, limiter, baseUrl(), 1), baseUrl());
    }

    private String baseUrl() {
        return server.url("/").toString();
    }

    private void enqueue(int status, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(body));
    }

    private static String fixture(String name) {
        try {
            return new String(new ClassPathResource("fixtures/sec-edgar/" + name)
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("fixture를 읽을 수 없다: " + name, e);
        }
    }
}
