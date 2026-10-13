package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.GovernanceDataSource;
import com.stockscreener.pipeline.datasource.GovernanceEndpoint;
import com.stockscreener.pipeline.datasource.GovernanceReportRow;
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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * DART 정기보고서 주요정보 어댑터. 표 여덟을 <b>원문 그대로</b> 받는다(ADR-0081).
 *
 * <p>실측과 필드 계약은 {@code docs/api-spec/dart-governance.md}에 있다.
 * <b>개발가이드 갈무리와 실측이 갈리면 실측이 정본이다</b> — 개발가이드가 틀렸던 자리가 넷이다.
 *
 * <p>IMPORTANT — <b>여기서 값을 해석하지 않는다.</b> 숫자로 바꾸는 것도, 행을 고르는 것도
 * {@link DartGovernanceFactMapper}의 몫이다. 어댑터가 타입을 확정하면 매핑을 한 번 틀릴 때마다
 * 수천 콜을 다시 태워야 한다.
 *
 * <p>MUST: 사업보고서({@code 11011})만 부른다. 보고서 넷을 다 부르면 같은 일에 콜이 네 배가 되고
 * <b>그 네 배는 아무 에러 없이 다른 잡의 여유를 먹는다</b>(우리 리미터가 DART 전체를 하나로 센다).
 */
@ConditionalOnApiKey("dart")
@Component
public class DartGovernanceAdapter implements GovernanceDataSource {

    private static final Logger log = LoggerFactory.getLogger(DartGovernanceAdapter.class);

    /**
     * 도메인 이름 → DART 경로. <b>벤더 지식이 이 표 하나에 갇힌다</b>(ADR-0006).
     *
     * <p>포트도 잡도 CHECK 제약도 왼쪽만 안다. 소스를 갈아끼우면 이 표만 바뀐다.
     */
    private static final Map<GovernanceEndpoint, String> PATHS = new EnumMap<>(Map.of(
            GovernanceEndpoint.SHARE_TOTALS, "/stockTotqySttus.json",
            GovernanceEndpoint.DIVIDEND, "/alotMatter.json",
            GovernanceEndpoint.EXEC_PAY, "/hmvAuditAllSttus.json",
            GovernanceEndpoint.OUTSIDE_DIRECTOR, "/outcmpnyDrctrNdChangeSttus.json",
            GovernanceEndpoint.LARGEST_HOLDER, "/hyslrSttus.json",
            GovernanceEndpoint.MINORITY_HOLDER, "/mrhlSttus.json",
            GovernanceEndpoint.CONTROL_CHANGE, "/hyslrChgSttus.json",
            GovernanceEndpoint.AUDIT_OPINION, "/accnutAdtorNmNdAdtOpinion.json"));

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final DartCorpCodeCache corpCodes;
    private final String apiKey;

    public DartGovernanceAdapter(ExternalApiClientFactory clientFactory,
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
    public List<GovernanceReportRow> fetchReport(SecurityRef security, GovernanceEndpoint endpoint,
                                                 int bsnsYear, String reportCode)
            throws DataSourceException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, DartResponse.SOURCE_NAME,
                    "DART_API_KEY가 설정되지 않았다.");
        }
        String path = PATHS.get(endpoint);
        if (path == null) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                    DartResponse.SOURCE_NAME, "경로를 모르는 엔드포인트다: " + endpoint);
        }
        String context = "governance " + endpoint + " ticker=" + security.ticker()
                + " year=" + bsnsYear;

        Optional<String> corpCode = corpCodes.findCorpCode(security.ticker());
        if (corpCode.isEmpty()) {
            // DART가 모르는 종목코드다. 신규 상장 직후에 정상적으로 생긴다.
            log.info("DART에 등록되지 않은 종목코드라 거버넌스 수집을 건너뛴다. ticker={}",
                    security.ticker());
            return List.of();
        }

        DartResponse.acquire(rateLimiter, path, context);

        ResponseEntity<byte[]> response;
        try {
            response = client.get().uri(uri -> uri
                            .path(path)
                            .queryParam("crtfc_key", apiKey)
                            .queryParam("corp_code", corpCode.get())
                            // MUST: 네 자리다. 개발가이드가 outcmpnyDrctrNdChangeSttus의 타입을
                            // STRING(1)로 적었으나 오기이고, 네 자리로 000이 오는 것을 실측이 봤다.
                            .queryParam("bsns_year", String.valueOf(bsnsYear))
                            .queryParam("reprt_code", reportCode)
                            .build())
                    .retrieve()
                    // 본문을 바이트로 받는다 — 상태를 먼저 보고 UTF-8로 판다(DartResponse.read · 문자열로 받으면 한글이 깨진다).
                    .toEntity(byte[].class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DartResponse.SOURCE_NAME, "호출에 실패했다. " + context, e);
        }

        JsonNode list;
        try {
            list = DartResponse.requireList(DartResponse.read(response, context), context);
        } catch (DataSourceException e) {
            if (DartResponse.isNoData(e)) {
                // status 013 — 그 회사에 그 표가 없다. 종목 실패가 아니며, 「아직 안 받았다」와
                // 갈라야 하므로 빈 목록이 아니라 흔적을 남긴다.
                return List.of(GovernanceReportRow.noData(endpoint, bsnsYear, reportCode));
            }
            throw e;
        }

        if (list.isEmpty()) {
            // status 000인데 배열이 비었다. 013과 뜻이 같으므로 같은 흔적을 남긴다.
            return List.of(GovernanceReportRow.noData(endpoint, bsnsYear, reportCode));
        }

        List<GovernanceReportRow> rows = new ArrayList<>(list.size());
        int ordinal = 0;
        for (JsonNode node : list) {
            rows.add(new GovernanceReportRow(endpoint, bsnsYear, reportCode,
                    node.path("rcept_no").asString("").strip(),
                    DartReportPeriods.publishedAt(node, bsnsYear),
                    ordinal++,
                    toFields(node)));
        }
        return rows;
    }

    /**
     * 행의 필드를 <b>이름과 문자열 그대로</b> 담는다.
     *
     * <p>MUST NOT: 여기서 값을 정리하지 않는다. 콤마도, 센티널 {@code -}도, 개행이 든 기수 표기도
     * 그대로 둔다 — 무엇이 정리 대상인지가 아직 다 관측되지 않았고, 원문이 남아 있어야 매핑을
     * 고칠 때 <b>재적재가 아니라 재유도</b>로 끝난다.
     */
    private static Map<String, String> toFields(JsonNode node) {
        Map<String, String> fields = new LinkedHashMap<>();
        node.propertyStream().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && !value.isArray() && !value.isObject()) {
                fields.put(entry.getKey(), value.asString(""));
            }
        });
        return fields;
    }
}
