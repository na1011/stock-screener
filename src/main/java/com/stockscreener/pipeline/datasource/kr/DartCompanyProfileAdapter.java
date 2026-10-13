package com.stockscreener.pipeline.datasource.kr;

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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.Set;

/**
 * DART 기업개황 어댑터. 업종코드(KSIC)와 결산월을 받는다.
 *
 * <p>실측과 필드 계약은 {@code docs/api-spec/dart-company-profile.md}에 있다.
 *
 * <p>IMPORTANT — <b>이 엔드포인트만 응답이 {@code list} 배열이 아니라 평면 객체다.</b>
 * 다른 DART 엔드포인트와 형태가 달라 {@link DartResponse#requireList}를 그대로 쓰면
 * 조용히 빈 결과가 된다 — 오류가 아니라 "이 종목은 업종이 없다"로 읽힌다. 그래서 status만
 * 검사하고 필드는 루트에서 직접 꺼낸다.
 *
 * <p>MUST NOT: {@code est_dt}를 상장일로 쓰지 않는다. <b>설립일이다</b> — 삼성전자가
 * 19690113(설립 1969, 상장 1975)으로 온다. 응답 전체에 상장일 필드가 없어 U-05(상장 3년)는
 * 이 엔드포인트로 열리지 않으며, 그 조건은 애초에 가격 시계열 길이로 판정한다.
 */
// 키가 없으면 등록하지 않는다. 다른 DART·DataGoKr 어댑터와 같은 이유다.
@ConditionalOnApiKey("dart")
@Component
public class DartCompanyProfileAdapter implements CompanyProfileDataSource {

    /** 요청 경로 — 일일 총량을 이 경로로 센다(ADR-0176). */
    private static final String PATH = "/company.json";

    private static final Logger log = LoggerFactory.getLogger(DartCompanyProfileAdapter.class);

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final DartCorpCodeCache corpCodes;
    private final String apiKey;

    public DartCompanyProfileAdapter(ExternalApiClientFactory clientFactory,
                                     DataSourceProperties properties,
                                     @Qualifier("dartRateLimiter") RateLimiter rateLimiter,
                                     DartCorpCodeCache corpCodes) {
        DataSourceProperties.Source source = properties.getDart();
        this.client = clientFactory.create(source);
        this.rateLimiter = rateLimiter;
        this.corpCodes = corpCodes;
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
    public Optional<CompanyProfile> fetchProfile(SecurityRef security) throws DataSourceException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, DartResponse.SOURCE_NAME,
                    "DART_API_KEY가 설정되지 않았다.");
        }
        String context = "profile ticker=" + security.ticker();

        Optional<String> corpCode = corpCodes.findCorpCode(security.ticker());
        if (corpCode.isEmpty()) {
            // DART가 모르는 종목코드다. 신규 상장 직후에 정상적으로 생긴다.
            log.info("DART에 등록되지 않은 종목코드라 개황을 건너뛴다. ticker={}", security.ticker());
            return Optional.empty();
        }

        DartResponse.acquire(rateLimiter, PATH, context);

        ResponseEntity<byte[]> response;
        try {
            response = client.get().uri(uri -> uri
                            .path(PATH)
                            .queryParam("crtfc_key", apiKey)
                            .queryParam("corp_code", corpCode.get())
                            .build())
                    .retrieve()
                    // 본문을 바이트로 받는다 — 상태를 먼저 보고 UTF-8로 판다(DartResponse.read · 문자열로 받으면 한글이 깨진다).
                    .toEntity(byte[].class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DartResponse.SOURCE_NAME, "호출에 실패했다. " + context, e);
        }

        JsonNode root;
        try {
            root = DartResponse.requireSuccess(DartResponse.read(response, context), context);
        } catch (DataSourceException e) {
            if (DartResponse.isNoData(e)) {
                // status 013 — 이 회사의 개황이 없다. 종목 실패가 아니다.
                return Optional.empty();
            }
            throw e;
        }

        String industryCode = root.path("induty_code").asString("").strip();
        if (industryCode.isEmpty()) {
            // 값이 비어 오는 것도 관측 결과다. 예외로 올리면 그 사실이 실패로 기록되어
            // 실제 장애와 구분되지 않는다.
            log.info("기업개황에 업종코드가 비어 있다. ticker={}", security.ticker());
            return Optional.empty();
        }

        return Optional.of(new CompanyProfile(
                industryCode,
                IndustryScheme.KSIC,
                accountingMonth(root, security),
                root.path("corp_cls").asString("").strip()));
    }

    /**
     * 결산월. 파싱할 수 없으면 null이고 <b>그 사실을 로그로 남긴다.</b>
     *
     * <p>표본 6개사가 전부 {@code "12"}였으므로 다른 표기는 미확인이다. 조용히 null로
     * 떨어뜨리면 "비12월 결산이 몇 종목인가"라는 질문에 영영 답하지 못한다.
     */
    private Integer accountingMonth(JsonNode root, SecurityRef security) {
        String raw = root.path("acc_mt").asString("").strip();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            int month = Integer.parseInt(raw);
            if (month < 1 || month > 12) {
                log.warn("결산월이 1~12 밖이다. ticker={} acc_mt='{}'", security.ticker(), raw);
                return null;
            }
            return month;
        } catch (NumberFormatException e) {
            log.warn("결산월을 숫자로 읽을 수 없다. ticker={} acc_mt='{}' — 표기가 실측(2자리 숫자)과 다르다.",
                    security.ticker(), raw);
            return null;
        }
    }
}
