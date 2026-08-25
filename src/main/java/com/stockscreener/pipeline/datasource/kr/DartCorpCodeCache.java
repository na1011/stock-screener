package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 종목코드 → DART 고유번호(`corp_code`) 매핑.
 *
 * <p>DART의 재무 API는 종목코드가 아니라 8자리 {@code corp_code}를 받는다. 그 매핑을 주는
 * <b>유일한 공식 경로</b>가 {@code corpCode.xml}이며, <b>ZIP 바이너리</b>로 온다.
 * <b>이 엔드포인트 계약의 정본이 여기다</b> — 해석 지점이 저장소에 이 클래스 하나뿐이다.
 *
 * <p>요청 인자는 {@code crtfc_key} 하나. 응답은 3.5MB ZIP이고 안에 {@code CORPCODE.xml} 한 개가
 * 들어 있다. 필드는 {@code corp_code}(8자리) · {@code corp_name} · {@code corp_eng_name} ·
 * {@code stock_code}(6자리, <b>상장사만 채워진다</b>) · {@code modify_date} 다섯이다.
 *
 * <p>테이블이 아니라 인메모리로 두는 이유는 되돌리기 쉽고 규모가 작기 때문이다 —
 * 실측 기준 전체 118,664법인 중 상장사는 3,925건뿐이다. 영속화가 필요해지면 그때 옮긴다.
 *
 * <p>지연 로딩한다. 기동 시 3.5MB를 받으면 키가 없는 개발 환경에서 애플리케이션이 뜨지
 * 못하고, 재무 수집을 하지 않는 날에도 매번 받게 된다.
 */
@Component
public class DartCorpCodeCache {

    private static final Logger log = LoggerFactory.getLogger(DartCorpCodeCache.class);

    /**
     * {@code <list>} 블록 하나에서 corp_code와 stock_code를 뽑는다.
     *
     * <p>StAX 대신 정규식을 쓰는 이유는 구조가 평평하고(list 안에 필드 5개) 네임스페이스가
     * 없어서다. XML 파서 의존성을 추가할 이유가 되지 못한다.
     */
    private static final Pattern LIST_BLOCK = Pattern.compile("<list>(.*?)</list>", Pattern.DOTALL);
    private static final Pattern CORP_CODE = Pattern.compile("<corp_code>(.*?)</corp_code>", Pattern.DOTALL);
    private static final Pattern STOCK_CODE = Pattern.compile("<stock_code>(.*?)</stock_code>", Pattern.DOTALL);
    private static final Pattern CORP_NAME = Pattern.compile("<corp_name>(.*?)</corp_name>", Pattern.DOTALL);

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final String apiKey;

    private volatile Map<String, String> byStockCode;

    public DartCorpCodeCache(com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory factory,
                             DataSourceProperties properties,
                             @Qualifier("dartRateLimiter") RateLimiter rateLimiter) {
        DataSourceProperties.Source source = properties.getDart();
        this.client = factory.create(source);
        this.rateLimiter = rateLimiter;
        this.apiKey = source.getApiKey();
    }

    /**
     * 종목코드에 대응하는 고유번호를 찾는다.
     *
     * <p>{@link Optional#empty()}는 "DART가 이 종목코드를 모른다"는 뜻이다. 신규 상장 직후나
     * 상장폐지 종목에서 정상적으로 발생하므로 예외가 아니다.
     */
    public Optional<String> findCorpCode(String stockCode) throws DataSourceException {
        return Optional.ofNullable(load().get(stockCode));
    }

    /** 매핑된 상장사 수. 로그와 진단에 쓴다. */
    public int size() throws DataSourceException {
        return load().size();
    }

    private Map<String, String> load() throws DataSourceException {
        Map<String, String> cached = byStockCode;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (byStockCode == null) {
                byStockCode = download();
            }
            return byStockCode;
        }
    }

    private Map<String, String> download() throws DataSourceException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, DartResponse.SOURCE_NAME,
                    "DART_API_KEY가 설정되지 않았다.");
        }
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DartResponse.SOURCE_NAME, "corpCode 다운로드 대기 중 인터럽트됐다.", e);
        }

        ResponseEntity<byte[]> response;
        try {
            response = client.get()
                    .uri(uri -> uri.path("/corpCode.xml").queryParam("crtfc_key", apiKey).build())
                    .retrieve()
                    .toEntity(byte[].class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DartResponse.SOURCE_NAME, "corpCode.xml 다운로드에 실패했다.", e);
        }

        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                    DartResponse.SOURCE_NAME, "corpCode.xml 응답이 비어 있다.");
        }

        // IMPORTANT — content-type이 application/x-msdownload로 온다(실측). 타입으로 분기하면
        // 실패하므로, ZIP 매직넘버(PK)로 판별한다. 인증 실패 시에는 JSON 오류가 오므로
        // 그 경우를 여기서 걸러야 "ZIP이 아니다"라는 엉뚱한 메시지를 피한다.
        if (body.length < 2 || body[0] != 'P' || body[1] != 'K') {
            String asText = new String(body, StandardCharsets.UTF_8);
            throw DartResponse.errorFromBody(asText, "corpCode.xml");
        }

        String xml = unzipFirstEntry(body);
        Map<String, String> mapping = parse(xml);
        log.info("DART 고유번호 매핑을 적재했다. 상장사 {}건", mapping.size());
        return Map.copyOf(mapping);
    }

    private static String unzipFirstEntry(byte[] zipBytes) throws DataSourceException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                        DartResponse.SOURCE_NAME, "corpCode ZIP이 비어 있다.");
            }
            return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                    DartResponse.SOURCE_NAME, "corpCode ZIP을 풀 수 없다.", e);
        }
    }

    /**
     * 상장사만 추린다. {@code stock_code}가 빈 법인이 대부분(비상장)이다.
     */
    private static Map<String, String> parse(String xml) {
        Map<String, String> mapping = new HashMap<>();
        Matcher blocks = LIST_BLOCK.matcher(xml);
        while (blocks.find()) {
            String block = blocks.group(1);
            String stockCode = group(STOCK_CODE, block);
            if (stockCode == null || stockCode.isBlank()) {
                continue;
            }
            String corpCode = group(CORP_CODE, block);
            if (corpCode == null || corpCode.isBlank()) {
                continue;
            }
            // 같은 종목코드가 두 번 나오면 뒤엣것이 이긴다. 실측에서는 중복이 없었다.
            String previous = mapping.put(stockCode.strip(), corpCode.strip());
            if (previous != null && !previous.equals(corpCode.strip())) {
                log.warn("종목코드 {}에 고유번호가 둘이다: {} vs {} — 뒤엣것을 쓴다. 회사명={}",
                        stockCode, previous, corpCode, group(CORP_NAME, block));
            }
        }
        return mapping;
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
