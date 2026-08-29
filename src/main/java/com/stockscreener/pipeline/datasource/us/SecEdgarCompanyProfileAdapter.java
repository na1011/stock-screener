package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.CompanyProfile;
import com.stockscreener.pipeline.datasource.CompanyProfileDataSource;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.IndustryScheme;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.Set;

/**
 * SEC EDGAR {@code submissions}에서 미국 업종(SIC)과 결산월을 받는다.
 *
 * <p>필드 계약과 실측은 {@code docs/api-spec/sec-edgar-company-profile.md}가 정본이다.
 * 여기에는 <b>그 문서를 읽어도 코드에서 틀리기 쉬운 것</b>만 적는다.
 *
 * <p><b>SIC를 가공하지 않는다.</b> 소스가 <b>항상 4자리로, 앞자리 0까지 채워서</b> 준다
 * (실측: {@code LMNR}·{@code ALCO}={@code 0100}, {@code CALM}={@code 0200}). zero-padding을
 * 넣고 싶어지는 자리이지만 넣을 일이 없고, 넣으면 <b>소스가 형식을 바꿨을 때 그것을 덮어</b>
 * {@code ck_stock_sic_code}가 잡을 것을 못 잡게 된다.
 *
 * <p><b>{@code ownerOrg}를 업종으로 쓰지 않는다.</b> 그것은 SEC의 심사 사무국이고 SIC와
 * 대응하지 않는다 — 실측에서 증권거래소(SIC {@code 6200})가 {@code "09 Crypto Assets"}로 왔다.
 * 이름이 그럴듯해서 업종으로 읽기 쉬운 자리다.
 *
 * <p><b>CIK에 안 붙는 티커는 예외가 아니라 {@link Optional#empty()}다.</b> 우리 8,504 중
 * <b>1,815</b>가 매핑 파일에 없고(ETF·펀드·우선주·워런트·유닛) <b>업종을 원리적으로 못 받는다.</b>
 * 예외로 올리면 매 실행 1,815건이 실패로 쌓여 진짜 장애를 덮는다 —
 * {@link SecEdgarFinancialAdapter}가 같은 상황을 {@code NOT_FOUND}로 올리는 것과 <b>다르고,
 * 그것이 의도다</b>: 저쪽은 「재무를 못 내는 종목」이라는 기록 자체가 값이지만, 이쪽 포트는
 * 빈 결과를 「소스가 이 종목을 모른다」로 정의해 두었다.
 *
 * <p><b>응답의 대부분을 버린다.</b> 공시 이력이 함께 와서 종목당 평균 385KB·최대 4.6MB인데
 * 우리가 쓰는 것은 필드 둘이다. 필드를 고를 수 있는 파라미터가 없다.
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class SecEdgarCompanyProfileAdapter implements CompanyProfileDataSource {

    private static final Logger log = LoggerFactory.getLogger(SecEdgarCompanyProfileAdapter.class);

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final SecTickerCikCache cikCache;
    private final boolean userAgentPresent;

    /**
     * MUST — {@code @Autowired}를 유지한다. 생성자가 둘이면 Spring이 어느 쪽을 쓸지 정하지
     * 못해 <b>기동에 실패</b>하고, 단위 테스트는 생성자를 직접 부르므로 그것을 잡지 못한다.
     */
    @Autowired
    public SecEdgarCompanyProfileAdapter(ExternalApiClientFactory clientFactory,
                                         DataSourceProperties properties,
                                         @Qualifier("secEdgarRateLimiter") RateLimiter rateLimiter,
                                         SecTickerCikCache cikCache) {
        this(clientFactory, properties, rateLimiter, cikCache,
                properties.getSecEdgar().getBaseUrl());
    }

    /** MockWebServer 테스트 전용 — base URL만 바꿔 끼운다. */
    SecEdgarCompanyProfileAdapter(ExternalApiClientFactory clientFactory,
                                  DataSourceProperties properties,
                                  RateLimiter rateLimiter,
                                  SecTickerCikCache cikCache,
                                  String baseUrl) {
        DataSourceProperties.Source source = properties.getSecEdgar();
        this.client = clientFactory.create(source, baseUrl, SecEdgarHeaders.of(source));
        this.rateLimiter = rateLimiter;
        this.cikCache = cikCache;
        this.userAgentPresent = source.hasApiKey();
    }

    @Override
    public Set<String> supportedMarketCodes() {
        return Set.of(UsMarket.CODE);
    }

    @Override
    public String sourceName() {
        return SecEdgarResponse.SOURCE_NAME;
    }

    @Override
    public Optional<CompanyProfile> fetchProfile(SecurityRef security) throws DataSourceException {
        requireUserAgent();
        Optional<Long> cik = cikCache.findCik(security.ticker());
        if (cik.isEmpty()) {
            log.info("SEC 매핑 파일에 없는 티커라 업종을 못 받는다. ticker={}", security.ticker());
            return Optional.empty();
        }

        String context = "submissions %s %s".formatted(
                security.ticker(), SecTickerCikCache.padded(cik.get()));
        ResponseEntity<String> response = call(cik.get(), context);
        JsonNode root = SecEdgarResponse.requireJson(
                response.getStatusCode(), response.getBody(), context);

        String sic = root.path("sic").asString("").strip();
        if (sic.isEmpty()) {
            // 값이 비어 오는 것도 관측 결과다. 예외로 올리면 그 사실이 실패로 기록되어
            // 실제 장애와 구분되지 않는다(DartCompanyProfileAdapter와 같은 판단).
            log.info("submissions에 sic이 비어 있다. ticker={}", security.ticker());
            return Optional.empty();
        }

        return Optional.of(new CompanyProfile(
                sic,
                IndustryScheme.SIC,
                accountingMonth(root, security),
                // 법인구분은 DART 고유 값이라 대응하는 것이 없다. 지어내지 않는다.
                null));
    }

    /**
     * 결산월. {@code fiscalYearEnd}가 {@code MMDD}라 앞 두 자리를 쓴다.
     *
     * <p>파싱할 수 없으면 null이고 <b>그 사실을 로그로 남긴다</b> — 조용히 null로 두면
     * 소스의 형식이 바뀐 것과 원래 없는 것이 구분되지 않는다. 실측 26/26이 네 자리였다.
     */
    private Integer accountingMonth(JsonNode root, SecurityRef security) {
        String raw = root.path("fiscalYearEnd").asString("").strip();
        if (raw.length() != 4) {
            if (!raw.isEmpty()) {
                log.info("fiscalYearEnd가 MMDD가 아니다. ticker={} value={}", security.ticker(), raw);
            }
            return null;
        }
        try {
            int month = Integer.parseInt(raw.substring(0, 2));
            if (month < 1 || month > 12) {
                log.info("fiscalYearEnd의 월이 1~12가 아니다. ticker={} value={}",
                        security.ticker(), raw);
                return null;
            }
            return month;
        } catch (NumberFormatException e) {
            log.info("fiscalYearEnd를 숫자로 못 읽었다. ticker={} value={}", security.ticker(), raw);
            return null;
        }
    }

    /** {@link SecEdgarFinancialAdapter#call}과 같은 계약이다 — 본문을 문자열로 받는다. */
    private ResponseEntity<String> call(long cik, String context) throws DataSourceException {
        if (!rateLimiter.hasDailyQuota()) {
            throw new DataSourceException(DataSourceException.Kind.RATE_LIMITED,
                    SecEdgarResponse.SOURCE_NAME,
                    "일일 호출 한도를 소진했다(사용 " + rateLimiter.usedToday() + "회). " + context);
        }
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    SecEdgarResponse.SOURCE_NAME, "호출 대기 중 인터럽트됐다. " + context, e);
        }
        try {
            // MUST: CIK를 10자리 zero-padding한다. 안 하면 404다.
            // MUST NOT: 호스트를 www.sec.gov로 바꾸지 않는다 — 이 엔드포인트는 data.sec.gov다.
            return client.get()
                    .uri("/submissions/{cik}.json", SecTickerCikCache.padded(cik))
                    .retrieve()
                    .toEntity(String.class);
        } catch (RuntimeException e) {
            // 벤더 예외가 상위로 새지 않게 여기서 감싼다(ADR-0006).
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    SecEdgarResponse.SOURCE_NAME, "호출에 실패했다. " + context, e);
        }
    }

    /** 호출 <b>전에</b> 신원 표시를 본다 — {@link SecEdgarFinancialAdapter}와 같은 이유다. */
    private void requireUserAgent() throws DataSourceException {
        if (!userAgentPresent) {
            throw new DataSourceException(DataSourceException.Kind.AUTH,
                    SecEdgarResponse.SOURCE_NAME,
                    "SEC_EDGAR_USER_AGENT가 비어 있다. SEC는 신원을 밝히지 않는 요청을 차단한다.");
        }
    }
}
